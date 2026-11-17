package com.feder.herramientas.correolocal;

import java.util.Objects;

/** Mensaje guardado en un buzón local: identificador UIDL estable y bytes crudos. */
public final class MensajeAlmacenado {

    private final String uidl;
    private final byte[] contenido;

    MensajeAlmacenado(String uidl, byte[] contenido) {
        this.uidl = Objects.requireNonNull(uidl, "uidl");
        this.contenido = Objects.requireNonNull(contenido, "contenido").clone();
    }

    public String uidl() {
        return uidl;
    }

    /** Copia de los bytes: modificarla no altera el mensaje guardado. */
    public byte[] contenido() {
        return contenido.clone();
    }

    public int tamano() {
        return contenido.length;
    }
}
