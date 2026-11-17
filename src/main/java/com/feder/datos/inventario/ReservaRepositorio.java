package com.feder.datos.inventario;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoReserva;
import com.feder.modelo.ReservaStock;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Acceso a RESERVA_STOCK y a la fila de DETALLE_PEDIDO que una reserva cubre. */
public class ReservaRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT rs.id, rs.lote_feder_id, rs.detalle_pedido_id, rs.cantidad, rs.estado, "
            + "rs.fecha_creacion, rs.fecha_expiracion, rs.fecha_cierre FROM reserva_stock rs ";

    /** Línea de pedido bloqueada antes de reservar (R086), con el dato necesario para no excederla. */
    public record LineaBloqueada(long id, long pedidoId, long productoId, BigDecimal cantidad) {
    }

    public long insertar(ReservaStock r) {
        return insertar("INSERT INTO reserva_stock (lote_feder_id, detalle_pedido_id, cantidad, estado, fecha_creacion, "
                        + "fecha_expiracion) VALUES (?, ?, ?, 'ACTIVA', ?, ?)",
                r.getLoteFederId(), r.getDetallePedidoId(), r.getCantidad(), r.getFechaCreacion(), r.getFechaExpiracion());
    }

    /** R086: bloquea la línea de pedido antes de sumar sus reservas y crear otra. */
    public Optional<LineaBloqueada> bloquearLinea(long detallePedidoId) {
        return consultarUno("SELECT id, pedido_id, producto_id, cantidad FROM detalle_pedido WHERE id = ? FOR UPDATE",
                fila -> new LineaBloqueada(fila.getLong("id"), fila.getLong("pedido_id"), fila.getLong("producto_id"),
                        fila.getBigDecimal("cantidad")), detallePedidoId);
    }

    /** Bloquea todas las líneas de un pedido en orden de identificador (orden global de R037). */
    public List<LineaBloqueada> bloquearLineasDePedido(long pedidoId) {
        return consultar("SELECT id, pedido_id, producto_id, cantidad FROM detalle_pedido WHERE pedido_id = ? "
                        + "ORDER BY id FOR UPDATE",
                fila -> new LineaBloqueada(fila.getLong("id"), fila.getLong("pedido_id"), fila.getLong("producto_id"),
                        fila.getBigDecimal("cantidad")), pedidoId);
    }

    public Optional<ReservaStock> buscar(long id) {
        return consultarUno(COLUMNAS + "WHERE rs.id = ?", ReservaRepositorio::mapear, id);
    }

    /** R015: cantidad cubierta = suma de reservas ACTIVA o CONSUMIDA de la línea. */
    public BigDecimal cubiertaDeLinea(long detallePedidoId) {
        return consultarUno("SELECT COALESCE(sum(cantidad), 0) AS total FROM reserva_stock "
                        + "WHERE detalle_pedido_id = ? AND estado IN ('ACTIVA', 'CONSUMIDA')",
                fila -> fila.getBigDecimal("total"), detallePedidoId).orElse(BigDecimal.ZERO);
    }

    public BigDecimal activaDeLinea(long detallePedidoId) {
        return consultarUno("SELECT COALESCE(sum(cantidad), 0) AS total FROM reserva_stock "
                        + "WHERE detalle_pedido_id = ? AND estado = 'ACTIVA'",
                fila -> fila.getBigDecimal("total"), detallePedidoId).orElse(BigDecimal.ZERO);
    }

    public List<ReservaStock> activasDePedido(long pedidoId) {
        return consultar(COLUMNAS + "JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                + "WHERE dp.pedido_id = ? AND rs.estado = 'ACTIVA' ORDER BY rs.id", ReservaRepositorio::mapear, pedidoId);
    }

    public List<ReservaStock> dePedido(long pedidoId) {
        return consultar(COLUMNAS + "JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                + "WHERE dp.pedido_id = ? ORDER BY rs.id", ReservaRepositorio::mapear, pedidoId);
    }

    public List<ReservaStock> activasDeLote(long loteFederId) {
        return consultar(COLUMNAS + "WHERE rs.lote_feder_id = ? AND rs.estado = 'ACTIVA' ORDER BY rs.id",
                ReservaRepositorio::mapear, loteFederId);
    }

    /** Pedidos con reservas ACTIVA en el lote, en orden de identificador (para bloquearlos primero, R037). */
    public List<Long> pedidosConReservaActivaEnLote(long loteFederId) {
        return consultar("SELECT DISTINCT dp.pedido_id FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                + "WHERE rs.lote_feder_id = ? AND rs.estado = 'ACTIVA' ORDER BY dp.pedido_id", fila -> fila.getLong(1), loteFederId);
    }

    public Optional<ReservaStock> bloquear(long id) {
        return consultarUno(COLUMNAS + "WHERE rs.id = ? FOR UPDATE", ReservaRepositorio::mapear, id);
    }

    /**
     * R084: una reserva sale de ACTIVA una sola vez. Devuelve false si otra operación ya la cerró,
     * y en ese caso el llamador no debe tocar el saldo del lote.
     */
    public boolean cerrar(long id, EstadoReserva nuevoEstado, OffsetDateTime momento) {
        return actualizar("UPDATE reserva_stock SET estado = ?, fecha_cierre = ? WHERE id = ? AND estado = 'ACTIVA'",
                nuevoEstado, momento, id) == 1;
    }

    /** R053: con la confirmación comercial las reservas dejan de caducar. */
    public int quitarExpiracion(long pedidoId) {
        return actualizar("UPDATE reserva_stock SET fecha_expiracion = NULL WHERE estado = 'ACTIVA' AND detalle_pedido_id IN "
                + "(SELECT id FROM detalle_pedido WHERE pedido_id = ?)", pedidoId);
    }

    private static ReservaStock mapear(ResultSet fila) throws SQLException {
        ReservaStock r = new ReservaStock();
        r.setId(fila.getLong("id"));
        r.setLoteFederId(fila.getLong("lote_feder_id"));
        r.setDetallePedidoId(fila.getLong("detalle_pedido_id"));
        r.setCantidad(fila.getBigDecimal("cantidad"));
        r.setEstado(enumeracion(fila, "estado", EstadoReserva.class));
        r.setFechaCreacion(fechaHora(fila, "fecha_creacion"));
        r.setFechaExpiracion(fechaHora(fila, "fecha_expiracion"));
        r.setFechaCierre(fechaHora(fila, "fecha_cierre"));
        return r;
    }
}
