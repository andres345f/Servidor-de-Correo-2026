package com.feder.negocio.produccion;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.DANIELA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.PEDRO;
import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.productores.ServicioComunidades;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

/** R066: trazabilidad de origen con la instantánea y la vista según quién consulta. */
class ServicioTrazabilidadTest {

    private ContextoNegocio ctx;
    private ServicioTrazabilidad trazabilidad;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        trazabilidad = new ServicioTrazabilidad(ctx);
    }

    @Test
    @DisplayName("El recorrido lote -> producción -> comunidad usa la instantánea de origen y la procedencia declarada")
    void recorridoDeOrigen() {
        enTransaccionRevertida(ctx, () -> {
            ServicioTrazabilidad.Trazabilidad t = trazabilidad.trazar(solicitante("visitante@feder.example"), "LP-2026-001");
            assertEquals("PRD-2026-001", t.traza().produccion().getCodigo());
            assertEquals("Comunidad Totaizal de San Juan", t.traza().produccion().getNombreComunidadOrigen());
            assertEquals("FED-ACE-020", t.traza().codigoProducto());
            assertEquals(OrigenProduccion.ORIGEN_VIGENTE, t.procedencia());
            assertTrue(t.traza().comunidadOrigenActiva());
            assertEquals(0, new BigDecimal("200").compareTo(t.traza().compradoPorFeder()));
            falla(Codigo.NO_ENCONTRADO, "No existe el lote", () -> trazabilidad.trazar(solicitante(MARIA), "LP-9999-999"));
        });
    }

    @Test
    @DisplayName("Visitantes, clientes y otros productores no ven los datos del productor; el titular y el personal sí")
    void vistaSegunQuienConsulta() {
        enTransaccionRevertida(ctx, () -> {
            assertFalse(trazabilidad.trazar(solicitante("visitante@feder.example"), "LP-2026-001").vistaCompleta());
            assertFalse(trazabilidad.trazar(solicitante(DANIELA), "LP-2026-001").vistaCompleta());
            assertFalse(trazabilidad.trazar(solicitante(PEDRO), "LP-2026-001").vistaCompleta());
            assertTrue(trazabilidad.trazar(solicitante(MARIA), "LP-2026-001").vistaCompleta());
            assertTrue(trazabilidad.trazar(solicitante(CARLA), "LP-2026-001").vistaCompleta());
        });
    }

    @Test
    @DisplayName("Indica si la comunidad de origen está hoy inactiva, sin cambiar la instantánea")
    void comunidadInactiva() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioComunidades(ctx).desactivar(solicitante(ADMIN), 1, "Fusión con otra comunidad");
            ServicioTrazabilidad.Trazabilidad t = trazabilidad.trazar(solicitante(DANIELA), "LP-2026-001");
            assertFalse(t.traza().comunidadOrigenActiva());
            assertEquals("Comunidad Totaizal de San Juan", t.traza().produccion().getNombreComunidadOrigen());
        });
    }
}
