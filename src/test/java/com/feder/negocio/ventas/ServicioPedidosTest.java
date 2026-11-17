package com.feder.negocio.ventas;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.ModalidadEntrega;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoPedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pedidos inmediatos, encargos y cancelación (R018, R027, R028, R031, R034, R035, R037, R052–R058, R060,
 * R062, R088) contra la base de pruebas. Cada prueba se revierte al terminar.
 */
class ServicioPedidosTest {

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

    @Test
    @DisplayName("PEDINM crea en una transacción pedido, línea, reserva ACTIVA con plazo, una entrega y el primer historial")
    void pedidoInmediatoCompleto() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoPedido r = ventas.pedidos.confirmarInmediato(quien(ANDRES), FabricaVentas.delivery("Calle Los Mangales 301", Set.of(), "84.00"));
            Pedido p = r.pedido().pedido();
            assertEquals(TipoPedido.INMEDIATO, p.getTipo());
            assertEquals(EstadoPedido.PENDIENTE, p.getEstado());
            assertIgual("84.00", p.getSubtotal());
            assertIgual("15.00", p.getCostoEnvio());
            assertIgual("99.00", p.getTotal());
            DetallePedido linea = r.pedido().lineas().get(0);
            assertIgual("28.00", linea.getPrecioUnitario());
            assertEquals("Chocolate artesanal 70 % cacao - Tableta de 100 g (UNIDAD)", linea.getDescripcionVenta());
            long id = p.getId();
            assertEquals(1, numero("SELECT count(*) FROM entrega WHERE pedido_id = ? AND modalidad = 'DELIVERY' AND estado = 'PENDIENTE'", id));
            assertEquals(1, numero("SELECT count(*) FROM historial_pedido WHERE pedido_id = ? AND estado_anterior IS NULL", id));
            assertIgual("3", decimal("SELECT sum(rs.cantidad) FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + "WHERE dp.pedido_id = ? AND rs.estado = 'ACTIVA' AND rs.lote_feder_id = 7", id));
            OffsetDateTime vence = (OffsetDateTime) valorFecha("SELECT min(rs.fecha_expiracion) FROM reserva_stock rs "
                    + "JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id WHERE dp.pedido_id = ?", id);
            assertTrue(vence.isAfter(OffsetDateTime.now().plus(Duration.ofHours(11))), "plazo de pago: " + vence);
            assertIgual("9", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
            assertEquals(0, numero("SELECT count(*) FROM item_carrito WHERE cliente_id = 14"));
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE pedido_id = ?", id));
        });
    }

    private static Object valorFecha(String sql, Object... parametros) {
        try (var s = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                s.setObject(i + 1, parametros[i]);
            }
            try (var r = s.executeQuery()) {
                r.next();
                return r.getObject(1, OffsetDateTime.class);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("R028: RECOJO no cobra envío; DELIVERY sin dirección se rechaza")
    void datosSegunModalidad() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "dirección", () -> ventas.pedidos.confirmarInmediato(quien(ANDRES),
                    FabricaVentas.delivery("  ", Set.of(), null)));
            PedidoCompleto p = ventas.pedidos.confirmarInmediato(quien(ANDRES), FabricaVentas.recojo("Andrés Molina", Set.of(), "84.00")).pedido();
            assertIgual("0.00", p.pedido().getCostoEnvio());
            assertEquals(ModalidadEntrega.RECOJO, p.entrega().getModalidad());
        });
    }

    @Test
    @DisplayName("R054: si el subtotal que vio el cliente ya no coincide con los precios vigentes no se crea nada")
    void precioCambiado() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long pedidos = numero("SELECT count(*) FROM pedido");
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "Los precios cambiaron", () -> ventas.pedidos.confirmarInmediato(quien(ANDRES),
                    FabricaVentas.delivery("Calle 1", Set.of(), "80.00")));
            assertEquals(pedidos, numero("SELECT count(*) FROM pedido"));
            assertEquals(1, numero("SELECT count(*) FROM item_carrito WHERE cliente_id = 14"));
        });
    }

    @Test
    @DisplayName("R060: sin stock vendible suficiente se informa la cantidad vendible y no se crea ningún pedido ni reserva")
    void inmediatoSinStockNoCreaNada() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ventas.carrito.agregar(quien(DANIELA), "FED-JAB-090", new BigDecimal("298"));
            long pedidos = numero("SELECT count(*) FROM pedido");
            long reservas = numero("SELECT count(*) FROM reserva_stock");
            assertNegocio(Codigo.STOCK_INSUFICIENTE, "el stock vendible es 234", () -> ventas.pedidos.confirmarInmediato(quien(DANIELA),
                    FabricaVentas.recojo("Daniela", Set.of(), "5400.00")));
            assertEquals(pedidos, numero("SELECT count(*) FROM pedido"));
            assertEquals(reservas, numero("SELECT count(*) FROM reserva_stock"));
        });
    }

    @Test
    @DisplayName("R034: un producto que dejó de venderse no se confirma; tampoco un código que no está en el carrito")
    void revalidacionDelCarrito() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "FED-ACE-020", () -> ventas.pedidos.confirmarInmediato(quien(ANDRES),
                    FabricaVentas.recojo("Andrés", Set.of("FED-ACE-020"), "50.00")));
            ejecutar("UPDATE producto SET activo = false WHERE id = 9");
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "ya no están a la venta", () -> ventas.pedidos.confirmarInmediato(quien(ANDRES),
                    FabricaVentas.recojo("Andrés", Set.of(), "84.00")));
            assertNegocio(Codigo.SIN_PERMISO, "CLIENTE", () -> ventas.pedidos.confirmarInmediato(quien(CARLA),
                    FabricaVentas.recojo("Carla", Set.of(), "84.00")));
        });
    }

    @Test
    @DisplayName("R054: un reenvío de la misma confirmación devuelve el pedido ya creado y nunca crea un segundo pedido")
    void reenvioDevuelveElPedidoCreado() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long pedidos = numero("SELECT count(*) FROM pedido");
            ResultadoPedido primero = ventas.pedidos.confirmarInmediato(quien(ANDRES), FabricaVentas.recojo("Andrés", Set.of(), "84.00"));
            assertFalse(primero.yaExistia());
            ResultadoPedido reenvio = ventas.pedidos.confirmarInmediato(quien(ANDRES), FabricaVentas.recojo("Andrés", Set.of(), "84.00"));
            assertTrue(reenvio.yaExistia());
            assertEquals(primero.pedido().pedido().getCodigo(), reenvio.pedido().pedido().getCodigo());
            assertEquals(pedidos + 1, numero("SELECT count(*) FROM pedido"));
            // Otra solicitud distinta (otro receptor) no se confunde con el reenvío: el carrito está vacío.
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no tiene productos", () -> ventas.pedidos.confirmarInmediato(quien(ANDRES),
                    FabricaVentas.recojo("Otra persona", Set.of(), "84.00")));
        });
    }

    @Test
    @DisplayName("R054: reenviar una confirmación por productos devuelve el pedido con esos productos, no el último del cliente")
    void reenvioPorProductosDevuelveSuPropioPedido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            // Dos selecciones distintas con el mismo subtotal (18 x 25.00 = 25 x 18.00 = 450.00) y la misma entrega.
            ventas.carrito.agregar(quien(ELMER), "FED-ACE-020", new BigDecimal("18"));
            ventas.carrito.agregar(quien(ELMER), "FED-JAB-090", new BigDecimal("25"));
            String aceite = ventas.pedidos.confirmarInmediato(quien(ELMER),
                    FabricaVentas.recojo("Elmer", Set.of("FED-ACE-020"), "450.00")).pedido().pedido().getCodigo();
            String jabon = ventas.pedidos.confirmarInmediato(quien(ELMER),
                    FabricaVentas.recojo("Elmer", Set.of("FED-JAB-090"), "450.00")).pedido().pedido().getCodigo();
            long pedidos = numero("SELECT count(*) FROM pedido");

            ResultadoPedido reenvioAceite = ventas.pedidos.confirmarInmediato(quien(ELMER),
                    FabricaVentas.recojo("Elmer", Set.of("FED-ACE-020"), "450.00"));
            assertTrue(reenvioAceite.yaExistia());
            assertEquals(aceite, reenvioAceite.pedido().pedido().getCodigo());
            ResultadoPedido reenvioJabon = ventas.pedidos.confirmarInmediato(quien(ELMER),
                    FabricaVentas.recojo("Elmer", Set.of("FED-JAB-090"), "450.00"));
            assertTrue(reenvioJabon.yaExistia());
            assertEquals(jabon, reenvioJabon.pedido().pedido().getCodigo());
            assertEquals(pedidos, numero("SELECT count(*) FROM pedido"));

            // Una selección que no coincide con ningún pedido reciente no se toma por un reenvío.
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no tiene estos productos: FED-MIE-500", () -> ventas.pedidos.confirmarInmediato(
                    quien(ELMER), FabricaVentas.recojo("Elmer", Set.of("FED-MIE-500"), "450.00")));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no tiene estos productos: FED-ACE-020", () -> ventas.pedidos.confirmarInmediato(
                    quien(ELMER), FabricaVentas.recojo("Elmer", Set.of("FED-ACE-020", "FED-JAB-090"), "450.00")));
        });
    }

    @Test
    @DisplayName("R054: el reenvío distingue la referencia de entrega y no adivina entre pedidos indistinguibles")
    void reenvioDistingueReferenciaYNoAdivina() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            // Mismo producto, cantidad, subtotal, dirección, receptor y teléfono; solo cambia la referencia.
            ventas.carrito.agregar(quien(ELMER), "FED-ACE-020", new BigDecimal("2"));
            String azul = ventas.pedidos.confirmarInmediato(quien(ELMER), conReferencia("Portón azul", "FED-ACE-020", "50.00"))
                    .pedido().pedido().getCodigo();
            ventas.carrito.agregar(quien(ELMER), "FED-ACE-020", new BigDecimal("2"));
            String rojo = ventas.pedidos.confirmarInmediato(quien(ELMER), conReferencia("Portón rojo", "FED-ACE-020", "50.00"))
                    .pedido().pedido().getCodigo();
            long pedidos = numero("SELECT count(*) FROM pedido");

            ResultadoPedido reenvioAzul = ventas.pedidos.confirmarInmediato(quien(ELMER), conReferencia("Portón azul", "FED-ACE-020", "50.00"));
            assertTrue(reenvioAzul.yaExistia());
            assertEquals(azul, reenvioAzul.pedido().pedido().getCodigo(), "el reenvío devuelve su propio pedido, no el más reciente");
            ResultadoPedido reenvioRojo = ventas.pedidos.confirmarInmediato(quien(ELMER), conReferencia("Portón rojo", "FED-ACE-020", "50.00"));
            assertEquals(rojo, reenvioRojo.pedido().pedido().getCodigo());
            assertEquals(pedidos, numero("SELECT count(*) FROM pedido"), "ningún reenvío crea otro pedido");

            // Dos pedidos idénticos en todos los datos: el reenvío no se asocia a ninguno de los dos.
            ventas.carrito.agregar(quien(ELMER), "FED-JAB-090", new BigDecimal("1"));
            ventas.pedidos.confirmarInmediato(quien(ELMER), conReferencia("Casa verde", "FED-JAB-090", "18.00"));
            ventas.carrito.agregar(quien(ELMER), "FED-JAB-090", new BigDecimal("1"));
            ventas.pedidos.confirmarInmediato(quien(ELMER), conReferencia("Casa verde", "FED-JAB-090", "18.00"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no tiene estos productos: FED-JAB-090", () -> ventas.pedidos.confirmarInmediato(
                    quien(ELMER), conReferencia("Casa verde", "FED-JAB-090", "18.00")));
        });
    }

    private static SolicitudPedido conReferencia(String referencia, String producto, String subtotal) {
        return new SolicitudPedido(ModalidadEntrega.DELIVERY, "Barrio Los Tajibos, calle 4 N.º 120", referencia, "Elmer Rojas",
                "70000109", Set.of(producto), new BigDecimal(subtotal), null);
    }

    @Test
    @DisplayName("R037: la reserva sigue FEFO (vence antes, primero) y reparte una línea entre lotes si hace falta")
    void reservaFefoEntreLotes() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ApoyoCu45.LoteFixture temprano = crearLote(GestorTransacciones.conexionActual(), "FEFO1", 1,
                    LocalDate.of(2026, 12, 31), new BigDecimal("4"));
            ventas.carrito.agregar(quien(ELMER), "FED-ACE-020", new BigDecimal("6"));
            PedidoCompleto p = ventas.pedidos.confirmarInmediato(quien(ELMER), FabricaVentas.recojo("Elmer", Set.of("FED-ACE-020"), "150.00")).pedido();
            String reservas = "SELECT COALESCE(sum(rs.cantidad), 0) FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + "WHERE dp.pedido_id = ? AND rs.lote_feder_id = ?";
            assertIgual("4", decimal(reservas, p.pedido().getId(), temprano.loteFederId()));
            assertIgual("2", decimal(reservas, p.pedido().getId(), 1L));
            assertEquals("DISPONIBLE", texto("SELECT estado FROM lote_feder WHERE id = ?", temprano.loteFederId()));
            assertEquals(1, numero("SELECT count(*) FROM item_carrito WHERE cliente_id = 9"));
        });
    }

    @Test
    @DisplayName("R035: un lote cuyo origen venció no se reserva aunque tenga saldo")
    void noReservaLotesVencidos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE lote_produccion SET fecha_vencimiento = DATE '2026-11-01' WHERE id = 9");
            ventas.carrito.agregar(quien(ELMER), "FED-MER-GUA", BigDecimal.ONE);
            assertNegocio(Codigo.STOCK_INSUFICIENTE, "stock vendible es 0", () -> ventas.pedidos.confirmarInmediato(quien(ELMER),
                    FabricaVentas.recojo("Elmer", Set.of("FED-MER-GUA"), "24.00")));
        });
    }

    @Test
    @DisplayName("R056: PEDENC crea un ENCARGO PENDIENTE sin reservas, solo para productos que lo admiten y sin stock suficiente")
    void encargo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ventas.carrito.agregar(quien(DANIELA), "FED-ACE-100", new BigDecimal("6"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no acepta encargos", () -> ventas.pedidos.confirmarEncargo(quien(DANIELA),
                    FabricaVentas.recojo("Daniela", Set.of("FED-JAB-090"), "36.00")));
            PedidoCompleto p = ventas.pedidos.confirmarEncargo(quien(DANIELA), FabricaVentas.recojo("Daniela", Set.of("FED-ACE-100"), "540.00")).pedido();
            assertEquals(TipoPedido.ENCARGO, p.pedido().getTipo());
            assertEquals(EstadoPedido.PENDIENTE, p.pedido().getEstado());
            assertIgual("540.00", p.pedido().getTotal());
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + "WHERE dp.pedido_id = ?", p.pedido().getId()));
            assertNotNull(p.entrega());
            ventas.carrito.agregar(quien(DANIELA), "FED-ACE-020", new BigDecimal("2"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "Hay stock vendible suficiente", () -> ventas.pedidos.confirmarEncargo(quien(DANIELA),
                    FabricaVentas.recojo("Daniela", Set.of("FED-ACE-020"), "50.00")));
        });
    }

    @Test
    @DisplayName("ACEENC acepta el encargo con fecha estimada y producción de respaldo dentro de su capacidad (R062)")
    void aceptacionDeEncargo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ventas.carrito.agregar(quien(DANIELA), "FED-ACE-100", new BigDecimal("6"));
            String codigo = ventas.pedidos.confirmarEncargo(quien(DANIELA), FabricaVentas.recojo("Daniela", Set.of("FED-ACE-100"), "540.00"))
                    .pedido().pedido().getCodigo();
            LocalDate fecha = LocalDate.now().plusDays(40);
            PedidoCompleto p = ventas.encargos.aceptar(quien(JORGE), codigo, fecha, "PRD-2026-011", "Producción estimada más preparación");
            assertEquals(EstadoPedido.ESPERANDO_ABASTECIMIENTO, p.pedido().getEstado());
            assertEquals(fecha, p.pedido().getFechaEstimadaDisponibilidad());
            assertEquals(11L, p.lineas().get(0).getProduccionId());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PEDIDO' AND entidad_id = ?", p.pedido().getId()));
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE pedido_id = ? AND tipo = 'FECHA_ESTIMADA_ACTUALIZADA'",
                    p.pedido().getId()));
            assertNegocio(Codigo.ESTADO_INVALIDO, "PENDIENTE", () -> ventas.encargos.aceptar(quien(JORGE), codigo, fecha, null, "otra"));
            ventas.encargos.actualizarFecha(quien(JORGE), codigo, fecha.plusDays(5), "Retraso de la producción",
                    "Aviso de la productora");
            assertEquals("Aviso de la productora", texto("SELECT detalle ->> 'fundamento' FROM bitacora WHERE entidad = 'PEDIDO' "
                    + "AND entidad_id = (SELECT id FROM pedido WHERE codigo = ?) ORDER BY id DESC LIMIT 1", codigo));
            assertEquals(fecha.plusDays(5), LocalDate.parse(texto("SELECT fecha_estimada_disponibilidad::text FROM pedido WHERE codigo = ?", codigo)));
        });
    }

    @Test
    @DisplayName("R062: no se respalda un encargo que supera la capacidad libre de la producción; un INMEDIATO no tiene fecha estimada")
    void respaldoSinCapacidad() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ventas.carrito.agregar(quien(DANIELA), "FED-ACE-100", new BigDecimal("70"));
            String codigo = ventas.pedidos.confirmarEncargo(quien(DANIELA), FabricaVentas.recojo("Daniela", Set.of("FED-ACE-100"), "6300.00"))
                    .pedido().pedido().getCodigo();
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no alcanza", () -> ventas.encargos.aceptar(quien(JORGE), codigo,
                    LocalDate.now().plusDays(30), "PRD-2026-011", "x"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "INMEDIATO", () -> ventas.encargos.aceptar(quien(JORGE), "PED-2026-0011",
                    LocalDate.now().plusDays(30), null, "x"));
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER", () -> ventas.encargos.aceptar(quien(DANIELA), codigo,
                    LocalDate.now().plusDays(30), null, "x"));
        });
    }

    @Test
    @DisplayName("CANPED del cliente propietario: libera las reservas, cancela la entrega y deja historial con comentario")
    void cancelacionPorElCliente() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoCancelacion c = ventas.pedidos.cancelar(quien(ANDRES), "PED-2026-0011", "Ya no necesito los productos");
            assertEquals(EstadoPedido.CANCELADO, c.pedido().pedido().getEstado());
            assertEquals(2, c.reservasLiberadas());
            assertTrue(c.reintegros().isEmpty());
            assertEquals("CANCELADA", texto("SELECT estado FROM entrega WHERE pedido_id = 11"));
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND estado = 'ACTIVA'"));
            assertEquals("Ya no necesito los productos", texto("SELECT comentario FROM historial_pedido WHERE pedido_id = 11 "
                    + "AND estado_nuevo = 'CANCELADO'"));
            assertEquals(14L, numero("SELECT usuario_id FROM historial_pedido WHERE pedido_id = 11 AND estado_nuevo = 'CANCELADO'"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'ENTREGA' AND entidad_id = 11 "
                    + "AND detalle ->> 'estado_nuevo' = 'CANCELADA'"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya no se puede cancelar", () -> ventas.pedidos.cancelar(quien(ANDRES), "PED-2026-0011", "otra vez"));
        });
    }

    @Test
    @DisplayName("Cancelar un pedido CONFIRMADO y pagado solicita el reintegro del pago (REEMBOLSO PENDIENTE)")
    void cancelacionConReintegro() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoCancelacion c = ventas.pedidos.cancelar(quien(PATRICIA), "PED-2026-0010", "Cambié de opinión");
            assertEquals(1, c.reintegros().size());
            assertIgual("218.00", c.reintegros().get(0));
            assertEquals("PENDIENTE", texto("SELECT estado FROM reembolso WHERE pago_id = 10"));
            assertIgual("0", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
        });
    }

    @Test
    @DisplayName("R031 y R088: nadie cancela pedidos ajenos; un pedido despachado o entregado no lo cancela el cliente")
    void reglasDeCancelacion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.NO_ENCONTRADO, "entre sus pedidos", () -> ventas.pedidos.cancelar(quien(ELMER), "PED-2026-0011", "x"));
            assertNegocio(Codigo.NO_ENCONTRADO, "entre sus pedidos", () -> ventas.pedidos.cancelar(quien(ELMER), "PED-2026-0999", "x"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "solo el personal", () -> ventas.pedidos.cancelar(quien(MARCO), "PED-2026-0009", "x"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "RETORNADA o NO_RECUPERADA", () -> ventas.pedidos.cancelar(quien(JORGE), "PED-2026-0009", "x"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "devolución", () -> ventas.pedidos.cancelar(quien(DANIELA), "PED-2026-0007", "x"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "motivo", () -> ventas.pedidos.cancelar(quien(ANDRES), "PED-2026-0011", " "));
        });
    }

    @Test
    @DisplayName("El personal cancela un pedido despachado cuya entrega retornó; la entrega conserva su estado")
    void cancelacionTrasDespacho() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE entrega SET estado = 'RETORNADA' WHERE pedido_id = 9");
            ResultadoCancelacion c = ventas.pedidos.cancelar(quien(JORGE), "PED-2026-0009", "El cliente no recibió el envío");
            assertEquals(EstadoPedido.CANCELADO, c.pedido().pedido().getEstado());
            assertEquals("RETORNADA", texto("SELECT estado FROM entrega WHERE pedido_id = 9"));
            assertEquals(1, c.reintegros().size());
        });
    }

    @Test
    @DisplayName("LISPED y VERPED: el cliente ve solo sus pedidos; el personal, todos")
    void consultasConPropiedad() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertTrue(ventas.pedidos.listar(quien(DANIELA), null).stream().allMatch(r -> r.pedido().getUsuarioId() == 11L));
            assertEquals(3, ventas.pedidos.listar(quien(DANIELA), null).size());
            assertEquals(11, ventas.pedidos.listar(quien(CARLA), null).size());
            assertEquals(1, ventas.pedidos.listar(quien(CARLA), EstadoPedido.ESPERANDO_ABASTECIMIENTO).size());
            ServicioPedidos.PedidoDetallado d = ventas.pedidos.ver(quien(DANIELA), "PED-2026-0008");
            assertEquals(2, d.historial().size());
            // R088: un pedido ajeno se informa igual que uno inexistente.
            String ajeno = assertNegocio(Codigo.NO_ENCONTRADO, "entre sus pedidos", () -> ventas.pedidos.ver(quien(DANIELA), "PED-2026-0011")).getMessage();
            String inexistente = assertNegocio(Codigo.NO_ENCONTRADO, "entre sus pedidos", () -> ventas.pedidos.ver(quien(DANIELA), "PED-2026-0999")).getMessage();
            assertEquals(ajeno.replace("0011", "0999"), inexistente);
            assertEquals("PED-2026-0011", ventas.pedidos.ver(quien(JORGE), "PED-2026-0011").pedido().pedido().getCodigo());
        });
    }
}
