package com.feder.negocio.mensajeria;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.datos.mensajeria.MensajeCorreoRepositorio;
import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Un fallo técnico transitorio (bloqueo mutuo, conexión perdida) NO debe marcar el correo como
 * procesado: la transacción completa se revierte y el mensaje se reintenta en el ciclo siguiente.
 * En cambio, un error de datos del usuario sí se responde y el correo queda procesado.
 */
class FallosTransitoriosTest {

    private static final GeneradorRespuesta GENERADOR = new GeneradorRespuesta() {
        @Override
        public Respuesta generar(ResultadoOperacion resultado, String comandoRecibido) {
            return new Respuesta("FEDER - " + resultado.titulo(), resultado.mensaje(), null);
        }

        @Override
        public String nuevoMessageId() {
            return "<" + UUID.randomUUID() + "@feder.local>";
        }
    };

    private static ServicioMensajeria servicioCon(ContextoNegocio contexto, SQLException falla) {
        CatalogoComandos catalogo = new CatalogoComandos();
        catalogo.registrar(ComandoSpec.comando("PRUEBA").casoDeUso("SISTEMA").descripcion("Simula un fallo")
                .parametros(ParametroSpec.texto("x", 10).conComodin()).publico().ejemplo("PRUEBA[\"*\"]")
                .handler((p, s) -> {
                    throw new AccesoDatosException("Fallo simulado", falla);
                })
                .construir());
        ServicioConfirmacion confirmaciones = new ServicioConfirmacion(new ConfirmacionRepositorio(), Clock.systemUTC(),
                Duration.ofMinutes(30), 30);
        ComandoDispatcher dispatcher = new ComandoDispatcher(catalogo, new ServicioIdentidad(new IdentidadRepositorio()),
                confirmaciones, contexto.transacciones());
        return new ServicioMensajeria(contexto.transacciones(), new MensajeCorreoRepositorio(),
                new CorreoSalienteRepositorio(), dispatcher, Clock.systemUTC(), 30, 24);
    }

    private static SolicitudCorreo solicitud(String uidl) {
        return SolicitudCorreo.conComando("pruebas@local:0", uidl, "<" + uidl + "@feder.example>",
                "elmer.rojas@feder.example", "PRUEBA[\"*\"]", "PRUEBA", List.of("*"));
    }

    @Test
    void bloqueoMutuoRevierteTodoYPermiteReintentar() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            ServicioMensajeria mensajeria = servicioCon(contexto, new SQLException("deadlock detected", "40P01"));
            String uidl = "transitorio-" + UUID.randomUUID();
            // Ejecutamos en un punto de guardado para simular la transacción propia del mensaje.
            assertThrows(AccesoDatosException.class,
                    () -> contexto.transacciones().conPuntoDeGuardado(() -> mensajeria.procesar(solicitud(uidl), GENERADOR)));
            assertFalse(mensajeria.uidlsRegistrados("pruebas@local:0").contains(uidl),
                    "el mensaje no debe quedar registrado como procesado");
        });
    }

    @Test
    void violacionDeDatosSeRespondeYQuedaProcesada() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            ServicioMensajeria mensajeria = servicioCon(contexto, new SQLException("duplicate key", "23505"));
            String uidl = "terminal-" + UUID.randomUUID();
            assertTrue(mensajeria.procesar(solicitud(uidl), GENERADOR));
            assertTrue(mensajeria.uidlsRegistrados("pruebas@local:0").contains(uidl));
        });
    }

    @Test
    void clasificacionDeSqlstate() {
        assertTrue(new AccesoDatosException("x", new SQLException("x", "40001")).esTransitoria());
        assertTrue(new AccesoDatosException("x", new SQLException("x", "08006")).esTransitoria());
        assertTrue(new AccesoDatosException("x", new SQLException("x", "57014")).esTransitoria());
        assertFalse(new AccesoDatosException("x", new SQLException("x", "23505")).esTransitoria());
        assertFalse(new AccesoDatosException("x", new SQLException("x", "P0001")).esTransitoria());
        assertTrue(new AccesoDatosException("x", new java.sql.SQLTransientConnectionException("sin conexión")).esTransitoria());
        assertFalse(new AccesoDatosException("x", new SQLException("desconocido")).esTransitoria());
        assertEquals(true, new AccesoDatosException("x", new SQLException("x", "23001")).esViolacionClaveForanea());
    }
}
