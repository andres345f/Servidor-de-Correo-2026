package com.feder.modelo;

import java.math.BigDecimal;

/** Línea de pedido con la cantidad y el precio aplicados en la venta. Tabla detalle_pedido. */
public class DetallePedido {

    private Long id;
    private Long pedidoId;
    private Long productoId;
    private Long produccionId;
    private BigDecimal cantidad;
    private BigDecimal precioUnitario;
    private BigDecimal subtotal;
    private String descripcionVenta;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPedidoId() {
        return pedidoId;
    }

    public void setPedidoId(Long pedidoId) {
        this.pedidoId = pedidoId;
    }

    public Long getProductoId() {
        return productoId;
    }

    public void setProductoId(Long productoId) {
        this.productoId = productoId;
    }

    public Long getProduccionId() {
        return produccionId;
    }

    public void setProduccionId(Long produccionId) {
        this.produccionId = produccionId;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public void setCantidad(BigDecimal cantidad) {
        this.cantidad = cantidad;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public void setPrecioUnitario(BigDecimal precioUnitario) {
        this.precioUnitario = precioUnitario;
    }

    public BigDecimal getSubtotal() {
        return subtotal;
    }

    public void setSubtotal(BigDecimal subtotal) {
        this.subtotal = subtotal;
    }

    public String getDescripcionVenta() {
        return descripcionVenta;
    }

    public void setDescripcionVenta(String descripcionVenta) {
        this.descripcionVenta = descripcionVenta;
    }
}
