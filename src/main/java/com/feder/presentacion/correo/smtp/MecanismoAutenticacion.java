package com.feder.presentacion.correo.smtp;

/** Mecanismos SASL admitidos por el cliente SMTP. */
public enum MecanismoAutenticacion {

    /** No se autentica (servidor local o retransmisión por IP). */
    NINGUNA,

    /** RFC 4616: usuario y contraseña en una sola línea Base64. */
    PLAIN,

    /** Mecanismo LOGIN: usuario y contraseña en dos pasos Base64. */
    LOGIN
}
