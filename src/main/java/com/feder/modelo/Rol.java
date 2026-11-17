package com.feder.modelo;

/** Rol del catálogo cerrado de CU1. Tabla rol. */
public class Rol {

    private Long id;
    private TipoRol codigo;
    private String nombre;
    private String descripcion;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TipoRol getCodigo() {
        return codigo;
    }

    public void setCodigo(TipoRol codigo) {
        this.codigo = codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }
}
