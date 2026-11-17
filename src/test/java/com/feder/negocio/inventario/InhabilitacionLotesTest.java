package com.feder.negocio.inventario;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.modelo.EstadoLoteFeder;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R035 «Bloqueo o vencimiento» y consultas del panel de R070. En la semilla, la reserva 17 (6 unidades de
 * FED-CHO-100 del pedido CONFIRMADO PED-2026-0010) está en LF-2026-007 y la reserva 19 (2 unidades de
 * FED-MIE-500 del pedido PENDIENTE PED-2026-0011, con plazo) en LF-2026-003.
 */
class InhabilitacionLotesTest {

    private ContextoNegocio ctx;
    private ServicioInventario inventario;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        inventario = ServicioInventario.crear(ctx);
    }

    private static Solicitante carla() {
        return BaseDatosPrueba.solicitante(CARLA);
    }

    private static void vencerOrigen(String codigoLoteFeder, LocalDate fecha) {
        ejecutar("UPDATE lote_produccion SET fecha_vencimiento = ? WHERE id = (SELECT dc.lote_origen_id FROM lote_feder lf "
                + "JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id WHERE lf.codigo = ?)", java.sql.Date.valueOf(fecha), codigoLoteFeder);
    }

    @Test
    @DisplayName("BLQLTF sin otro stock: libera la reserva, el pedido queda sin cobertura y se deja constancia en bitácora")
    void bloqueoSinStockParaReasignar() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            LoteInhabilitado r = inventario.bloquearLote(carla(), "LF-2026-007", "Tabletas con humedad");
            assertEquals(EstadoLoteFeder.BLOQUEADO, r.lote().estado());
            assertEquals(EstadoLoteFeder.DISPONIBLE, r.estadoAnterior());
            assertEquals(1, r.reservasLiberadas());
            assertIgual("6", r.liberado());
            assertIgual("0", r.reasignado());
            assertEquals(List.of("PED-2026-0010"), r.pedidosSinCobertura());
            assertEquals("LIBERADA", texto("SELECT estado FROM reserva_stock WHERE id = 17"));
            assertIgual("0", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
            assertIgual("0", inventario.stock().vendible(9));
            assertEquals("CONFIRMADO", texto("SELECT estado FROM pedido WHERE id = 10"));
            assertEquals("BLOQUEADO", texto("SELECT detalle ->> 'estado_nuevo' FROM bitacora WHERE entidad = 'LOTE_FEDER' "
                    + "AND entidad_id = 7 ORDER BY id DESC LIMIT 1"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya está BLOQUEADO", () -> inventario.bloquearLote(carla(), "LF-2026-007", "otra vez"));
            // Cuando llega stock apto, el personal repone la cobertura; el pedido CONFIRMADO conserva su estado (R056).
            crearLote(GestorTransacciones.conexionActual(), "BLQ1", 9, LocalDate.now().plusYears(1), new BigDecimal("10"));
            List<PedidoAbastecido> repuesto = inventario.abastecerPedido(10, carla().usuarioId());
            assertTrue(repuesto.get(0).cubierto());
            assertFalse(repuesto.get(0).confirmado());
            assertEquals("CONFIRMADO", texto("SELECT estado FROM pedido WHERE id = 10"));
        });
    }

    @Test
    @DisplayName("BLQLTF con stock en otro lote: la reserva se reasigna con FEFO y el pedido sigue cubierto")
    void bloqueoReasignaConFefo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            LoteFixture otro = crearLote(GestorTransacciones.conexionActual(), "BLQ2", 9, LocalDate.now().plusYears(1), new BigDecimal("10"));
            LoteInhabilitado r = inventario.bloquearLote(carla(), "LF-2026-007", "Error de lote de origen");
            assertIgual("6", r.reasignado());
            assertTrue(r.pedidosSinCobertura().isEmpty());
            assertIgual("6", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = ?", otro.loteFederId()));
            assertEquals(1, numero("SELECT count(*) FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + "WHERE dp.pedido_id = 10 AND rs.lote_feder_id = ? AND rs.estado = 'ACTIVA' AND rs.fecha_expiracion IS NULL",
                    otro.loteFederId()));
        });
    }

    @Test
    @DisplayName("R070: bloquear el único lote de un producto lo deja agotado y avisa al personal")
    void bloqueoEmiteAlerta() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long antes = numero("SELECT count(*) FROM notificacion WHERE producto_id = 10 AND tipo = 'ALERTA_STOCK'");
            inventario.bloquearLote(carla(), "LF-2026-008", "Humedad en las bolsas");
            assertTrue(numero("SELECT count(*) FROM notificacion WHERE producto_id = 10 AND tipo = 'ALERTA_STOCK'") > antes);
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER",
                    () -> inventario.bloquearLote(BaseDatosPrueba.solicitante(ELMER), "LF-2026-002", "x"));
        });
    }

    @Test
    @DisplayName("VENLTF: el lote cuyo origen venció pasa a VENCIDO y su reserva se reasigna conservando el plazo de pago")
    void procesoDeVencimiento() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            String plazo = texto("SELECT fecha_expiracion::text FROM reserva_stock WHERE id = 19");
            vencerOrigen("LF-2026-003", LocalDate.now().minusDays(1));
            LoteFixture otro = crearLote(GestorTransacciones.conexionActual(), "VEN1", 4, LocalDate.now().plusYears(1), new BigDecimal("5"));
            List<LoteInhabilitado> vencidos = inventario.vencerLotes(carla());
            LoteInhabilitado r = vencidos.stream().filter(v -> v.lote().codigo().equals("LF-2026-003")).findFirst().orElseThrow();
            assertEquals(EstadoLoteFeder.VENCIDO, r.lote().estado());
            assertEquals("LIBERADA", texto("SELECT estado FROM reserva_stock WHERE id = 19"));
            assertEquals(plazo, texto("SELECT fecha_expiracion::text FROM reserva_stock WHERE lote_feder_id = ? AND estado = 'ACTIVA'",
                    otro.loteFederId()));
            assertTrue(r.pedidosSinCobertura().isEmpty());
            // Un segundo pase no vuelve a tocar el lote ya vencido.
            assertTrue(inventario.vencerLotes(carla()).stream().noneMatch(v -> v.lote().codigo().equals("LF-2026-003")));
        });
    }

    @Test
    @DisplayName("LISVEN: lotes con saldo próximos a vencer dentro de N días y vencidos pendientes de baja en cualquier estado")
    void panelDeVencimientos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            LocalDate hoy = inventario.stock().hoy();
            vencerOrigen("LF-2026-008", hoy.plusDays(5));
            vencerOrigen("LF-2026-002", hoy.minusDays(2));
            ejecutar("UPDATE lote_feder SET estado = 'BLOQUEADO' WHERE codigo = 'LF-2026-002'");
            List<String> diez = inventario.lotesPorVencer(10).stream().map(LoteFederVista::codigo).toList();
            List<String> tres = inventario.lotesPorVencer(3).stream().map(LoteFederVista::codigo).toList();
            assertTrue(diez.contains("LF-2026-008") && diez.contains("LF-2026-002"), diez.toString());
            assertFalse(tres.contains("LF-2026-008"), tres.toString());
            assertTrue(tres.contains("LF-2026-002"), tres.toString());
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "entre 0 y 365", () -> inventario.lotesPorVencer(400));
        });
    }
}
