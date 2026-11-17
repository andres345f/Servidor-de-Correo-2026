package com.feder.negocio.inventario;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.LoteOrigenRepositorio;
import com.feder.datos.compras.OperacionCompraRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.MovimientoRepositorio;
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
 * R056 «Encargo sin stock ficticio»: abastecimiento de encargos al registrar la ENTRADA_COMPRA (en la
 * misma transacción, con prioridad para los respaldados por la producción del lote) y abastecimiento
 * manual con stock vendible. En la semilla, PED-2026-0008 (ENCARGO, ESPERANDO_ABASTECIMIENTO, id 8) pide
 * 12 FED-ACE-100 respaldadas por PRD-2026-011, la producción del lote LP-2026-011 de CMP-2026-005.
 */
class AbastecimientoEncargosTest {

    private static final String RESERVADO_ENCARGO = "SELECT COALESCE(sum(rs.cantidad), 0) FROM reserva_stock rs "
            + "JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id WHERE dp.pedido_id = 8 AND rs.estado = 'ACTIVA'";

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

    private ResultadoLinea recibir(String aceptada, String clave) {
        return recepcion.recibir(carla(), new SolicitudRecepcion("CMP-2026-005", "LP-2026-011", new BigDecimal(aceptada),
                BigDecimal.ZERO, "Estante A-03", null, null, clave));
    }

    @Test
    @DisplayName("R056 (a): la recepción cubre primero el encargo en espera y lo pasa a CONFIRMADO; solo el resto queda vendible")
    void recepcionAbasteceElEncargo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long avisos = numero("SELECT count(*) FROM notificacion WHERE pedido_id = 8 AND tipo = 'ENCARGO_ABASTECIDO'");
            ResultadoLinea r = recibir("28", "ABA-T-001");
            assertEquals(List.of(new PedidoAbastecido("PED-2026-0008", r.abastecidos().get(0).reservado(), true, true)), r.abastecidos());
            assertIgual("12", r.abastecidos().get(0).reservado());
            assertIgual("12", decimal(RESERVADO_ENCARGO));
            assertIgual("12", decimal("SELECT cantidad_reservada FROM lote_feder WHERE codigo = ?", r.codigoLoteFeder()));
            assertIgual("16", inventario.stock().vendible(2));
            assertEquals("CONFIRMADO", texto("SELECT estado FROM pedido WHERE id = 8"));
            assertEquals(1, numero("SELECT count(*) FROM historial_pedido WHERE pedido_id = 8 "
                    + "AND estado_anterior = 'ESPERANDO_ABASTECIMIENTO' AND estado_nuevo = 'CONFIRMADO'"));
            assertEquals(avisos + 1, numero("SELECT count(*) FROM notificacion WHERE pedido_id = 8 AND tipo = 'ENCARGO_ABASTECIDO'"));
            // Las reservas de un encargo no caducan (R053 (2)).
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + "WHERE dp.pedido_id = 8 AND rs.fecha_expiracion IS NOT NULL"));
        });
    }

    @Test
    @DisplayName("R056: una recepción menor que lo pendiente reserva todo lo recibido y el encargo sigue esperando")
    void recepcionParcialNoConfirma() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoLinea primera = recibir("5", "ABA-T-002");
            assertFalse(primera.abastecidos().get(0).confirmado());
            assertIgual("5", decimal(RESERVADO_ENCARGO));
            assertIgual("0", inventario.stock().vendible(2));
            assertEquals("ESPERANDO_ABASTECIMIENTO", texto("SELECT estado FROM pedido WHERE id = 8"));
            ResultadoLinea segunda = recibir("10", "ABA-T-003");
            assertIgual("7", segunda.abastecidos().get(0).reservado());
            assertTrue(segunda.abastecidos().get(0).confirmado());
            assertIgual("12", decimal(RESERVADO_ENCARGO));
            assertIgual("3", inventario.stock().vendible(2));
        });
    }

    @Test
    @DisplayName("R056: un encargo cancelado ya no recibe unidades; todo lo aceptado queda vendible")
    void encargoCanceladoNoSeAbastece() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE pedido SET estado = 'CANCELADO' WHERE id = 8");
            ResultadoLinea r = recibir("28", "ABA-T-004");
            assertTrue(r.abastecidos().isEmpty());
            assertIgual("28", inventario.stock().vendible(2));
        });
    }

    @Test
    @DisplayName("R056 (b): el personal abastece el encargo con stock vendible; un pedido sin líneas en espera se rechaza")
    void abastecimientoManual() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            crearLote(GestorTransacciones.conexionActual(), "ABA1", 2, LocalDate.now().plusYears(1), new BigDecimal("20"));
            List<PedidoAbastecido> r = inventario.abastecerPedido(8, carla().usuarioId());
            assertEquals(1, r.size());
            assertTrue(r.get(0).confirmado());
            assertIgual("12", decimal(RESERVADO_ENCARGO));
            assertIgual("8", inventario.stock().vendible(2));
            assertEquals("CONFIRMADO", texto("SELECT estado FROM pedido WHERE id = 8"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "no tiene líneas pendientes", () -> inventario.abastecerPedido(8, carla().usuarioId()));
            assertNegocio(Codigo.ESTADO_INVALIDO, "no tiene líneas pendientes", () -> inventario.abastecerPedido(11, carla().usuarioId()));
        });
    }

    @Test
    @DisplayName("R056 (b): sin stock vendible no se reserva nada y el encargo sigue esperando")
    void abastecimientoSinStock() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertTrue(inventario.abastecerPedido(8, carla().usuarioId()).isEmpty());
            assertEquals("ESPERANDO_ABASTECIMIENTO", texto("SELECT estado FROM pedido WHERE id = 8"));
        });
    }
}
