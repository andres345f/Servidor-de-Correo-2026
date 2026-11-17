package com.feder.negocio.produccion;

import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.feder.modelo.EstadoProduccion;
import com.feder.negocio.ContextoNegocio;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.time.LocalDate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * R061/R073 con dos transacciones reales: mientras una revocación de PRODUCTOR tiene bloqueado el perfil y lo deja
 * inactivo sin confirmar, el alta de producción espera en lugar de leer el perfil anterior. La otra transacción se
 * revierte y la del alta también, así la base queda como la dejó la semilla.
 */
class TitularConcurrenciaTest {

    @Test
    @DisplayName("El alta de producción espera a una desactivación del perfil en curso")
    void altaEsperaLaRevocacionEnCurso() throws Exception {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        ServicioProduccion produccion = new ServicioProduccion(ctx);
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try (Connection otra = BaseDatosPrueba.conexion().abrir()) {
            otra.setAutoCommit(false);
            otra.createStatement().executeUpdate("UPDATE productor SET activo = FALSE WHERE id = 1");
            Future<EstadoProduccion> alta = hilo.submit(() -> enTransaccionRevertida(ctx, () -> produccion.registrarPlanificada(
                    solicitante(MARIA), new ServicioProduccion.DatosPlan("FED-ACE-100", LocalDate.parse("2026-12-01"), null, null,
                            null, null)).produccion().produccion().getEstado()));
            assertThrows(TimeoutException.class, () -> alta.get(700, TimeUnit.MILLISECONDS),
                    "El alta debe esperar el bloqueo del perfil del productor");
            otra.rollback();
            assertEquals(EstadoProduccion.PLANIFICADA, alta.get(10, TimeUnit.SECONDS));
        } finally {
            hilo.shutdownNow();
        }
    }
}
