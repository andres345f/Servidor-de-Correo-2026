package com.feder.datos.notificaciones;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.CanalNotificacion;
import com.feder.modelo.EstadoNotificacion;
import com.feder.modelo.Notificacion;
import com.feder.modelo.TipoNotificacion;

import java.util.List;

/** Acceso a NOTIFICACION. */
public class NotificacionRepositorio extends RepositorioJdbc {

    public long registrar(Notificacion n) {
        return insertar("INSERT INTO notificacion (usuario_id, pedido_id, producto_id, tipo, canal, titulo, mensaje, estado, fecha_envio) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                n.getUsuarioId(), n.getPedidoId(), n.getProductoId(), n.getTipo(), n.getCanal(), n.getTitulo(),
                n.getMensaje(), n.getEstado(), n.getFechaEnvio());
    }

    public List<Notificacion> deUsuario(long usuarioId, boolean soloNoLeidas, int limite) {
        return consultar("SELECT id, usuario_id, pedido_id, producto_id, tipo, canal, titulo, mensaje, estado, fecha_creacion, "
                        + "fecha_envio, fecha_lectura FROM notificacion WHERE usuario_id = ? AND (? = FALSE OR fecha_lectura IS NULL) "
                        + "ORDER BY fecha_creacion DESC, id DESC LIMIT ?",
                fila -> {
                    Notificacion n = new Notificacion();
                    n.setId(fila.getLong("id"));
                    n.setUsuarioId(fila.getLong("usuario_id"));
                    n.setPedidoId(largoONulo(fila, "pedido_id"));
                    n.setProductoId(largoONulo(fila, "producto_id"));
                    n.setTipo(enumeracion(fila, "tipo", TipoNotificacion.class));
                    n.setCanal(enumeracion(fila, "canal", CanalNotificacion.class));
                    n.setTitulo(fila.getString("titulo"));
                    n.setMensaje(fila.getString("mensaje"));
                    n.setEstado(enumeracion(fila, "estado", EstadoNotificacion.class));
                    n.setFechaCreacion(fechaHora(fila, "fecha_creacion"));
                    n.setFechaEnvio(fechaHora(fila, "fecha_envio"));
                    n.setFechaLectura(fechaHora(fila, "fecha_lectura"));
                    return n;
                },
                usuarioId, soloNoLeidas, limite);
    }

    /** Marca como leídas las notificaciones del usuario; devuelve cuántas cambió. */
    public int marcarLeidas(long usuarioId) {
        return actualizar("UPDATE notificacion SET fecha_lectura = now() WHERE usuario_id = ? AND fecha_lectura IS NULL", usuarioId);
    }

    /** Marca una sola notificación, solo si pertenece al usuario y no estaba leída. */
    public int marcarLeida(long usuarioId, long notificacionId) {
        return actualizar("UPDATE notificacion SET fecha_lectura = now() WHERE id = ? AND usuario_id = ? AND fecha_lectura IS NULL",
                notificacionId, usuarioId);
    }

    public boolean existeDeUsuario(long usuarioId, long notificacionId) {
        return consultarUno("SELECT 1 AS x FROM notificacion WHERE id = ? AND usuario_id = ?", fila -> 1, notificacionId, usuarioId)
                .isPresent();
    }
}
