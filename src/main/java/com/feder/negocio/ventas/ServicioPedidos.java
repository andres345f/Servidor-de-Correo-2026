package com.feder.negocio.ventas;

import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.ventas.CarritoRepositorio;
import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.HistorialPedido;
import com.feder.modelo.ReservaStock;
import com.feder.modelo.TipoPedido;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.ServicioInventario;

import java.util.ArrayList;
import java.util.List;

/**
 * Pedidos de la tienda (CU5): confirmación del carrito como pedido inmediato o encargo, cancelación y
 * consultas. Cada operación es una sola transacción; el CLIENTE solo opera sobre sus propios pedidos y
 * el personal sobre todos (R088).
 */
public class ServicioPedidos {

    /** Tope común de los listados (Listados): se pide una fila de más para saber si hay más registros. */
    private static final int LIMITE_LISTADO = Listados.A_PEDIR;

    private final ContextoNegocio ctx;
    private final PedidoRepositorio pedidos;
    private final HistorialPedidoRepositorio historial;
    private final ReservaRepositorio reservas;
    private final ConfirmacionCarrito confirmacion;
    private final CancelacionPedido cancelacion;

    public ServicioPedidos(ContextoNegocio ctx, CarritoRepositorio carrito, PedidoRepositorio pedidos,
                           EntregaRepositorio entregas, HistorialPedidoRepositorio historial, ReservaRepositorio reservas,
                           ServicioInventario inventario, PoliticaReintegro reintegros) {
        this.ctx = ctx;
        this.pedidos = pedidos;
        this.historial = historial;
        this.reservas = reservas;
        CambioEstadoPedido cambioEstado = new CambioEstadoPedido(ctx, pedidos, historial);
        this.confirmacion = new ConfirmacionCarrito(ctx, carrito, pedidos, entregas, cambioEstado, inventario);
        this.cancelacion = new CancelacionPedido(ctx, pedidos, entregas, cambioEstado, inventario, reintegros);
    }

    /** Pedido con su historial y sus reservas, para VERPED. */
    public record PedidoDetallado(PedidoCompleto pedido, List<HistorialPedido> historial, List<ReservaStock> reservas) {
    }

    /** R054 y R060: pedido INMEDIATO; cada línea queda cubierta por reservas ACTIVA o no se crea nada. */
    public ResultadoPedido confirmarInmediato(Solicitante solicitante, SolicitudPedido solicitud) {
        return ctx.transacciones().enTransaccion(() -> confirmacion.confirmar(solicitante, solicitud, TipoPedido.INMEDIATO));
    }

    /** R056: ENCARGO sin reservas, solo de productos que lo admiten y cuyo stock vendible no alcanza. */
    public ResultadoPedido confirmarEncargo(Solicitante solicitante, SolicitudPedido solicitud) {
        return ctx.transacciones().enTransaccion(() -> confirmacion.confirmar(solicitante, solicitud, TipoPedido.ENCARGO));
    }

    public ResultadoCancelacion cancelar(Solicitante solicitante, String codigo, String motivo) {
        return ctx.transacciones().enTransaccion(() -> cancelacion.cancelar(solicitante, codigo, motivo));
    }

    /** El personal ve todos los pedidos; un CLIENTE solo los suyos. */
    public List<PedidoRepositorio.PedidoResumen> listar(Solicitante solicitante, EstadoPedido estado) {
        Long usuario = solicitante.esPersonalInterno() ? null : clientePropio(solicitante);
        return ctx.transacciones().enTransaccion(() -> pedidos.listar(usuario, estado, LIMITE_LISTADO));
    }

    /** R088: un CLIENTE solo ve sus pedidos; uno ajeno se informa igual que uno inexistente. */
    public PedidoDetallado ver(Solicitante solicitante, String codigo) {
        boolean personal = solicitante.esPersonalInterno();
        Long propio = personal ? null : clientePropio(solicitante);
        return ctx.transacciones().enTransaccion(() -> {
            PedidoCompleto completo = pedidos.buscarConLineas(codigo)
                    .filter(c -> propio == null || propio.equals(c.pedido().getUsuarioId()))
                    .orElseThrow(() -> pedidoNoEncontrado(codigo, personal));
            return new PedidoDetallado(completo, historial.dePedido(completo.pedido().getId()),
                    reservas.dePedido(completo.pedido().getId()));
        });
    }

    /**
     * R053 (3): expira los pedidos INMEDIATO PENDIENTE cuyo plazo de pago venció (lo ejecuta el personal o un
     * proceso programado). Cada pedido se bloquea y se relee antes de expirarlo. Nunca rechaza un pago QR o TARJETA:
     * los pedidos con uno PENDIENTE se dejan como están y se informan aparte para conciliarlos (CONPAG o RECPAG); los
     * demás que no se expiran se informan con su motivo. Un efectivo sin aceptación comercial sí termina sin cobro
     * junto con el pedido que expira (R049).
     */
    public ResultadoExpiracion expirarReservasVencidas(Solicitante solicitante) {
        Permisos.exigirPersonal(solicitante, "expirar reservas vencidas");
        return ctx.transacciones().enTransaccion(() -> {
            List<String> expirados = new ArrayList<>();
            List<String> porConciliar = new ArrayList<>();
            List<String> omitidos = new ArrayList<>();
            for (Long id : pedidos.inmediatosConReservaVencida(ctx.ahora())) {
                String codigo = pedidos.bloquear(id).orElseThrow().getCodigo();
                CancelacionPedido.RevisionExpiracion revision = cancelacion.expirar(id);
                if (revision.expirado()) {
                    expirados.add(codigo);
                } else if (revision.porConciliar()) {
                    porConciliar.add(codigo + ": " + revision.motivo());
                } else {
                    omitidos.add(codigo + ": " + revision.motivo());
                }
            }
            return new ResultadoExpiracion(expirados, porConciliar, omitidos);
        });
    }

    /**
     * Pedidos cancelados por reserva expirada, pedidos que esperan la conciliación de un pago QR o TARJETA y pedidos
     * que se dejaron como estaban por otro motivo (estos dos últimos, con su explicación).
     */
    public record ResultadoExpiracion(List<String> expirados, List<String> porConciliar, List<String> omitidos) {
    }

    static NegocioException pedidoNoEncontrado(String codigo, boolean personal) {
        return NegocioException.noEncontrado(personal ? "No existe el pedido " + codigo + ". Consulte LISPED[\"*\"]."
                : "No existe el pedido " + codigo + " entre sus pedidos. Consulte LISPED[\"*\"].");
    }

    private static long clientePropio(Solicitante solicitante) {
        if (!solicitante.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Solo un CLIENTE vigente o el personal de FEDER consultan pedidos.");
        }
        return solicitante.usuarioId();
    }
}
