package com.feder.negocio;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.mensajeria.ServicioAvisos;
import com.feder.negocio.notificaciones.ServicioNotificaciones;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Dependencias comunes que reciben los módulos de los casos de uso: el gestor de transacciones,
 * el reloj (inyectable para pruebas) y los servicios transversales de bitácora, notificaciones
 * y avisos por correo a terceros.
 */
public final class ContextoNegocio {

    private final GestorTransacciones transacciones;
    private final Clock reloj;
    private final ServicioBitacora bitacora;
    private final ServicioNotificaciones notificaciones;
    private final ServicioAvisos avisos;

    public ContextoNegocio(GestorTransacciones transacciones, Clock reloj, ServicioBitacora bitacora,
                           ServicioNotificaciones notificaciones) {
        this(transacciones, reloj, bitacora, notificaciones, new ServicioAvisos(new IdentidadRepositorio()));
    }

    public ContextoNegocio(GestorTransacciones transacciones, Clock reloj, ServicioBitacora bitacora,
                           ServicioNotificaciones notificaciones, ServicioAvisos avisos) {
        this.transacciones = transacciones;
        this.reloj = reloj;
        this.bitacora = bitacora;
        this.notificaciones = notificaciones;
        this.avisos = avisos;
    }

    public GestorTransacciones transacciones() {
        return transacciones;
    }

    public Clock reloj() {
        return reloj;
    }

    public OffsetDateTime ahora() {
        return OffsetDateTime.now(reloj);
    }

    public ServicioBitacora bitacora() {
        return bitacora;
    }

    public ServicioNotificaciones notificaciones() {
        return notificaciones;
    }

    /** Avisos por correo a terceros (se envían solo si la operación se confirma). */
    public ServicioAvisos avisos() {
        return avisos;
    }
}
