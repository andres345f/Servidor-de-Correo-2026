package com.feder.negocio.ventas;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Carrito del cliente (R033, R034): suma por producto, solo productos activos, nunca reserva stock. */
class ServicioCarritoTest {

    private ContextoNegocio ctx;
    private ServicioCarrito carrito;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        carrito = new FabricaVentas(ctx).carrito;
    }

    private static Solicitante quien(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    @Test
    @DisplayName("Agregar un producto que ya está suma la cantidad a la misma línea, sin reservar stock")
    void agregarSumaSinReservar() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal reservadoAntes = decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7");
            ServicioCarrito.ResumenCarrito r = carrito.agregar(quien(ANDRES), "FED-CHO-100", new BigDecimal("2"));
            assertEquals(1, r.lineas().size());
            assertIgual("5", r.lineas().get(0).item().item().getCantidad());
            assertIgual("140.00", r.subtotal());
            assertEquals(1, numero("SELECT count(*) FROM item_carrito WHERE cliente_id = 14"));
            assertIgual(reservadoAntes.toPlainString(), decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
        });
    }

    @Test
    @DisplayName("Cantidades: enteras en productos por UNIDAD, con decimales en KILOGRAMO; solo productos a la venta")
    void cantidadesYProductosValidos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "entero", () -> carrito.agregar(quien(ELMER), "FED-ACE-020", new BigDecimal("1.5")));
            carrito.agregar(quien(ELMER), "FED-MIE-GRA", new BigDecimal("1.250"));
            assertNegocio(Codigo.NO_ENCONTRADO, "no está a la venta", () -> carrito.agregar(quien(ELMER), "FED-MER-MAN", BigDecimal.ONE));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "mayor que 0", () -> carrito.agregar(quien(ELMER), "FED-ACE-020", BigDecimal.ZERO));
        });
    }

    @Test
    @DisplayName("La cantidad acumulada de una línea del carrito no supera NUMERIC(12,3): se rechaza antes de escribir")
    void cantidadAcumuladaDentroDeLaColumna() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            carrito.agregar(quien(ELMER), "FED-ACE-020", new BigDecimal("999999999"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "999999999.999", () -> carrito.agregar(quien(ELMER), "FED-ACE-020", BigDecimal.ONE));
            assertIgual("999999999", decimal("SELECT cantidad FROM item_carrito WHERE cliente_id = 9 AND producto_id = 1"));
        });
    }

    @Test
    @DisplayName("Solo una cuenta con CLIENTE vigente usa el carrito (R034)")
    void soloClientes() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.SIN_PERMISO, "CLIENTE", () -> carrito.agregar(quien(CARLA), "FED-ACE-020", BigDecimal.ONE));
            assertNegocio(Codigo.SIN_PERMISO, "CLIENTE", () -> carrito.ver(Solicitante.anonimo("visitante@correo.example")));
        });
    }

    @Test
    @DisplayName("Un producto desactivado se conserva en el carrito pero se muestra como no disponible")
    void productoDesactivadoNoDisponible() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ejecutar("UPDATE producto SET activo = false WHERE codigo = 'FED-JAB-090'");
            ServicioCarrito.ResumenCarrito r = carrito.ver(quien(DANIELA));
            assertEquals(1, r.lineas().size());
            assertFalse(r.lineas().get(0).confirmable());
            assertTrue(r.lineas().get(0).observacion().startsWith("No disponible"));
            assertIgual("0.00", r.subtotal());
        });
    }

    @Test
    @DisplayName("Si el stock vendible no alcanza se informa la cantidad vendible y, si admite encargo, la opción PEDENC")
    void avisoDeStockInsuficiente() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ServicioCarrito.ResumenCarrito r = carrito.agregar(quien(ELMER), "FED-ACE-100", new BigDecimal("3"));
            String obs = r.lineas().stream().filter(l -> l.item().producto().producto().getCodigo().equals("FED-ACE-100"))
                    .findFirst().orElseThrow().observacion();
            assertTrue(obs.contains("Stock vendible insuficiente (0)") && obs.contains("PEDENC"), obs);
        });
    }

    @Test
    @DisplayName("QUICAR reduce o quita el producto y VACCAR vacía el carrito")
    void quitarYVaciar() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            carrito.agregar(quien(ANDRES), "FED-ACE-020", new BigDecimal("4"));
            carrito.quitar(quien(ANDRES), "FED-ACE-020", new BigDecimal("1"));
            assertIgual("3", decimal("SELECT cantidad FROM item_carrito WHERE cliente_id = 14 AND producto_id = 1"));
            carrito.quitar(quien(ANDRES), "FED-ACE-020", null);
            assertEquals(0, numero("SELECT count(*) FROM item_carrito WHERE cliente_id = 14 AND producto_id = 1"));
            assertNegocio(Codigo.NO_ENCONTRADO, "no tiene el producto", () -> carrito.quitar(quien(ANDRES), "FED-ACE-020", null));
            assertEquals(1, carrito.vaciar(quien(ANDRES)));
            assertTrue(carrito.ver(quien(ANDRES)).lineas().isEmpty());
        });
    }
}
