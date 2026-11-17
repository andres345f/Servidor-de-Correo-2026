package com.feder.modelo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Lote de producto terminado de origen, todavía en poder del productor. Tabla lote_produccion. */
public class LoteProduccion {

    private Long id;
    private Long produccionId;
    private String codigoLote;
    private LocalDate fechaElaboracion;
    private LocalDate fechaVencimiento;
    private BigDecimal cantidadProducida;
    private BigDecimal cantidadOfrecida;
    private BigDecimal precioOfrecido;
    private EstadoLoteProduccion estado;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProduccionId() {
        return produccionId;
    }

    public void setProduccionId(Long produccionId) {
        this.produccionId = produccionId;
    }

    public String getCodigoLote() {
        return codigoLote;
    }

    public void setCodigoLote(String codigoLote) {
        this.codigoLote = codigoLote;
    }

    public LocalDate getFechaElaboracion() {
        return fechaElaboracion;
    }

    public void setFechaElaboracion(LocalDate fechaElaboracion) {
        this.fechaElaboracion = fechaElaboracion;
    }

    public LocalDate getFechaVencimiento() {
        return fechaVencimiento;
    }

    public void setFechaVencimiento(LocalDate fechaVencimiento) {
        this.fechaVencimiento = fechaVencimiento;
    }

    public BigDecimal getCantidadProducida() {
        return cantidadProducida;
    }

    public void setCantidadProducida(BigDecimal cantidadProducida) {
        this.cantidadProducida = cantidadProducida;
    }

    public BigDecimal getCantidadOfrecida() {
        return cantidadOfrecida;
    }

    public void setCantidadOfrecida(BigDecimal cantidadOfrecida) {
        this.cantidadOfrecida = cantidadOfrecida;
    }

    public BigDecimal getPrecioOfrecido() {
        return precioOfrecido;
    }

    public void setPrecioOfrecido(BigDecimal precioOfrecido) {
        this.precioOfrecido = precioOfrecido;
    }

    public EstadoLoteProduccion getEstado() {
        return estado;
    }

    public void setEstado(EstadoLoteProduccion estado) {
        this.estado = estado;
    }
}
