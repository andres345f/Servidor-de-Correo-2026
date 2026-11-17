package com.feder.modelo;

import java.time.OffsetDateTime;

/** Concesión de un rol a una cuenta, con su ciclo de vida. Tabla usuario_rol. */
public class UsuarioRol {

    private Long id;
    private Long usuarioId;
    private Long rolId;
    private Long asignadoPorId;
    private Long revocadoPorId;
    private EstadoConcesion estado;
    private OffsetDateTime fechaAsignacion;
    private OffsetDateTime fechaActivacion;
    private OffsetDateTime fechaExpiracion;
    private OffsetDateTime fechaRevocacion;
    private String motivo;
    private String motivoRevocacion;

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

    public Long getRolId() {
        return rolId;
    }

    public void setRolId(Long rolId) {
        this.rolId = rolId;
    }

    public Long getAsignadoPorId() {
        return asignadoPorId;
    }

    public void setAsignadoPorId(Long asignadoPorId) {
        this.asignadoPorId = asignadoPorId;
    }

    public Long getRevocadoPorId() {
        return revocadoPorId;
    }

    public void setRevocadoPorId(Long revocadoPorId) {
        this.revocadoPorId = revocadoPorId;
    }

    public EstadoConcesion getEstado() {
        return estado;
    }

    public void setEstado(EstadoConcesion estado) {
        this.estado = estado;
    }

    public OffsetDateTime getFechaAsignacion() {
        return fechaAsignacion;
    }

    public void setFechaAsignacion(OffsetDateTime fechaAsignacion) {
        this.fechaAsignacion = fechaAsignacion;
    }

    public OffsetDateTime getFechaActivacion() {
        return fechaActivacion;
    }

    public void setFechaActivacion(OffsetDateTime fechaActivacion) {
        this.fechaActivacion = fechaActivacion;
    }

    public OffsetDateTime getFechaExpiracion() {
        return fechaExpiracion;
    }

    public void setFechaExpiracion(OffsetDateTime fechaExpiracion) {
        this.fechaExpiracion = fechaExpiracion;
    }

    public OffsetDateTime getFechaRevocacion() {
        return fechaRevocacion;
    }

    public void setFechaRevocacion(OffsetDateTime fechaRevocacion) {
        this.fechaRevocacion = fechaRevocacion;
    }

    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(String motivo) {
        this.motivo = motivo;
    }

    public String getMotivoRevocacion() {
        return motivoRevocacion;
    }

    public void setMotivoRevocacion(String motivoRevocacion) {
        this.motivoRevocacion = motivoRevocacion;
    }
}
