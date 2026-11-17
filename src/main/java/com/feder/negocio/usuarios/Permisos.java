package com.feder.negocio.usuarios;

import com.feder.modelo.TipoRol;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

/**
 * Comprobaciones de autorización que repiten los servicios aunque el catálogo ya filtre por rol:
 * un servicio puede invocarse desde otro sitio y la regla debe cumplirse igual (R088).
 */
public final class Permisos {

    private Permisos() {
    }

    public static void exigirRol(Solicitante solicitante, TipoRol rol, String accion) {
        if (!solicitante.estaRegistrado() || !solicitante.tieneRol(rol)) {
            throw NegocioException.sinPermiso("Solo una cuenta activa con el rol " + rol + " vigente puede " + accion + ".");
        }
    }

    public static void exigirPersonalInterno(Solicitante solicitante, String accion) {
        if (!solicitante.estaRegistrado() || !solicitante.esPersonalInterno()) {
            throw NegocioException.sinPermiso("Solo el ADMINISTRADOR o PERSONAL_FEDER con rol vigente puede " + accion + ".");
        }
    }

    public static void exigirRegistrado(Solicitante solicitante) {
        if (!solicitante.estaRegistrado()) {
            throw new NegocioException(NegocioException.Codigo.NO_IDENTIFICADO,
                    "Su dirección no corresponde a una cuenta activa de FEDER.");
        }
    }

    /** Nadie decide sobre una solicitud o un acceso propios (R074, R088, R097). */
    public static void exigirDistintoDelTitular(Solicitante solicitante, long titularId, String mensaje) {
        if (solicitante.usuarioId() == titularId) {
            throw NegocioException.sinPermiso(mensaje);
        }
    }
}
