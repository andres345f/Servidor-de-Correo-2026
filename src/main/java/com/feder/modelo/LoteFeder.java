package com.feder.modelo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Existencia física de FEDER procedente de un detalle de compra. Tabla lote_feder. */
public class LoteFeder {

    private Long id;
    private Long detalleCompraId;
    private String codigo;
    private OffsetDateTime fechaIngreso;
    private BigDecimal cantidadDisponible;
    private BigDecimal cantidadReservada;
    private String ubicacionAlmacen;
    private EstadoLoteFeder estado;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getDetalleCompraId() {
        return detalleCompraId;
    }

    public void setDetalleCompraId(Long detalleCompraId) {
        this.detalleCompraId = detalleCompraId;
    }

    public String getCodigo() {
        return codigo;
    }

    public void setCodigo(String codigo) {
        this.codigo = codigo;
    }

    public OffsetDateTime getFechaIngreso() {
        return fechaIngreso;
    }

    public void setFechaIngreso(OffsetDateTime fechaIngreso) {
        this.fechaIngreso = fechaIngreso;
    }

    public BigDecimal getCantidadDisponible() {
        return cantidadDisponible;
    }

    public void setCantidadDisponible(BigDecimal cantidadDisponible) {
        this.cantidadDisponible = cantidadDisponible;
    }

    public BigDecimal getCantidadReservada() {
        return cantidadReservada;
    }

    public void setCantidadReservada(BigDecimal cantidadReservada) {
        this.cantidadReservada = cantidadReservada;
    }

    public String getUbicacionAlmacen() {
        return ubicacionAlmacen;
    }

    public void setUbicacionAlmacen(String ubicacionAlmacen) {
        this.ubicacionAlmacen = ubicacionAlmacen;
    }

    public EstadoLoteFeder getEstado() {
        return estado;
    }

    public void setEstado(EstadoLoteFeder estado) {
        this.estado = estado;
    }
}
