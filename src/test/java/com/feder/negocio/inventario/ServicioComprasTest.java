package com.feder.negocio.inventario;

import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.LoteOrigenRepositorio;
import com.feder.datos.compras.OperacionCompraRepositorio;
import com.feder.datos.compras.ProductorCompraRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.modelo.EstadoCompra;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compras a productores y pago (R004, R006, R039, R088) contra la base de pruebas, siempre revertidas. */
class ServicioComprasTest {

    private ContextoNegocio ctx;
    private ServicioCompras compras;
    private ServicioRecepcion recepcion;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        CompraRepositorio repoCompras = new CompraRepositorio();
        DetalleCompraRepositorio detalles = new DetalleCompraRepositorio();
        LoteOrigenRepositorio origenes = new LoteOrigenRepositorio();
        compras = new ServicioCompras(ctx, repoCompras, detalles, origenes, new ProductorCompraRepositorio(),
                new OperacionCompraRepositorio(), new LoteFederRepositorio());
        recepcion = new ServicioRecepcion(ctx, repoCompras, detalles, origenes, new OperacionCompraRepositorio(),
                new MovimientoRepositorio(), new LoteFederRepositorio(), ServicioInventario.crear(ctx));
    }

    private static Solicitante quien(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    @Test
    @DisplayName("INSCMP crea la compra REGISTRADA con su primera línea, total redondeado y bitácora CREAR")
    void registraCompraDentroDeLoOfrecido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            CompraDetallada c = compras.registrarCompra(quien(CARLA), "PROD-0001", "LP-2026-002", new BigDecimal("20"),
                    new BigDecimal("9.00"), "Jabones de diciembre");
            assertEquals(EstadoCompra.REGISTRADA, c.compra().getEstado());
            assertTrue(c.compra().getCodigo().matches("CMP-\\d{4}-\\d{3,}"), c.compra().getCodigo());
            assertIgual("180.00", c.compra().getTotal());
            assertEquals(1, c.lineas().size());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'COMPRA_FEDER' AND accion = 'CREAR' AND entidad_id = ?",
                    c.compra().getId()));
            // Sin inventario: comprar no crea lotes de FEDER ni movimientos (solo la recepción lo hace).
            assertEquals(0, numero("SELECT count(*) FROM lote_feder WHERE detalle_compra_id = ?", c.lineas().get(0).detalle().getId()));
        });
    }

    @Test
    @DisplayName("R039: una línea no supera lo que el lote todavía ofrece; al comprometer todo, la oferta queda AGOTADO")
    void compraDentroDeLoOfrecido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "todavía ofrece a FEDER (50",
                    () -> compras.registrarCompra(quien(CARLA), "PROD-0001", "LP-2026-002", new BigDecimal("51"), null, null));
            compras.registrarCompra(quien(CARLA), "PROD-0001", "LP-2026-002", new BigDecimal("50"), null, null);
            assertEquals("AGOTADO", texto("SELECT estado FROM lote_produccion WHERE codigo_lote = 'LP-2026-002'"));
            assertIgual("9.00", decimal("SELECT precio_unitario FROM detalle_compra ORDER BY id DESC LIMIT 1"));
        });
    }

    @Test
    @DisplayName("R004: lote de otro productor, lote RETIRADO o cantidad decimal en UNIDAD se rechazan")
    void compraAutorizada() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "pertenece a otro productor",
                    () -> compras.registrarCompra(quien(CARLA), "PROD-0002", "LP-2026-002", BigDecimal.ONE, null, null));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "retirado",
                    () -> compras.registrarCompra(quien(CARLA), "PROD-0002", "LP-2026-006", BigDecimal.ONE, null, null));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "entero",
                    () -> compras.registrarCompra(quien(CARLA), "PROD-0001", "LP-2026-002", new BigDecimal("1.5"), null, null));
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER",
                    () -> compras.registrarCompra(quien(MARIA), "PROD-0001", "LP-2026-002", BigDecimal.ONE, null, null));
        });
    }

    @Test
    @DisplayName("ADDCMP agrega una línea solo a una compra REGISTRADA y suma su importe al total acordado")
    void agregaLineaSoloEnRegistrada() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            CompraDetallada c = compras.agregarLinea(quien(CARLA), "CMP-2026-005", "LP-2026-001", new BigDecimal("30"), new BigDecimal("15.00"));
            assertIgual("2250.00", c.compra().getTotal());
            assertEquals(2, c.lineas().size());
            assertNegocio(Codigo.DUPLICADO, "un lote por compra",
                    () -> compras.agregarLinea(quien(CARLA), "CMP-2026-005", "LP-2026-001", BigDecimal.ONE, null));
            assertNegocio(Codigo.ESTADO_INVALIDO, "REGISTRADA",
                    () -> compras.agregarLinea(quien(CARLA), "CMP-2026-003", "LP-2026-009", BigDecimal.ONE, null));
        });
    }

    @Test
    @DisplayName("R006: solo se paga una compra RECIBIDA, una vez, por lo aceptado; después sus cantidades no cambian")
    void pagoAlProductor() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.ESTADO_INVALIDO, "RECIBIDA_PARCIAL", () -> compras.registrarPago(quien(JORGE), "CMP-2026-003"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya fue pagada", () -> compras.registrarPago(quien(JORGE), "CMP-2026-001"));
            recepcion.cancelarPendiente(quien(CARLA), "CMP-2026-003", "LP-2026-008", null, "No entregará el resto", "CAN-T-001");
            CompraDetallada pagada = compras.registrarPago(quien(JORGE), "CMP-2026-003");
            // 150 x 13,00 + 80 x 17,00 + 100 x 14,00: se paga lo aceptado, no lo rechazado ni lo cancelado.
            assertIgual("4710.00", pagada.importeAPagar());
            String detalle = texto("SELECT detalle::text FROM bitacora WHERE entidad = 'COMPRA_FEDER' AND entidad_id = 3 ORDER BY id DESC LIMIT 1");
            assertTrue(detalle.contains("PAGO_PRODUCTOR") && detalle.contains("4710.00"), detalle);
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya fue pagada", () -> compras.registrarPago(quien(JORGE), "CMP-2026-003"));
        });
    }

    @Test
    @DisplayName("R088: un PRODUCTOR solo lista y consulta sus propias compras; un CLIENTE no accede")
    void productorVeSoloSusCompras() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertTrue(compras.listar(quien(MARIA), null).stream().allMatch(r -> r.codigoProductor().equals("PROD-0001")));
            assertEquals(2, compras.listar(quien(MARIA), null).size());
            assertEquals(5, compras.listar(quien(CARLA), null).size());
            assertEquals("CMP-2026-001", compras.ver(quien(MARIA), "CMP-2026-001").compra().getCodigo());
            assertNegocio(Codigo.NO_ENCONTRADO, "No existe la compra CMP-2026-002", () -> compras.ver(quien(MARIA), "CMP-2026-002"));
            assertNegocio(Codigo.SIN_PERMISO, "productores", () -> compras.listar(quien(ELMER), null));
        });
    }
}
