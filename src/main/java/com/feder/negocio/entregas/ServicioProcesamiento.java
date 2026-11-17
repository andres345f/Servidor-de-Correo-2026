package com.feder.negocio.entregas;

import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.inventario.ReservaRepositorio.LineaBloqueada;
import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.Entrega;
import com.feder.modelo.EstadoEntrega;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoNotificacion;
import com.feder.modelo.TipoPedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.pagos.CobrosPedido;
import com.feder.negocio.pagos.ConfirmacionComercial;
import com.feder.negocio.ventas.CambioEstadoPedido;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Procesamiento de un pedido por el personal (R047, R053, R055): confirmación comercial, preparación y
 * despacho atómico. Cada operación es una transacción que bloquea primero la fila del PEDIDO y relee su estado.
 */
public class ServicioProcesamiento {

    private final ContextoNegocio ctx;
    private final PedidoRepositorio pedidos;
    private final ReservaRepositorio reservas;
    private final CambioEstadoPedido cambioPedido;
    private final CambioEstadoEntrega cambioEntrega;
    private final EntregaRepositorio entregas;
    private final ServicioInventario inventario;
    private final CobrosPedido cobros;
    private final ConfirmacionComercial confirmacion;
    private final NotificadorCliente notificador;

    public ServicioProcesamiento(ContextoNegocio ctx, PedidoRepositorio pedidos, ReservaRepositorio reservas,
                                 EntregaRepositorio entregas, CambioEstadoPedido cambioPedido,
                                 ServicioInventario inventario, CobrosPedido cobros, ConfirmacionComercial confirmacion) {
        this.ctx = ctx;
        this.pedidos = pedidos;
        this.reservas = reservas;
        this.entregas = entregas;
        this.cambioPedido = cambioPedido;
        this.cambioEntrega = new CambioEstadoEntrega(ctx, entregas);
        this.inventario = inventario;
        this.cobros = cobros;
        this.confirmacion = confirmacion;
        this.notificador = new NotificadorCliente(ctx);
    }

    /** Pedido despachado con sus salidas de inventario. */
    public record Despacho(PedidoCompleto pedido, List<MovimientoInventario> salidas) {
    }

    /**
     * R047 (1) y (2): confirmación comercial de un INMEDIATO PENDIENTE, si está pagado por completo o si tiene
     * un PAGO EFECTIVO PENDIENTE por el total sin cobro anticipado (contra entrega). Un encargo se acepta con ACEENC.
     */
    public PedidoCompleto confirmar(Solicitante solicitante, String codigo, String comentario) {
        Permisos.exigirPersonal(solicitante, "confirmar pedidos");
        String nota = Textos.opcional(comentario);
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = bloquear(codigo).pedido();
            if (pedido.getTipo() == TipoPedido.ENCARGO) {
                throw NegocioException.regla("El pedido " + codigo + " es un ENCARGO: se acepta con ACEENC, que fija su fecha "
                        + "estimada, y pasa a CONFIRMADO cuando queda abastecido.");
            }
            exigirEstado(pedido, EstadoPedido.PENDIENTE, "confirmar");
            if (cobros.pagadoPorCompleto(pedido)) {
                confirmacion.confirmar(pedido, solicitante.usuarioId(), nota != null ? nota : "Pago completo verificado", false);
            } else if (cobros.contraEntrega(pedido).isPresent() && cobros.pagado(pedido.getId()).signum() == 0) {
                confirmacion.confirmar(pedido, solicitante.usuarioId(),
                        nota != null ? nota : "Aceptado para pago en efectivo contra entrega", true);
            } else {
                throw NegocioException.regla("El pedido " + codigo + " aún no puede confirmarse: necesita los pagos QR o TARJETA "
                        + "verificados por el total, o un pago EFECTIVO contra entrega por el total sin anticipos (REGPAG).");
            }
            return pedidos.buscarConLineas(codigo).orElseThrow();
        });
    }

    /** R047 (4): CONFIRMADO -> EN_PREPARACION con todas las líneas cubiertas por reservas ACTIVA y el cobro resuelto. */
    public PedidoCompleto preparar(Solicitante solicitante, String codigo) {
        Permisos.exigirPersonal(solicitante, "preparar pedidos");
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = bloquear(codigo).pedido();
            exigirEstado(pedido, EstadoPedido.CONFIRMADO, "preparar");
            for (LineaBloqueada linea : reservas.bloquearLineasDePedido(pedido.getId())) {
                if (reservas.activaDeLinea(linea.id()).compareTo(linea.cantidad()) != 0) {
                    throw NegocioException.regla("El pedido " + codigo + " tiene líneas sin cobertura completa de stock reservado: "
                            + "abastézcalo con ABAPED antes de prepararlo.");
                }
            }
            cobros.exigirCobroResuelto(pedido, "preparar");
            cambioPedido.cambiar(pedido, EstadoPedido.EN_PREPARACION, solicitante.usuarioId(), null);
            notificador.avisar(pedido, TipoNotificacion.CAMBIO_ESTADO_PEDIDO, "Pedido en preparación",
                    "FEDER está preparando el pedido " + codigo + ".");
            return pedidos.buscarConLineas(codigo).orElseThrow();
        });
    }

    /**
     * R055, R027 y R083: despacho atómico. En una sola transacción la ENTREGA pasa de PENDIENTE a EN_CAMINO
     * (DELIVERY) o LISTA_PARA_RECOJO (RECOJO), todas las reservas pasan a CONSUMIDA con su SALIDA_VENTA y el
     * pedido a DESPACHADO. Si un lote está vencido o bloqueado no se consume nada.
     */
    public Despacho despachar(Solicitante solicitante, String codigo, OffsetDateTime fechaProgramada, String observacion) {
        Permisos.exigirPersonal(solicitante, "despachar pedidos");
        String nota = Textos.opcional(observacion);
        if (fechaProgramada != null && fechaProgramada.isBefore(ctx.ahora().minusMinutes(1))) {
            throw NegocioException.regla("La fecha programada de la entrega no puede estar en el pasado.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            PedidoCompleto completo = bloquear(codigo);
            Pedido pedido = completo.pedido();
            exigirEstado(pedido, EstadoPedido.EN_PREPARACION, "despachar");
            cobros.exigirCobroResuelto(pedido, "despachar");
            Entrega entrega = completo.entrega();
            EstadoEntrega destino = TransicionesEntrega.trasDespacho(entrega.getModalidad());
            // La entrega se actualiza antes que el inventario, según el orden de bloqueo de CU6.
            cambioEntrega.cambiar(entrega, destino, solicitante.usuarioId(), nota);
            if (fechaProgramada != null) {
                entregas.programar(entrega.getId(), fechaProgramada);
            }
            List<MovimientoInventario> salidas = inventario.consumirReservasDePedido(pedido.getId(), solicitante.usuarioId());
            cambioPedido.cambiar(pedido, EstadoPedido.DESPACHADO, solicitante.usuarioId(),
                    destino == EstadoEntrega.EN_CAMINO ? "Enviado a domicilio" : "Listo para recojo en FEDER");
            notificador.avisarConCorreo(pedido, TipoNotificacion.CAMBIO_ESTADO_PEDIDO,
                    destino == EstadoEntrega.EN_CAMINO ? "Tu pedido está en camino" : "Tu pedido está listo para recojo",
                    destino == EstadoEntrega.EN_CAMINO ? "El pedido " + codigo + " salió de FEDER y va camino a su domicilio"
                            + (fechaProgramada == null ? "." : "; entrega programada para el " + FechasBolivia.formatear(fechaProgramada) + ".")
                            : "El pedido " + codigo + " está listo: puede recogerlo en FEDER.");
            return new Despacho(pedidos.buscarConLineas(codigo).orElseThrow(), salidas);
        });
    }

    private PedidoCompleto bloquear(String codigo) {
        return pedidos.bloquearConLineas(codigo)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el pedido " + codigo + ". Consulte LISPED[\"*\"]."));
    }

    private static void exigirEstado(Pedido pedido, EstadoPedido esperado, String accion) {
        if (pedido.getEstado() != esperado) {
            throw NegocioException.estadoInvalido("Solo se puede " + accion + " un pedido " + esperado + "; el pedido "
                    + pedido.getCodigo() + " está " + pedido.getEstado() + ".");
        }
    }
}
