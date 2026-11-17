package com.feder.negocio.indicadores;

import static com.feder.negocio.indicadores.ResultadosPrueba.detalle;
import static com.feder.negocio.indicadores.ResultadosPrueba.fila;
import static com.feder.negocio.indicadores.ResultadosPrueba.tabla;
import static com.feder.negocio.indicadores.SqlPrueba.ejecutar;
import static com.feder.negocio.indicadores.SqlPrueba.importe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Regresiones de CU7: mermas y devuelto no apto por producto y categoría, margen % por fila, la regla de
 * «no determinable» de las entregas, la serie mensual y las conciliaciones de control, sobre la semilla.
 */
class MermasDesglosesYControlesTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 1, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 12, 31);
    private static final LocalDate HOY = LocalDate.of(2026, 11, 15);

    private static ContextoNegocio ctx;
    private static ServicioIndicadoresReportes servicio;

    @BeforeAll
    static void preparar() {
        ctx = ResultadosPrueba.contextoConRelojFijo();
        servicio = new ServicioIndicadoresReportes(ctx);
    }

    private static Solicitante admin() {
        return BaseDatosPrueba.solicitante("administracion@feder.example");
    }

    private static BigDecimal bs(String valor) {
        return new BigDecimal(valor);
    }

    private static DesgloseComercial producto(AnalisisComercial analisis, String codigo) {
        return analisis.productos().stream().filter(d -> d.codigo().equals(codigo)).findFirst()
                .orElseThrow(() -> new AssertionError("Sin desglose para " + codigo));
    }

    // ------------------------------------------------------------------ Devuelto no apto (CU78-02)

    @Test
    @DisplayName("Las 2 tabletas no aptas de DEV-2026-0002 (2 x 17.00) se informan aparte en agosto y no tocan las mermas")
    void devueltoNoAptoInformativo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            LocalDate desde = LocalDate.of(2026, 8, 1);
            LocalDate hasta = LocalDate.of(2026, 8, 31);
            BigDecimal esperado = importe("SELECT SUM((d.cantidad_recibida - d.cantidad_apta) * dc.precio_unitario) "
                    + " FROM devolucion d JOIN movimiento_inventario m ON m.id = d.movimiento_inventario_id "
                    + " JOIN lote_feder lf ON lf.id = m.lote_feder_id JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
                    + " WHERE d.estado IN ('APROBADA', 'COMPLETADA') "
                    + "   AND (d.fecha_inspeccion AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?", desde, hasta);
            assertEquals(bs("34.00"), esperado);
            AnalisisComercial agosto = servicio.analizarVentas(admin(), desde, hasta);
            assertEquals(esperado, Formatos.redondear(agosto.devueltoNoApto()));
            assertEquals(esperado, Formatos.redondear(producto(agosto, "FED-CHO-100").devueltoNoApto()));
            assertEquals(bs("0.00"), Formatos.redondear(agosto.mermas()), "No se suma a las mermas");

            AnalisisComercial anio = servicio.analizarVentas(admin(), INICIO, FIN);
            assertEquals(bs("59.70"), Formatos.redondear(anio.mermas()));
            assertEquals(bs("499.00"), Formatos.redondear(anio.margenBruto()), "Ni el margen cambia");

            ResultadoOperacion costos = ResultadosIndicadores.costos(agosto);
            assertEquals("34.00", detalle(costos, "Devuelto no apto por fecha de inspección, informativo (Bs)"));
            assertEquals("34.00", fila(tabla(costos, "Costos por producto"), "FED-CHO-100").get(6));
        });
    }

    // ------------------------------------------------------------------ Desgloses de mermas y margen % (CU78-03)

    @Test
    @DisplayName("Las mermas se desglosan por producto y categoría: baja de 3 x 15.00 y ajuste de 0.350 x 42.00")
    void mermasPorProductoYCategoria() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            AnalisisComercial anio = servicio.analizarVentas(admin(), INICIO, FIN);
            assertEquals(bs("45.00"), Formatos.redondear(producto(anio, "FED-ACE-020").mermas()));
            assertEquals(bs("14.70"), Formatos.redondear(producto(anio, "FED-MIE-GRA").mermas()));
            DesgloseComercial aceites = anio.porCategoria().stream().filter(d -> d.categoria().equals("Aceites naturales"))
                    .findFirst().orElseThrow();
            assertEquals(bs("45.00"), Formatos.redondear(aceites.mermas()));

            ResultadoOperacion costos = ResultadosIndicadores.costos(anio);
            assertEquals("45.00", fila(tabla(costos, "Costos por producto"), "FED-ACE-020").get(5));
            assertEquals("14.70", fila(tabla(costos, "Costos por categoría"), "Miel y derivados").get(3));
        });
    }

    @Test
    @DisplayName("El margen % se calcula por producto y por categoría, solo si su ingreso es mayor que 0")
    void margenPorcentualPorFila() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal ingreso = importe("SELECT SUM(dp.subtotal) FROM detalle_pedido dp JOIN entrega e ON e.pedido_id = dp.pedido_id "
                    + " JOIN producto pr ON pr.id = dp.producto_id WHERE pr.codigo = 'FED-ACE-020' "
                    + "   AND (e.fecha_entrega AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?", INICIO, FIN);
            BigDecimal costo = importe("SELECT SUM(m.cantidad * dc.precio_unitario) FROM movimiento_inventario m "
                    + " JOIN lote_feder lf ON lf.id = m.lote_feder_id JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
                    + " JOIN reserva_stock rs ON rs.id = m.reserva_id JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + " JOIN producto pr ON pr.id = dp.producto_id JOIN entrega e ON e.pedido_id = dp.pedido_id "
                    + " WHERE m.tipo = 'SALIDA_VENTA' AND pr.codigo = 'FED-ACE-020' "
                    + "   AND (e.fecha_entrega AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?", INICIO, FIN);
            BigDecimal esperado = Formatos.porcentaje(ingreso.subtract(costo), ingreso);

            AnalisisComercial anio = servicio.analizarVentas(admin(), INICIO, FIN);
            assertEquals(Optional.of(esperado), producto(anio, "FED-ACE-020").margenPorcentaje());
            ResultadoOperacion margen = ResultadosIndicadores.margen(anio);
            assertEquals(esperado.toPlainString(), fila(tabla(margen, "Margen por producto"), "FED-ACE-020").get(7));
            assertTrue(tabla(margen, "Margen por categoría").columnas().contains("Margen (%)"));

            AnalisisComercial enero = servicio.analizarVentas(admin(), INICIO, LocalDate.of(2026, 1, 31));
            enero.productos().forEach(d -> assertTrue(d.margenPorcentaje().isEmpty(), d.codigo()));
        });
    }

    // ------------------------------------------------------------------ No determinable (CU78-04)

    @Test
    @DisplayName("Un pedido ya ENTREGADO sin transiciones de entrega no vuelve no determinables los cortes siguientes")
    void entregadoSinTransicionesNoBloquea() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("INSERT INTO historial_pedido (pedido_id, usuario_id, estado_anterior, estado_nuevo, fecha) VALUES "
                    + "(10, 2, 'CONFIRMADO', 'EN_PREPARACION', '2026-11-14 08:00:00-04'), "
                    + "(10, 2, 'EN_PREPARACION', 'DESPACHADO', '2026-11-14 09:00:00-04'), "
                    + "(10, 2, 'DESPACHADO', 'ENTREGADO', '2026-11-14 12:00:00-04')");
            assertEquals(Optional.of(bs("0.00")), servicio.costoNoRecuperadoPendiente(admin(), HOY));
            assertTrue(servicio.situacionDespachos(admin(), HOY).esDeterminable());
        });
    }

    @Test
    @DisplayName("Una transición que no parte del estado anterior registrado deja el corte como no determinable")
    void cadenaConHuecoNoEsDeterminable() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("INSERT INTO bitacora (usuario_id, fecha, accion, entidad, entidad_id, detalle) VALUES "
                    + "(2, '2026-11-15 10:00:00-04', 'MODIFICAR', 'ENTREGA', 9, "
                    + "'{\"estado_anterior\": \"FALLIDA\", \"estado_nuevo\": \"NO_RECUPERADA\"}')");
            ejecutar("UPDATE entrega SET estado = 'NO_RECUPERADA' WHERE id = 9");
            assertTrue(servicio.costoNoRecuperadoPendiente(admin(), HOY).isEmpty());
            assertEquals(List.of("PED-2026-0009"), servicio.situacionDespachos(admin(), HOY).pedidosNoDeterminables());
        });
    }

    @Test
    @DisplayName("Si la entrega cambió de estado sin registrar la transición, el corte de hoy no se estima")
    void estadoActualSinTransicionNoEsDeterminable() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE entrega SET estado = 'NO_RECUPERADA' WHERE id = 9");
            SituacionDespachos situacion = servicio.situacionDespachos(admin(), HOY);
            assertTrue(situacion.costoNoRecuperado().isEmpty());
            assertTrue(situacion.costoPendienteDeEntrega().isEmpty(), "Tampoco se informa como pendiente de entrega");
            assertTrue(situacion.describirNoDeterminable().contains("PED-2026-0009"));
            ResultadoOperacion inventario = ResultadosIndicadores.inventario(HOY,
                    servicio.valorInventarioPorProducto(admin(), HOY), situacion);
            assertTrue(detalle(inventario, "Mercancía despachada pendiente de entrega (Bs)").startsWith("No determinable"));
        });
    }

    // ------------------------------------------------------------------ Serie mensual y conciliaciones (CU78-08)

    @Test
    @DisplayName("La serie mensual de 2026 tiene 12 meses y sus importes suman los del año")
    void serieMensualSumaElAnio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            List<IndicadoresDelMes> meses = servicio.serieMensual(admin(), INICIO, FIN);
            assertEquals(12, meses.size());
            assertEquals("2026-01", meses.get(0).etiqueta());
            BigDecimal ingresos = meses.stream().map(m -> m.ventas().ingresosReconocidos()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal cobros = meses.stream().map(m -> m.caja().cobrosConfirmados()).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal compras = meses.stream().map(m -> m.compras().comprasAceptadas()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(bs("1327.50"), Formatos.redondear(ingresos));
            assertEquals(bs("1920.50"), Formatos.redondear(cobros));
            assertEquals(bs("18615.00"), Formatos.redondear(compras));
            ResultadoOperacion r = ResultadosIndicadores.serieMensual(new Periodo(INICIO, FIN), meses);
            assertEquals("4710.00", fila(tabla(r, "Indicadores por mes"), "2026-08").get(10));

            NegocioException e = assertThrows(NegocioException.class,
                    () -> servicio.serieMensual(admin(), LocalDate.of(2024, 1, 1), FIN));
            assertTrue(e.getMessage().contains("hasta 24 meses"), e.getMessage());
        });
    }

    @Test
    @DisplayName("Conciliaciones al 2026-11-15: caja 1788.50 = ventas + envío + anticipos; inventario 17629.80")
    void conciliacionesDeControl() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ConciliacionControl c = servicio.conciliaciones(admin(), HOY);
            assertEquals(bs("1788.50"), c.caja());
            assertEquals(bs("1788.50"), c.ventasMasAnticipos());
            assertTrue(c.cajaConcilia());
            assertEquals(bs("17629.80"), c.inventarioCalculado());
            assertTrue(c.inventarioConcilia());
            ResultadoOperacion r = ResultadosIndicadores.conciliaciones(c);
            assertEquals("Sí", detalle(r, "Caja conciliada"));
            assertEquals("0.00", fila(tabla(r, "Conciliación de caja"), "Diferencia").get(1));

            // Un encargo entregado sin ningún cobro rompe la conciliación de caja y se informa la diferencia.
            ejecutar("UPDATE entrega SET estado = 'ENTREGADA', fecha_entrega = '2026-11-15 11:00:00-04' WHERE pedido_id = 8");
            ConciliacionControl descuadre = servicio.conciliaciones(admin(), HOY);
            assertEquals(bs("-1080.00"), descuadre.diferenciaCaja(), "Total del encargo PED-2026-0008 sin cobrar");
            assertEquals("No", detalle(ResultadosIndicadores.conciliaciones(descuadre), "Caja conciliada"));
        });
    }
}
