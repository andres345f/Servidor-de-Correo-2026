package com.feder.modelo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Reintegro de dinero al cliente sobre un pago cobrado. Tabla reembolso. */
public class Reembolso {

    private Long id;
    private Long pagoId;
    private Long devolucionId;
    private BigDecimal monto;
    private EstadoReembolso estado;
    private String motivo;
    private OffsetDateTime fechaSolicitud;
    private OffsetDateTime fechaEjecucion;
    private String referenciaExterna;
    private String claveIdempotencia;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getPagoId() {
        return pagoId;
    }

    public void setPagoId(Long pagoId) {
        this.pagoId = pagoId;
    }

    public Long getDevolucionId() {
        return devolucionId;
    }

    public void setDevolucionId(Long devolucionId) {
        this.devolucionId = devolucionId;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public void setMonto(BigDecimal monto) {
        this.monto = monto;
    }

    public EstadoReembolso getEstado() {
        return estado;
    }

    public void setEstado(EstadoReembolso estado) {
        this.estado = estado;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public OffsetDateTime getFechaSolicitud() {
        return fechaSolicitud;
    }

    public void setFechaSolicitud(OffsetDateTime fechaSolicitud) {
        this.fechaSolicitud = fechaSolicitud;
    }

    public OffsetDateTime getFechaEjecucion() {
        return fechaEjecucion;
    }

    public void setFechaEjecucion(OffsetDateTime fechaEjecucion) {
        this.fechaEjecucion = fechaEjecucion;
    }

    public String getReferenciaExterna() {
        return referenciaExterna;
    }

    public void setReferenciaExterna(String referenciaExterna) {
        this.referenciaExterna = referenciaExterna;
    }

    public String getClaveIdempotencia() {
        return claveIdempotencia;
    }

    public void setClaveIdempotencia(String claveIdempotencia) {
        this.claveIdempotencia = claveIdempotencia;
    }
}
