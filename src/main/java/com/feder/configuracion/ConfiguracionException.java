package com.feder.configuracion;

/** Error de configuración: falta un valor o su formato es incorrecto. Nunca incluye secretos. */
public class ConfiguracionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConfiguracionException(String mensaje) {
        super(mensaje);
    }
}
