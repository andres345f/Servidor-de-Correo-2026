package com.feder.presentacion.correo;

/**
 * Forma de proteger el canal con el servidor de correo.
 */
public enum TransporteSeguro {

    /** Texto plano. Solo es aceptable en el entorno local (127.0.0.1). */
    NINGUNO,

    /** Conexión en claro que se eleva a TLS con STLS (POP3) o STARTTLS (SMTP) antes de autenticar. */
    STARTTLS,

    /** TLS implícito desde el primer byte (POP3 en 995, SMTP en 465). */
    TLS
}
