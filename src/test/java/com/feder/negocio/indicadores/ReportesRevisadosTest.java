package com.feder.negocio.indicadores;

import static com.feder.negocio.indicadores.ResultadosPrueba.detalle;
import static com.feder.negocio.indicadores.ResultadosPrueba.fila;
import static com.feder.negocio.indicadores.ResultadosPrueba.hayTabla;
import static com.feder.negocio.indicadores.ResultadosPrueba.tabla;
import static com.feder.negocio.indicadores.SqlPrueba.ejecutar;
import static com.feder.negocio.indicadores.SqlPrueba.importe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.reportes.IndicadoresInventarioRepositorio.AceptadoPorOrigen;
import com.feder.datos.reportes.ReporteTrazabilidadRepositorio;
import com.feder.modelo.FormatoReporte;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Regresiones de CU8: posventa en el saldo vigente con rectificaciones, estados a la fecha hasta, tiempos sin
 * datos posteriores, participación por productor y por comunidad, filtros opcionales, advertencias de origen,
 * avisos de vencimiento a hoy y los días de «próximo a vencer» configurables.
 */
class ReportesRevisadosTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 1, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 12, 31);
    private static final LocalDate HOY = LocalDate.of(2026, 11, 15);
    private static final String ADMIN = "administracion@feder.example";
    private static final String PERSONAL = "ventas.jorge.suarez@feder.example";
    private static final String MERMELADA = "FED-MER-GUA Mermelada de guayaba";

    private static ContextoNegocio ctx;
    private static ServicioIndicadoresReportes servicio;

    @BeforeAll
    static void preparar() {
        ctx = ResultadosPrueba.contextoConRelojFijo();
        servicio = new ServicioIndicadoresReportes(ctx);
    }

    private static Solicitante solicitante(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    // ------------------------------------------------------------------ Posventa vigente (CU78-01)

    /**
     * DEV-2026-0003 se aprueba y reincorpora 1 frasco el 10/12, se anula el 12/12 con su AJUSTE_NEGATIVO
     * compensatorio, y su rectificación aprueba y reincorpora 1 frasco el mismo día 12/12.
     */
    private static void prepararRectificacion() {
        ejecutar("UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REPOSICION', cantidad_aprobada = 1, "
                + "fecha_resolucion = '2026-12-10 09:00:00-04', cantidad_recibida = 1, fecha_recepcion = '2026-12-10 09:00:00-04', "
                + "cantidad_apta = 1, fecha_inspeccion = '2026-12-10 09:30:00-04' WHERE codigo = 'DEV-2026-0003'");
        ejecutar("INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, devolucion_id, tipo, cantidad, fecha, motivo, clave_operacion) "
                + "SELECT 8, 2, id, 'ENTRADA_DEVOLUCION', 1, '2026-12-10 10:00:00-04', 'Reingreso apto', 'prueba-cu8-dev3-reingreso' "
                + "FROM devolucion WHERE codigo = 'DEV-2026-0003'");
        ejecutar("UPDATE devolucion SET estado = 'ANULADA', fecha_anulacion = '2026-12-12 09:00:00-04', "
                + "motivo_anulacion = 'Aprobada por error' WHERE codigo = 'DEV-2026-0003'");
        ejecutar("INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, devolucion_id, tipo, cantidad, fecha, motivo, clave_operacion) "
                + "SELECT 8, 1, id, 'AJUSTE_NEGATIVO', 1, '2026-12-12 09:00:00-04', 'Compensación de la rectificación', "
                + "'prueba-cu8-dev3-compensacion' FROM devolucion WHERE codigo = 'DEV-2026-0003'");
        ejecutar("INSERT INTO devolucion (movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado, "
                + "resolucion, cantidad_aprobada, cantidad_recibida, cantidad_apta, fecha_solicitud, fecha_recepcion, "
                + "fecha_inspeccion, fecha_resolucion) "
                + "SELECT 25, id, 'DEV-PRUEBA-0004', 1, 'Rectificación de DEV-2026-0003', 'APROBADA', 'REPOSICION', 1, 1, 1, "
                + "'2026-12-12 09:10:00-04', '2026-12-12 09:10:00-04', '2026-12-12 09:10:00-04', '2026-12-12 09:15:00-04' "
                + "FROM devolucion WHERE codigo = 'DEV-2026-0003'");
        ejecutar("INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, devolucion_id, tipo, cantidad, fecha, motivo, clave_operacion) "
                + "SELECT 8, 2, id, 'ENTRADA_DEVOLUCION', 1, '2026-12-12 10:00:00-04', 'Reingreso de la rectificación', "
                + "'prueba-cu8-dev4-reingreso' FROM devolucion WHERE codigo = 'DEV-PRUEBA-0004'");
    }

    @Test
    @DisplayName("Una devolución anulada y su rectificación cuentan una sola vez lo aprobado, recibido y reincorporado")
    void posventaConRectificacion() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            prepararRectificacion();
            return servicio.generarReporteVentas(solicitante(PERSONAL), LocalDate.of(2026, 12, 1), FIN, FormatoReporte.PDF);
        });
        // Solicitada: solo la rectificación (la anulada se pidió en noviembre). Aprobada: 1 - 1 + 1. Recibida: la
        // rectificación hereda el frasco recibido. Reincorporada: 1 - 1 (compensación) + 1.
        assertEquals(List.of(MERMELADA, "UNIDAD", "1", "1", "1", "1", "0"), fila(tabla(r, "Posventa por producto"), MERMELADA));
        ResultadoOperacion.Tabla porEstado = tabla(r, "Devoluciones por estado y resolución");
        assertEquals("1", fila(porEstado, "ANULADA", "REPOSICION").get(2));
        assertEquals("1", fila(porEstado, "APROBADA", "REPOSICION").get(2));
        assertEquals("1", fila(porEstado, "COMPLETADA", "REEMBOLSO").get(2));
        assertEquals("DEV-2026-0003", fila(tabla(r, "Devoluciones con hechos"), "DEV-PRUEBA-0004").get(5));
    }

    @Test
    @DisplayName("Las devoluciones y los reembolsos se informan con su estado a la fecha hasta, sin hechos posteriores")
    void estadosDePosventaALaFechaHasta() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            prepararRectificacion();
            ResultadoOperacion noviembre = servicio.generarReporteVentas(solicitante(PERSONAL), LocalDate.of(2026, 11, 1),
                    LocalDate.of(2026, 11, 30), FormatoReporte.PDF);
            List<String> dev3 = fila(tabla(noviembre, "Devoluciones con hechos"), "DEV-2026-0003");
            assertEquals("SOLICITADA", dev3.get(3));
            assertEquals("", dev3.get(4), "La resolución de diciembre no se adelanta");
            assertEquals("", dev3.get(9), "Ni la cantidad aprobada");
            assertEquals("1", fila(tabla(noviembre, "Devoluciones por estado"), "SOLICITADA", "Sin resolución").get(2));

            ResultadoOperacion antesDeAnular = servicio.generarReporteVentas(solicitante(PERSONAL), LocalDate.of(2026, 12, 1),
                    LocalDate.of(2026, 12, 11), FormatoReporte.PDF);
            assertEquals("APROBADA", fila(tabla(antesDeAnular, "Devoluciones con hechos"), "DEV-2026-0003").get(3));

            // DEV-2026-0002: su reembolso se ejecutó el 26/08; al 25/08 todavía estaba PENDIENTE.
            ResultadoOperacion agosto = servicio.generarReporteVentas(solicitante(PERSONAL), LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 25), FormatoReporte.PDF);
            assertEquals("1", fila(tabla(agosto, "Reembolsos solicitados"), "PENDIENTE").get(1));
            assertFalse(tabla(agosto, "Reembolsos solicitados").filas().stream().anyMatch(f -> f.get(0).equals("EJECUTADO")));
        });
    }

    // ------------------------------------------------------------------ Tiempos a la fecha hasta (CU78-05)

    @Test
    @DisplayName("Un tramo que se cierra después de la fecha hasta se muestra abierto y no se promedia")
    void tiemposSinDatosPosteriores() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteVentas(solicitante(PERSONAL), LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 6),
                        FormatoReporte.PDF));
        List<String> pedido = fila(tabla(r, "Tiempos de preparación"), "PED-2026-0001");
        assertEquals("", pedido.get(2), "El despacho del 07/04 es posterior a la fecha hasta");
        assertEquals("abierto", pedido.get(4));
        assertEquals("Sin tramos cerrados", detalle(r, "Promedio de preparación (horas, solo pedidos despachados)"));
    }

    // ------------------------------------------------------------------ Participación (CU78-06)

    private static AceptadoPorOrigen aporte(String productor, String comunidad, String cantidad, String anulada) {
        return new AceptadoPorOrigen(productor, comunidad, "Municipio", "Santa Cruz", "FED-X", "Producto X", "UNIDAD",
                new BigDecimal(cantidad), new BigDecimal(anulada), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    @DisplayName("La participación se calcula por productor y por comunidad dentro del producto, no por pareja")
    void participacionPorProductorYPorComunidad() {
        List<AceptadoPorOrigen> aceptado = List.of(aporte("P1", "A", "6", "0"), aporte("P1", "B", "4", "0"),
                aporte("P2", "A", "10", "0"));
        List<GeneradorReporteAbastecimiento.Participacion> productores =
                GeneradorReporteAbastecimiento.participaciones(aceptado, AceptadoPorOrigen::productor);
        assertEquals(2, productores.size());
        assertEquals(Optional.of(new BigDecimal("50.00")), productores.get(0).porcentaje());
        List<GeneradorReporteAbastecimiento.Participacion> comunidades =
                GeneradorReporteAbastecimiento.participaciones(aceptado, AceptadoPorOrigen::comunidad);
        assertEquals(new BigDecimal("16"), comunidades.get(0).neto());
        assertEquals(Optional.of(new BigDecimal("80.00")), comunidades.get(0).porcentaje());
        assertEquals(Optional.of(new BigDecimal("20.00")), comunidades.get(1).porcentaje());

        List<GeneradorReporteAbastecimiento.Participacion> conNegativo = GeneradorReporteAbastecimiento.participaciones(
                List.of(aporte("P1", "A", "5", "0"), aporte("P2", "A", "0", "2")), AceptadoPorOrigen::productor);
        assertTrue(conNegativo.stream().allMatch(p -> p.porcentaje().isEmpty()), "Con un aporte negativo solo cantidades");
    }

    // ------------------------------------------------------------------ Filtros opcionales (CU78-07)

    @Test
    @DisplayName("REPVEN con tipo=ENCARGO solo cuenta encargos y no presenta los indicadores de CU7")
    void filtroDeTipoEnVentas() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteVentas(solicitante(ADMIN), INICIO, FIN, FormatoReporte.PDF, "tipo=encargo"));
        assertEquals("tipo=ENCARGO", detalle(r, "Filtros"));
        assertTrue(tabla(r, "Pedidos por estado").filas().stream().allMatch(f -> f.get(0).equals("ENCARGO")));
        assertTrue(tabla(r, "Saldo pendiente").filas().stream().allMatch(f -> f.get(1).equals("ENCARGO")));
        assertTrue(detalle(r, "Indicadores de CU7").startsWith("No se presentan"));
        assertFalse(hayTabla(r, "Cobros y reintegros"));
    }

    @Test
    @DisplayName("REPVEN con categoría separa el ingreso por producto y no atribuye envío ni caja")
    void filtroDeCategoriaEnVentas() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal esperado = importe("SELECT SUM(dp.subtotal) FROM detalle_pedido dp JOIN entrega e ON e.pedido_id = dp.pedido_id "
                    + " JOIN producto pr ON pr.id = dp.producto_id JOIN categoria ca ON ca.id = pr.categoria_id "
                    + " WHERE ca.nombre = 'Aceites naturales' "
                    + "   AND (e.fecha_entrega AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?", INICIO, FIN);
            ResultadoOperacion reporte = servicio.generarReporteVentas(solicitante(ADMIN), INICIO, FIN, FormatoReporte.EXCEL,
                    "categoria=aceites naturales");
            assertEquals(esperado.toPlainString(), detalle(reporte, "Ingresos reconocidos (Bs)"));
            return reporte;
        });
        assertTrue(tabla(r, "Cantidades entregadas").filas().stream().allMatch(f -> f.get(0).startsWith("FED-ACE")));
        assertEquals("No se presenta con este filtro", detalle(r, "Importe cobrado por envío, aparte (Bs)"));
    }

    @Test
    @DisplayName("REPVEN con metodo=QR filtra solo los cobros y reintegros")
    void filtroDeMetodoEnVentas() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteVentas(solicitante(ADMIN), INICIO, FIN, FormatoReporte.PDF, "metodo=QR"));
        assertEquals("1027.50", detalle(r, "Cobros confirmados (Bs)"));
        assertEquals(1, tabla(r, "Cobros y reintegros por método").filas().size());
        assertEquals("1327.50", detalle(r, "Ingresos reconocidos (Bs)"), "Las ventas no se filtran por método");
    }

    @Test
    @DisplayName("REPINV con productor=PROD-0002 solo muestra sus lotes y su valor a costo")
    void filtroDeProductorEnInventario() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal esperado = importe("SELECT SUM(lf.cantidad_disponible * dc.precio_unitario) FROM lote_feder lf "
                    + " JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id JOIN compra_feder cf ON cf.id = dc.compra_id "
                    + " JOIN productor po ON po.id = cf.productor_id WHERE po.codigo_productor = 'PROD-0002'");
            ResultadoOperacion reporte = servicio.generarReporteInventario(solicitante(ADMIN), HOY, FormatoReporte.EXCEL,
                    "productor=prod-0002");
            assertEquals(esperado.toPlainString(), detalle(reporte, "Valor del inventario a costo (Bs)"));
            return reporte;
        });
        assertEquals(3, tabla(r, "Lotes de FEDER").filas().size());
        assertTrue(tabla(r, "Lotes de FEDER").filas().stream().allMatch(f -> f.get(2).equals("PROD-0002")));
        assertEquals(3, tabla(r, "Stock vendible").filas().size(), "Solo los productos con lotes de ese productor");
        assertEquals("0.00", detalle(r, "Costo de la mercancía pendiente de entrega (Bs)"), "El pedido 9 no usa sus lotes");
    }

    @Test
    @DisplayName("REPINV limita los movimientos al período del filtro, sin pasar del corte")
    void filtroDePeriodoDeMovimientos() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteInventario(solicitante(PERSONAL), HOY, FormatoReporte.PDF, "desde=2026-08-01;hasta=2026-08-31"));
        ResultadoOperacion.Tabla movimientos = tabla(r, "Movimientos del 2026-08-01 al 2026-08-31");
        assertTrue(movimientos.filas().stream().noneMatch(f -> f.get(2).equals("LF-2026-001") && f.get(0).equals("ENTRADA_COMPRA")),
                "La recepción de marzo queda fuera");
        assertTrue(movimientos.filas().stream().anyMatch(f -> f.get(2).equals("LF-2026-006") && f.get(0).equals("ENTRADA_COMPRA")));
    }

    @Test
    @DisplayName("REPABA con productor=PROD-0001 solo lista sus compras y su costo aceptado")
    void filtroDeProductorEnAbastecimiento() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteAbastecimiento(solicitante(ADMIN), INICIO, FIN, FormatoReporte.EXCEL, "productor=PROD-0001"));
        assertTrue(tabla(r, "Compras del período").filas().stream().allMatch(f -> f.get(2).equals("PROD-0001")));
        assertEquals("5205.00", detalle(r, "Compras aceptadas a costo (Bs)"));
        assertEquals(1, tabla(r, "Productores registrados").filas().size());
    }

    @Test
    @DisplayName("Los filtros desconocidos o con valores inválidos se rechazan con un mensaje claro")
    void filtrosInvalidos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            NegocioException clave = assertThrows(NegocioException.class, () ->
                    servicio.generarReporteVentas(solicitante(ADMIN), INICIO, FIN, FormatoReporte.PDF, "productor=PROD-0001"));
            assertEquals(NegocioException.Codigo.PARAMETRO_INVALIDO, clave.codigo());
            assertTrue(clave.getMessage().startsWith("Filtro desconocido «productor»"), clave.getMessage());
            NegocioException valor = assertThrows(NegocioException.class, () ->
                    servicio.generarReporteVentas(solicitante(ADMIN), INICIO, FIN, FormatoReporte.PDF, "tipo=MAYORISTA"));
            assertEquals("El filtro tipo debe ser uno de: INMEDIATO, ENCARGO.", valor.getMessage());
            NegocioException forma = assertThrows(NegocioException.class, () ->
                    servicio.generarReporteAbastecimiento(solicitante(ADMIN), INICIO, FIN, FormatoReporte.PDF, "estado"));
            assertTrue(forma.getMessage().contains("clave=valor"), forma.getMessage());
            NegocioException rango = assertThrows(NegocioException.class, () ->
                    servicio.generarReporteInventario(solicitante(ADMIN), HOY, FormatoReporte.PDF, "desde=2026-09-01;hasta=2026-08-01"));
            assertTrue(rango.getMessage().contains("es posterior al filtro hasta"), rango.getMessage());
        });
    }

    // ------------------------------------------------------------------ Advertencias de origen (CU78-09)

    @Test
    @DisplayName("Una constancia de error de origen registrada sobre el LOTE_FEDER vendido también se advierte")
    void advertenciaSobreLoteFeder() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("INSERT INTO bitacora (usuario_id, fecha, accion, entidad, entidad_id, detalle) VALUES "
                    + "(1, '2026-06-25 10:00:00-04', 'MODIFICAR', 'LOTE_FEDER', 3, ?::jsonb)",
                    "{\"operacion\": \"" + ReporteTrazabilidadRepositorio.OPERACION_ERROR_LOTE_ORIGEN
                            + "\", \"motivo\": \"El frasco provenía de otro lote de origen\"}");
            return servicio.generarReporteTrazabilidad(solicitante(PERSONAL), "PED-2026-0002", FormatoReporte.PDF);
        });
        String advertencia = detalle(r, "Advertencia de origen");
        assertTrue(advertencia.contains("el lote FEDER LF-2026-003"), advertencia);
        assertTrue(advertencia.contains("otro lote de origen"), advertencia);
    }

    // ------------------------------------------------------------------ Avisos de vencimiento a hoy (CU78-10)

    @Test
    @DisplayName("Los avisos de vencimiento usan el saldo actual y la fecha de hoy aunque el corte sea pasado")
    void avisosDeVencimientoAHoy() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteInventario(solicitante(PERSONAL), LocalDate.of(2026, 6, 1), FormatoReporte.PDF));
        assertTrue(tabla(r, "Lotes de FEDER").filas().stream().noneMatch(f -> f.get(0).equals("LF-2026-006")),
                "El lote ingresó en agosto, después del corte");
        assertEquals(List.of("LF-2026-006", "FED-HAR-PLA", "141", "DISPONIBLE", "2026-12-01", "Próximo a vencer"),
                fila(tabla(r, "Avisos de vencimiento a hoy (2026-11-15)"), "LF-2026-006"));
    }

    @Test
    @DisplayName("Un lote con saldo hoy y vencimiento pasado se avisa como vencido pendiente de baja")
    void vencidoPendienteDeBaja() {
        assertEquals("Vencido pendiente de baja", GeneradorReporteInventario.aviso(LocalDate.of(2026, 11, 14), HOY));
        assertEquals("Próximo a vencer", GeneradorReporteInventario.aviso(HOY, HOY));
    }

    @Test
    @DisplayName("Los días de «próximo a vencer» llegan de la configuración del módulo")
    void diasDeAvisoConfigurables() {
        ServicioIndicadoresReportes quinceDias = new ServicioIndicadoresReportes(ctx, 15);
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                quinceDias.generarReporteInventario(solicitante(PERSONAL), HOY, FormatoReporte.PDF));
        assertTrue(tabla(r, "Avisos de vencimiento").filas().isEmpty(), "LF-2026-006 vence en 16 días");
        assertTrue(detalle(r, "Aviso de vencimiento").contains("2026-11-30"));
    }
}
