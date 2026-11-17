package com.feder.negocio.indicadores;

import static com.feder.negocio.indicadores.SqlPrueba.ejecutar;
import static com.feder.negocio.indicadores.SqlPrueba.importe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Indicadores financieros de CU7 sobre la semilla (db/02_seed.sql). Los valores esperados se calcularon a mano
 * con los datos de la semilla y, además, con SQL independiente que agrupa por la fecha local de Bolivia
 * (AT TIME ZONE) en lugar de los límites del período que usa el código.
 */
class ServicioIndicadoresReportesTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 1, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 12, 31);
    private static final LocalDate HOY = LocalDate.of(2026, 11, 15);
    private static final String ADMIN = "administracion@feder.example";

    private static ContextoNegocio ctx;
    private static ServicioIndicadoresReportes servicio;

    @BeforeAll
    static void preparar() {
        ctx = BaseDatosPrueba.contexto();
        servicio = new ServicioIndicadoresReportes(ctx);
    }

    private static BigDecimal bs(String valor) {
        return new BigDecimal(valor);
    }

    private static Solicitante admin() {
        return BaseDatosPrueba.solicitante(ADMIN);
    }

    // ------------------------------------------------------------------ Ventas, costos y margen

    @Test
    @DisplayName("Ingresos reconocidos 2026: entregados 1383.50 menos el reembolso por devolución 56.00")
    void ingresosReconocidosDelAnio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal esperado = importe("SELECT (SELECT SUM(pe.subtotal) FROM pedido pe JOIN entrega e ON e.pedido_id = pe.id "
                    + " WHERE (e.fecha_entrega AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?) "
                    + "- (SELECT COALESCE(SUM(monto), 0) FROM reembolso WHERE estado = 'EJECUTADO' AND devolucion_id IS NOT NULL "
                    + " AND (fecha_ejecucion AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?)", INICIO, FIN, INICIO, FIN);
            assertEquals(bs("1327.50"), esperado);
            assertEquals(esperado, servicio.ingresosReconocidos(admin(), INICIO, FIN));
            AnalisisComercial analisis = servicio.analizarVentas(admin(), INICIO, FIN);
            assertEquals(bs("50.00"), Formatos.redondear(analisis.envioCobrado()));
            BigDecimal sumaDesglose = analisis.productos().stream().map(DesgloseComercial::ingreso)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            assertEquals(esperado, Formatos.redondear(sumaDesglose), "El desglose por producto debe sumar el total");
        });
    }

    @Test
    @DisplayName("Costo de ventas 2026 = 828.50 - 28.00 reingresado; posventa 28.00; margen 499.00 (37.59 %)")
    void costosYMargenDelAnio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal salidas = importe("SELECT SUM(m.cantidad * dc.precio_unitario) FROM movimiento_inventario m "
                    + " JOIN lote_feder lf ON lf.id = m.lote_feder_id JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
                    + " JOIN reserva_stock rs ON rs.id = m.reserva_id JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + " JOIN entrega e ON e.pedido_id = dp.pedido_id WHERE m.tipo = 'SALIDA_VENTA' "
                    + " AND (e.fecha_entrega AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?", INICIO, FIN);
            assertEquals(bs("828.50"), salidas);
            assertEquals(bs("800.50"), servicio.costoDeVentas(admin(), INICIO, FIN));
            AnalisisComercial analisis = servicio.analizarVentas(admin(), INICIO, FIN);
            assertEquals(bs("28.00"), Formatos.redondear(analisis.costoPosventa()));
            assertEquals(bs("499.00"), servicio.margenBruto(admin(), INICIO, FIN));
            assertEquals(Optional.of(bs("37.59")), analisis.margenPorcentaje());
            assertEquals(bs("59.70"), Formatos.redondear(analisis.mermas()), "Baja de 3 x 15.00 y ajuste de 0.350 x 42.00");
        });
    }

    @Test
    @DisplayName("Sin entregas en el período el margen % no se calcula")
    void margenPorcentajeSinIngresos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            AnalisisComercial analisis = servicio.analizarVentas(admin(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
            assertEquals(bs("0.00"), Formatos.redondear(analisis.ingresosReconocidos()));
            assertTrue(analisis.margenPorcentaje().isEmpty());
        });
    }

    @Test
    @DisplayName("Una entrega a las 23:30 de Bolivia pertenece a ese día, aunque en UTC ya sea el día siguiente")
    void limiteDelPeriodoEnHoraDeBolivia() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE entrega SET fecha_entrega = '2026-11-09 23:30:00-04' WHERE pedido_id = 7");
            LocalDate dia = LocalDate.of(2026, 11, 9);
            assertEquals(bs("135.50"), servicio.ingresosReconocidos(admin(), dia, dia));
            assertEquals(bs("80.50"), servicio.costoDeVentas(admin(), dia, dia));
            assertEquals(bs("0.00"), servicio.ingresosReconocidos(admin(), dia.plusDays(1), dia.plusDays(1)));
        });
    }

    @Test
    @DisplayName("Un cobro a las 00:00 exactas de Bolivia cuenta en ese día y no en el anterior")
    void limiteInicialDelPeriodo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE pago SET fecha_pago = '2026-11-06 00:00:00-04' WHERE id = 8");
            LocalDate dia = LocalDate.of(2026, 11, 6);
            assertEquals(bs("135.50"), servicio.cobrosConfirmados(admin(), dia, dia));
            assertEquals(bs("0.00"), servicio.cobrosConfirmados(admin(), dia.minusDays(1), dia.minusDays(1)));
        });
    }

    // ------------------------------------------------------------------ Caja y anticipos

    @Test
    @DisplayName("Cobros 2026 = 1920.50; reintegros 132.00 (76.00 por cancelación y 56.00 por devolución)")
    void cajaDelAnio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal cobros = importe("SELECT SUM(monto) FROM pago WHERE estado = 'PAGADO' "
                    + "AND (fecha_pago AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?", INICIO, FIN);
            assertEquals(bs("1920.50"), cobros);
            assertEquals(cobros, servicio.cobrosConfirmados(admin(), INICIO, FIN));
            assertEquals(bs("132.00"), servicio.reintegrosEjecutados(admin(), INICIO, FIN));
            AnalisisCaja caja = servicio.analizarCaja(admin(), INICIO, FIN);
            assertEquals(bs("76.00"), Formatos.redondear(caja.reintegrosPorCancelacion()));
            assertEquals(bs("56.00"), Formatos.redondear(caja.reintegrosPorDevolucion()));
            assertEquals(bs("1788.50"), Formatos.redondear(caja.cobroNeto()));
        });
    }

    @Test
    @DisplayName("Anticipos: 411.00 al 2026-11-15; el pago del pedido cancelado es anticipo hasta su reintegro")
    void anticiposAFechaDeCorte() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals(bs("411.00"), servicio.anticiposPendientes(admin(), HOY));
            assertEquals(bs("76.00"), servicio.anticiposPendientes(admin(), LocalDate.of(2026, 7, 5)));
            assertEquals(bs("0.00"), servicio.anticiposPendientes(admin(), LocalDate.of(2026, 7, 6)));
            assertEquals(bs("0.00"), servicio.devolucionesPendientesDeReintegro(admin(), HOY));
            assertEquals(bs("56.00"), servicio.devolucionesPendientesDeReintegro(admin(), LocalDate.of(2026, 8, 25)));
        });
    }

    @Test
    @DisplayName("Conciliación: cobros - reintegros = ingresos reconocidos + envío + anticipos")
    void conciliacionDeCaja() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            Solicitante admin = admin();
            BigDecimal caja = servicio.cobrosConfirmados(admin, INICIO, HOY).subtract(servicio.reintegrosEjecutados(admin, INICIO, HOY));
            AnalisisComercial ventas = servicio.analizarVentas(admin, INICIO, HOY);
            BigDecimal ventasMasAnticipos = Formatos.redondear(ventas.ingresosReconocidos().add(ventas.envioCobrado())
                    .add(servicio.anticiposPendientes(admin, HOY)));
            assertEquals(caja, ventasMasAnticipos);
        });
    }

    // ------------------------------------------------------------------ Compras e inventario

    @Test
    @DisplayName("Compras aceptadas 2026 = 18615.00 y agosto = 4710.00, por la fecha del movimiento")
    void comprasAceptadas() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal esperado = importe("SELECT SUM(m.cantidad * dc.precio_unitario) FROM movimiento_inventario m "
                    + " JOIN lote_feder lf ON lf.id = m.lote_feder_id JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
                    + " WHERE m.tipo = 'ENTRADA_COMPRA'");
            assertEquals(bs("18615.00"), esperado);
            assertEquals(esperado, servicio.comprasAceptadas(admin(), INICIO, FIN));
            assertEquals(bs("4710.00"), servicio.comprasAceptadas(admin(), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)));
            AnalisisCompras compras = servicio.analizarCompras(admin(), INICIO, FIN);
            assertEquals(bs("5205.00"), Formatos.redondear(compras.porProductor().get("PROD-0001")));
            assertEquals(bs("8700.00"), Formatos.redondear(compras.porProductor().get("PROD-0002")));
            assertEquals(bs("4710.00"), Formatos.redondear(compras.porProductor().get("PROD-0003")));
        });
    }

    @Test
    @DisplayName("Valor del inventario: 17629.80 al 2026-11-15, igual al saldo actual de los lotes a costo")
    void valorInventario() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal saldoActual = importe("SELECT SUM(lf.cantidad_disponible * dc.precio_unitario) FROM lote_feder lf "
                    + " JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id");
            assertEquals(bs("17629.80"), saldoActual);
            assertEquals(saldoActual, servicio.valorInventario(admin(), HOY));
            assertEquals(bs("0.00"), servicio.valorInventario(admin(), LocalDate.of(2026, 3, 25)));
            assertEquals(bs("5205.00"), servicio.valorInventario(admin(), LocalDate.of(2026, 3, 26)));
        });
    }

    @Test
    @DisplayName("Inventario inicial + entradas - salidas a costo = inventario final")
    void conciliacionDeInventario() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            LocalDate desde = LocalDate.of(2026, 8, 1);
            LocalDate hasta = LocalDate.of(2026, 10, 31);
            BigDecimal movimientoNeto = importe("SELECT SUM(CASE WHEN m.tipo IN ('ENTRADA_COMPRA', 'ENTRADA_RETORNO_ENTREGA', "
                    + "'ENTRADA_DEVOLUCION', 'AJUSTE_POSITIVO') THEN 1 ELSE -1 END * m.cantidad * dc.precio_unitario) "
                    + " FROM movimiento_inventario m JOIN lote_feder lf ON lf.id = m.lote_feder_id "
                    + " JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
                    + " WHERE (m.fecha AT TIME ZONE 'America/La_Paz')::date BETWEEN ? AND ?", desde, hasta);
            BigDecimal inicial = servicio.valorInventario(admin(), desde.minusDays(1));
            assertEquals(servicio.valorInventario(admin(), hasta), inicial.add(movimientoNeto));
        });
    }

    // ------------------------------------------------------------------ Entregas no recuperadas

    @Test
    @DisplayName("Sin entregas NO_RECUPERADA el costo no recuperado es 0.00 y lo pendiente de entrega es el pedido 9")
    void costoNoRecuperadoEnLaSemilla() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals(Optional.of(bs("0.00")), servicio.costoNoRecuperadoPendiente(admin(), HOY));
            SituacionDespachos situacion = servicio.situacionDespachos(admin(), HOY);
            assertEquals(Optional.of(bs("97.00")), situacion.costoPendienteDeEntrega().map(Formatos::redondear),
                    "4 harinas x 13.00 + 5 jabones x 9.00 en camino");
        });
    }

    @Test
    @DisplayName("NO_RECUPERADA: el costo cuenta hasta la cancelación y desde ella pasa a la merma, nunca en los dos")
    void entregaNoRecuperadaHastaLaCancelacion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("INSERT INTO bitacora (usuario_id, fecha, accion, entidad, entidad_id, detalle) VALUES "
                    + "(2, '2026-11-15 10:00:00-04', 'MODIFICAR', 'ENTREGA', 9, '{\"estado_anterior\": \"EN_CAMINO\", \"estado_nuevo\": \"FALLIDA\"}'), "
                    + "(2, '2026-11-15 18:00:00-04', 'MODIFICAR', 'ENTREGA', 9, '{\"estado_anterior\": \"FALLIDA\", \"estado_nuevo\": \"NO_RECUPERADA\"}')");
            ejecutar("INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, fecha, clave_operacion) "
                    + "VALUES (2, 2, 16, 'ENTRADA_RETORNO_ENTREGA', 2, '2026-11-15 17:00:00-04', 'prueba-cu7-retorno-ped9')");
            ejecutar("UPDATE entrega SET estado = 'NO_RECUPERADA' WHERE id = 9");
            Solicitante admin = admin();
            // Volvieron 2 de los 5 jabones: no se recuperaron 4 harinas x 13.00 y 3 jabones x 9.00.
            assertEquals(Optional.of(bs("79.00")), servicio.costoNoRecuperadoPendiente(admin, HOY));
            assertEquals(Optional.of(bs("0.00")), servicio.situacionDespachos(admin, HOY).costoPendienteDeEntrega().map(Formatos::redondear));
            assertEquals(Optional.of(bs("0.00")), servicio.costoNoRecuperadoPendiente(admin, HOY.minusDays(1)));
            assertEquals(Optional.of(bs("97.00")), servicio.situacionDespachos(admin, HOY.minusDays(1)).costoPendienteDeEntrega()
                    .map(Formatos::redondear));

            LocalDate cancelacion = HOY.plusDays(1);
            ejecutar("INSERT INTO historial_pedido (pedido_id, usuario_id, estado_anterior, estado_nuevo, fecha, comentario) "
                    + "VALUES (9, 1, 'DESPACHADO', 'CANCELADO', '2026-11-16 09:00:00-04', 'Cancelación tras no recuperar la entrega')");
            assertEquals(Optional.of(bs("0.00")), servicio.costoNoRecuperadoPendiente(admin, cancelacion));
            assertEquals(bs("79.00"), Formatos.redondear(servicio.analizarVentas(admin, cancelacion, cancelacion).mermas()));
            assertEquals(bs("0.00"), Formatos.redondear(servicio.analizarVentas(admin, HOY, HOY).mermas()));

            // Recuperación tardía de una harina: resta de las mermas por su propia fecha.
            ejecutar("INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, fecha, clave_operacion) "
                    + "VALUES (6, 1, 15, 'ENTRADA_RETORNO_ENTREGA', 1, '2026-11-17 11:00:00-04', 'prueba-cu7-retorno-tardio-ped9')");
            LocalDate recuperacion = HOY.plusDays(2);
            assertEquals(bs("-13.00"), Formatos.redondear(servicio.analizarVentas(admin, recuperacion, recuperacion).mermas()));
        });
    }

    @Test
    @DisplayName("Si un pedido despachado no tiene transiciones de entrega en la bitácora, el resultado no es determinable")
    void costoNoRecuperadoNoDeterminable() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("INSERT INTO historial_pedido (pedido_id, usuario_id, estado_anterior, estado_nuevo, fecha) VALUES "
                    + "(10, 2, 'CONFIRMADO', 'EN_PREPARACION', '2026-11-15 09:00:00-04'), "
                    + "(10, 2, 'EN_PREPARACION', 'DESPACHADO', '2026-11-15 10:00:00-04')");
            Solicitante admin = admin();
            assertTrue(servicio.costoNoRecuperadoPendiente(admin, HOY).isEmpty());
            assertEquals(List.of("PED-2026-0010"), servicio.situacionDespachos(admin, HOY).pedidosNoDeterminables());
            assertTrue(servicio.costoNoRecuperadoPendiente(admin, HOY.minusDays(1)).isPresent());
        });
    }

    // ------------------------------------------------------------------ Permisos y validaciones

    @Test
    @DisplayName("Solo un ADMINISTRADOR vigente consulta los indicadores de CU7")
    void soloAdministrador() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            for (String correo : List.of("almacen.carla.mendez@feder.example", "ventas.jorge.suarez@feder.example",
                    "maria.quispe@feder.example", "elmer.rojas@feder.example", "hugo.paniagua@feder.example",
                    "desconocido@feder.example")) {
                Solicitante s = BaseDatosPrueba.solicitante(correo);
                List<Executable> operaciones = List.of(
                        () -> servicio.ingresosReconocidos(s, INICIO, FIN),
                        () -> servicio.costoDeVentas(s, INICIO, FIN),
                        () -> servicio.margenBruto(s, INICIO, FIN),
                        () -> servicio.anticiposPendientes(s, HOY),
                        () -> servicio.valorInventario(s, HOY),
                        () -> servicio.cobrosConfirmados(s, INICIO, FIN),
                        () -> servicio.reintegrosEjecutados(s, INICIO, FIN),
                        () -> servicio.comprasAceptadas(s, INICIO, FIN),
                        () -> servicio.costoNoRecuperadoPendiente(s, HOY));
                for (Executable operacion : operaciones) {
                    NegocioException e = assertThrows(NegocioException.class, operacion, correo);
                    assertEquals(NegocioException.Codigo.SIN_PERMISO, e.codigo());
                    assertTrue(e.getMessage().contains("ADMINISTRADOR"));
                }
            }
        });
    }

    @Test
    @DisplayName("Un período con desde posterior a hasta se rechaza con un mensaje claro")
    void periodoInvertido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            NegocioException e = assertThrows(NegocioException.class,
                    () -> servicio.ingresosReconocidos(admin(), FIN, INICIO));
            assertEquals(NegocioException.Codigo.PARAMETRO_INVALIDO, e.codigo());
            assertEquals("La fecha desde (2026-12-31) es posterior a la fecha hasta (2026-01-01).", e.getMessage());
            assertFalse(e.getMessage().contains("SQL"));
        });
    }
}
