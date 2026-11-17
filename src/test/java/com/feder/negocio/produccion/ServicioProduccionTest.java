package com.feder.negocio.produccion;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.PEDRO;
import static com.feder.negocio.usuarios.ApoyoPruebas.ROSA;
import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.negocio.usuarios.ApoyoPruebas.texto;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.produccion.ProduccionRepositorio.ProduccionResumen;
import com.feder.modelo.EstadoLoteProduccion;
import com.feder.modelo.EstadoProduccion;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.negocio.usuarios.ServicioRoles;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Alta, estados, edición y balance de la producción (R061, R063 a R066). */
class ServicioProduccionTest {

    private ContextoNegocio ctx;
    private ServicioProduccion produccion;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        produccion = new ServicioProduccion(ctx);
    }

    private static ServicioProduccion.DatosPlan plan(String producto, String cantidad) {
        return new ServicioProduccion.DatosPlan(producto, LocalDate.parse("2026-12-01"), LocalDate.parse("2027-01-15"),
                cantidad == null ? null : new BigDecimal(cantidad), null, null);
    }

    private LocalDate ayer() {
        return FormatoNegocio.hoy(ctx.reloj()).minusDays(1);
    }

    private ServicioProduccion.DatosTerminada terminada(String producto, LocalDate elaboracion, LocalDate vencimiento,
                                                        String producida, String ofrecida, Long comunidad) {
        return new ServicioProduccion.DatosTerminada(producto, new ServicioLotes.DatosLote(elaboracion, vencimiento,
                new BigDecimal(producida), new BigDecimal(ofrecida), new BigDecimal("9.505")), null, comunidad, null, null);
    }

    @Test
    @DisplayName("R061/R066: alta PLANIFICADA del propio productor con instantánea de su comunidad y bitácora CREAR")
    void altaPlanificada() {
        enTransaccionRevertida(ctx, () -> {
            ServicioProduccion.Alta alta = produccion.registrarPlanificada(solicitante(MARIA), plan("FED-ACE-100", "100"));
            ProduccionResumen r = alta.produccion();
            assertEquals(EstadoProduccion.PLANIFICADA, r.produccion().getEstado());
            assertTrue(r.produccion().getCodigo().matches("PRD-\\d{4}-\\d{3,}"), r.produccion().getCodigo());
            assertEquals(1L, r.produccion().getComunidadOrigenId());
            assertEquals("Comunidad Totaizal de San Juan", r.produccion().getNombreComunidadOrigen());
            assertEquals("San José de Chiquitos", r.produccion().getMunicipioOrigen());
            assertNull(r.produccion().getUbicacionProduccion(), "La ubicación no se inventa");
            assertEquals(OrigenProduccion.ORIGEN_VIGENTE, texto("SELECT detalle ->> 'origen' FROM bitacora WHERE accion = 'CREAR' "
                    + "AND entidad = 'PRODUCCION' AND entidad_id = ? AND usuario_id = 6", r.produccion().getId()));
            assertEquals(0, numero("SELECT count(*) FROM lote_feder lf JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
                    + "JOIN lote_produccion lp ON lp.id = dc.lote_origen_id WHERE lp.produccion_id = ?", r.produccion().getId()),
                    "Registrar producción no crea inventario de FEDER");
        });
    }

    @Test
    @DisplayName("R061: producto activo, sin registro delegado, cantidades enteras por UNIDAD y fechas coherentes")
    void altasRechazadas() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.REGLA_DE_NEGOCIO, "inactivo", () -> produccion.registrarPlanificada(solicitante(MARIA), plan("FED-MER-MAN", null)));
            falla(Codigo.NO_ENCONTRADO, "No existe el producto", () -> produccion.registrarPlanificada(solicitante(MARIA), plan("FED-XXX", null)));
            falla(Codigo.SIN_PERMISO, "PRODUCTOR", () -> produccion.registrarPlanificada(solicitante(CARLA), plan("FED-ACE-100", null)));
            falla(Codigo.SIN_PERMISO, "PRODUCTOR", () -> produccion.registrarPlanificada(solicitante(ADMIN), plan("FED-ACE-100", null)));
            falla(Codigo.PARAMETRO_INVALIDO, "entera", () -> produccion.registrarPlanificada(solicitante(MARIA), plan("FED-ACE-100", "10.5")));
            falla(Codigo.REGLA_DE_NEGOCIO, "disponibilidad", () -> produccion.registrarPlanificada(solicitante(MARIA),
                    new ServicioProduccion.DatosPlan("FED-ACE-100", LocalDate.parse("2026-12-01"), LocalDate.parse("2026-11-01"),
                            null, null, null)));
            ServicioProduccion.Alta granel = produccion.registrarPlanificada(solicitante(PEDRO), plan("FED-MIE-GRA", "10.5"));
            assertEquals(0, new BigDecimal("10.5").compareTo(granel.produccion().produccion().getCantidadEstimada()));
        });
    }

    @Test
    @DisplayName("R061: un productor cuyo rol PRODUCTOR fue revocado ya no registra producción")
    void productorRevocadoNoRegistra() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioRoles(ctx).revocar(solicitante(ADMIN), MARIA, TipoRol.PRODUCTOR, "Revisión de calidad");
            falla(Codigo.SIN_PERMISO, "PRODUCTOR", () -> produccion.registrarPlanificada(solicitante(MARIA), plan("FED-ACE-100", null)));
        });
    }

    @Test
    @DisplayName("R061/R040: alta directa TERMINADA con su primer lote en la misma transacción, sin inventar la fecha de inicio")
    void altaTerminadaConLote() {
        enTransaccionRevertida(ctx, () -> {
            ServicioProduccion.Alta alta = produccion.registrarTerminada(solicitante(MARIA),
                    terminada("FED-JAB-090", ayer(), null, "200", "150", null));
            assertEquals(EstadoProduccion.TERMINADA, alta.produccion().produccion().getEstado());
            assertNull(alta.produccion().produccion().getFechaInicio());
            assertEquals(EstadoLoteProduccion.DISPONIBLE, alta.lote().getEstado());
            assertEquals(new BigDecimal("9.51"), alta.lote().getPrecioOfrecido(), "Precio con 2 decimales, half-up");
            assertTrue(alta.lote().getCodigoLote().matches("LP-\\d{4}-\\d{3,}"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'CREAR' AND entidad = 'LOTE_PRODUCCION' AND entidad_id = ?",
                    alta.lote().getId()));
        });
    }

    @Test
    @DisplayName("Datos del lote inválidos impiden también el alta de la producción TERMINADA")
    void altaTerminadaRechazada() {
        enTransaccionRevertida(ctx, () -> {
            long antes = numero("SELECT count(*) FROM produccion");
            LocalDate hoy = FormatoNegocio.hoy(ctx.reloj());
            falla(Codigo.REGLA_DE_NEGOCIO, "elaboración", () -> produccion.registrarTerminada(solicitante(MARIA),
                    terminada("FED-JAB-090", null, hoy.plusYears(1), "200", "150", null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "posterior a hoy", () -> produccion.registrarTerminada(solicitante(MARIA),
                    terminada("FED-JAB-090", hoy.plusDays(2), null, "200", "150", null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "no puede superar", () -> produccion.registrarTerminada(solicitante(MARIA),
                    terminada("FED-JAB-090", ayer(), null, "100", "150", null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "vencimiento", () -> produccion.registrarTerminada(solicitante(MARIA),
                    terminada("FED-JAB-090", ayer(), ayer().minusDays(5), "100", "50", null)));
            assertEquals(antes, numero("SELECT count(*) FROM produccion"));
        });
    }

    @Test
    @DisplayName("R066: otra comunidad de origen se admite si el historial de cambios de comunidad la respalda")
    void origenValidadoPorHistorial() {
        enTransaccionRevertida(ctx, () -> {
            new com.feder.negocio.productores.ServicioProductores(ctx)
                    .cambiarComunidad(solicitante(ADMIN), 2, 3, "Trasladó su apiario a El Palmar");
            ServicioProduccion.Alta alta = produccion.registrarTerminada(solicitante(PEDRO),
                    terminada("FED-MIE-500", ayer(), null, "50", "40", 2L));
            assertEquals(2L, alta.produccion().produccion().getComunidadOrigenId());
            assertEquals("Comunidad Villa Colmenar", alta.produccion().produccion().getNombreComunidadOrigen());
            assertEquals(OrigenProduccion.ORIGEN_HISTORIAL, alta.origen());
            ServicioProduccion.Alta vigente = produccion.registrarTerminada(solicitante(PEDRO),
                    terminada("FED-MIE-500", ayer(), null, "50", "40", null));
            assertEquals(3L, vigente.produccion().produccion().getComunidadOrigenId(), "Por defecto, la comunidad vigente");
            assertEquals(0, numero("SELECT count(*) FROM produccion WHERE productor_id = 2 AND id < 13 AND comunidad_origen_id <> 2"),
                    "El cambio de comunidad no altera la producción ya registrada");
        });
    }

    @Test
    @DisplayName("R066: sin respaldo en el historial hace falta la validación previa de un ADMINISTRADOR (VALORI)")
    void origenValidadoPorAdministrador() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.REGLA_DE_NEGOCIO, "VALORI", () -> produccion.registrarTerminada(solicitante(PEDRO),
                    terminada("FED-MIE-500", ayer(), null, "50", "40", 1L)));
            falla(Codigo.REGLA_DE_NEGOCIO, "fecha de elaboración", () -> produccion.registrarTerminada(solicitante(PEDRO),
                    terminada("FED-MIE-500", null, null, "50", "40", 1L)));
            ServicioCorreccionOrigen correcciones = new ServicioCorreccionOrigen(ctx);
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> correcciones.validarOrigen(solicitante(CARLA), 2, 1, ayer(), "M", "E"));
            correcciones.validarOrigen(solicitante(ADMIN), 2, 1, ayer(), "Trabajó en el taller de Totaizal", "Acta comunal 14");
            ServicioProduccion.Alta alta = produccion.registrarTerminada(solicitante(PEDRO),
                    terminada("FED-MIE-500", ayer(), null, "50", "40", 1L));
            assertEquals(1L, alta.produccion().produccion().getComunidadOrigenId());
            assertEquals(OrigenProduccion.ORIGEN_ADMINISTRADOR, alta.origen());
            assertEquals("1", texto("SELECT detalle ->> 'validado_por' FROM bitacora WHERE entidad = 'PRODUCCION' AND entidad_id = ?",
                    alta.produccion().produccion().getId()));
        });
    }

    @Test
    @DisplayName("Máquina de estados: PLANIFICADA -> EN_CURSO -> TERMINADA; cancelar solo abierta y con motivo; solo el titular")
    void transicionesDeEstado() {
        enTransaccionRevertida(ctx, () -> {
            assertEquals(EstadoProduccion.EN_CURSO, produccion.cambiarEstado(solicitante(ROSA), "PRD-2026-012",
                    EstadoProduccion.EN_CURSO, null).produccion().produccion().getEstado());
            falla(Codigo.ESTADO_INVALIDO, "no puede pasar a PLANIFICADA", () ->
                    produccion.cambiarEstado(solicitante(ROSA), "PRD-2026-012", EstadoProduccion.PLANIFICADA, null));
            falla(Codigo.ESTADO_INVALIDO, "TERMINADA", () ->
                    produccion.cambiarEstado(solicitante(MARIA), "PRD-2026-001", EstadoProduccion.CANCELADA, "Error"));
            falla(Codigo.PARAMETRO_INVALIDO, "motivo de la cancelación", () ->
                    produccion.cambiarEstado(solicitante(MARIA), "PRD-2026-011", EstadoProduccion.CANCELADA, null));
            falla(Codigo.SIN_PERMISO, "titular", () ->
                    produccion.cambiarEstado(solicitante(MARIA), "PRD-2026-012", EstadoProduccion.TERMINADA, null));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PRODUCCION' AND entidad_id = 12 "
                    + "AND accion = 'MODIFICAR' AND detalle ->> 'estado_nuevo' = 'EN_CURSO'"));
        });
    }

    @Test
    @DisplayName("R064: reducir la estimación por debajo del compromiso quita la fecha estimada del encargo y notifica al cliente")
    void reduccionReaccionaSobreEncargos() {
        enTransaccionRevertida(ctx, () -> {
            long avisos = numero("SELECT count(*) FROM notificacion WHERE usuario_id = 11 AND pedido_id = 8");
            ServicioProduccion.Cambio c = produccion.modificar(solicitante(MARIA), "PRD-2026-011",
                    new ServicioProduccion.Cambios(null, null, new BigDecimal("10"), null, null));
            assertEquals(1, c.encargosSinFecha());
            assertNull(texto("SELECT fecha_estimada_disponibilidad FROM pedido WHERE id = 8"));
            assertEquals("ESPERANDO_ABASTECIMIENTO", texto("SELECT estado FROM pedido WHERE id = 8"), "El pedido conserva su estado");
            assertEquals(avisos + 1, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 11 AND pedido_id = 8 "
                    + "AND tipo = 'FECHA_ESTIMADA_ACTUALIZADA'"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PEDIDO' AND entidad_id = 8 AND usuario_id = 6"));
        });
    }

    @Test
    @DisplayName("R064: cancelar cuando lo comprometido ya está en tránsito no afecta a los encargos")
    void cancelacionSinReaccion() {
        enTransaccionRevertida(ctx, () -> {
            ServicioProduccion.Cambio c = produccion.cambiarEstado(solicitante(MARIA), "PRD-2026-011", EstadoProduccion.CANCELADA,
                    "Plaga en los frutos de totaí");
            assertEquals(0, c.encargosSinFecha());
            assertEquals("2026-12-12", texto("SELECT fecha_estimada_disponibilidad::text FROM pedido WHERE id = 8"));
        });
    }

    @Test
    @DisplayName("R065: con lotes la ubicación solo la corrige el ADMINISTRADOR; la fecha de inicio no supera la elaboración")
    void edicionDelPlan() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.REGLA_DE_NEGOCIO, "CORPCN", () -> produccion.modificar(solicitante(MARIA), "PRD-2026-011",
                    new ServicioProduccion.Cambios(null, null, null, "Otro taller", null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "posterior a la elaboración", () -> produccion.modificar(solicitante(MARIA), "PRD-2026-011",
                    new ServicioProduccion.Cambios(LocalDate.parse("2026-11-10"), null, null, null, null)));
            falla(Codigo.ESTADO_INVALIDO, "PLANIFICADA o EN_CURSO", () -> produccion.modificar(solicitante(MARIA), "PRD-2026-001",
                    new ServicioProduccion.Cambios(null, null, new BigDecimal("5"), null, null)));
            ServicioProduccion.Cambio c = produccion.modificar(solicitante(MARIA), "PRD-2026-011",
                    new ServicioProduccion.Cambios(null, LocalDate.parse("2026-12-15"), null, null, "Retraso por lluvias"));
            assertEquals(LocalDate.parse("2026-12-15"), c.produccion().produccion().getFechaDisponibilidadEstimada());
            ProduccionResumen corregida = new ServicioCorreccionOrigen(ctx).corregir(solicitante(ADMIN), "PRD-2026-011",
                    CampoCorregible.UBICACION_PRODUCCION, "Taller familiar ampliado", "Se registró mal la sala");
            assertEquals("Taller familiar ampliado", corregida.produccion().getUbicacionProduccion());
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> new ServicioCorreccionOrigen(ctx).corregir(solicitante(MARIA),
                    "PRD-2026-011", CampoCorregible.MUNICIPIO_ORIGEN, "Otro", "Motivo"));
        });
    }

    @Test
    @DisplayName("Consultas: el productor ve solo lo suyo; el personal interno todo, con el balance de cantidades (R063)")
    void consultasYBalance() {
        enTransaccionRevertida(ctx, () -> {
            assertEquals(3, produccion.listar(solicitante(MARIA), null).size());
            assertEquals(12, produccion.listar(solicitante(CARLA), null).size());
            assertEquals(1, produccion.listar(solicitante(ROSA), EstadoProduccion.PLANIFICADA).size());
            falla(Codigo.SIN_PERMISO, "propia producción", () -> produccion.consultar(solicitante(MARIA), "PRD-2026-012"));
            BalanceProduccion b = produccion.consultar(solicitante(CARLA), "PRD-2026-011").balance();
            assertEquals(0, new BigDecimal("12").compareTo(b.compromisoVigente()));
            assertEquals(0, new BigDecimal("30").compareTo(b.adquirida()));
            assertEquals(0, BigDecimal.ZERO.compareTo(b.recibida()));
            assertEquals(0, new BigDecimal("30").compareTo(b.enTransito()));
            assertEquals(0, BigDecimal.ZERO.compareTo(b.pendienteDeAdquirir()));
        });
    }

    @Test
    @DisplayName("R066: el historial no respalda una fecha anterior a la afiliación del productor; hace falta VALORI")
    void historialNoRespaldaFechasAnterioresALaAfiliacion() {
        enTransaccionRevertida(ctx, () -> {
            new com.feder.negocio.productores.ServicioProductores(ctx)
                    .cambiarComunidad(solicitante(ADMIN), 2, 3, "Trasladó su apiario a El Palmar");
            LocalDate antesDeAfiliarse = LocalDate.parse("2019-05-01");
            falla(Codigo.REGLA_DE_NEGOCIO, "VALORI", () -> produccion.registrarTerminada(solicitante(PEDRO),
                    terminada("FED-MIE-500", antesDeAfiliarse, null, "50", "40", 2L)));
            LocalDate diaDeAfiliacion = LocalDate.parse("2026-02-06");
            assertEquals(OrigenProduccion.ORIGEN_HISTORIAL, produccion.registrarTerminada(solicitante(PEDRO),
                    terminada("FED-MIE-500", diaDeAfiliacion, null, "50", "40", 2L)).origen());
        });
    }

    @Test
    @DisplayName("R061: el alta TERMINADA registra la fecha de inicio y la observación conocidas, coherentes con el lote")
    void altaTerminadaConDatosConocidos() {
        enTransaccionRevertida(ctx, () -> {
            LocalDate elaboracion = ayer();
            ServicioProduccion.DatosTerminada datos = new ServicioProduccion.DatosTerminada("FED-JAB-090",
                    new ServicioLotes.DatosLote(elaboracion, null, new BigDecimal("200"), new BigDecimal("150"), null),
                    null, null, elaboracion.minusDays(9), "Curado durante nueve días");
            ProduccionResumen r = produccion.registrarTerminada(solicitante(MARIA), datos).produccion();
            assertEquals(elaboracion.minusDays(9), r.produccion().getFechaInicio());
            assertEquals("Curado durante nueve días", r.produccion().getObservacion());
            falla(Codigo.REGLA_DE_NEGOCIO, "anterior a la fecha de inicio", () -> produccion.registrarTerminada(solicitante(MARIA),
                    new ServicioProduccion.DatosTerminada("FED-JAB-090", datos.lote(), null, null, elaboracion.plusDays(1), null)));
            falla(Codigo.REGLA_DE_NEGOCIO, "posterior a hoy", () -> produccion.registrarTerminada(solicitante(MARIA),
                    new ServicioProduccion.DatosTerminada("FED-JAB-090", new ServicioLotes.DatosLote(null, null, new BigDecimal("200"),
                            new BigDecimal("150"), null), null, null, FormatoNegocio.hoy(ctx.reloj()).plusDays(3), null)));
        });
    }

    @Test
    @DisplayName("R065/R088: un ADMINISTRADOR que también es el productor titular no corrige su propia producción (CORPCN)")
    void administradorTitularNoCorrigeSuProduccion() {
        enTransaccionRevertida(ctx, () -> {
            com.feder.negocio.usuarios.ApoyoPruebas.mariaTambienAdministradora(ctx);
            falla(Codigo.SIN_PERMISO, "propia producción", () -> new ServicioCorreccionOrigen(ctx).corregir(solicitante(MARIA),
                    "PRD-2026-011", CampoCorregible.UBICACION_PRODUCCION, "Otra sala", "Corrección"));
            assertEquals(0, numero("SELECT count(*) FROM produccion WHERE codigo = 'PRD-2026-011' AND ubicacion_produccion = 'Otra sala'"));
        });
    }
}