package com.feder.negocio.comun;

import com.feder.modelo.TipoRol;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Quién pide la operación.
 *
 * Se construye a partir de la dirección del remitente. Esa dirección NO prueba la identidad:
 * por eso las respuestas se envían siempre a la dirección registrada del usuario y las
 * operaciones que modifican datos exigen una confirmación por correo (ver ServicioConfirmacion).
 */
public final class Solicitante {

    private final Long usuarioId;
    private final String correo;
    private final String nombre;
    private final Set<TipoRol> roles;

    private Solicitante(Long usuarioId, String correo, String nombre, Set<TipoRol> roles) {
        this.usuarioId = usuarioId;
        this.correo = correo;
        this.nombre = nombre;
        this.roles = roles.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(roles));
    }

    /** Usuario registrado y activo, con sus roles vigentes. */
    public static Solicitante registrado(long usuarioId, String correo, String nombre, Set<TipoRol> rolesVigentes) {
        return new Solicitante(usuarioId, correo, nombre, rolesVigentes);
    }

    /** Remitente que no corresponde a ningún usuario activo. Solo puede usar comandos públicos. */
    public static Solicitante anonimo(String correo) {
        return new Solicitante(null, correo, null, Collections.emptySet());
    }

    public boolean estaRegistrado() {
        return usuarioId != null;
    }

    public long usuarioId() {
        if (usuarioId == null) {
            throw NegocioException.sinPermiso("Debe estar registrado en FEDER para realizar esta operación.");
        }
        return usuarioId;
    }

    public String correo() {
        return correo;
    }

    public String nombre() {
        return nombre;
    }

    public Set<TipoRol> roles() {
        return roles;
    }

    public boolean tieneRol(TipoRol rol) {
        return roles.contains(rol);
    }

    public boolean tieneAlgunRol(Set<TipoRol> requeridos) {
        return requeridos.stream().anyMatch(roles::contains);
    }

    /** Personal interno con visibilidad total: ADMINISTRADOR o PERSONAL_FEDER. */
    public boolean esPersonalInterno() {
        return tieneRol(TipoRol.ADMINISTRADOR) || tieneRol(TipoRol.PERSONAL_FEDER);
    }
}
