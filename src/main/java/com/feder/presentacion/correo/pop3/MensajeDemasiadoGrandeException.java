package com.feder.presentacion.correo.pop3;

import com.feder.presentacion.correo.CorreoException;

/**
 * El mensaje supera el tamaño máximo que el sistema acepta descargar. El procesador lo registra
 * en cuarentena para no intentar descargarlo de nuevo en cada ciclo.
 */
public class MensajeDemasiadoGrandeException extends CorreoException {

    private static final long serialVersionUID = 1L;

    public MensajeDemasiadoGrandeException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
