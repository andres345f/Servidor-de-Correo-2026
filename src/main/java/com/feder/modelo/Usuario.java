package com.feder.modelo;

import java.time.OffsetDateTime;

/** Cuenta de acceso de una persona y sus datos de identificación. Tabla usuario. */
public class Usuario {

    private Long id;
    private String correo;
    private String passwordHash;
    private String nombres;
    private String apellidos;
    private Integer ci;
    private String ciComplemento;
    private Integer telefono;
    private Boolean activo;
    private OffsetDateTime fechaRegistro;
    private OffsetDateTime ultimoAcceso;
    private OffsetDateTime fechaAceptacionTerminos;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCorreo() {
        return correo;
    }

    public void setCorreo(String correo) {
        this.correo = correo;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getNombres() {
        return nombres;
    }

    public void setNombres(String nombres) {
        this.nombres = nombres;
    }

    public String getApellidos() {
        return apellidos;
    }

    public void setApellidos(String apellidos) {
        this.apellidos = apellidos;
    }

    public Integer getCi() {
        return ci;
    }

    public void setCi(Integer ci) {
        this.ci = ci;
    }

    public String getCiComplemento() {
        return ciComplemento;
    }

    public void setCiComplemento(String ciComplemento) {
        this.ciComplemento = ciComplemento;
    }

    public Integer getTelefono() {
        return telefono;
    }

    public void setTelefono(Integer telefono) {
        this.telefono = telefono;
    }

    public Boolean getActivo() {
        return activo;
    }

    public void setActivo(Boolean activo) {
        this.activo = activo;
    }

    public OffsetDateTime getFechaRegistro() {
        return fechaRegistro;
    }

    public void setFechaRegistro(OffsetDateTime fechaRegistro) {
        this.fechaRegistro = fechaRegistro;
    }

    public OffsetDateTime getUltimoAcceso() {
        return ultimoAcceso;
    }

    public void setUltimoAcceso(OffsetDateTime ultimoAcceso) {
        this.ultimoAcceso = ultimoAcceso;
    }

    public OffsetDateTime getFechaAceptacionTerminos() {
        return fechaAceptacionTerminos;
    }

    public void setFechaAceptacionTerminos(OffsetDateTime fechaAceptacionTerminos) {
        this.fechaAceptacionTerminos = fechaAceptacionTerminos;
    }
}
