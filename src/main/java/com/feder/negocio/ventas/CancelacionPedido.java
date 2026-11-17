package com.feder.negocio.ventas;

import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Entrega;
import com.feder.modelo.EstadoEntrega;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.EstadoReserva;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoNotificacion;
import com.feder.modelo.TipoPedido;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Cancelación de un pedido (HISTORIAL_PEDIDO «Responsable de la cancelación» R031 y PEDIDO «Cancelación
 * sin reingreso ficticio» R052), con la fila del PEDIDO bloqueada:
 *  - antes del despacho (PENDIENTE, ESPERANDO_ABASTECIMIENTO, CONFIRMADO, EN_PREPARACION) la cancela el
 *    CLIENTE propietario o el personal: se liberan las reservas y la ENTREGA pasa a CANCELADA;
 *  - desde DESPACHADO solo el personal, con la ENTREGA RETORNADA o NO_RECUPERADA, que se conserva.
 * Los pagos cobrados se reintegran según la {@link PoliticaReintegro}. No se borra ningún registro.
 */
final class CancelacionPedido {

    private static final Set<EstadoPedido> ANTES_DEL_DESPACHO = EnumSet.of(EstadoPedido.PENDIENTE,
            EstadoPedido.ESPERANDO_ABASTECIMIENTO, EstadoPedido.CONFIRMADO, EstadoPedido.EN_PREPARACION);
    static final String RESERVA_EXPIRADA = "reserva expirada";
    private static final Set<EstadoEntrega> ENTREGA_SIN_MERCANCIA = EnumSet.of(EstadoEntrega.RETORNADA, EstadoEntrega.NO_RECUPERADA);

    private final ContextoNegocio ctx;
    private final PedidoRepositorio pedidos;
    private final EntregaRepositorio entregas;
    private final CambioEstadoPedido cambioEstado;
    private final ServicioInventario inventario;
    private final PoliticaReintegro reintegros;

    CancelacionPedido(ContextoNegocio ctx, PedidoRepositorio pedidos, EntregaRepositorio entregas,
                      CambioEstadoPedido cambioEstado, ServicioInventario inventario, PoliticaReintegro reintegros) {
        this.ctx = ctx;
        this.pedidos = pedidos;
        this.entregas = entregas;
        this.cambioEstado = cambioEstado;
        this.inventario = inventario;
        this.reintegros = reintegros;
    }

    ResultadoCancelacion cancelar(Solicitante solicitante, String codigo, String motivo) {
        String comentario = Textos.obligatorio(motivo, "motivo de la cancelación");
        boolean personal = solicitante.esPersonalInterno();
        // R088: la propiedad se comprueba antes de bloquear, así un cliente no bloquea pedidos ajenos, y un
        // pedido ajeno se informa igual que uno inexistente.
        Pedido leido = pedidos.buscarPorCodigo(codigo)
                .filter(p -> personal || esPropietario(solicitante, p))
                .orElseThrow(() -> ServicioPedidos.pedidoNoEncontrado(codigo, personal));
        PedidoCompleto completo = pedidos.bloquearConLineas(leido.getCodigo()).orElseThrow();
        Pedido pedido = completo.pedido();
        boolean propietario = esPropietario(solicitante, pedido);
        Entrega entrega = completo.entrega();
        int liberadas;
        if (ANTES_DEL_DESPACHO.contains(pedido.getEstado())) {
            liberadas = inventario.liberarReservasDePedido(pedido.getId(), EstadoReserva.LIBERADA);
            cancelarEntrega(entrega, solicitante.usuarioId());
        } else if (pedido.getEstado() == EstadoPedido.DESPACHADO) {
            exigirCancelacionTrasDespacho(personal, entrega);
            liberadas = 0;
        } else {
            throw NegocioException.estadoInvalido("El pedido " + pedido.getCodigo() + " está " + pedido.getEstado()
                    + " y ya no se puede cancelar" + (pedido.getEstado() == EstadoPedido.ENTREGADO
                    ? ": para un producto recibido corresponde una devolución." : "."));
        }
        cambioEstado.cambiar(pedido, EstadoPedido.CANCELADO, solicitante.usuarioId(), comentario);
        List<BigDecimal> importes = reintegros.alCancelar(pedido, solicitante.usuarioId(), comentario);
        String aviso = "El pedido " + pedido.getCodigo() + " fue cancelado" + (propietario ? " a su solicitud" : " por FEDER")
                + ". Motivo: " + comentario + (importes.isEmpty() ? "" : ". Le reintegraremos el importe pagado.");
        // Si lo canceló FEDER, el cliente también recibe el aviso por correo; si lo pidió él, basta el aviso interno.
        NotificadorCliente notificador = new NotificadorCliente(ctx);
        if (propietario) {
            notificador.avisar(pedido, TipoNotificacion.CAMBIO_ESTADO_PEDIDO, "Pedido cancelado", aviso);
        } else {
            notificador.avisarConCorreo(pedido, TipoNotificacion.CAMBIO_ESTADO_PEDIDO, "Pedido cancelado", aviso);
        }
        return new ResultadoCancelacion(pedidos.buscarConLineas(codigo).orElseThrow(), liberadas, importes);
    }

    private static boolean esPropietario(Solicitante solicitante, Pedido pedido) {
        return solicitante.tieneRol(TipoRol.CLIENTE) && pedido.getUsuarioId() == solicitante.usuarioId();
    }

    /** Cómo terminó la revisión de un pedido en el proceso de expiración (EXPRES). */
    record RevisionExpiracion(boolean expirado, boolean porConciliar, String motivo) {

        static final RevisionExpiracion EXPIRADO = new RevisionExpiracion(true, false, null);

        static RevisionExpiracion porConciliar(String motivo) {
            return new RevisionExpiracion(false, true, motivo);
        }

        static RevisionExpiracion sinCambios(String motivo) {
            return new RevisionExpiracion(false, false, motivo);
        }
    }

    /**
     * R053 (3): expira un pedido INMEDIATO PENDIENTE cuyo plazo de pago venció. Bloquea primero el pedido y relee su
     * estado, sus reservas y sus pagos. Caducar la reserva no equivale a un pago RECHAZADO: si el pedido tiene un pago
     * QR o TARJETA PENDIENTE, primero se concilia. Según R048 ese pago pasa a RECHAZADO solo con un resultado definitivo
     * sin cobro, y sin un proveedor real ese resultado lo da el personal (CONPAG si cobró, RECPAG con su motivo si no),
     * así que el proceso nunca lo rechaza: deja el pedido y sus reservas como estaban y lo informa para conciliarlo. Con
     * un pago PAGADO tampoco se expira. Si no, las reservas pasan a EXPIRADA, la ENTREGA a CANCELADA y el pedido a
     * CANCELADO con un historial sin responsable (A27) y el comentario «reserva expirada».
     */
    RevisionExpiracion expirar(long pedidoId) {
        PedidoCompleto completo = pedidos.bloquear(pedidoId).flatMap(p -> pedidos.buscarConLineas(p.getCodigo())).orElseThrow();
        Pedido pedido = completo.pedido();
        if (pedido.getTipo() != TipoPedido.INMEDIATO || pedido.getEstado() != EstadoPedido.PENDIENTE) {
            return RevisionExpiracion.sinCambios("ya no está PENDIENTE");
        }
        if (!pedidos.reservasActivasVencidas(pedidoId, ctx.ahora())) {
            return RevisionExpiracion.sinCambios("sus reservas no vencieron o ya tiene confirmación comercial");
        }
        List<String> intentos = pedidos.intentosPasarelaPendientes(pedidoId);
        if (!intentos.isEmpty()) {
            return RevisionExpiracion.porConciliar("tiene un pago PENDIENTE por QR o TARJETA (clave " + String.join(", ", intentos)
                    + ") y la reserva vencida no lo rechaza: verifique el comprobante y confírmelo con CONPAG o, si no hubo "
                    + "cobro, recházelo con RECPAG y su motivo; después EXPRES expira el pedido");
        }
        if (pedidos.tienePagoPendienteOCobrado(pedidoId)) {
            return RevisionExpiracion.sinCambios("tiene un pago PAGADO");
        }
        inventario.liberarReservasDePedido(pedidoId, EstadoReserva.EXPIRADA);
        cancelarEntrega(completo.entrega(), null);
        cambioEstado.cambiar(pedido, EstadoPedido.CANCELADO, null, RESERVA_EXPIRADA);
        // Un efectivo pendiente sin aceptación comercial termina sin cobro junto con el pedido (R049).
        reintegros.alCancelar(pedido, null, RESERVA_EXPIRADA);
        new NotificadorCliente(ctx).avisarConCorreo(pedido, TipoNotificacion.CAMBIO_ESTADO_PEDIDO,
                "Pedido cancelado", "El pedido " + pedido.getCodigo() + " se canceló porque venció el plazo de pago y su "
                        + "reserva expiró. Puede volver a comprar desde su carrito.");
        return RevisionExpiracion.EXPIRADO;
    }

    /** R027: la ENTREGA pasa a CANCELADA en la misma transacción, con su transición en BITACORA. */
    private void cancelarEntrega(Entrega entrega, Long autorId) {
        if (entrega == null || entrega.getEstado() == EstadoEntrega.CANCELADA) {
            return;
        }
        EstadoEntrega anterior = entrega.getEstado();
        if (!entregas.cambiarEstado(entrega.getId(), anterior, EstadoEntrega.CANCELADA)) {
            throw NegocioException.estadoInvalido("La entrega del pedido cambió de estado durante la cancelación.");
        }
        ctx.bitacora().registrar(autorId, AccionBitacora.MODIFICAR, "ENTREGA", entrega.getId(),
                DetalleJson.nuevo().con("estado_anterior", anterior).con("estado_nuevo", EstadoEntrega.CANCELADA));
    }

    private static void exigirCancelacionTrasDespacho(boolean personal, Entrega entrega) {
        if (!personal) {
            throw NegocioException.estadoInvalido("El pedido ya fue despachado: solo el personal de FEDER puede cancelarlo, "
                    + "cuando la mercancía retornó o no se recuperó.");
        }
        if (entrega == null || !ENTREGA_SIN_MERCANCIA.contains(entrega.getEstado())) {
            throw NegocioException.estadoInvalido("Un pedido DESPACHADO solo se cancela con su entrega RETORNADA o NO_RECUPERADA; "
                    + "la entrega está " + (entrega == null ? "sin registrar" : entrega.getEstado()) + ".");
        }
    }
}
