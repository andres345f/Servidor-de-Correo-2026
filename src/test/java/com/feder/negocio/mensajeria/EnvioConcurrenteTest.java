package com.feder.negocio.mensajeria;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.datos.mensajeria.MensajeCorreoRepositorio;
import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dos trabajadores de la bandeja de salida sobre el MISMO correo, cada uno en su hilo y con su conexión, con datos
 * confirmados. El trabajador A queda «enviando» mientras su reserva vence; el trabajador B toma el correo. Pase lo que
 * pase después con A, no puede sobrescribir lo que B registró (ni deshacer un envío registrado).
 * Al terminar se elimina el correo de prueba.
 */
class EnvioConcurrenteTest {

    private final ExecutorService hilos = Executors.newFixedThreadPool(2);
    private ContextoNegocio contexto;
    private ServicioMensajeria mensajeria;
    private String messageId;
    private long correoId;

    @BeforeEach
    void preparar() {
        contexto = BaseDatosPrueba.contexto();
        ServicioConfirmacion confirmaciones = new ServicioConfirmacion(new ConfirmacionRepositorio(), Clock.systemUTC(),
                Duration.ofMinutes(30), 30);
        ComandoDispatcher dispatcher = new ComandoDispatcher(new CatalogoComandos(),
                new ServicioIdentidad(new IdentidadRepositorio()), confirmaciones, contexto.transacciones());
        mensajeria = new ServicioMensajeria(contexto.transacciones(), new MensajeCorreoRepositorio(),
                new CorreoSalienteRepositorio(), dispatcher, Clock.systemUTC(), 30, 24);
        messageId = "<concurrente-" + UUID.randomUUID() + "@feder.local>";
        correoId = contexto.transacciones().enTransaccion(() -> new CorreoSalienteRepositorio().encolar(null,
                "daniela.flores@feder.example", "FEDER - Aviso: prueba concurrente", "texto", null, messageId,
                null, null, null, null, null));
    }

    @AfterEach
    void limpiar() {
        hilos.shutdownNow();
        if (contexto == null || correoId == 0) {
            return; // La preparación se omitió (sin base local): no hay nada que limpiar.
        }
        contexto.transacciones().enTransaccion(() -> ejecutar("DELETE FROM correo_saliente WHERE id = ?", correoId));
    }

    @Test
    @DisplayName("A sigue enviando, B envía con éxito y luego A falla: el correo queda ENVIADO")
    void falloTardioDeOtroTrabajadorNoDeshaceElEnvio() throws Exception {
        CountDownLatch aEnviando = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);
        Future<ServicioMensajeria.ResultadoEnvio> a = hilos.submit(() -> mensajeria.enviarSiguientePendiente(correo -> {
            esperarEnElEnvio(correo, aEnviando, liberarA);
            throw new EnviadorCorreo.FalloEnvio("conexión cortada en el trabajador A", false, null);
        }));
        assertTrue(aEnviando.await(10, TimeUnit.SECONDS), "A tomó el correo");
        vencerReservaDeA();
        Future<ServicioMensajeria.ResultadoEnvio> b = hilos.submit(() -> mensajeria.enviarSiguientePendiente(correo -> {
            assertEquals(messageId, correo.messageId);
        }));
        assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO, b.get(10, TimeUnit.SECONDS));
        liberarA.countDown();
        assertEquals(ServicioMensajeria.ResultadoEnvio.FALLO_TRANSITORIO, a.get(10, TimeUnit.SECONDS));

        assertEquals("ENVIADO", texto("SELECT estado FROM correo_saliente WHERE id = ?"));
        assertEquals("2", texto("SELECT intentos::text FROM correo_saliente WHERE id = ?"));
        assertNull(texto("SELECT ultimo_error FROM correo_saliente WHERE id = ?"));
    }

    @Test
    @DisplayName("A sigue enviando, B falla y luego A termina bien: el correo queda ENVIADO")
    void exitoTardioDeOtroTrabajadorSeRegistra() throws Exception {
        CountDownLatch aEnviando = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);
        Future<ServicioMensajeria.ResultadoEnvio> a = hilos.submit(() -> mensajeria.enviarSiguientePendiente(correo ->
                esperarEnElEnvio(correo, aEnviando, liberarA)));
        assertTrue(aEnviando.await(10, TimeUnit.SECONDS), "A tomó el correo");
        vencerReservaDeA();
        Future<ServicioMensajeria.ResultadoEnvio> b = hilos.submit(() -> mensajeria.enviarSiguientePendiente(correo -> {
            throw new EnviadorCorreo.FalloEnvio("servidor no disponible para B", false, null);
        }));
        assertEquals(ServicioMensajeria.ResultadoEnvio.FALLO_TRANSITORIO, b.get(10, TimeUnit.SECONDS));
        assertEquals("PENDIENTE", texto("SELECT estado FROM correo_saliente WHERE id = ?"));
        liberarA.countDown();
        assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO, a.get(10, TimeUnit.SECONDS));

        assertEquals("ENVIADO", texto("SELECT estado FROM correo_saliente WHERE id = ?"));
        assertNull(texto("SELECT ultimo_error FROM correo_saliente WHERE id = ?"));
    }

    @Test
    @DisplayName("Mientras dura la reserva de A, ningún otro trabajador toma el mismo correo")
    void laReservaImpideUnSegundoEnvioSimultaneo() throws Exception {
        CountDownLatch aEnviando = new CountDownLatch(1);
        CountDownLatch liberarA = new CountDownLatch(1);
        Future<ServicioMensajeria.ResultadoEnvio> a = hilos.submit(() -> mensajeria.enviarSiguientePendiente(correo ->
                esperarEnElEnvio(correo, aEnviando, liberarA)));
        assertTrue(aEnviando.await(10, TimeUnit.SECONDS), "A tomó el correo");
        Future<ServicioMensajeria.ResultadoEnvio> b = hilos.submit(() -> mensajeria.enviarSiguientePendiente(correo -> {
            throw new AssertionError("B no debía tomar el correo reservado por A: " + correo.messageId);
        }));
        assertEquals(ServicioMensajeria.ResultadoEnvio.SIN_PENDIENTES, b.get(10, TimeUnit.SECONDS));
        liberarA.countDown();
        assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO, a.get(10, TimeUnit.SECONDS));
        assertEquals("1", texto("SELECT intentos::text FROM correo_saliente WHERE id = ?"));
    }

    private void esperarEnElEnvio(EnviadorCorreo.CorreoPendiente correo, CountDownLatch enviando, CountDownLatch liberar) {
        assertEquals(messageId, correo.messageId, "el trabajador debía tomar el correo de la prueba");
        enviando.countDown();
        try {
            assertTrue(liberar.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Simula que la reserva de A venció mientras A seguía enviando (por ejemplo, un proceso colgado). */
    private void vencerReservaDeA() {
        contexto.transacciones().enTransaccion(() ->
                ejecutar("UPDATE correo_saliente SET proximo_intento = now() WHERE id = ?", correoId));
    }

    private String texto(String sql) {
        return contexto.transacciones().enTransaccion(() -> {
            try (PreparedStatement p = GestorTransacciones.conexionActual().prepareStatement(sql)) {
                p.setLong(1, correoId);
                try (ResultSet r = p.executeQuery()) {
                    return r.next() ? r.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void ejecutar(String sql, long id) {
        try (PreparedStatement p = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            p.setLong(1, id);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
