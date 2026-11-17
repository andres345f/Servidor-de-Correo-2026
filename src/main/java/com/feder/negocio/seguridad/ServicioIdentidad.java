package com.feder.negocio.seguridad;

import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.comun.Solicitante;

import java.util.Locale;

/**
 * Convierte la dirección del remitente en un {@link Solicitante}.
 *
 * Importante: el encabezado From puede falsificarse. Esta clase solo determina a qué cuenta
 * se refiere el correo; la seguridad se apoya en dos reglas aplicadas en otras clases:
 * las respuestas se envían a la dirección registrada y las escrituras exigen confirmación.
 */
public class ServicioIdentidad {

    private final IdentidadRepositorio repositorio;

    public ServicioIdentidad(IdentidadRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    /** Debe llamarse dentro de una transacción. Los roles se consultan en cada petición (R088). */
    public Solicitante resolver(String remitente) {
        String correo = remitente == null ? "" : remitente.trim();
        // La búsqueda no distingue mayúsculas (R090), pero la respuesta se envía a la dirección
        // EXACTA registrada en la cuenta: así nunca se entrega a un buzón distinto del titular.
        return repositorio.buscarCuentaActiva(correo.toLowerCase(Locale.ROOT))
                .map(cuenta -> Solicitante.registrado(cuenta.id, cuenta.correo, cuenta.nombreCompleto,
                        repositorio.rolesVigentes(cuenta.id)))
                .orElseGet(() -> Solicitante.anonimo(correo));
    }
}
