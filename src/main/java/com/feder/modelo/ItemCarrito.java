package com.feder.modelo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Producto y cantidad en el carrito persistente de un cliente. Tabla item_carrito. */
public class ItemCarrito {

    private Long id;
    private Long clienteId;
    private Long productoId;
    private BigDecimal cantidad;
    private OffsetDateTime fechaAgregado;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getClienteId() {
        return clienteId;
    }

    public void setClienteId(Long clienteId) {
        this.clienteId = clienteId;
    }

    public Long getProductoId() {
        return productoId;
    }

    public void setProductoId(Long productoId) {
        this.productoId = productoId;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public void setCantidad(BigDecimal cantidad) {
        this.cantidad = cantidad;
    }

    public OffsetDateTime getFechaAgregado() {
        return fechaAgregado;
    }

    public void setFechaAgregado(OffsetDateTime fechaAgregado) {
        this.fechaAgregado = fechaAgregado;
    }
}
