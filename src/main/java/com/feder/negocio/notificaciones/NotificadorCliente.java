package com.feder.negocio.notificaciones;

import com.feder.modelo.Pedido;
import com.feder.modelo.TipoNotificacion;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.mensajeria.AvisosPendientes;

/**
 * Avisos al cliente de un pedido (CU6). Siempre registra la NOTIFICACION del canal SISTEMA (consultable con
 * LISNOT) y, en los eventos importantes, además envía un correo a la dirección registrada del cliente.
 * Todo ocurre dentro de la transacción del evento (R045): si la operación se revierte, no queda ni el
 * registro ni el correo.
 */
public class NotificadorCliente {

    private final ContextoNegocio ctx;

    public NotificadorCliente(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    /** Aviso solo en el canal SISTEMA. */
    public void avisar(Pedido pedido, TipoNotificacion tipo, String titulo, String mensaje) {
        ctx.notificaciones().sobrePedido(pedido.getUsuarioId(), pedido.getId(), tipo, titulo, mensaje);
    }

    /** Aviso en el canal SISTEMA y por correo al cliente del pedido. */
    public void avisarConCorreo(Pedido pedido, TipoNotificacion tipo, String titulo, String mensaje) {
        avisar(pedido, tipo, titulo, mensaje);
        int antes = AvisosPendientes.marca();
        ctx.avisos().avisarCuenta(pedido.getUsuarioId(), ResultadoOperacion.exito(titulo, mensaje)
                .detalle("Pedido", pedido.getCodigo()));
        // Solo dejamos constancia del canal CORREO si el correo quedó realmente en la bandeja de salida;
        // fuera del procesamiento de un mensaje (instalación, pruebas) no hay correo que registrar.
        if (AvisosPendientes.marca() > antes) {
            long notificacion = ctx.notificaciones().porCorreoSobrePedido(pedido.getUsuarioId(), pedido.getId(), tipo,
                    titulo, mensaje);
            // El envío del correo actualizará esta notificación: ENVIADA al aceptarlo el servidor, FALLIDA si falla.
            AvisosPendientes.vincularNotificacion(antes, notificacion);
        }
    }
}
