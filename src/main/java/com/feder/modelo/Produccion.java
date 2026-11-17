package com.feder.modelo;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Producción de un producto terminado por un productor, con su procedencia histórica. Tabla produccion. */
public class Produccion {

    private Long id;
    private Long productorId;
    private Long productoId;
    private Long comunidadOrigenId;
    private String codigo;
    private LocalDate fechaInicio;
    private LocalDate fechaDisponibilidadEstimada;
    private BigDecimal cantidadEstimada;
    private String ubicacionProduccion;
    private EstadoProduccion estado;
    private String observacion;
    private String nombreComunidadOrigen;
    private String municipioOrigen;
    private String departamentoOrigen;

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

    public Long getProductoId() {
        return productoId;
    }

    public void setProductoId(Long productoId) {
        this.productoId = productoId;
    }

    public Long getComunidadOrigenId() {
        return comunidadOrigenId;
    }

    public void setComunidadOrigenId(Long comunidadOrigenId) {
        this.comunidadOrigenId = comunidadOrigenId;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public LocalDate getFechaInicio() {
        return fechaInicio;
    }

    public void setFechaInicio(LocalDate fechaInicio) {
        this.fechaInicio = fechaInicio;
    }

    public LocalDate getFechaDisponibilidadEstimada() {
        return fechaDisponibilidadEstimada;
    }

    public void setFechaDisponibilidadEstimada(LocalDate fechaDisponibilidadEstimada) {
        this.fechaDisponibilidadEstimada = fechaDisponibilidadEstimada;
    }

    public BigDecimal getCantidadEstimada() {
        return cantidadEstimada;
    }

    public void setCantidadEstimada(BigDecimal cantidadEstimada) {
        this.cantidadEstimada = cantidadEstimada;
    }

    public String getUbicacionProduccion() {
        return ubicacionProduccion;
    }

    public void setUbicacionProduccion(String ubicacionProduccion) {
        this.ubicacionProduccion = ubicacionProduccion;
    }

    public EstadoProduccion getEstado() {
        return estado;
    }

    public void setEstado(EstadoProduccion estado) {
        this.estado = estado;
    }

    public String getObservacion() {
        return observacion;
    }

    public void setObservacion(String observacion) {
        this.observacion = observacion;
    }

    public String getNombreComunidadOrigen() {
        return nombreComunidadOrigen;
    }

    public void setNombreComunidadOrigen(String nombreComunidadOrigen) {
        this.nombreComunidadOrigen = nombreComunidadOrigen;
    }

    public String getMunicipioOrigen() {
        return municipioOrigen;
    }

    public void setMunicipioOrigen(String municipioOrigen) {
        this.municipioOrigen = municipioOrigen;
    }

    public String getDepartamentoOrigen() {
        return departamentoOrigen;
    }

    public void setDepartamentoOrigen(String departamentoOrigen) {
        this.departamentoOrigen = departamentoOrigen;
    }
}
