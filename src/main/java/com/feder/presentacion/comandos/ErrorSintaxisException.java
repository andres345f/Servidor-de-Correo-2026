package com.feder.presentacion.comandos;

/**
 * Error de sintaxis en el asunto. El mensaje está en español, es apto para enviarse al usuario
 * e incluye la posición (contada desde 1, en caracteres) y lo que se esperaba.
 */
public class ErrorSintaxisException extends Exception {

    private static final long serialVersionUID = 1L;

    private final int posicion;

    public ErrorSintaxisException(String mensaje, int posicion) {
        super(mensaje);
        this.posicion = posicion;
    }

    /** Posición del carácter problemático, contada desde 1. */
    public int posicion() {
        return posicion;
    }
}
