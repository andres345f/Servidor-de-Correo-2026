package com.feder.presentacion.correo.smtp;

import com.feder.presentacion.correo.CorreoException;

/** Rechazo definitivo (código 5xx): reintentar el mismo envío no sirve. */
public class SmtpPermanenteException extends CorreoException {

    private static final long serialVersionUID = 1L;

    private final int codigo;

    public SmtpPermanenteException(String mensaje, int codigo, String respuestaServidor) {
        super(mensaje, respuestaServidor);
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }
}
