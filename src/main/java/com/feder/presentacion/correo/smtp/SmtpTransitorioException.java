package com.feder.presentacion.correo.smtp;

import com.feder.presentacion.correo.CorreoException;

/** Rechazo temporal (código 4xx): el envío puede reintentarse más tarde. */
public class SmtpTransitorioException extends CorreoException {

    private static final long serialVersionUID = 1L;

    private final int codigo;

    public SmtpTransitorioException(String mensaje, int codigo, String respuestaServidor) {
        super(mensaje, respuestaServidor);
        this.codigo = codigo;
    }

    public int codigo() {
        return codigo;
    }
}
