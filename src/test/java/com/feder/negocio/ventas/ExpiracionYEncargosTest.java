package com.feder.negocio.ventas;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.modelo.EstadoPedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Expiración de reservas (R053 (3)), revalidación del precio mostrado (R054 (2)), consulta del carrito
 * por productos, vínculo de respaldo por línea (A25, R062) y abastecimiento manual (R056 (b)).
 */
class ExpiracionYEncargosTest {

    private ContextoNegocio ctx;
    private FabricaVentas ventas;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        ventas = new FabricaVentas(ctx);
    }

    private static Solicitante quien(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    private static void vencerPlazo(String codigoPedido) {
        ejecutar("UPDATE reserva_stock SET fecha_expiracion = now() - interval '1 hour' WHERE estado = 'ACTIVA' AND detalle_pedido_id IN "
                + "(SELECT dp.id FROM detalle_pedido dp JOIN pedido p ON p.id = dp.pedido_id WHERE p.codigo = ?)", codigoPedido);
    }

    @Test
    @DisplayName("R053 (3): EXPRES cancela el pedido PENDIENTE con plazo vencido; con un pago QR PENDIENTE lo deja para conciliar")
    void expiracionDeReservas() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            String codigo = ventas.pedidos.confirmarInmediato(quien(ANDRES), FabricaVentas.recojo("Andrés", Set.of(), "84.00"))
                    .pedido().pedido().getCodigo();
            assertIgual("9", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
            vencerPlazo(codigo);
            vencerPlazo("PED-2026-0011");
            // El intento QR de la semilla sigue PENDIENTE: sea cual sea su antigüedad, frena la expiración hasta conciliarlo.
            ServicioPedidos.ResultadoExpiracion r = ventas.pedidos.expirarReservasVencidas(quien(CARLA));
            assertTrue(r.expirados().contains(codigo), r.toString());
            assertTrue(r.porConciliar().stream().anyMatch(o -> o.startsWith("PED-2026-0011") && o.contains("pago PENDIENTE")), r.toString());
            assertTrue(r.omitidos().stream().noneMatch(o -> o.startsWith("PED-2026-0011")), r.toString());
            assertEquals("CANCELADO", texto("SELECT estado FROM pedido WHERE codigo = ?", codigo));
            assertEquals("PENDIENTE", texto("SELECT estado FROM pedido WHERE codigo = 'PED-2026-0011'"));
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + "JOIN pedido p ON p.id = dp.pedido_id WHERE p.codigo = ? AND rs.estado <> 'EXPIRADA'", codigo));
            assertIgual("6", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
            assertEquals("CANCELADA", texto("SELECT e.estado FROM entrega e JOIN pedido p ON p.id = e.pedido_id WHERE p.codigo = ?", codigo));
            assertEquals(1, numero("SELECT count(*) FROM historial_pedido h JOIN pedido p ON p.id = h.pedido_id WHERE p.codigo = ? "
                    + "AND h.estado_nuevo = 'CANCELADO' AND h.usuario_id IS NULL AND h.comentario = 'reserva expirada'", codigo));
            // Un segundo pase no encuentra nada nuevo que expirar.
            assertTrue(ventas.pedidos.expirarReservasVencidas(quien(CARLA)).expirados().isEmpty());
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER", () -> ventas.pedidos.expirarReservasVencidas(quien(ANDRES)));
        });
    }

    @Test
    @DisplayName("R053: un pedido con reservas todavía vigentes no se expira")
    void plazoVigenteNoExpira() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            String codigo = ventas.pedidos.confirmarInmediato(quien(ANDRES), FabricaVentas.recojo("Andrés", Set.of(), "84.00"))
                    .pedido().pedido().getCodigo();
            assertTrue(ventas.pedidos.expirarReservasVencidas(quien(CARLA)).expirados().stream().noneMatch(codigo::equals));
            assertEquals("PENDIENTE", texto("SELECT estado FROM pedido WHERE codigo = ?", codigo));
        });
    }

    @Test
    @DisplayName("R054 (2): sin el subtotal que vio el cliente no se confirma; VERCAR muestra el subtotal de la selección")
    void subtotalObligatorioYVistaPorProductos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ventas.carrito.agregar(quien(ANDRES), "FED-ACE-020", new BigDecimal("2"));
            assertEquals(2, ventas.carrito.ver(quien(ANDRES)).lineas().size());
            assertIgual("134.00", ventas.carrito.ver(quien(ANDRES)).subtotal());
            ServicioCarrito.ResumenCarrito seleccion = ventas.carrito.ver(quien(ANDRES), Set.of("FED-ACE-020"));
            assertEquals(1, seleccion.lineas().size());
            assertIgual("50.00", seleccion.subtotal());
            long pedidos = numero("SELECT count(*) FROM pedido");
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "Indique el subtotal", () -> ventas.pedidos.confirmarInmediato(quien(ANDRES),
                    FabricaVentas.delivery("Calle Los Mangales 301", Set.of("FED-ACE-020"), null)));
            assertEquals(pedidos, numero("SELECT count(*) FROM pedido"));
            PedidoCompleto p = ventas.pedidos.confirmarInmediato(quien(ANDRES), FabricaVentas.recojo("Andrés", Set.of("FED-ACE-020"), "50.00"))
                    .pedido();
            assertIgual("50.00", p.pedido().getSubtotal());
        });
    }

    @Test
    @DisplayName("VINENC vincula el respaldo de una línea de encargo en espera; no se repite ni admite otra producción")
    void vinculoDeRespaldoPorLinea() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ventas.carrito.agregar(quien(DANIELA), "FED-ACE-100", new BigDecimal("6"));
            String codigo = ventas.pedidos.confirmarEncargo(quien(DANIELA), FabricaVentas.recojo("Daniela", Set.of("FED-ACE-100"), "540.00"))
                    .pedido().pedido().getCodigo();
            ventas.encargos.aceptar(quien(JORGE), codigo, LocalDate.now().plusDays(30), null, "Estimación del almacén");
            PedidoCompleto p = ventas.encargos.vincularRespaldo(quien(JORGE), codigo, "PRD-2026-011");
            assertEquals(11L, p.lineas().get(0).getProduccionId());
            assertEquals(EstadoPedido.ESPERANDO_ABASTECIMIENTO, p.pedido().getEstado());
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "ya está respaldada", () -> ventas.encargos.vincularRespaldo(quien(JORGE), codigo, "PRD-2026-011"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no tiene una línea del producto",
                    () -> ventas.encargos.vincularRespaldo(quien(JORGE), codigo, "PRD-2026-012"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "INMEDIATO", () -> ventas.encargos.vincularRespaldo(quien(JORGE), "PED-2026-0010", "PRD-2026-012"));
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER", () -> ventas.encargos.vincularRespaldo(quien(DANIELA), codigo, "PRD-2026-011"));
        });
    }

    @Test
    @DisplayName("ABAPED: el encargo en espera se abastece con stock vendible y pasa a CONFIRMADO")
    void abastecimientoManualDesdeVentas() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            crearLote(GestorTransacciones.conexionActual(), "ABV1", 2, LocalDate.now().plusYears(1), new BigDecimal("15"));
            ServicioEncargos.Abastecimiento a = ventas.encargos.abastecer(quien(CARLA), "PED-2026-0008");
            assertEquals(EstadoPedido.CONFIRMADO, a.pedido().pedido().getEstado());
            assertIgual("12", a.abastecidos().get(0).reservado());
            assertNegocio(Codigo.NO_ENCONTRADO, "No existe el pedido", () -> ventas.encargos.abastecer(quien(CARLA), "PED-2026-0999"));
        });
    }
}
