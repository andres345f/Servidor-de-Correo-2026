package com.feder.datos.ventas;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.HistorialPedido;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a HISTORIAL_PEDIDO, la línea de tiempo del pedido visible para el cliente. Solo se agregan
 * registros; la base valida el par de estados (ck_historial_pedido_transicion) y el comentario de
 * cancelación, y la capa de negocio que el par corresponda al tipo y al estado actual del pedido (R032).
 */
public class HistorialPedidoRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT id, pedido_id, usuario_id, estado_anterior, estado_nuevo, fecha, comentario "
            + "FROM historial_pedido ";

    /** @param h usuario_id nulo cuando el cambio lo hace el sistema (A27). */
    public long registrar(HistorialPedido h) {
        return insertar("INSERT INTO historial_pedido (pedido_id, usuario_id, estado_anterior, estado_nuevo, fecha, comentario) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                h.getPedidoId(), h.getUsuarioId(), h.getEstadoAnterior(), h.getEstadoNuevo(), h.getFecha(), h.getComentario());
    }

    public List<HistorialPedido> dePedido(long pedidoId) {
        return consultar(COLUMNAS + "WHERE pedido_id = ? ORDER BY fecha, id", HistorialPedidoRepositorio::mapear, pedidoId);
    }

    public Optional<HistorialPedido> ultimo(long pedidoId) {
        return consultarUno(COLUMNAS + "WHERE pedido_id = ? ORDER BY fecha DESC, id DESC LIMIT 1",
                HistorialPedidoRepositorio::mapear, pedidoId);
    }

    private static HistorialPedido mapear(ResultSet fila) throws SQLException {
        HistorialPedido h = new HistorialPedido();
        h.setId(fila.getLong("id"));
        h.setPedidoId(fila.getLong("pedido_id"));
        h.setUsuarioId(largoONulo(fila, "usuario_id"));
        h.setEstadoAnterior(enumeracion(fila, "estado_anterior", EstadoPedido.class));
        h.setEstadoNuevo(enumeracion(fila, "estado_nuevo", EstadoPedido.class));
        h.setFecha(fechaHora(fila, "fecha"));
        h.setComentario(fila.getString("comentario"));
        return h;
    }
}
