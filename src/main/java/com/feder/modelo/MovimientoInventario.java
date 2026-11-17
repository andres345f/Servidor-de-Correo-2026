package com.feder.modelo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Entrada o salida física e inmutable de un lote de FEDER. Tabla movimiento_inventario. */
public class MovimientoInventario {

    private Long id;
    private Long loteFederId;
    private Long usuarioId;
    private Long reservaId;
    private Long devolucionId;
    private TipoMovimiento tipo;
    private BigDecimal cantidad;
    private OffsetDateTime fecha;
    private String motivo;
    private String claveOperacion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLoteFederId() {
        return loteFederId;
    }

    public void setLoteFederId(Long loteFederId) {
        this.loteFederId = loteFederId;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(Long usuarioId) {
        this.usuarioId = usuarioId;
    }

    public Long getReservaId() {
        return reservaId;
    }

    public void setReservaId(Long reservaId) {
        this.reservaId = reservaId;
    }

    public Long getDevolucionId() {
        return devolucionId;
    }

    public void setDevolucionId(Long devolucionId) {
        this.devolucionId = devolucionId;
    }

    public TipoMovimiento getTipo() {
        return tipo;
    }

    public void setTipo(TipoMovimiento tipo) {
        this.tipo = tipo;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public void setCantidad(BigDecimal cantidad) {
        this.cantidad = cantidad;
    }

    public OffsetDateTime getFecha() {
        return fecha;
    }

    public void setFecha(OffsetDateTime fecha) {
        this.fecha = fecha;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public String getClaveOperacion() {
        return claveOperacion;
    }

    public void setClaveOperacion(String claveOperacion) {
        this.claveOperacion = claveOperacion;
    }
}
