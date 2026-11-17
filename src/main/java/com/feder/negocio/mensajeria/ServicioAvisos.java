package com.feder.negocio.mensajeria;

import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;

import java.util.logging.Logger;

/**
 * Avisos por correo a una persona distinta del remitente: una invitación (R100), la decisión sobre
 * una solicitud de productor (R075) o el aviso a la cuenta existente ante un registro duplicado (R095).
 *
 * El aviso siempre va a la dirección REGISTRADA de la cuenta (o a la dirección que se está invitando),
 * nunca a una dirección elegida por el remitente. Se envía solo si la operación que lo origina se confirma.
 */
public class ServicioAvisos {

    private static final Logger ACCESO = Logger.getLogger("feder.access");

    private final IdentidadRepositorio identidades;

    public ServicioAvisos(IdentidadRepositorio identidades) {
        this.identidades = identidades;
    }

    /** Aviso a la dirección registrada de una cuenta (activa o no). Debe llamarse dentro de la transacción. */
    public void avisarCuenta(long usuarioId, ResultadoOperacion contenido) {
        String correo = identidades.correoDeCuenta(usuarioId)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la cuenta a la que se debía avisar."));
        avisarDireccion(correo, contenido);
    }

    /** Aviso a una dirección concreta (por ejemplo, la dirección invitada que aún no tiene cuenta activa). */
    public void avisarDireccion(String correo, ResultadoOperacion contenido) {
        if (!AvisosPendientes.agregar(correo, contenido)) {
            // Fuera del procesamiento de un correo (instalación, pruebas) no hay bandeja donde dejarlo.
            ACCESO.fine(() -> "Aviso no enviado (sin correo en proceso) para " + correo + ": " + contenido.titulo());
        }
    }
}
