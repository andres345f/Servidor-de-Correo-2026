package com.feder.modelo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Devolución justificada de unidades despachadas. Tabla devolucion. */
public class Devolucion {

    private Long id;
    private Long movimientoInventarioId;
    private Long devolucionAnuladaId;
    private String codigo;
    private BigDecimal cantidad;
    private String motivo;
    private EstadoDevolucion estado;
    private ResolucionDevolucion resolucion;
    private BigDecimal cantidadAprobada;
    private BigDecimal cantidadRecibida;
    private BigDecimal cantidadApta;
    private BigDecimal importeReembolsable;
    private OffsetDateTime fechaSolicitud;
    private OffsetDateTime fechaRecepcion;
    private OffsetDateTime fechaInspeccion;
    private OffsetDateTime fechaResolucion;
    private OffsetDateTime fechaCierre;
    private OffsetDateTime fechaAnulacion;
    private String motivoAnulacion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getMovimientoInventarioId() {
        return movimientoInventarioId;
    }

    public void setMovimientoInventarioId(Long movimientoInventarioId) {
        this.movimientoInventarioId = movimientoInventarioId;
    }

    public Long getDevolucionAnuladaId() {
        return devolucionAnuladaId;
    }

    public void setDevolucionAnuladaId(Long devolucionAnuladaId) {
        this.devolucionAnuladaId = devolucionAnuladaId;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public void setCantidad(BigDecimal cantidad) {
        this.cantidad = cantidad;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public EstadoDevolucion getEstado() {
        return estado;
    }

    public void setEstado(EstadoDevolucion estado) {
        this.estado = estado;
    }

    public ResolucionDevolucion getResolucion() {
        return resolucion;
    }

    public void setResolucion(ResolucionDevolucion resolucion) {
        this.resolucion = resolucion;
    }

    public BigDecimal getCantidadAprobada() {
        return cantidadAprobada;
    }

    public void setCantidadAprobada(BigDecimal cantidadAprobada) {
        this.cantidadAprobada = cantidadAprobada;
    }

    public BigDecimal getCantidadRecibida() {
        return cantidadRecibida;
    }

    public void setCantidadRecibida(BigDecimal cantidadRecibida) {
        this.cantidadRecibida = cantidadRecibida;
    }

    public BigDecimal getCantidadApta() {
        return cantidadApta;
    }

    public void setCantidadApta(BigDecimal cantidadApta) {
        this.cantidadApta = cantidadApta;
    }

    public BigDecimal getImporteReembolsable() {
        return importeReembolsable;
    }

    public void setImporteReembolsable(BigDecimal importeReembolsable) {
        this.importeReembolsable = importeReembolsable;
    }

    public OffsetDateTime getFechaSolicitud() {
        return fechaSolicitud;
    }

    public void setFechaSolicitud(OffsetDateTime fechaSolicitud) {
        this.fechaSolicitud = fechaSolicitud;
    }

    public OffsetDateTime getFechaRecepcion() {
        return fechaRecepcion;
    }

    public void setFechaRecepcion(OffsetDateTime fechaRecepcion) {
        this.fechaRecepcion = fechaRecepcion;
    }

    public OffsetDateTime getFechaInspeccion() {
        return fechaInspeccion;
    }

    public void setFechaInspeccion(OffsetDateTime fechaInspeccion) {
        this.fechaInspeccion = fechaInspeccion;
    }

    public OffsetDateTime getFechaResolucion() {
        return fechaResolucion;
    }

    public void setFechaResolucion(OffsetDateTime fechaResolucion) {
        this.fechaResolucion = fechaResolucion;
    }

    public OffsetDateTime getFechaCierre() {
        return fechaCierre;
    }

    public void setFechaCierre(OffsetDateTime fechaCierre) {
        this.fechaCierre = fechaCierre;
    }

    public OffsetDateTime getFechaAnulacion() {
        return fechaAnulacion;
    }

    public void setFechaAnulacion(OffsetDateTime fechaAnulacion) {
        this.fechaAnulacion = fechaAnulacion;
    }

    public String getMotivoAnulacion() {
        return motivoAnulacion;
    }

    public void setMotivoAnulacion(String motivoAnulacion) {
        this.motivoAnulacion = motivoAnulacion;
    }
}
