package com.feder.datos.compras;

import com.feder.modelo.DetalleCompra;
import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;

/** Línea de compra con su lote de origen y su producto, para mostrarla y validar cantidades. */
public record DetalleCompraVista(DetalleCompra detalle, String codigoLoteOrigen, long productoId, String codigoProducto,
                                 String nombreProducto, UnidadMedida unidad) {

    /** pendiente = comprada - recibida - rechazada - cancelada (R014). */
    public BigDecimal pendiente() {
        return pendiente(detalle);
    }

    public static BigDecimal pendiente(DetalleCompra d) {
        return d.getCantidadComprada().subtract(d.getCantidadRecibida()).subtract(d.getCantidadRechazada())
                .subtract(d.getCantidadCancelada());
    }
}
