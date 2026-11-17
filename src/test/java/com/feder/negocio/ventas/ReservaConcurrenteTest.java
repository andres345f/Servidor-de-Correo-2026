package com.feder.negocio.ventas;

import com.feder.datos.ConexionBD;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.negocio.inventario.ApoyoCu45.LoteFixture;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R037 «Reserva atómica»: varios clientes confirman a la vez, cada uno en su propia transacción y
 * conexión, pedidos inmediatos por las últimas unidades de un producto. Las unidades nunca se venden
 * dos veces. Los datos se confirman de verdad (commit), por eso la prueba crea un producto y un lote
 * propios con nombres únicos y borra todo lo que creó al terminar.
 */
class ReservaConcurrenteTest {

    private static final List<String> CLIENTES = List.of(ApoyoCu45.ELMER, "silvia.anez@feder.example", ApoyoCu45.DANIELA,
            ApoyoCu45.MARCO, ApoyoCu45.PATRICIA, ApoyoCu45.ANDRES);

    private ConexionBD conexionBD;
    private String sufijo;
    private long productoId;
    private LoteFixture lote;

    @BeforeEach
    void crearProductoYLote() throws SQLException {
        conexionBD = BaseDatosPrueba.conexion();
        sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // Todo el escenario se crea en una sola transacción: si algo falla no queda nada a medias.
        try (Connection c = conexionBD.abrir()) {
            c.setAutoCommit(false);
            productoId = ApoyoCu45.insertarId(c, "INSERT INTO producto (categoria_id, codigo, nombre, presentacion, unidad_medida, "
                    + "precio_venta, stock_minimo) VALUES (1, ?, ?, 'Botella de 20 ml', 'UNIDAD', 25.00, 0) RETURNING id",
                    "TST-" + sufijo, "Producto de concurrencia " + sufijo);
            lote = ApoyoCu45.crearLote(c, sufijo, productoId, LocalDate.of(2027, 6, 30), new BigDecimal("3"));
            c.commit();
        }
    }

    @AfterEach
    void borrarLoCreado() throws SQLException {
        if (lote == null) {
            return;
        }
        try (Connection c = conexionBD.abrir()) {
            c.setAutoCommit(false);
            List<Long> pedidos = new ArrayList<>();
            try (var s = c.prepareStatement("SELECT DISTINCT pedido_id FROM detalle_pedido WHERE producto_id = ?")) {
                s.setLong(1, productoId);
                try (var r = s.executeQuery()) {
                    while (r.next()) {
                        pedidos.add(r.getLong(1));
                    }
                }
            }
            ApoyoCu45.ejecutar(c, "DELETE FROM reserva_stock WHERE lote_feder_id = ?", lote.loteFederId());
            ApoyoCu45.ejecutar(c, "DELETE FROM notificacion WHERE producto_id = ?", productoId);
            for (Long pedido : pedidos) {
                ApoyoCu45.ejecutar(c, "DELETE FROM notificacion WHERE pedido_id = ?", pedido);
                ApoyoCu45.ejecutar(c, "DELETE FROM historial_pedido WHERE pedido_id = ?", pedido);
                ApoyoCu45.ejecutar(c, "DELETE FROM entrega WHERE pedido_id = ?", pedido);
                ApoyoCu45.ejecutar(c, "DELETE FROM detalle_pedido WHERE pedido_id = ?", pedido);
                ApoyoCu45.ejecutar(c, "DELETE FROM pedido WHERE id = ?", pedido);
            }
            ApoyoCu45.ejecutar(c, "DELETE FROM item_carrito WHERE producto_id = ?", productoId);
            ApoyoCu45.ejecutar(c, "DELETE FROM lote_feder WHERE id = ?", lote.loteFederId());
            ApoyoCu45.ejecutar(c, "DELETE FROM detalle_compra WHERE id = ?", lote.detalleId());
            ApoyoCu45.ejecutar(c, "DELETE FROM compra_feder WHERE id = ?", lote.compraId());
            ApoyoCu45.ejecutar(c, "DELETE FROM lote_produccion WHERE id = ?", lote.loteOrigenId());
            ApoyoCu45.ejecutar(c, "DELETE FROM produccion WHERE id = ?", lote.produccionId());
            ApoyoCu45.ejecutar(c, "DELETE FROM producto WHERE id = ?", productoId);
            c.commit();
        }
    }

    @Test
    @DisplayName("Dos clientes compiten por las 3 últimas unidades pidiendo 2 cada uno: solo uno obtiene la reserva")
    void dosClientesPorLasUltimasUnidades() throws Exception {
        List<Object> resultados = competir(List.of(ApoyoCu45.ELMER, ApoyoCu45.ANDRES), "2");
        assertEquals(1, resultados.stream().filter(r -> r instanceof ResultadoPedido).count(), resultados.toString());
        assertTrue(resultados.stream().anyMatch(r -> r instanceof NegocioException
                && ((NegocioException) r).codigo() == NegocioException.Codigo.STOCK_INSUFICIENTE), resultados.toString());
        verificarSaldo("2");
    }

    @Test
    @DisplayName("Seis clientes piden 1 unidad a la vez con 3 disponibles: exactamente 3 pedidos y ninguna sobreventa")
    void seisClientesTresUnidades() throws Exception {
        List<Object> resultados = competir(CLIENTES, "1");
        assertEquals(3, resultados.stream().filter(r -> r instanceof ResultadoPedido).count(), resultados.toString());
        assertEquals(3, resultados.stream().filter(r -> r instanceof NegocioException).count(), resultados.toString());
        verificarSaldo("3");
    }

    /** Cada cliente agrega el producto a su carrito (confirmado) y después todos confirman a la vez. */
    private List<Object> competir(List<String> clientes, String cantidad) throws Exception {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        FabricaVentas ventas = new FabricaVentas(ctx);
        for (String cliente : clientes) {
            ctx.transacciones().enTransaccion(() -> {
                ventas.carrito.agregar(BaseDatosPrueba.solicitante(cliente), "TST-" + sufijo, new BigDecimal(cantidad));
            });
        }
        ExecutorService hilos = Executors.newFixedThreadPool(clientes.size());
        CyclicBarrier salida = new CyclicBarrier(clientes.size());
        CountDownLatch fin = new CountDownLatch(clientes.size());
        List<Future<Object>> futuros = new ArrayList<>();
        try {
            for (String cliente : clientes) {
                futuros.add(hilos.submit(() -> {
                    try {
                        salida.await(10, TimeUnit.SECONDS);
                        return (Object) ctx.transacciones().enTransaccion(() -> ventas.pedidos.confirmarInmediato(
                                BaseDatosPrueba.solicitante(cliente), FabricaVentas.recojo("Cliente", Set.of("TST-" + sufijo),
                                        new BigDecimal(cantidad).multiply(new BigDecimal("25.00")).toPlainString())));
                    } catch (NegocioException e) {
                        return e;
                    } finally {
                        fin.countDown();
                    }
                }));
            }
            assertTrue(fin.await(60, TimeUnit.SECONDS), "Las confirmaciones simultáneas no terminaron a tiempo");
            List<Object> resultados = new ArrayList<>();
            for (Future<Object> f : futuros) {
                resultados.add(f.get());
            }
            return resultados;
        } finally {
            hilos.shutdownNow();
        }
    }

    private void verificarSaldo(String reservadoEsperado) {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        ctx.transacciones().enTransaccion(() -> {
            ApoyoCu45.assertIgual(reservadoEsperado, ApoyoCu45.decimal(
                    "SELECT cantidad_reservada FROM lote_feder WHERE id = ?", lote.loteFederId()));
            ApoyoCu45.assertIgual(reservadoEsperado, ApoyoCu45.decimal(
                    "SELECT COALESCE(sum(cantidad), 0) FROM reserva_stock WHERE lote_feder_id = ? AND estado = 'ACTIVA'", lote.loteFederId()));
            ApoyoCu45.assertIgual("3", ApoyoCu45.decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = ?", lote.loteFederId()));
        });
    }
}
