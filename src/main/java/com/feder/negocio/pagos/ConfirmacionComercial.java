package com.feder.negocio.pagos;

import com.feder.modelo.EstadoPedido;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoNotificacion;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.ventas.CambioEstadoPedido;

/**
 * Confirmación comercial de un pedido INMEDIATO (R047 (1) y (2), R053 (1)): PENDIENTE -> CONFIRMADO con su
 * historial y, en la misma transacción, sus reservas dejan de caducar. La invocan la confirmación de un
 * pago (anticipo o total) y la aceptación para efectivo contra entrega. El pedido ya está bloqueado.
 */
public class ConfirmacionComercial {

    private final CambioEstadoPedido cambioEstado;
    private final ServicioInventario inventario;
    private final NotificadorCliente notificador;

    public ConfirmacionComercial(CambioEstadoPedido cambioEstado, ServicioInventario inventario, NotificadorCliente notificador) {
        this.cambioEstado = cambioEstado;
        this.inventario = inventario;
        this.notificador = notificador;
    }

    public void confirmar(Pedido pedido, Long responsableId, String comentario, boolean contraEntrega) {
        cambioEstado.cambiar(pedido, EstadoPedido.CONFIRMADO, responsableId, comentario);
        inventario.quitarExpiracionDeReservas(pedido.getId());
        notificador.avisarConCorreo(pedido, TipoNotificacion.CAMBIO_ESTADO_PEDIDO, "Pedido confirmado", "El pedido "
                + pedido.getCodigo() + " fue confirmado y sus productos quedan apartados para usted"
                + (contraEntrega ? ". Pagará Bs " + Importes.formatear(pedido.getTotal()) + " en efectivo al recibirlo." : "."));
    }

    /**
     * R047 (último párrafo) y R053 (1): un anticipo cobrado y verificado de un INMEDIATO PENDIENTE ya es confirmación
     * comercial, así que sus reservas dejan de caducar en la misma transacción aunque el pedido siga PENDIENTE
     * hasta completar el total.
     */
    public void anticipoConfirmado(Pedido pedido) {
        inventario.quitarExpiracionDeReservas(pedido.getId());
    }
}
