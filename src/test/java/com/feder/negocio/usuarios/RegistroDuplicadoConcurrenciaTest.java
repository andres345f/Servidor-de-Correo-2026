package com.feder.negocio.usuarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.usuarios.BandejaPruebas.Procesado;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * R095 con dos transacciones reales: otra operación registra la misma cédula sin confirmar todavía. El registro
 * por correo no la ve al comprobar, espera en el INSERT y, cuando la otra confirma, la unicidad lo rechaza. Debe
 * responder igual el mensaje genérico y avisar a la cuenta que acaba de quedar registrada. Se usa la base solo con
 * esquema, que se vacía al terminar.
 */
class RegistroDuplicadoConcurrenciaTest {

    private static final String REMITENTE = "carrera.registro@feder.example";
    private static final String TITULAR = "titular.carrera@feder.example";
    private static final String CI = "99000080";
    private static final long ESPERA_MAXIMA_MS = 15_000;

    private ContextoNegocio ctx;
    private boolean baseVaciaUsada;

    @BeforeEach
    void preparar() throws Exception {
        ctx = BaseVacia.contexto();
        baseVaciaUsada = true;
        BaseVacia.vaciar();
    }

    @AfterEach
    void limpiar() throws Exception {
        // Si preparar() se omitió por una suposición fallida, esta no es la base solo con esquema: no se toca.
        if (baseVaciaUsada) {
            BaseVacia.vaciar();
        }
    }

    @Test
    @DisplayName("La violación de unicidad simultánea responde el mensaje genérico y avisa a la cuenta ganadora")
    void registroSimultaneoConLaMismaCedula() throws Exception {
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try (Connection otra = BaseVacia.conexionDirecta()) {
            otra.setAutoCommit(false);
            try (Statement s = otra.createStatement()) {
                s.executeUpdate("INSERT INTO usuario (correo, nombres, apellidos, ci, telefono, activo) VALUES ('"
                        + TITULAR + "', 'Titular', 'Carrera', " + CI + ", 70000180, false)");
            }
            Future<Procesado> registro = hilo.submit(() -> BaseDatosPrueba.enTransaccionRevertida(ctx,
                    () -> new BandejaPruebas(ctx).confirmarPorCorreo(REMITENTE, "REGCLI", CI, "", "Lucas", "Pérez Vaca",
                            "71234567", "true")));
            // Solo se confirma la otra transacción cuando el INSERT del registro ya espera su bloqueo: así la
            // comprobación previa no vio la cédula y se ejercita siempre la rama de violación de unicidad.
            esperarInsertBloqueado(registro);
            otra.commit();
            Procesado p = registro.get(10, TimeUnit.SECONDS);
            assertEquals(REMITENTE, p.respuesta().destinatario());
            assertTrue(p.respuesta().cuerpo().startsWith(ServicioCuentas.MENSAJE_REGISTRO_GENERICO), p.respuesta()::cuerpo);
            assertTrue(p.unicoAvisoA(TITULAR).cuerpo().contains("con su cédula de identidad"));
            assertEquals(1, p.avisos().size());
        } finally {
            hilo.shutdownNow();
        }
        assertEquals(1, cuentasConLaCedula(), "Solo existe la cuenta que confirmó primero");
    }

    private static void esperarInsertBloqueado(Future<?> registro) throws Exception {
        long limite = System.currentTimeMillis() + ESPERA_MAXIMA_MS;
        try (Connection c = BaseVacia.conexionDirecta(); Statement s = c.createStatement()) {
            while (System.currentTimeMillis() < limite) {
                assertFalse(registro.isDone(), "El registro terminó sin esperar el INSERT de la otra transacción");
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                        + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND query LIKE 'INSERT INTO usuario (%'")) {
                    r.next();
                    if (r.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.sleep(50);
            }
        }
        throw new AssertionError("El INSERT del registro no llegó a esperar el bloqueo en " + ESPERA_MAXIMA_MS + " ms");
    }

    private static int cuentasConLaCedula() throws Exception {
        try (Connection c = BaseVacia.conexionDirecta(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM usuario WHERE ci = " + CI)) {
            r.next();
            return r.getInt(1);
        }
    }
}
