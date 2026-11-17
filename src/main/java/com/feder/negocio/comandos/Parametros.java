package com.feder.negocio.comandos;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;

/**
 * Parámetros de un comando ya validados y convertidos a su tipo Java.
 * Se accede por nombre; un parámetro opcional vacío se devuelve como null.
 */
public final class Parametros {

    /** Marca interna para el valor "*" (todos). */
    public static final Object COMODIN = new Object() {
        @Override
        public String toString() {
            return "*";
        }
    };

    private final Map<String, Object> valores;

    public Parametros(Map<String, Object> valores) {
        this.valores = Collections.unmodifiableMap(valores);
    }

    public boolean esComodin(String nombre) {
        return obtener(nombre) == COMODIN;
    }

    public boolean tieneValor(String nombre) {
        Object valor = obtener(nombre);
        return valor != null && valor != COMODIN;
    }

    public String texto(String nombre) {
        return (String) valor(nombre);
    }

    public Long entero(String nombre) {
        return (Long) valor(nombre);
    }

    public BigDecimal decimal(String nombre) {
        return (BigDecimal) valor(nombre);
    }

    public LocalDate fecha(String nombre) {
        return (LocalDate) valor(nombre);
    }

    public OffsetDateTime fechaHora(String nombre) {
        return (OffsetDateTime) valor(nombre);
    }

    public Boolean booleano(String nombre) {
        return (Boolean) valor(nombre);
    }

    public <E extends Enum<E>> E enumeracion(String nombre, Class<E> tipo) {
        Object valor = valor(nombre);
        return valor == null ? null : tipo.cast(valor);
    }

    private Object valor(String nombre) {
        Object valor = obtener(nombre);
        return valor == COMODIN ? null : valor;
    }

    private Object obtener(String nombre) {
        if (!valores.containsKey(nombre)) {
            throw new IllegalArgumentException("El comando no declara el parámetro '" + nombre + "'");
        }
        return valores.get(nombre);
    }
}
