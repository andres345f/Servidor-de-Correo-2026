package com.feder.negocio.productores;

import static com.feder.negocio.usuarios.ApoyoPruebas.DANIELA;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.feder.modelo.EstadoSolicitudProductor;
import com.feder.negocio.ContextoNegocio;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * R008/R075 con dos transacciones reales: mientras una desactivación de la comunidad está en curso (fila bloqueada y
 * sin confirmar), una solicitud de productor hacia ella espera en lugar de leer la comunidad todavía activa. Ambas
 * transacciones se revierten, así la base queda como la dejó la semilla.
 */
class ComunidadConcurrenciaTest {

    @Test
    @DisplayName("La solicitud de productor espera a una desactivación de la comunidad en curso")
    void solicitudEsperaLaDesactivacionEnCurso() throws Exception {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        ServicioProductores productores = new ServicioProductores(ctx);
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try (Connection otra = BaseDatosPrueba.conexion().abrir()) {
            otra.setAutoCommit(false);
            otra.createStatement().executeUpdate("UPDATE comunidad_agricola SET activa = FALSE WHERE id = 2");
            Future<EstadoSolicitudProductor> solicitud = hilo.submit(() -> enTransaccionRevertida(ctx, () -> productores.solicitar(
                    solicitante(DANIELA), 2, "Colmenas familiares junto al río").productor().getEstadoSolicitud()));
            assertThrows(TimeoutException.class, () -> solicitud.get(700, TimeUnit.MILLISECONDS),
                    "La solicitud debe esperar el bloqueo de la comunidad");
            otra.rollback();
            assertEquals(EstadoSolicitudProductor.PENDIENTE, solicitud.get(10, TimeUnit.SECONDS));
        } finally {
            hilo.shutdownNow();
        }
    }
}
