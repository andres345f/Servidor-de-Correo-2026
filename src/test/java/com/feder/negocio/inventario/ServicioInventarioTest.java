package com.feder.negocio.inventario;

import com.feder.datos.AccesoDatosException;
import com.feder.modelo.EstadoReserva;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.TipoMovimiento;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Movimientos de inventario (R037, R038, R041, R043, R044, R055, R070, R083, R084, R086) y los métodos
 * que CU6 usará para despachar, liberar reservas y registrar retornos. Todo se revierte al terminar.
 */
class ServicioInventarioTest {

    private ContextoNegocio ctx;
    private ServicioInventario inventario;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        inventario = ServicioInventario.crear(ctx);
    }

    private static Solicitante quien(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    private static SolicitudAjuste ajuste(String lote, TipoMovimiento tipo, String cantidad, String clave, String evidencia) {
        return new SolicitudAjuste(lote, tipo, new BigDecimal(cantidad), "Conteo físico del " + clave, clave, evidencia);
    }

    private static BigDecimal saldo(long lote) {
        return decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = ?", lote);
    }

    @Test
    @DisplayName("Una baja descuenta el saldo en la misma transacción, con movimiento inmutable y bitácora")
    void bajaDescuentaSaldo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            MovimientoInventario m = inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-002", TipoMovimiento.BAJA_DANADO, "4", "BJ-T-001", null));
            assertIgual("230", saldo(2));
            assertEquals("BAJA_DANADO", texto("SELECT tipo FROM movimiento_inventario WHERE id = ?", m.getId()));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'MOVIMIENTO_INVENTARIO' AND entidad_id = ?", m.getId()));
            assertThrows(IllegalStateException.class,
                    () -> ejecutar("UPDATE movimiento_inventario SET cantidad = 1 WHERE id = ?", m.getId()));
        });
    }

    @Test
    @DisplayName("Una salida manual solo toma stock libre: las unidades reservadas no se ajustan ni se dan de baja")
    void noTocaLoReservado() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                assertNegocio(Codigo.REGLA_DE_NEGOCIO, "Solo se retira stock libre",
                        () -> inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-007", TipoMovimiento.AJUSTE_NEGATIVO, "71", "AJ-T-001", null))));
    }

    @Test
    @DisplayName("R041: el AJUSTE_POSITIVO es solo del ADMINISTRADOR, con evidencia y dentro de lo que salió por bajas")
    void ajustePositivoExtraordinario() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.SIN_PERMISO, "ADMINISTRADOR",
                    () -> inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-001", TipoMovimiento.AJUSTE_POSITIVO, "1", "AP-T-001", "acta")));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "evidencia",
                    () -> inventario.registrarAjuste(quien(ADMIN), ajuste("LF-2026-001", TipoMovimiento.AJUSTE_POSITIVO, "1", "AP-T-002", null)));
            inventario.registrarAjuste(quien(ADMIN), ajuste("LF-2026-001", TipoMovimiento.AJUSTE_POSITIVO, "2", "AP-T-003",
                    "Conteo de Carla Méndez el 2026-11-15, acta INV-0001"));
            assertIgual("185", saldo(1));
            assertTrue(texto("SELECT detalle::text FROM bitacora WHERE entidad = 'MOVIMIENTO_INVENTARIO' ORDER BY id DESC LIMIT 1")
                    .contains("INV-0001"));
            // El lote solo perdió 3 unidades por baja: restituir 2 más superaría ese límite (disparador de la base).
            AccesoDatosException e = assertThrows(AccesoDatosException.class, () -> inventario.registrarAjuste(quien(ADMIN),
                    ajuste("LF-2026-001", TipoMovimiento.AJUSTE_POSITIVO, "2", "AP-T-004", "acta INV-0002")));
            assertEquals("rg_movimiento_inventario_ajuste_positivo_limite", e.restriccion());
        });
    }

    @Test
    @DisplayName("Misma clave y misma operación devuelve el movimiento registrado; otra operación con esa clave se rechaza")
    void claveDeOperacionIdempotente() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            MovimientoInventario primero = inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-002", TipoMovimiento.AJUSTE_NEGATIVO, "1", "AJ-T-002", null));
            MovimientoInventario repetido = inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-002", TipoMovimiento.AJUSTE_NEGATIVO, "1", "AJ-T-002", null));
            assertEquals(primero.getId(), repetido.getId());
            assertIgual("233", saldo(2));
            assertNegocio(Codigo.DUPLICADO, "ya se usó",
                    () -> inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-002", TipoMovimiento.AJUSTE_NEGATIVO, "2", "AJ-T-002", null)));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "BAJA_VENCIDO exige",
                    () -> inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-002", TipoMovimiento.BAJA_VENCIDO, "1", "BV-T-001", null)));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "Tipo de ajuste no admitido",
                    () -> inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-002", TipoMovimiento.SALIDA_VENTA, "1", "SV-T-001", null)));
        });
    }

    @Test
    @DisplayName("R038 y R070: al llegar a saldo 0 el lote queda AGOTADO y se avisa al personal que el producto se agotó")
    void agotamientoYAlerta() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long alertas = numero("SELECT count(*) FROM notificacion WHERE tipo = 'ALERTA_STOCK' AND producto_id = 3");
            inventario.registrarAjuste(quien(CARLA), ajuste("LF-2026-002", TipoMovimiento.BAJA_DEFECTUOSO, "234", "BJ-T-002", null));
            assertEquals("AGOTADO", texto("SELECT estado FROM lote_feder WHERE id = 2"));
            assertIgual("0", inventario.stock().vendible(3));
            assertEquals(alertas + 3, numero("SELECT count(*) FROM notificacion WHERE tipo = 'ALERTA_STOCK' AND producto_id = 3"));
            assertTrue(texto("SELECT mensaje FROM notificacion WHERE tipo = 'ALERTA_STOCK' AND producto_id = 3 ORDER BY id DESC LIMIT 1")
                    .contains("agotado"));
        });
    }

    @Test
    @DisplayName("El stock vendible excluye lotes BLOQUEADO y lotes cuyo origen venció (R035)")
    void stockVendibleExcluyeBloqueadosYVencidos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertIgual("95", inventario.stock().vendible(10));
            ejecutar("UPDATE lote_produccion SET fecha_vencimiento = DATE '2026-11-01' WHERE id = 9");
            assertIgual("0", inventario.stock().vendible(10));
            assertIgual("70", inventario.stock().vendible(9));
            ejecutar("UPDATE lote_feder SET estado = 'BLOQUEADO' WHERE id = 7");
            assertIgual("0", inventario.stock().vendible(9));
        });
    }

    @Test
    @DisplayName("Despacho (CU6): consume todas las reservas del pedido con su SALIDA_VENTA; repetirlo no duplica salidas")
    void consumoDeReservasEnElDespacho() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            List<MovimientoInventario> salidas = inventario.consumirReservasDePedido(10, 2);
            assertEquals(2, salidas.size());
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (17, 18) AND estado = 'CONSUMIDA' AND fecha_cierre IS NOT NULL"));
            assertIgual("70", saldo(7));
            assertIgual("0", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "no está cubierta", () -> inventario.consumirReservasDePedido(10, 2));
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE reserva_id = 17 AND tipo = 'SALIDA_VENTA'"));
        });
    }

    @Test
    @DisplayName("El despacho no sale de un lote vencido: se revierte entero sin consumir ninguna reserva (R055)")
    void despachoNoSaleDeLoteVencido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE lote_produccion SET fecha_vencimiento = DATE '2026-11-01' WHERE id = 8");
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "vencido", () -> inventario.consumirReservasDePedido(10, 2));
        });
    }

    @Test
    @DisplayName("Retorno de entrega (CU6): exige la reserva CONSUMIDA y la suma de retornos no supera la salida")
    void retornoDeEntrega() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.ESTADO_INVALIDO, "CONSUMIDA", () -> inventario.registrarRetornoEntrega(17, BigDecimal.ONE, 2, "RET-T-000", null));
            inventario.consumirReservasDePedido(10, 2);
            inventario.registrarRetornoEntrega(17, new BigDecimal("2"), 2, "RET-T-001", "Cliente ausente");
            assertIgual("72", saldo(7));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "supera lo despachado",
                    () -> inventario.registrarRetornoEntrega(17, new BigDecimal("5"), 2, "RET-T-002", null));
        });
    }

    @Test
    @DisplayName("Liberar reservas (cancelación): cada reserva sale de ACTIVA una sola vez y descuenta lo reservado (R084)")
    void liberacionUnica() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals(2, inventario.liberarReservasDePedido(11, EstadoReserva.LIBERADA));
            assertIgual("0", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 3"));
            assertIgual("0", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 5"));
            assertEquals(0, inventario.liberarReservasDePedido(11, EstadoReserva.EXPIRADA));
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND estado = 'LIBERADA'"));
        });
    }

    @Test
    @DisplayName("R053: con la confirmación comercial las reservas activas dejan de caducar")
    void quitarExpiracion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals(2, inventario.quitarExpiracionDeReservas(11));
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND fecha_expiracion IS NOT NULL"));
        });
    }

    @Test
    @DisplayName("R086: una línea nunca se cubre por encima de su cantidad")
    void sinExcesoDeReservas() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                assertNegocio(Codigo.REGLA_DE_NEGOCIO, "excede", () -> inventario.reservarLinea(18, BigDecimal.ONE, null)));
    }

    @Test
    @DisplayName("Reingreso de devolución y salida de reposición (CU6) mueven el saldo del lote con su clave")
    void devolucionYReposicion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            inventario.registrarEntradaDevolucion(3, 8, BigDecimal.ONE, 2, "DEV-T-001");
            assertIgual("96", saldo(8));
            List<MovimientoInventario> reposicion = inventario.registrarSalidaReposicion(1, 4, BigDecimal.ONE, 2, "REP-T-001");
            assertEquals(1, reposicion.size());
            assertEquals(3L, reposicion.get(0).getLoteFederId());
            assertIgual("146", saldo(3));
            assertEquals(reposicion.get(0).getId(), inventario.registrarSalidaReposicion(1, 4, BigDecimal.ONE, 2, "REP-T-001").get(0).getId());
        });
    }
}
