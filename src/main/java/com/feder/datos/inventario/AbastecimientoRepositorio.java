package com.feder.datos.inventario;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Lectura, sin bloqueo, de las líneas de pedido que esperan unidades (PEDIDO «Encargo sin stock
 * ficticio», R056): líneas de un ENCARGO en ESPERANDO_ABASTECIMIENTO y líneas de pedidos CONFIRMADO o
 * EN_PREPARACION que perdieron cobertura. Pendiente = cantidad - reservas ACTIVA o CONSUMIDA (R015).
 * Es el primer paso del abastecimiento: el llamador bloquea después esos pedidos y líneas.
 */
public class AbastecimientoRepositorio extends RepositorioJdbc {

    private static final String SELECT = "SELECT dp.id, dp.pedido_id, dp.producto_id, dp.cantidad - COALESCE((SELECT sum(rs.cantidad) "
            + "FROM reserva_stock rs WHERE rs.detalle_pedido_id = dp.id AND rs.estado IN ('ACTIVA', 'CONSUMIDA')), 0) AS pendiente "
            + "FROM detalle_pedido dp JOIN pedido p ON p.id = dp.pedido_id "
            + "WHERE ((p.tipo = 'ENCARGO' AND p.estado = 'ESPERANDO_ABASTECIMIENTO') OR p.estado IN ('CONFIRMADO', 'EN_PREPARACION')) ";

    private static final String CON_PENDIENTE = "AND dp.cantidad > COALESCE((SELECT sum(rs.cantidad) FROM reserva_stock rs "
            + "WHERE rs.detalle_pedido_id = dp.id AND rs.estado IN ('ACTIVA', 'CONSUMIDA')), 0) ";

    /** Línea de pedido con la cantidad que todavía le falta cubrir. */
    public record LineaPendiente(long detallePedidoId, long pedidoId, long productoId, BigDecimal pendiente) {
    }

    /**
     * Líneas de un producto en el orden de prioridad de R056 (a): primero las respaldadas por la producción
     * del lote de origen recibido, después las demás por fecha de creación del pedido.
     */
    public List<LineaPendiente> deProducto(long productoId, long loteOrigenId) {
        return consultar(SELECT + "AND dp.producto_id = ? " + CON_PENDIENTE
                        + "ORDER BY CASE WHEN dp.produccion_id = (SELECT lp.produccion_id FROM lote_produccion lp WHERE lp.id = ?) "
                        + "THEN 0 ELSE 1 END, p.fecha_creacion, p.id, dp.id",
                AbastecimientoRepositorio::mapear, productoId, loteOrigenId);
    }

    /** Líneas pendientes de un pedido, si su estado admite abastecimiento. */
    public List<LineaPendiente> dePedido(long pedidoId) {
        return consultar(SELECT + "AND dp.pedido_id = ? " + CON_PENDIENTE + "ORDER BY dp.id",
                AbastecimientoRepositorio::mapear, pedidoId);
    }

    /** Cuántas líneas del pedido no están cubiertas por completo con reservas ACTIVA o CONSUMIDA. */
    public long lineasSinCubrir(long pedidoId) {
        return consultarUno("SELECT count(*) AS n FROM detalle_pedido dp WHERE dp.pedido_id = ? " + CON_PENDIENTE,
                fila -> fila.getLong("n"), pedidoId).orElse(0L);
    }

    private static LineaPendiente mapear(ResultSet fila) throws SQLException {
        return new LineaPendiente(fila.getLong("id"), fila.getLong("pedido_id"), fila.getLong("producto_id"),
                fila.getBigDecimal("pendiente"));
    }
}
