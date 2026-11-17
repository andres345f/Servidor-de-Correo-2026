package com.feder.negocio.ventas;

import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoRol;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

/**
 * Acceso a un pedido según quién lo pide (R088): el personal de FEDER ve todos; un CLIENTE solo los suyos.
 * Un pedido ajeno se informa igual que uno inexistente, y la propiedad se comprueba antes de bloquear la
 * fila, así un cliente nunca bloquea pedidos de otros.
 */
public class AccesoPedido {

    private final PedidoRepositorio pedidos;

    public AccesoPedido(PedidoRepositorio pedidos) {
        this.pedidos = pedidos;
    }

    /** Pedido visible para el solicitante, sin bloquear. */
    public Pedido visible(Solicitante solicitante, String codigo) {
        boolean personal = solicitante.esPersonalInterno();
        return pedidos.buscarPorCodigo(codigo)
                .filter(p -> personal || esPropietario(solicitante, p))
                .orElseThrow(() -> ServicioPedidos.pedidoNoEncontrado(codigo, personal));
    }

    /** Pedido visible para el solicitante, con su fila bloqueada (primera fila del orden de bloqueo). */
    public Pedido bloquearVisible(Solicitante solicitante, String codigo) {
        Pedido leido = visible(solicitante, codigo);
        return pedidos.bloquear(leido.getId()).orElseThrow();
    }

    /** Un CLIENTE solo consulta lo suyo: devuelve su id, o nulo si el solicitante es personal de FEDER. */
    public static Long clienteFiltrado(Solicitante solicitante) {
        if (solicitante.esPersonalInterno()) {
            return null;
        }
        if (!solicitante.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Solo un CLIENTE vigente o el personal de FEDER realizan esta consulta.");
        }
        return solicitante.usuarioId();
    }

    public static boolean esPropietario(Solicitante solicitante, Pedido pedido) {
        return esPropietarioDe(solicitante, pedido.getUsuarioId());
    }

    /** El solicitante es el CLIENTE vigente dueño del pedido cuyo cliente es {@code clienteId}. */
    public static boolean esPropietarioDe(Solicitante solicitante, long clienteId) {
        return solicitante.estaRegistrado() && solicitante.tieneRol(TipoRol.CLIENTE) && clienteId == solicitante.usuarioId();
    }
}
