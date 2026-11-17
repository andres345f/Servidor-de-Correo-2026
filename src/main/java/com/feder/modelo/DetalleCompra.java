package com.feder.modelo;

import java.math.BigDecimal;

/** Línea de una compra: cantidad acordada de un lote de origen y cómo se resolvió. Tabla detalle_compra. */
public class DetalleCompra {

    private Long id;
    private Long compraId;
    private Long loteOrigenId;
    private BigDecimal cantidadComprada;
    private BigDecimal precioUnitario;
    private BigDecimal cantidadRecibida;
    private BigDecimal cantidadRechazada;
    private BigDecimal cantidadCancelada;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCompraId() {
        return compraId;
    }

    public void setCompraId(Long compraId) {
        this.compraId = compraId;
    }

    public Long getLoteOrigenId() {
        return loteOrigenId;
    }

    public void setLoteOrigenId(Long loteOrigenId) {
        this.loteOrigenId = loteOrigenId;
    }

    public BigDecimal getCantidadComprada() {
        return cantidadComprada;
    }

    public void setCantidadComprada(BigDecimal cantidadComprada) {
        this.cantidadComprada = cantidadComprada;
    }

    public BigDecimal getPrecioUnitario() {
        return precioUnitario;
    }

    public void setPrecioUnitario(BigDecimal precioUnitario) {
        this.precioUnitario = precioUnitario;
    }

    public BigDecimal getCantidadRecibida() {
        return cantidadRecibida;
    }

    public void setCantidadRecibida(BigDecimal cantidadRecibida) {
        this.cantidadRecibida = cantidadRecibida;
    }

    public BigDecimal getCantidadRechazada() {
        return cantidadRechazada;
    }

    public void setCantidadRechazada(BigDecimal cantidadRechazada) {
        this.cantidadRechazada = cantidadRechazada;
    }

    public BigDecimal getCantidadCancelada() {
        return cantidadCancelada;
    }

    public void setCantidadCancelada(BigDecimal cantidadCancelada) {
        this.cantidadCancelada = cantidadCancelada;
    }
}
