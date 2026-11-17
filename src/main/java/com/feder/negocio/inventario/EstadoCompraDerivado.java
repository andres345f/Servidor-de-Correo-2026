package com.feder.negocio.inventario;

import com.feder.datos.compras.DetalleCompraVista;
import com.feder.modelo.DetalleCompra;
import com.feder.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.util.List;

/**
 * COMPRA_FEDER «Estado coherente» (R005): el estado se deriva de las cantidades de los detalles.
 * REGISTRADA: nada aceptado ni rechazado y queda pendiente. RECIBIDA_PARCIAL: algo aceptado o rechazado
 * y queda pendiente. RECIBIDA: sin pendiente y con algo aceptado. CANCELADA: sin pendiente y nada aceptado.
 */
public final class EstadoCompraDerivado {

    private EstadoCompraDerivado() {
    }

    public static EstadoCompra derivar(List<DetalleCompra> detalles) {
        boolean quedaPendiente = false;
        boolean huboRecepcion = false;
        BigDecimal aceptado = BigDecimal.ZERO;
        for (DetalleCompra d : detalles) {
            quedaPendiente |= DetalleCompraVista.pendiente(d).signum() > 0;
            huboRecepcion |= d.getCantidadRecibida().signum() > 0 || d.getCantidadRechazada().signum() > 0;
            aceptado = aceptado.add(d.getCantidadRecibida());
        }
        if (quedaPendiente) {
            return huboRecepcion ? EstadoCompra.RECIBIDA_PARCIAL : EstadoCompra.REGISTRADA;
        }
        return aceptado.signum() > 0 ? EstadoCompra.RECIBIDA : EstadoCompra.CANCELADA;
    }
}
