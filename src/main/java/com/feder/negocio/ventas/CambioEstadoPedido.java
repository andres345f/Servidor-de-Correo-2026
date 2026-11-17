package com.feder.negocio.ventas;

import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.HistorialPedido;
import com.feder.modelo.Pedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;

/**
 * Cambia el estado de un pedido y agrega su HISTORIAL_PEDIDO en la misma transacción (R032).
 * El llamador debe tener bloqueada la fila del pedido (PedidoRepositorio.bloquear). Lo reutiliza CU6
 * para la confirmación comercial, la preparación, el despacho y la entrega.
 */
public class CambioEstadoPedido {

    private final ContextoNegocio ctx;
    private final PedidoRepositorio pedidos;
    private final HistorialPedidoRepositorio historial;

    public CambioEstadoPedido(ContextoNegocio ctx, PedidoRepositorio pedidos, HistorialPedidoRepositorio historial) {
        this.ctx = ctx;
        this.pedidos = pedidos;
        this.historial = historial;
    }

    /**
     * @param responsableId usuario que realiza el cambio; nulo si lo hace el sistema (A27).
     * @param comentario    obligatorio en una cancelación.
     */
    public void cambiar(Pedido pedido, EstadoPedido nuevo, Long responsableId, String comentario) {
        EstadoPedido actual = pedido.getEstado();
        if (!TransicionesPedido.permitida(pedido.getTipo(), actual, nuevo)) {
            throw NegocioException.estadoInvalido("Un pedido " + pedido.getTipo() + " no puede pasar de " + actual + " a "
                    + nuevo + ".");
        }
        if (!pedidos.cambiarEstado(pedido.getId(), actual, nuevo)) {
            throw NegocioException.estadoInvalido("El pedido " + pedido.getCodigo() + " cambió de estado mientras se procesaba la operación.");
        }
        registrarHistorial(pedido.getId(), responsableId, actual, nuevo, comentario);
        pedido.setEstado(nuevo);
    }

    /** Primer registro del historial, al crear el pedido (estado anterior nulo). */
    void registrarCreacion(Pedido pedido, long clienteId, String comentario) {
        registrarHistorial(pedido.getId(), clienteId, null, EstadoPedido.PENDIENTE, comentario);
    }

    private void registrarHistorial(long pedidoId, Long responsableId, EstadoPedido anterior, EstadoPedido nuevo, String comentario) {
        HistorialPedido h = new HistorialPedido();
        h.setPedidoId(pedidoId);
        h.setUsuarioId(responsableId);
        h.setEstadoAnterior(anterior);
        h.setEstadoNuevo(nuevo);
        h.setFecha(ctx.ahora());
        h.setComentario(comentario == null || comentario.length() <= 255 ? comentario : comentario.substring(0, 255));
        h.setId(historial.registrar(h));
    }
}
