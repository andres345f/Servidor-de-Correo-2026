package com.feder.modelo;

import java.time.OffsetDateTime;

/** Registro de auditoría de una operación sensible (solo inserción). Tabla bitacora. */
public class Bitacora {

    private Long id;
    private Long usuarioId;
    private OffsetDateTime fecha;
    private AccionBitacora accion;
    private String entidad;
    private Long entidadId;
    private String detalle;
    private String direccionIp;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUsuarioId() {
        return usuarioId;
    }

    public void setUsuarioId(Long usuarioId) {
        this.usuarioId = usuarioId;
    }

    public OffsetDateTime getFecha() {
        return fecha;
    }

    public void setFecha(OffsetDateTime fecha) {
        this.fecha = fecha;
    }

    public AccionBitacora getAccion() {
        return accion;
    }

    public void setAccion(AccionBitacora accion) {
        this.accion = accion;
    }

    public String getEntidad() {
        return entidad;
    }

    public void setEntidad(String entidad) {
        this.entidad = entidad;
    }

    public Long getEntidadId() {
        return entidadId;
    }

    public void setEntidadId(Long entidadId) {
        this.entidadId = entidadId;
    }

    public String getDetalle() {
        return detalle;
    }

    public void setDetalle(String detalle) {
        this.detalle = detalle;
    }

    public String getDireccionIp() {
        return direccionIp;
    }

    public void setDireccionIp(String direccionIp) {
        this.direccionIp = direccionIp;
    }
}
