package com.feder.presentacion.correo;

import java.io.IOException;

/** Un bloque recibido supera el tamaño máximo permitido; la sesión debe cerrarse después. */
public class LimiteExcedidoException extends IOException {

    private static final long serialVersionUID = 1L;

    public LimiteExcedidoException(String mensaje) {
        super(mensaje);
    }
}
