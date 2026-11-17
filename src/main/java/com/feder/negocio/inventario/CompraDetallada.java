package com.feder.negocio.inventario;

import com.feder.datos.compras.DetalleCompraVista;
import com.feder.datos.compras.ProductorCompraRepositorio.Proveedor;
import com.feder.modelo.CompraFeder;
import com.feder.negocio.catalogo.Importes;

import java.math.BigDecimal;
import java.util.List;

/** Compra con su productor y sus líneas, tal como se consulta en VERCMP. */
public record CompraDetallada(CompraFeder compra, Proveedor productor, List<DetalleCompraVista> lineas) {

    /** R006: importe a pagar = suma de round(cantidad_recibida x precio_unitario, 2); se paga solo lo aceptado. */
    public BigDecimal importeAPagar() {
        return lineas.stream()
                .map(l -> Importes.subtotal(l.detalle().getCantidadRecibida(), l.detalle().getPrecioUnitario()))
                .reduce(BigDecimal.ZERO.setScale(Importes.ESCALA), BigDecimal::add);
    }
}
