package com.feder.negocio.notificaciones;

import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.modelo.CanalNotificacion;
import com.feder.modelo.EstadoNotificacion;
import com.feder.modelo.Notificacion;
import com.feder.modelo.TipoNotificacion;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Crea las notificaciones al cliente (cambios de estado, pagos, alertas de stock...).
 * Se registran en el canal SISTEMA, consultables por correo con LISNOT; quedan ENVIADA porque
 * se entregan al buzón interno en la misma transacción que el hecho que las origina.
 */
public class ServicioNotificaciones {

    private final NotificacionRepositorio repositorio;
    private final Clock reloj;

    public ServicioNotificaciones(NotificacionRepositorio repositorio, Clock reloj) {
        this.repositorio = repositorio;
        this.reloj = reloj;
    }

    /** Aviso relacionado con un pedido (todos los tipos salvo ALERTA_STOCK). */
    public void sobrePedido(long usuarioId, long pedidoId, TipoNotificacion tipo, String titulo, String mensaje) {
        if (tipo == TipoNotificacion.ALERTA_STOCK) {
            throw new IllegalArgumentException("ALERTA_STOCK se refiere a un producto, no a un pedido");
        }
        registrar(usuarioId, pedidoId, null, tipo, titulo, mensaje);
    }

    /** Alerta de stock bajo de un producto (R070), dirigida al personal de FEDER. */
    public void alertaStock(long usuarioId, long productoId, String titulo, String mensaje) {
        registrar(usuarioId, null, productoId, TipoNotificacion.ALERTA_STOCK, titulo, mensaje);
    }

    public List<Notificacion> deUsuario(long usuarioId, boolean soloNoLeidas, int limite) {
        return repositorio.deUsuario(usuarioId, soloNoLeidas, limite);
    }

    public int marcarLeidas(long usuarioId) {
        return repositorio.marcarLeidas(usuarioId);
    }

    /** Marca como leída una notificación del propio usuario; devuelve 0 si no es suya o ya estaba leída. */
    public int marcarLeida(long usuarioId, long notificacionId) {
        return repositorio.marcarLeida(usuarioId, notificacionId);
    }

    public boolean esDelUsuario(long usuarioId, long notificacionId) {
        return repositorio.existeDeUsuario(usuarioId, notificacionId);
    }

    /**
     * Registro del mismo aviso en el canal CORREO (R045: uno por destinatario y canal). El correo ya quedó
     * en la bandeja de salida de esta transacción y se envía después de confirmarla, por eso la
     * notificación nace PENDIENTE y sin fecha de envío: no se afirma un envío que aún no ocurrió.
     * El envío la pasa después a ENVIADA (con fecha_envio) o a FALLIDA. Devuelve su identificador.
     */
    public long porCorreoSobrePedido(long usuarioId, long pedidoId, TipoNotificacion tipo, String titulo, String mensaje) {
        if (tipo == TipoNotificacion.ALERTA_STOCK) {
            throw new IllegalArgumentException("ALERTA_STOCK se refiere a un producto, no a un pedido");
        }
        return repositorio.registrar(nueva(usuarioId, pedidoId, null, tipo, CanalNotificacion.CORREO, titulo, mensaje,
                EstadoNotificacion.PENDIENTE, null));
    }

    private void registrar(long usuarioId, Long pedidoId, Long productoId, TipoNotificacion tipo, String titulo, String mensaje) {
        repositorio.registrar(nueva(usuarioId, pedidoId, productoId, tipo, CanalNotificacion.SISTEMA, titulo, mensaje,
                EstadoNotificacion.ENVIADA, OffsetDateTime.now(reloj)));
    }

    private static Notificacion nueva(long usuarioId, Long pedidoId, Long productoId, TipoNotificacion tipo, CanalNotificacion canal,
                                      String titulo, String mensaje, EstadoNotificacion estado, OffsetDateTime fechaEnvio) {
        Notificacion n = new Notificacion();
        n.setUsuarioId(usuarioId);
        n.setPedidoId(pedidoId);
        n.setProductoId(productoId);
        n.setTipo(tipo);
        n.setCanal(canal);
        n.setTitulo(titulo.length() > 120 ? titulo.substring(0, 120) : titulo);
        n.setMensaje(mensaje);
        n.setEstado(estado);
        n.setFechaEnvio(fechaEnvio);
        return n;
    }
}
