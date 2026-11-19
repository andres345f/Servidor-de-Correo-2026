package com.feder.pagofacil;

/** Fallo de comunicación con PagoFácil o respuesta de error del proveedor. Nunca contiene los tokens. */
public class PagoFacilException extends RuntimeException {

    public PagoFacilException(String mensaje) {
        super(mensaje);
    }

    public PagoFacilException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
