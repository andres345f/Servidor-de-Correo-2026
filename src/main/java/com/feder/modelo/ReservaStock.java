package com.feder.modelo;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Unidades de un lote de FEDER apartadas para una línea de pedido. Tabla reserva_stock. */
public class ReservaStock {

    private Long id;
    private Long loteFederId;
    private Long detallePedidoId;
    private BigDecimal cantidad;
    private EstadoReserva estado;
    private OffsetDateTime fechaCreacion;
    private OffsetDateTime fechaExpiracion;
    private OffsetDateTime fechaCierre;

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

    public Long getDetallePedidoId() {
        return detallePedidoId;
    }

    public void setDetallePedidoId(Long detallePedidoId) {
        this.detallePedidoId = detallePedidoId;
    }

    public BigDecimal getCantidad() {
        return cantidad;
    }

    public void setCantidad(BigDecimal cantidad) {
        this.cantidad = cantidad;
    }

    public EstadoReserva getEstado() {
        return estado;
    }

    public void setEstado(EstadoReserva estado) {
        this.estado = estado;
    }

    public OffsetDateTime getFechaCreacion() {
        return fechaCreacion;
    }

    public void setFechaCreacion(OffsetDateTime fechaCreacion) {
        this.fechaCreacion = fechaCreacion;
    }

    public OffsetDateTime getFechaExpiracion() {
        return fechaExpiracion;
    }

    public void setFechaExpiracion(OffsetDateTime fechaExpiracion) {
        this.fechaExpiracion = fechaExpiracion;
    }

    public OffsetDateTime getFechaCierre() {
        return fechaCierre;
    }

    public void setFechaCierre(OffsetDateTime fechaCierre) {
        this.fechaCierre = fechaCierre;
    }
}
