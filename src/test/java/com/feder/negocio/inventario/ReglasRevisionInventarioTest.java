package com.feder.negocio.inventario;

import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.LoteOrigenRepositorio;
import com.feder.datos.compras.OperacionCompraRepositorio;
import com.feder.datos.compras.ProductorCompraRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.MovimientoRepositorio;
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

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reglas de compras e inventario corregidas en la revisión: productor habilitado en cada línea (R004),
 * pago concurrente con una corrección (R006), rectificación documental de precio (R012) e idempotencia
 * de los movimientos por clave de operación comparando lote, reserva y devolución.
 */
class ReglasRevisionInventarioTest {

    private ContextoNegocio ctx;
    private ServicioCompras compras;
    private ServicioInventario inventario;
    private DetalleCompraRepositorio detalles;
    private CompraRepositorio repoCompras;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        repoCompras = new CompraRepositorio();
        detalles = new DetalleCompraRepositorio();
        compras = new ServicioCompras(ctx, repoCompras, detalles, new LoteOrigenRepositorio(), new ProductorCompraRepositorio(),
                new OperacionCompraRepositorio(), new LoteFederRepositorio());
        inventario = ServicioInventario.crear(ctx);
    }

    private static Solicitante quien(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    private MovimientoInventario baja(String lote, String clave) {
        return inventario.registrarAjuste(quien(CARLA), new SolicitudAjuste(lote, TipoMovimiento.BAJA_DANADO, new BigDecimal("2"),
                "Botellas rotas", clave, null));
    }

    @Test
    @DisplayName("R004: ADDCMP no agrega líneas si el productor de la compra dejó de estar habilitado")
    void agregarLineaExigeProductorHabilitado() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE productor SET activo = false WHERE id = 1");
            BigDecimal total = decimal("SELECT total FROM compra_feder WHERE codigo = 'CMP-2026-005'");
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no está habilitado", () -> compras.agregarLinea(quien(CARLA), "CMP-2026-005",
                    "LP-2026-001", new BigDecimal("30"), new BigDecimal("15.00")));
            assertEquals(0, total.compareTo(decimal("SELECT total FROM compra_feder WHERE codigo = 'CMP-2026-005'")));
            assertEquals(1, numero("SELECT count(*) FROM detalle_compra WHERE compra_id = 5"));
        });
    }

    @Test
    @DisplayName("R006: si la compra quedó pagada mientras se corregía una línea, el recálculo revierte la operación")
    void recalculoRechazaCompraPagada() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            RecalculoCompra recalculo = new RecalculoCompra(repoCompras, detalles);
            ejecutar("UPDATE compra_feder SET fecha_pago_productor = NULL WHERE id = 1");
            assertEquals(com.feder.modelo.EstadoCompra.RECIBIDA, recalculo.recalcular(1));
            ejecutar("UPDATE compra_feder SET fecha_pago_productor = now() WHERE id = 1");
            assertNegocio(Codigo.ESTADO_INVALIDO, "fue pagada al productor mientras", () -> recalculo.recalcular(1));
        });
    }

    @Test
    @DisplayName("R012: RECPRE documenta la diferencia en bitácora sin tocar el precio, el total ni los movimientos")
    void rectificacionDePrecio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal total = decimal("SELECT total FROM compra_feder WHERE codigo = 'CMP-2026-003'");
            ServicioCompras.RectificacionPrecio r = compras.rectificarPrecio(quien(ADMIN), "CMP-2026-003", "LP-2026-008",
                    new BigDecimal("16.00"), new BigDecimal("80"), "Acta de compra con 16.00 Bs");
            assertIgual("-80.00", r.importeDiferencia());
            assertIgual("17.00", decimal("SELECT precio_unitario FROM detalle_compra WHERE id = 7"));
            assertEquals(0, total.compareTo(decimal("SELECT total FROM compra_feder WHERE codigo = 'CMP-2026-003'")));
            assertEquals("-80.00", texto("SELECT detalle ->> 'importe_diferencia' FROM bitacora WHERE entidad = 'DETALLE_COMPRA' "
                    + "AND entidad_id = 7 AND detalle ->> 'operacion' = 'RECTIFICACION_PRECIO'"));
            assertNegocio(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> compras.rectificarPrecio(quien(CARLA), "CMP-2026-003",
                    "LP-2026-008", new BigDecimal("16.00"), new BigDecimal("1"), "x"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "supera lo aceptado", () -> compras.rectificarPrecio(quien(ADMIN), "CMP-2026-003",
                    "LP-2026-008", new BigDecimal("16.00"), new BigDecimal("81"), "x"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "PRECMP", () -> compras.rectificarPrecio(quien(ADMIN), "CMP-2026-005",
                    "LP-2026-011", new BigDecimal("50.00"), new BigDecimal("1"), "x"));
        });
    }

    @Test
    @DisplayName("Idempotencia: la misma clave en el mismo lote devuelve el movimiento; en otro lote se rechaza y no aplica nada")
    void claveDeAjusteComparaElLote() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            MovimientoInventario primero = baja("LF-2026-001", "BAJA-T-K1");
            assertEquals(primero.getId(), baja("LF-2026-001", "BAJA-T-K1").getId());
            BigDecimal saldo = decimal("SELECT cantidad_disponible FROM lote_feder WHERE codigo = 'LF-2026-003'");
            assertNegocio(Codigo.DUPLICADO, "ya se usó", () -> baja("LF-2026-003", "BAJA-T-K1"));
            assertEquals(0, saldo.compareTo(decimal("SELECT cantidad_disponible FROM lote_feder WHERE codigo = 'LF-2026-003'")));
        });
    }

    @Test
    @DisplayName("Idempotencia de CU6: un retorno con la clave de otra reserva se rechaza; la misma reserva devuelve el registrado")
    void claveDeRetornoComparaLaReserva() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long usuario = quien(CARLA).usuarioId();
            MovimientoInventario m = inventario.registrarRetornoEntrega(15, new BigDecimal("1"), usuario, "RET-T-1", "No recibido");
            assertEquals(m.getId(), inventario.registrarRetornoEntrega(15, new BigDecimal("1"), usuario, "RET-T-1", "No recibido").getId());
            assertNegocio(Codigo.DUPLICADO, "ya se usó",
                    () -> inventario.registrarRetornoEntrega(16, new BigDecimal("1"), usuario, "RET-T-1", "No recibido"));
            assertEquals(0, numero("SELECT count(*) FROM movimiento_inventario WHERE reserva_id = 16 AND tipo = 'ENTRADA_RETORNO_ENTREGA'"));
        });
    }

    @Test
    @DisplayName("SALIDA_REPOSICION rechaza cantidades no positivas y claves sin sitio para el sufijo, aunque no haya stock")
    void reposicionValidaAntesDeBuscarStock() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long usuario = quien(CARLA).usuarioId();
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "mayor que 0",
                    () -> inventario.registrarSalidaReposicion(1, 11, BigDecimal.ZERO, usuario, "REP-T-1"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "mayor que 0",
                    () -> inventario.registrarSalidaReposicion(1, 11, new BigDecimal("-1"), usuario, "REP-T-1"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "60 caracteres",
                    () -> inventario.registrarSalidaReposicion(1, 2, BigDecimal.ONE, usuario, "R".repeat(61)));
        });
    }
}
