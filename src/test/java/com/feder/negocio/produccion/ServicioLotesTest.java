package com.feder.negocio.produccion;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.ROSA;
import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.negocio.usuarios.ApoyoPruebas.texto;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.EstadoLoteProduccion;
import com.feder.modelo.LoteProduccion;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.usuarios.ApoyoPruebas;
import com.feder.negocio.usuarios.ServicioRoles;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Asignación, corrección y retiro de lotes de origen (R039, R040). */
class ServicioLotesTest {

    private ContextoNegocio ctx;
    private ServicioLotes lotes;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        lotes = new ServicioLotes(ctx);
    }

    private LocalDate ayer() {
        return FormatoNegocio.hoy(ctx.reloj()).minusDays(1);
    }

    private ServicioLotes.DatosLote lote(LocalDate elaboracion, String producida, String ofrecida) {
        return new ServicioLotes.DatosLote(elaboracion, null, new BigDecimal(producida), new BigDecimal(ofrecida), null);
    }

    private static ServicioLotes.CambiosLote cambios(LocalDate elaboracion, String producida, String ofrecida, String motivo) {
        return new ServicioLotes.CambiosLote(elaboracion, null, producida == null ? null : new BigDecimal(producida),
                ofrecida == null ? null : new BigDecimal(ofrecida), null, motivo);
    }

    @Test
    @DisplayName("R040: el titular asigna un lote a su producción EN_CURSO, con código de trazabilidad y bitácora")
    void registrarLote() {
        enTransaccionRevertida(ctx, () -> {
            LoteProduccion l = lotes.registrar(solicitante(MARIA), "PRD-2026-011", lote(ayer(), "30", "25"));
            assertTrue(l.getCodigoLote().matches("LP-\\d{4}-\\d{3,}"));
            assertEquals(EstadoLoteProduccion.DISPONIBLE, l.getEstado());
            assertEquals(11L, l.getProduccionId());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'CREAR' AND entidad = 'LOTE_PRODUCCION' "
                    + "AND entidad_id = ? AND usuario_id = 6", l.getId()));
        });
    }

    @Test
    @DisplayName("R040: solo en producciones EN_CURSO o TERMINADA propias, con elaboración desde el inicio y cantidades enteras")
    void lotesRechazados() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.ESTADO_INVALIDO, "EN_CURSO o TERMINADA", () -> lotes.registrar(solicitante(ROSA), "PRD-2026-012", lote(ayer(), "10", "5")));
            falla(Codigo.SIN_PERMISO, "titular", () -> lotes.registrar(solicitante(MARIA), "PRD-2026-010", lote(ayer(), "10", "5")));
            falla(Codigo.REGLA_DE_NEGOCIO, "fecha de inicio", () ->
                    lotes.registrar(solicitante(MARIA), "PRD-2026-011", lote(LocalDate.parse("2026-10-01"), "10", "5")));
            falla(Codigo.PARAMETRO_INVALIDO, "entera", () -> lotes.registrar(solicitante(MARIA), "PRD-2026-011", lote(ayer(), "10.5", "5")));
            falla(Codigo.SIN_PERMISO, "PRODUCTOR", () -> lotes.registrar(solicitante(CARLA), "PRD-2026-011", lote(ayer(), "10", "5")));
        });
    }

    @Test
    @DisplayName("R040: antes de la primera compra el titular corrige su lote; el ADMINISTRADOR no interviene")
    void corregirLoteSinCompras() {
        enTransaccionRevertida(ctx, () -> {
            LoteProduccion l = lotes.registrar(solicitante(MARIA), "PRD-2026-011", lote(ayer(), "30", "25"));
            lotes.modificar(solicitante(MARIA), l.getCodigoLote(), cambios(ayer().minusDays(1), "35", null, null));
            assertEquals("35", texto("SELECT detalle ->> 'cantidad_producida_nuevo' FROM bitacora WHERE entidad = 'LOTE_PRODUCCION' "
                    + "AND entidad_id = ? AND accion = 'MODIFICAR'", l.getId()));
            falla(Codigo.REGLA_DE_NEGOCIO, "productor titular", () ->
                    lotes.modificar(solicitante(ADMIN), l.getCodigoLote(), cambios(null, "40", null, "Corrección")));
            falla(Codigo.SIN_PERMISO, "titular", () -> lotes.modificar(solicitante(CARLA), l.getCodigoLote(), cambios(null, "40", null, null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "iguales", () -> lotes.modificar(solicitante(MARIA), l.getCodigoLote(), cambios(null, "35.000", null, null)));
        });
    }

    @Test
    @DisplayName("R040/R039: lote ya comprado: el titular solo ajusta la oferta sin bajar de lo comprometido; AGOTADO al cubrirse")
    void loteCompradoPorElTitular() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.REGLA_DE_NEGOCIO, "solo los corrige un ADMINISTRADOR", () ->
                    lotes.modificar(solicitante(MARIA), "LP-2026-011", cambios(ayer(), null, null, null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "menor que lo ya comprometido", () ->
                    lotes.modificar(solicitante(MARIA), "LP-2026-011", cambios(null, null, "20", null)));
            assertEquals(EstadoLoteProduccion.AGOTADO, lotes.modificar(solicitante(MARIA), "LP-2026-011",
                    cambios(null, null, "30", "Solo se ofrece lo ya comprado")).lote().getEstado());
            assertEquals(EstadoLoteProduccion.DISPONIBLE, lotes.modificar(solicitante(MARIA), "LP-2026-011",
                    cambios(null, null, "40", "Se vuelve a ofrecer el resto")).lote().getEstado());
        });
    }

    @Test
    @DisplayName("R040: tras la compra, el ADMINISTRADOR corrige con motivo elaboración, vencimiento o cantidad producida")
    void correccionAdministrativa() {
        enTransaccionRevertida(ctx, () -> {
            ServicioLotes.CambiosLote vencimiento = new ServicioLotes.CambiosLote(null, LocalDate.parse("2027-10-05"), null, null, null,
                    "Error de transcripción en la etiqueta");
            assertEquals(LocalDate.parse("2027-10-05"), lotes.modificar(solicitante(ADMIN), "LP-2026-011", vencimiento).lote()
                    .getFechaVencimiento());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'LOTE_PRODUCCION' AND entidad_id = 11 "
                    + "AND usuario_id = 1 AND detalle ->> 'motivo' = 'Error de transcripción en la etiqueta'"));
            falla(Codigo.PARAMETRO_INVALIDO, "motivo", () -> lotes.modificar(solicitante(ADMIN), "LP-2026-011", cambios(null, "39", null, null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "la oferta la decide el productor", () ->
                    lotes.modificar(solicitante(ADMIN), "LP-2026-011", cambios(null, null, "35", "Motivo")));
        });
    }

    @Test
    @DisplayName("El titular retira un lote DISPONIBLE; un lote retirado ya no se modifica")
    void retirarLote() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.SIN_PERMISO, "titular", () -> lotes.retirar(solicitante(ROSA), "LP-2026-002", "Motivo"));
            assertEquals(EstadoLoteProduccion.RETIRADO, lotes.retirar(solicitante(MARIA), "LP-2026-002", "Destinado a una feria").lote().getEstado());
            falla(Codigo.ESTADO_INVALIDO, "Solo se retira un lote DISPONIBLE", () -> lotes.retirar(solicitante(MARIA), "LP-2026-002", "Otra vez"));
            falla(Codigo.ESTADO_INVALIDO, "retirado", () -> lotes.modificar(solicitante(MARIA), "LP-2026-002",
                    new ServicioLotes.CambiosLote(null, null, null, null, new BigDecimal("8"), null)));
            falla(Codigo.NO_ENCONTRADO, "No existe el lote", () -> lotes.retirar(solicitante(MARIA), "LP-0000-000", "Motivo"));
        });
    }

    @Test
    @DisplayName("Listado de lotes: el productor solo los suyos; el personal interno todos")
    void listado() {
        enTransaccionRevertida(ctx, () -> {
            assertEquals(4, lotes.listar(solicitante(ROSA), null).size());
            assertEquals(11, lotes.listar(solicitante(CARLA), null).size());
            assertEquals(2, lotes.listar(solicitante(CARLA), "PRD-2026-005").size());
            assertEquals(0, lotes.listar(solicitante(ROSA), "PRD-2026-005").size(), "No ve lotes de otro productor");
        });
    }

    @Test
    @DisplayName("R040: un lote comprado y luego retirado sigue admitiendo la corrección del ADMINISTRADOR, no la del titular")
    void loteRetiradoCorregidoPorAdministrador() {
        enTransaccionRevertida(ctx, () -> {
            lotes.retirar(solicitante(MARIA), "LP-2026-002", "Destinado a una feria");
            ServicioLotes.CambiosLote vencimiento = new ServicioLotes.CambiosLote(null, LocalDate.parse("2027-03-20"), null, null, null,
                    "Vencimiento omitido al registrar");
            LoteProduccion corregido = lotes.modificar(solicitante(ADMIN), "LP-2026-002", vencimiento).lote();
            assertEquals(LocalDate.parse("2027-03-20"), corregido.getFechaVencimiento());
            assertEquals(EstadoLoteProduccion.RETIRADO, corregido.getEstado(), "La corrección no cambia el estado del lote");
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'LOTE_PRODUCCION' AND entidad_id = 2 "
                    + "AND usuario_id = 1 AND detalle ->> 'motivo' = 'Vencimiento omitido al registrar'"));
            falla(Codigo.ESTADO_INVALIDO, "retirado", () -> lotes.modificar(solicitante(MARIA), "LP-2026-002",
                    new ServicioLotes.CambiosLote(null, null, null, new BigDecimal("280"), null, null)));
        });
    }

    @Test
    @DisplayName("R088: un ADMINISTRADOR no corrige como tal los lotes de su propia producción")
    void administradorTitularNoCorrigeSusLotes() {
        enTransaccionRevertida(ctx, () -> {
            ApoyoPruebas.mariaTambienAdministradora(ctx);
            new ServicioRoles(ctx).revocar(solicitante(ADMIN), MARIA, TipoRol.PRODUCTOR, "Revisión de calidad");
            ServicioLotes.CambiosLote vencimiento = new ServicioLotes.CambiosLote(null, LocalDate.parse("2027-10-05"), null, null, null,
                    "Corrección de la etiqueta");
            falla(Codigo.SIN_PERMISO, "propia producción", () -> lotes.modificar(solicitante(MARIA), "LP-2026-011", vencimiento));
        });
    }
}