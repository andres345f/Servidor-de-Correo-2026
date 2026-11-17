package com.feder.negocio.pagos;

import com.feder.datos.ConexionBD;
import com.feder.datos.pagos.FacturaRepositorio;
import com.feder.datos.pagos.PagoRepositorio;
import com.feder.datos.pagos.ReembolsoRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.ventas.CambioEstadoPedido;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * R048 con dos conexiones reales: mientras otra transacción tiene bloqueado el pedido y confirma el pago con su
 * referencia, una verificación simultánea espera el bloqueo, relee el pago fuera de PENDIENTE y no cambia nada.
 * Los datos se confirman de verdad: la prueba crea su propio pedido con nombres únicos y lo borra al terminar.
 */
class ConfirmacionPagoConcurrenteTest {

    private ConexionBD conexionBD;
    private String sufijo;
    private long pedidoId;
    private long pagoId;

    @BeforeEach
    void crearPedidoConPagoPendiente() throws SQLException {
        conexionBD = BaseDatosPrueba.conexion();
        sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        try (Connection c = conexionBD.abrir()) {
            c.setAutoCommit(false);
            pedidoId = ApoyoCu45.insertarId(c, "INSERT INTO pedido (usuario_id, codigo, tipo, estado, subtotal, costo_envio, total, "
                    + "clave_idempotencia) VALUES (14, ?, 'INMEDIATO', 'PENDIENTE', 100.00, 0, 100.00, ?) RETURNING id",
                    "PED-T-" + sufijo, "T-" + sufijo);
            pagoId = ApoyoCu45.insertarId(c, "INSERT INTO pago (pedido_id, metodo, monto, estado, clave_idempotencia) "
                    + "VALUES (?, 'QR', 100.00, 'PENDIENTE', ?) RETURNING id", pedidoId, "PAG-T-" + sufijo);
            c.commit();
        }
    }

    /**
     * Borra en orden inverso de las claves foráneas todo lo que una confirmación (correcta o, ante una regresión,
     * duplicada) pudo dejar del pedido de la prueba. La bitácora solo admite inserciones y no referencia al pedido,
     * así que no impide el borrado.
     */
    @AfterEach
    void borrarLoCreado() throws SQLException {
        try (Connection c = conexionBD.abrir()) {
            c.setAutoCommit(false);
            ApoyoCu45.ejecutar(c, "DELETE FROM reembolso WHERE pago_id IN (SELECT id FROM pago WHERE pedido_id = ?)", pedidoId);
            ApoyoCu45.ejecutar(c, "DELETE FROM factura WHERE pedido_id = ?", pedidoId);
            ApoyoCu45.ejecutar(c, "DELETE FROM notificacion WHERE pedido_id = ?", pedidoId);
            ApoyoCu45.ejecutar(c, "DELETE FROM historial_pedido WHERE pedido_id = ?", pedidoId);
            ApoyoCu45.ejecutar(c, "DELETE FROM pago WHERE pedido_id = ?", pedidoId);
            ApoyoCu45.ejecutar(c, "DELETE FROM pedido WHERE id = ?", pedidoId);
            c.commit();
        }
    }

    @Test
    @DisplayName("Dos confirmaciones simultáneas del mismo intento: la segunda espera el bloqueo del pedido y no lo confirma otra vez")
    void segundaConfirmacionNoCambiaNada() throws Exception {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        PedidoRepositorio pedidos = new PedidoRepositorio();
        PagoRepositorio pagos = new PagoRepositorio();
        ServicioPagos servicio = new ServicioPagos(ctx, pagos, pedidos,
                new ServicioReembolsos(ctx, new ReembolsoRepositorio(), pagos, pedidos, new DevolucionRepositorio()),
                new ConfirmacionComercial(new CambioEstadoPedido(ctx, pedidos, new HistorialPedidoRepositorio()),
                        ServicioInventario.crear(ctx), new NotificadorCliente(ctx)), new PasarelaPagosLocal(),
                new ServicioFacturas(ctx, new FacturaRepositorio(), pedidos, pagos, new CuentaRepositorio()));
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try (Connection primera = conexionBD.abrir()) {
            primera.setAutoCommit(false);
            try (PreparedStatement bloqueo = primera.prepareStatement("SELECT id FROM pedido WHERE id = ? FOR UPDATE")) {
                bloqueo.setLong(1, pedidoId);
                bloqueo.executeQuery().close();
            }
            ApoyoCu45.ejecutar(primera, "UPDATE pago SET estado = 'PAGADO', fecha_pago = now(), referencia_externa = ? WHERE id = ?",
                    "REF-A-" + sufijo, pagoId);
            Future<Object> segunda = hilo.submit(() -> {
                try {
                    return (Object) ctx.transacciones().enTransaccion(() -> servicio.confirmar(
                            BaseDatosPrueba.solicitante(ApoyoCu45.CARLA), "PAG-T-" + sufijo, "REF-B-" + sufijo, new BigDecimal("100.00")));
                } catch (NegocioException e) {
                    return e;
                }
            });
            esperarTransaccionBloqueada();
            primera.commit();
            NegocioException e = assertInstanceOf(NegocioException.class, segunda.get(30, TimeUnit.SECONDS));
            assertEquals(NegocioException.Codigo.ESTADO_INVALIDO, e.codigo());
        } finally {
            hilo.shutdownNow();
        }
        assertEquals("PAGADO|REF-A-" + sufijo, leer("SELECT estado || '|' || referencia_externa FROM pago WHERE id = ?", pagoId));
        assertEquals("PENDIENTE", leer("SELECT estado FROM pedido WHERE id = ?", pedidoId));
        assertEquals("0", leer("SELECT count(*) FROM bitacora WHERE entidad = 'PAGO' AND entidad_id = ?", pagoId));
    }

    private void esperarTransaccionBloqueada() throws Exception {
        long limite = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < limite) {
            if (Long.parseLong(leer("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock'")) > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("La segunda confirmación no llegó a esperar el bloqueo del pedido");
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
