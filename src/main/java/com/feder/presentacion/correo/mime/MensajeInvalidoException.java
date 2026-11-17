package com.feder.presentacion.correo.mime;

/** El mensaje recibido no se puede interpretar de forma segura (cabeceras duplicadas, remitente inválido...). */
public class MensajeInvalidoException extends Exception {

    private static final long serialVersionUID = 1L;

    public MensajeInvalidoException(String mensaje) {
        super(mensaje);
    }
}
