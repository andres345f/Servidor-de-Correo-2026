package com.feder.presentacion.correo.pop3;

/** Respuesta de STAT: cantidad de mensajes y tamaño total en octetos. */
public record EstadoBuzon(int cantidad, long octetos) {
}
