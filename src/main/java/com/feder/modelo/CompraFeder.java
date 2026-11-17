package com.feder.modelo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Adquisición de FEDER a un productor. Tabla compra_feder. */
public class CompraFeder {

    private Long id;
    private Long productorId;
    private String codigo;
    private OffsetDateTime fechaCompra;
    private EstadoCompra estado;
    private BigDecimal total;
    private OffsetDateTime fechaPagoProductor;
    private String observacion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProductorId() {
        return productorId;
    }

    public void setProductorId(Long productorId) {
        this.productorId = productorId;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public OffsetDateTime getFechaCompra() {
        return fechaCompra;
    }

    public void setFechaCompra(OffsetDateTime fechaCompra) {
        this.fechaCompra = fechaCompra;
    }

    public EstadoCompra getEstado() {
        return estado;
    }

    public void setEstado(EstadoCompra estado) {
        this.estado = estado;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public void setTotal(BigDecimal total) {
        this.total = total;
    }

    public OffsetDateTime getFechaPagoProductor() {
        return fechaPagoProductor;
    }

    public void setFechaPagoProductor(OffsetDateTime fechaPagoProductor) {
        this.fechaPagoProductor = fechaPagoProductor;
    }

    public String getObservacion() {
        return observacion;
    }

    public void setObservacion(String observacion) {
        this.observacion = observacion;
    }
}
