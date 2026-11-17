package com.feder.negocio.inventario;

import com.feder.datos.ConexionBD;
import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.LoteOrigenRepositorio;
import com.feder.datos.compras.OperacionCompraRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R006 y R009 con dos conexiones reales: un pago al productor confirmado mientras una corrección de
 * recepción (ANUREC reclasificada como rechazo) ya cambió la línea. La corrección debe revertirse al
 * bloquear la compra, para que lo pagado siga siendo lo aceptado. Los datos se confirman de verdad, por
 * eso la prueba crea su propia compra con nombres únicos y la borra al terminar.
 */
class PagoConcurrenteTest {

    private ConexionBD conexionBD;
    private String sufijo;
    private long productoId;
    private ApoyoCu45.LoteFixture lote;

    @BeforeEach
    void crearCompraRecibida() throws SQLException {
        conexionBD = BaseDatosPrueba.conexion();
        sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        try (Connection c = conexionBD.abrir()) {
            c.setAutoCommit(false);
            productoId = ApoyoCu45.insertarId(c, "INSERT INTO producto (categoria_id, codigo, nombre, presentacion, unidad_medida, "
                    + "precio_venta, stock_minimo) VALUES (1, ?, ?, 'Frasco', 'UNIDAD', 20.00, 0) RETURNING id",
                    "TST-" + sufijo, "Producto de pago concurrente " + sufijo);
            lote = ApoyoCu45.crearLote(c, sufijo, productoId, LocalDate.of(2027, 6, 30), new BigDecimal("10"));
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
            ApoyoCu45.ejecutar(c, "DELETE FROM notificacion WHERE producto_id = ?", productoId);
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
    @DisplayName("Un pago confirmado durante una ANUREC hace que la corrección se revierta: las cantidades pagadas no cambian")
    void pagoDuranteLaCorreccion() throws Exception {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        ServicioRecepcion recepcion = new ServicioRecepcion(ctx, new CompraRepositorio(), new DetalleCompraRepositorio(),
                new LoteOrigenRepositorio(), new OperacionCompraRepositorio(), new MovimientoRepositorio(),
                new LoteFederRepositorio(), ServicioInventario.crear(ctx));
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try (Connection pago = conexionBD.abrir()) {
            pago.setAutoCommit(false);
            // Como PAGCMP: bloquea la fila de la compra y registra el pago, todavía sin confirmar.
            ApoyoCu45.ejecutar(pago, "UPDATE compra_feder SET fecha_pago_productor = now() WHERE id = ?", lote.compraId());
            Future<Object> correccion = hilo.submit(() -> {
                try {
                    return (Object) ctx.transacciones().enTransaccion(() -> recepcion.anularRecepcion(
                            BaseDatosPrueba.solicitante(ApoyoCu45.CARLA), "CMP-T-" + sufijo, "LP-T-" + sufijo,
                            new BigDecimal("2"), true, "Dos frascos no debían aceptarse", "ANU-T-" + sufijo));
                } catch (NegocioException e) {
                    return e;
                }
            });
            esperarTransaccionBloqueada();
            pago.commit();
            Object resultado = correccion.get(30, TimeUnit.SECONDS);
            NegocioException e = assertInstanceOf(NegocioException.class, resultado, String.valueOf(resultado));
            assertEquals(NegocioException.Codigo.ESTADO_INVALIDO, e.codigo());
            assertTrue(e.getMessage().contains("fue pagada al productor mientras"), e.getMessage());
        } finally {
            hilo.shutdownNow();
        }
        assertEquals("10.000|0.000|10.000", leer("SELECT dc.cantidad_recibida || '|' || dc.cantidad_rechazada || '|' "
                + "|| lf.cantidad_disponible FROM detalle_compra dc JOIN lote_feder lf ON lf.detalle_compra_id = dc.id WHERE dc.id = ?",
                lote.detalleId()));
        assertEquals("0", leer("SELECT count(*) FROM movimiento_inventario WHERE lote_feder_id = ?", lote.loteFederId()));
    }

    /** Espera a que la corrección quede detenida en el bloqueo de la compra que tiene la otra conexión. */
    private void esperarTransaccionBloqueada() throws Exception {
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            if (Long.parseLong(leer("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock'")) > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("La corrección no llegó a esperar el bloqueo de la compra");
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
