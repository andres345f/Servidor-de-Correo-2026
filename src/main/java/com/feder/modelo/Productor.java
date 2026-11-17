package com.feder.modelo;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Perfil de negocio del productor y su solicitud de alta. Tabla productor. */
public class Productor {

    private Long id;
    private Long usuarioId;
    private Long comunidadAgricolaId;
    private String codigoProductor;
    private EstadoSolicitudProductor estadoSolicitud;
    private OffsetDateTime fechaSolicitud;
    private OffsetDateTime fechaRevision;
    private String motivoRechazo;
    private String ubicacionActividad;
    private LocalDate fechaAfiliacion;
    private Boolean activo;

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

    public Long getComunidadAgricolaId() {
        return comunidadAgricolaId;
    }

    public void setComunidadAgricolaId(Long comunidadAgricolaId) {
        this.comunidadAgricolaId = comunidadAgricolaId;
    }

    public String getCodigoProductor() {
        return codigoProductor;
    }

    public void setCodigoProductor(String codigoProductor) {
        this.codigoProductor = codigoProductor;
    }

    public EstadoSolicitudProductor getEstadoSolicitud() {
        return estadoSolicitud;
    }

    public void setEstadoSolicitud(EstadoSolicitudProductor estadoSolicitud) {
        this.estadoSolicitud = estadoSolicitud;
    }

    public OffsetDateTime getFechaSolicitud() {
        return fechaSolicitud;
    }

    public void setFechaSolicitud(OffsetDateTime fechaSolicitud) {
        this.fechaSolicitud = fechaSolicitud;
    }

    public OffsetDateTime getFechaRevision() {
        return fechaRevision;
    }

    public void setFechaRevision(OffsetDateTime fechaRevision) {
        this.fechaRevision = fechaRevision;
    }

    public String getMotivoRechazo() {
        return motivoRechazo;
    }

    public void setMotivoRechazo(String motivoRechazo) {
        this.motivoRechazo = motivoRechazo;
    }

    public String getUbicacionActividad() {
        return ubicacionActividad;
    }

    public void setUbicacionActividad(String ubicacionActividad) {
        this.ubicacionActividad = ubicacionActividad;
    }

    public LocalDate getFechaAfiliacion() {
        return fechaAfiliacion;
    }

    public void setFechaAfiliacion(LocalDate fechaAfiliacion) {
        this.fechaAfiliacion = fechaAfiliacion;
    }

    public Boolean getActivo() {
        return activo;
    }

    public void setActivo(Boolean activo) {
        this.activo = activo;
    }
}
