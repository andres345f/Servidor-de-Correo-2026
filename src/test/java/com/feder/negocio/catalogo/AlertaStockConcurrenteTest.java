package com.feder.negocio.catalogo;

import com.feder.datos.ConexionBD;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.StockRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.negocio.inventario.ApoyoCu45.LoteFixture;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R070 con dos conexiones reales: dos transacciones retiran stock de lotes DISTINTOS del mismo producto, así que
 * ninguna espera el bloqueo de fila de la otra. La alerta de stock se evalúa serializada por producto (bloqueo
 * consultivo de transacción), de modo que cada cruce del mínimo avisa exactamente una vez a cada destinatario.
 *
 * El orden se fuerza sin esperas fijas: la primera transacción evalúa su alerta y no confirma hasta que la segunda
 * queda detenida esperando ese bloqueo (o, sin la serialización, hasta que termina). El retiro imita a un ajuste de
 * inventario (bloquea solo su lote y descuenta dentro de {@link ServicioStock#vigilando}) sin crear movimientos ni
 * bitácora, que son inmutables: así la prueba puede borrar todo lo que confirmó.
 */
class AlertaStockConcurrenteTest {

    /** Stock mínimo del producto de la prueba; cada lote empieza con 7 unidades (vendible 14). */
    private static final BigDecimal MINIMO = new BigDecimal("10");

    private ConexionBD conexionBD;
    private String sufijo;
    private long productoId;
    private LoteFixture loteA;
    private LoteFixture loteB;

    @BeforeEach
    void crearProductoConDosLotes() throws SQLException {
        conexionBD = BaseDatosPrueba.conexion();
        sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        try (Connection c = conexionBD.abrir()) {
            c.setAutoCommit(false);
            productoId = ApoyoCu45.insertarId(c, "INSERT INTO producto (categoria_id, codigo, nombre, presentacion, unidad_medida, "
                    + "precio_venta, stock_minimo) VALUES (1, ?, ?, 'Frasco', 'UNIDAD', 20.00, ?) RETURNING id",
                    "TST-" + sufijo, "Producto de alerta concurrente " + sufijo, MINIMO);
            loteA = ApoyoCu45.crearLote(c, sufijo + "A", productoId, LocalDate.of(2027, 6, 30), new BigDecimal("7"));
            loteB = ApoyoCu45.crearLote(c, sufijo + "B", productoId, LocalDate.of(2027, 6, 30), new BigDecimal("7"));
            c.commit();
        }
    }

    @AfterEach
    void borrarLoCreado() throws SQLException {
        try (Connection c = conexionBD.abrir()) {
            c.setAutoCommit(false);
            ApoyoCu45.ejecutar(c, "DELETE FROM notificacion WHERE producto_id = ?", productoId);
            for (LoteFixture l : new LoteFixture[] {loteA, loteB}) {
                if (l == null) {
                    continue;
                }
                ApoyoCu45.ejecutar(c, "DELETE FROM lote_feder WHERE id = ?", l.loteFederId());
                ApoyoCu45.ejecutar(c, "DELETE FROM detalle_compra WHERE id = ?", l.detalleId());
                ApoyoCu45.ejecutar(c, "DELETE FROM compra_feder WHERE id = ?", l.compraId());
                ApoyoCu45.ejecutar(c, "DELETE FROM lote_produccion WHERE id = ?", l.loteOrigenId());
                ApoyoCu45.ejecutar(c, "DELETE FROM produccion WHERE id = ?", l.produccionId());
            }
            ApoyoCu45.ejecutar(c, "DELETE FROM producto WHERE id = ?", productoId);
            c.commit();
        }
    }

    @Test
    @DisplayName("14 -> 11 -> 8, primero el lote A: ninguna ve el cruce por separado, avisa una sola vez quien evalúa después")
    void alertaQueSePerdia_loteAPrimero() throws Exception {
        retirarEnParalelo(true, "3");
        exigirUnaAlertaPorDestinatario("8", "8");
    }

    @Test
    @DisplayName("14 -> 11 -> 8, primero el lote B: el mismo resultado con el orden inverso")
    void alertaQueSePerdia_loteBPrimero() throws Exception {
        retirarEnParalelo(false, "3");
        exigirUnaAlertaPorDestinatario("8", "8");
    }

    @Test
    @DisplayName("14 -> 9 -> 4, primero el lote A: ambas verían el cruce, avisa una sola vez quien lo produce")
    void alertaQueSeDuplicaba_loteAPrimero() throws Exception {
        retirarEnParalelo(true, "5");
        exigirUnaAlertaPorDestinatario("9", "4");
    }

    @Test
    @DisplayName("14 -> 9 -> 4, primero el lote B: el mismo resultado con el orden inverso")
    void alertaQueSeDuplicaba_loteBPrimero() throws Exception {
        retirarEnParalelo(false, "5");
        exigirUnaAlertaPorDestinatario("9", "4");
    }

    /**
     * La primera transacción retira {@code cantidad} de su lote, evalúa la alerta y espera sin confirmar; la segunda
     * retira lo mismo del otro lote. La primera confirma cuando la segunda ya espera el bloqueo de la alerta.
     */
    private void retirarEnParalelo(boolean empiezaLoteA, String cantidad) throws Exception {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        ServicioStock stock = new ServicioStock(ctx, new StockRepositorio(), new ProductoRepositorio());
        long primero = (empiezaLoteA ? loteA : loteB).loteFederId();
        long segundo = (empiezaLoteA ? loteB : loteA).loteFederId();
        CountDownLatch primeroEvaluado = new CountDownLatch(1);
        CountDownLatch confirmarPrimero = new CountDownLatch(1);
        ExecutorService hilos = Executors.newFixedThreadPool(2);
        try {
            Future<?> transaccion1 = hilos.submit(() -> ctx.transacciones().enTransaccion(() -> {
                retirar(stock, primero, cantidad);
                primeroEvaluado.countDown();
                esperar(confirmarPrimero);
            }));
            assertTrue(primeroEvaluado.await(15, TimeUnit.SECONDS), "La primera transacción no llegó a evaluar la alerta");
            Future<?> transaccion2 = hilos.submit(() -> ctx.transacciones().enTransaccion(() -> retirar(stock, segundo, cantidad)));
            esperarBloqueoDeAlertaOFin(transaccion2);
            confirmarPrimero.countDown();
            transaccion1.get(30, TimeUnit.SECONDS);
            transaccion2.get(30, TimeUnit.SECONDS);
        } finally {
            confirmarPrimero.countDown();
            hilos.shutdownNow();
        }
    }

    /** Como un ajuste de inventario: bloquea solo su lote y descuenta el stock dentro de la vigilancia de la alerta. */
    private void retirar(ServicioStock stock, long loteId, String cantidad) {
        ApoyoCu45.valor("SELECT id FROM lote_feder WHERE id = ? FOR UPDATE", loteId);
        stock.vigilando(List.of(productoId), () -> ApoyoCu45.ejecutar(
                "UPDATE lote_feder SET cantidad_disponible = cantidad_disponible - ? WHERE id = ?", new BigDecimal(cantidad), loteId));
    }

    private void exigirUnaAlertaPorDestinatario(String stockDelAviso, String stockFinal) throws SQLException {
        long destinatarios = Long.parseLong(leer("SELECT count(DISTINCT u.id) FROM usuario u JOIN usuario_rol ur ON ur.usuario_id = u.id "
                + "JOIN rol r ON r.id = ur.rol_id WHERE u.activo AND ur.estado = 'VIGENTE' "
                + "AND r.codigo IN ('ADMINISTRADOR', 'PERSONAL_FEDER')"));
        assertTrue(destinatarios > 0, "La semilla debe tener destinatarios de alertas");
        String alertas = "SELECT count(*) FROM notificacion WHERE producto_id = ? AND tipo = 'ALERTA_STOCK'";
        assertEquals(destinatarios, Long.parseLong(leer(alertas, productoId)), "una alerta por destinatario");
        assertEquals(destinatarios, Long.parseLong(leer("SELECT count(DISTINCT usuario_id) FROM notificacion "
                + "WHERE producto_id = ? AND tipo = 'ALERTA_STOCK'", productoId)));
        assertEquals(destinatarios, Long.parseLong(leer(alertas + " AND mensaje LIKE ?", productoId,
                "%(" + stockDelAviso + ") quedó bajo el mínimo%")), "el aviso informa el stock del cruce");
        assertEquals(stockFinal, leer("SELECT (sum(cantidad_disponible) - sum(cantidad_reservada))::int FROM lote_feder WHERE id IN (?, ?)",
                loteA.loteFederId(), loteB.loteFederId()));
    }

    /**
     * Espera a que la segunda transacción quede detenida en el bloqueo consultivo de la alerta. Si termina antes (lo
     * que ocurre si la evaluación no se serializa), sigue igualmente: las aserciones mostrarán el aviso perdido o doble.
     */
    private void esperarBloqueoDeAlertaOFin(Future<?> transaccion) throws Exception {
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            if (transaccion.isDone() || Long.parseLong(leer("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND wait_event = 'advisory'")) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("La segunda transacción no llegó a esperar el bloqueo de la alerta ni terminó");
    }

    private static void esperar(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("La prueba no liberó la primera transacción");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String leer(String sql, Object... parametros) throws SQLException {
        try (Connection c = conexionBD.abrir(); PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                s.setObject(i + 1, parametros[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getString(1);
            }
        }
    }
}
