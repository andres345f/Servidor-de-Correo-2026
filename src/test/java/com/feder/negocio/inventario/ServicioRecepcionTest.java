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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recepción y cancelación de líneas de compra (R005, R013, R014, R035, R036): el inventario solo crece
 * con la aceptación física, en la misma transacción, y una clave repetida no aplica nada dos veces.
 * La línea usada es la del lote LP-2026-011 de la compra CMP-2026-005 (30 unidades pendientes).
 */
class ServicioRecepcionTest {

    private static final String DETALLE = "SELECT %s FROM detalle_compra WHERE id = 10";

    private ContextoNegocio ctx;
    private ServicioRecepcion recepcion;
    private ServicioInventario inventario;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        inventario = ServicioInventario.crear(ctx);
        recepcion = new ServicioRecepcion(ctx, new CompraRepositorio(), new DetalleCompraRepositorio(),
                new LoteOrigenRepositorio(), new OperacionCompraRepositorio(), new MovimientoRepositorio(),
                new LoteFederRepositorio(), inventario);
    }

    private static Solicitante carla() {
        return BaseDatosPrueba.solicitante(CARLA);
    }

    private static SolicitudRecepcion recibir(String aceptada, String rechazada, String motivo, String clave) {
        return new SolicitudRecepcion("CMP-2026-005", "LP-2026-011", new BigDecimal(aceptada), new BigDecimal(rechazada),
                "Estante A-03", motivo, null, clave);
    }

    @Test
    @DisplayName("La primera aceptación crea el LOTE_FEDER con su ENTRADA_COMPRA y su registro RECEPCION en bitácora")
    void primeraAceptacionCreaLote() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoLinea r = recepcion.recibir(carla(), new SolicitudRecepcion("CMP-2026-005", "LP-2026-011",
                    new BigDecimal("28"), new BigDecimal("2"), "Estante A-03", "Dos botellas con el sello roto", "NE-MQ-0002", "REC-T-001"));
            assertFalse(r.yaRegistrada());
            assertEquals(EstadoCompra.RECIBIDA, r.estadoCompra());
            assertIgual("28", decimal(DETALLE.formatted("cantidad_recibida")));
            assertIgual("2", decimal(DETALLE.formatted("cantidad_rechazada")));
            assertIgual("28", decimal("SELECT cantidad_disponible FROM lote_feder WHERE codigo = ?", r.codigoLoteFeder()));
            assertEquals("DISPONIBLE", texto("SELECT estado FROM lote_feder WHERE codigo = ?", r.codigoLoteFeder()));
            assertEquals("ENTRADA_COMPRA", texto("SELECT tipo FROM movimiento_inventario WHERE clave_operacion = 'REC-T-001'"));
            assertEquals("number", texto("SELECT jsonb_typeof(detalle -> 'cantidad_aceptada') FROM bitacora "
                    + "WHERE entidad = 'DETALLE_COMPRA' AND detalle ->> 'clave_operacion' = 'REC-T-001'"));
            assertEquals("RECIBIDA", texto("SELECT estado FROM compra_feder WHERE codigo = 'CMP-2026-005'"));
            // R056 (a): 12 de las 28 unidades cubren el encargo PED-2026-0008 (respaldado por la misma producción).
            assertIgual("16", inventario.stock().vendible(2));
            assertEquals(1, r.abastecidos().size());
            assertTrue(r.abastecidos().get(0).confirmado());
        });
    }

    @Test
    @DisplayName("R013: repetir la recepción con la misma clave devuelve la operación registrada sin sumar de nuevo")
    void recepcionIdempotente() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            recepcion.recibir(carla(), recibir("10", "0", null, "REC-T-002"));
            ResultadoLinea repetida = recepcion.recibir(carla(), recibir("10", "0", null, "REC-T-002"));
            assertTrue(repetida.yaRegistrada());
            assertIgual("10", decimal(DETALLE.formatted("cantidad_recibida")));
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE clave_operacion = 'REC-T-002'"));
            assertNegocio(Codigo.DUPLICADO, "otra operación de compra",
                    () -> recepcion.cancelarPendiente(carla(), "CMP-2026-005", "LP-2026-011", BigDecimal.ONE, "x", "REC-T-002"));
        });
    }

    @Test
    @DisplayName("Las recepciones parciales suman al mismo lote y la compra queda RECIBIDA_PARCIAL mientras haya pendiente")
    void recepcionesParcialesSumanAlMismoLote() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoLinea primera = recepcion.recibir(carla(), recibir("10", "0", null, "REC-T-003"));
            ResultadoLinea segunda = recepcion.recibir(carla(), recibir("5", "0", null, "REC-T-004"));
            assertEquals(EstadoCompra.RECIBIDA_PARCIAL, segunda.estadoCompra());
            assertEquals(primera.codigoLoteFeder(), segunda.codigoLoteFeder());
            assertEquals(1, numero("SELECT count(*) FROM lote_feder WHERE detalle_compra_id = 10"));
            assertIgual("15", decimal("SELECT cantidad_disponible FROM lote_feder WHERE detalle_compra_id = 10"));
            assertIgual("15", segunda.linea().pendiente());
        });
    }

    @Test
    @DisplayName("R014: lo aceptado más lo rechazado no supera lo pendiente; un rechazo exige motivo")
    void limitesDeLaRecepcion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "supera lo pendiente", () -> recepcion.recibir(carla(), recibir("31", "0", null, "REC-T-005")));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "motivo", () -> recepcion.recibir(carla(), recibir("0", "3", null, "REC-T-006")));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "entero", () -> recepcion.recibir(carla(), recibir("1.5", "0", null, "REC-T-007")));
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER",
                    () -> recepcion.recibir(BaseDatosPrueba.solicitante(MARIA), recibir("1", "0", null, "REC-T-008")));
            assertIgual("0", decimal(DETALLE.formatted("cantidad_recibida")));
            assertEquals(0, numero("SELECT count(*) FROM lote_feder WHERE detalle_compra_id = 10"));
        });
    }

    @Test
    @DisplayName("Una línea rechazada por completo no crea lote de FEDER y la compra queda CANCELADA (cerrada sin mercancía)")
    void rechazoTotalSinLote() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoLinea r = recepcion.recibir(carla(), recibir("0", "30", "Botellas con el aceite enturbiado", "REC-T-009"));
            assertNull(r.codigoLoteFeder());
            assertEquals(EstadoCompra.CANCELADA, r.estadoCompra());
            assertEquals(0, numero("SELECT count(*) FROM lote_feder WHERE detalle_compra_id = 10"));
            assertEquals(0, numero("SELECT count(*) FROM movimiento_inventario WHERE clave_operacion = 'REC-T-009'"));
        });
    }

    @Test
    @DisplayName("R035: no se acepta mercancía cuyo lote de origen ya venció; sí puede registrarse como rechazada")
    void noAceptaMercanciaVencida() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE lote_produccion SET fecha_vencimiento = DATE '2026-11-10' WHERE codigo_lote = 'LP-2026-011'");
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "venció", () -> recepcion.recibir(carla(), recibir("5", "0", null, "REC-T-010")));
            recepcion.recibir(carla(), recibir("0", "5", "Producto vencido", "REC-T-011"));
            assertIgual("5", decimal(DETALLE.formatted("cantidad_rechazada")));
        });
    }

    @Test
    @DisplayName("CANCMP cancela lo pendiente con motivo y bitácora; cierra la compra y no se repite con la misma clave")
    void cancelacionDePendiente() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoLinea r = recepcion.cancelarPendiente(carla(), "CMP-2026-003", "LP-2026-008", null,
                    "La productora no entregará las 30 tabletas", "CAN-T-001");
            assertIgual("30", r.cancelada());
            assertEquals(EstadoCompra.RECIBIDA, r.estadoCompra());
            assertEquals("ANULAR", texto("SELECT accion FROM bitacora WHERE detalle ->> 'clave_operacion' = 'CAN-T-001'"));
            assertTrue(recepcion.cancelarPendiente(carla(), "CMP-2026-003", "LP-2026-008", null, "otra vez", "CAN-T-001").yaRegistrada());
            assertNegocio(Codigo.ESTADO_INVALIDO, "no tiene cantidad pendiente",
                    () -> recepcion.cancelarPendiente(carla(), "CMP-2026-003", "LP-2026-008", null, "x", "CAN-T-002"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya fue pagada",
                    () -> recepcion.recibir(carla(), new SolicitudRecepcion("CMP-2026-001", "LP-2026-001", BigDecimal.ONE,
                            BigDecimal.ZERO, null, null, null, "REC-T-012")));
        });
    }

    @Test
    @DisplayName("R009: una aceptación de más se anula con ANULACION_RECEPCION; vuelve a pendiente o pasa a rechazada")
    void correccionDeRecepcion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            recepcion.recibir(carla(), recibir("20", "0", null, "REC-T-020"));
            ResultadoLinea r = recepcion.anularRecepcion(carla(), "CMP-2026-005", "LP-2026-011", new BigDecimal("5"), false,
                    "Se registraron cinco de más", "ANU-T-001");
            assertEquals(EstadoCompra.RECIBIDA_PARCIAL, r.estadoCompra());
            assertIgual("15", decimal(DETALLE.formatted("cantidad_recibida")));
            assertIgual("15", r.linea().pendiente());
            assertIgual("15", decimal("SELECT cantidad_disponible FROM lote_feder WHERE detalle_compra_id = 10"));
            assertEquals("ANULACION_RECEPCION", texto("SELECT tipo FROM movimiento_inventario WHERE clave_operacion = 'ANU-T-001'"));
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE clave_operacion = 'REC-T-020'"));
            recepcion.anularRecepcion(carla(), "CMP-2026-005", "LP-2026-011", new BigDecimal("3"), true, "Llegaron rotas", "ANU-T-002");
            assertIgual("12", decimal(DETALLE.formatted("cantidad_recibida")));
            assertIgual("3", decimal(DETALLE.formatted("cantidad_rechazada")));
            assertTrue(recepcion.anularRecepcion(carla(), "CMP-2026-005", "LP-2026-011", new BigDecimal("3"), true, "x", "ANU-T-002")
                    .yaRegistrada());
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "supera lo aceptado neto", () -> recepcion.anularRecepcion(carla(),
                    "CMP-2026-005", "LP-2026-011", new BigDecimal("13"), false, "x", "ANU-T-003"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "Solo se anula stock libre", () -> recepcion.anularRecepcion(carla(),
                    "CMP-2026-003", "LP-2026-008", new BigDecimal("71"), false, "x", "ANU-T-004"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya fue pagada", () -> recepcion.anularRecepcion(carla(),
                    "CMP-2026-001", "LP-2026-001", BigDecimal.ONE, false, "x", "ANU-T-005"));
        });
    }

    @Test
    @DisplayName("R012: el precio pactado se corrige solo antes de la primera aceptación, con bitácora y nuevo total")
    void correccionDePrecio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ServicioCompras compras = new ServicioCompras(ctx, new CompraRepositorio(), new DetalleCompraRepositorio(),
                    new LoteOrigenRepositorio(), new ProductorCompraRepositorio(),
                    new OperacionCompraRepositorio(), new LoteFederRepositorio());
            CompraDetallada c = compras.corregirPrecio(carla(), "CMP-2026-005", "LP-2026-011", new BigDecimal("58.00"), "Acuerdo escrito");
            assertIgual("1740.00", c.compra().getTotal());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'DETALLE_COMPRA' AND entidad_id = 10 "
                    + "AND detalle ->> 'operacion' = 'CORRECCION_PRECIO'"));
            recepcion.recibir(carla(), recibir("1", "0", null, "REC-T-021"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "primera aceptación",
                    () -> compras.corregirPrecio(carla(), "CMP-2026-005", "LP-2026-011", new BigDecimal("55.00"), "otra"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya fue pagada",
                    () -> compras.corregirPrecio(carla(), "CMP-2026-001", "LP-2026-001", new BigDecimal("14.00"), "x"));
        });
    }

    @Test
    @DisplayName("Lo cancelado libera la oferta del lote de origen; lo aceptado nunca se cancela")
    void cancelacionLiberaOferta() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            recepcion.recibir(carla(), recibir("20", "0", null, "REC-T-013"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "Solo se cancela cantidad pendiente",
                    () -> recepcion.cancelarPendiente(carla(), "CMP-2026-005", "LP-2026-011", new BigDecimal("11"), "x", "CAN-T-003"));
            recepcion.cancelarPendiente(carla(), "CMP-2026-005", "LP-2026-011", new BigDecimal("10"), "No llegará", "CAN-T-004");
            assertIgual("20", decimal("SELECT sum(cantidad_comprada - cantidad_cancelada) FROM detalle_compra WHERE lote_origen_id = 11"));
            assertEquals("RECIBIDA", texto("SELECT estado FROM compra_feder WHERE codigo = 'CMP-2026-005'"));
        });
    }
}
