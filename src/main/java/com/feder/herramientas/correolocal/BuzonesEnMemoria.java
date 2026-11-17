package com.feder.herramientas.correolocal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Buzones en memoria compartidos por los servidores SMTP y POP3 locales.
 *
 * Las direcciones se normalizan a minúsculas. Cada buzón admite una sola sesión POP3 a la vez
 * (reservar/liberar), como sugiere RFC 1939 para el estado TRANSACTION.
 */
public final class BuzonesEnMemoria {

    private final ConcurrentMap<String, Buzon> buzones = new ConcurrentHashMap<>();

    /** Guarda el mensaje y devuelve su UIDL. */
    public String entregar(String direccion, byte[] contenido) {
        String uidl = UUID.randomUUID().toString();
        buzon(direccion).agregar(new MensajeAlmacenado(uidl, contenido));
        return uidl;
    }

    /** Copia del contenido actual del buzón, en orden de llegada. */
    public List<MensajeAlmacenado> mensajes(String direccion) {
        return buzon(direccion).copia();
    }

    public int cantidad(String direccion) {
        return buzon(direccion).copia().size();
    }

    /** Elimina los mensajes indicados (se llama al terminar una sesión POP3 con QUIT). */
    public void eliminar(String direccion, Collection<String> uidls) {
        buzon(direccion).eliminar(new HashSet<>(uidls));
    }

    /** Intenta reservar el buzón para una sesión POP3; false si otra sesión lo tiene. */
    public boolean reservar(String direccion) {
        return buzon(direccion).reservar();
    }

    public void liberar(String direccion) {
        buzon(direccion).liberar();
    }

    private Buzon buzon(String direccion) {
        Objects.requireNonNull(direccion, "direccion");
        return buzones.computeIfAbsent(direccion.strip().toLowerCase(Locale.ROOT), clave -> new Buzon());
    }

    private static final class Buzon {
        private final List<MensajeAlmacenado> mensajes = new ArrayList<>();
        private boolean reservado;

        synchronized void agregar(MensajeAlmacenado mensaje) {
            mensajes.add(mensaje);
        }

        synchronized List<MensajeAlmacenado> copia() {
            return List.copyOf(mensajes);
        }

        synchronized void eliminar(Set<String> uidls) {
            mensajes.removeIf(m -> uidls.contains(m.uidl()));
        }

        synchronized boolean reservar() {
            if (reservado) {
                return false;
            }
            reservado = true;
            return true;
        }

        synchronized void liberar() {
            reservado = false;
        }
    }
}
