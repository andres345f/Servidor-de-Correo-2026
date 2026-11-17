package com.feder.negocio.pagos;

import com.feder.datos.pagos.PagoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoPago;
import com.feder.modelo.MetodoPago;
import com.feder.modelo.Pago;
import com.feder.modelo.Pedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Situación de cobro de un pedido, para las transiciones que dependen del pago (R047, R049, R057). El
 * llamador tiene bloqueada la fila del PEDIDO, de modo que las sumas no cambian mientras decide.
 */
public class CobrosPedido {

    private final ContextoNegocio ctx;
    private final PagoRepositorio pagos;

    public CobrosPedido(ContextoNegocio ctx, PagoRepositorio pagos) {
        this.ctx = ctx;
        this.pagos = pagos;
    }

    public BigDecimal pagado(long pedidoId) {
        return pagos.sumaPagada(pedidoId);
    }

    public boolean pagadoPorCompleto(Pedido pedido) {
        return pagado(pedido.getId()).compareTo(pedido.getTotal()) == 0;
    }

    /** PAGO EFECTIVO PENDIENTE por el saldo exacto: el cobro se hará en la entrega (R047 (2) y (4)). */
    public Optional<Pago> contraEntrega(Pedido pedido) {
        BigDecimal saldo = pedido.getTotal().subtract(pagado(pedido.getId()));
        return pagos.pendienteDePedido(pedido.getId())
                .filter(p -> p.getMetodo() == MetodoPago.EFECTIVO && p.getMonto().compareTo(saldo) == 0);
    }

    /** R047 (4): pagado por completo o con el saldo acordado en efectivo contra entrega. */
    public void exigirCobroResuelto(Pedido pedido, String accion) {
        if (!pagadoPorCompleto(pedido) && contraEntrega(pedido).isEmpty()) {
            BigDecimal pagado = pagado(pedido.getId());
            throw NegocioException.regla("No se puede " + accion + " el pedido " + pedido.getCodigo() + ": lleva pagado Bs "
                    + Importes.formatear(pagado) + " de Bs " + Importes.formatear(pedido.getTotal())
                    + " y no tiene un pago en efectivo contra entrega por el saldo. Consulte LISPAG[\"" + pedido.getCodigo() + "\"].");
        }
    }

    /**
     * R049 y R057: al entregar, el efectivo contra entrega pasa a PAGADO en la misma transacción, con el
     * personal que recibió el dinero como autor. Exige que con ello el pedido quede pagado por completo.
     *
     * @return el pago en efectivo confirmado, si lo había.
     */
    public Optional<Pago> cobrarEnLaEntrega(Pedido pedido, long receptorId) {
        if (pagadoPorCompleto(pedido)) {
            return Optional.empty();
        }
        Pago efectivo = contraEntrega(pedido).orElseThrow(() -> NegocioException.regla("El pedido " + pedido.getCodigo()
                + " no puede pasar a ENTREGADO: lo pagado (Bs " + Importes.formatear(pagado(pedido.getId()))
                + ") no iguala el total (Bs " + Importes.formatear(pedido.getTotal()) + ") y no hay efectivo contra entrega por el saldo."));
        Pago bloqueado = pagos.bloquear(efectivo.getId()).orElseThrow();
        if (bloqueado.getEstado() != EstadoPago.PENDIENTE || !pagos.confirmar(bloqueado.getId(), ctx.ahora(), null)) {
            throw NegocioException.estadoInvalido("El pago en efectivo del pedido cambió de estado durante la entrega.");
        }
        ctx.bitacora().registrar(receptorId, AccionBitacora.MODIFICAR, "PAGO", bloqueado.getId(), DetalleJson.nuevo()
                .con("estado_anterior", EstadoPago.PENDIENTE).con("estado_nuevo", EstadoPago.PAGADO)
                .con("verificacion", "EFECTIVO_RECIBIDO_EN_LA_ENTREGA").con("monto", bloqueado.getMonto()));
        bloqueado.setEstado(EstadoPago.PAGADO);
        return Optional.of(bloqueado);
    }
}
