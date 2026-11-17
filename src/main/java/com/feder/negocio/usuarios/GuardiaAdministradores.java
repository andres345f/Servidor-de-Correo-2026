package com.feder.negocio.usuarios;

import com.feder.datos.usuarios.ConcesionRepositorio;
import com.feder.modelo.TipoRol;
import com.feder.negocio.comun.NegocioException;

/**
 * Regla «Último administrador» (R102): siempre debe quedar al menos una cuenta activa con
 * ADMINISTRADOR VIGENTE.
 *
 * Primero se bloquea la fila ROL de ADMINISTRADOR y después se vuelve a contar con datos actuales.
 * En READ COMMITTED cada consulta toma una instantánea nueva, así que el conteo posterior al bloqueo
 * ve lo que confirmó la operación concurrente que tenía el bloqueo. Dos revocaciones o
 * desactivaciones simultáneas quedan en fila y la segunda ve el resultado de la primera.
 */
class GuardiaAdministradores {

    private final ConcesionRepositorio concesiones = new ConcesionRepositorio();

    /** Toma el bloqueo; debe llamarse antes de leer o bloquear la cuenta o la concesión afectada. */
    void bloquear() {
        concesiones.bloquearRol(TipoRol.ADMINISTRADOR);
    }

    /** Con el bloqueo ya tomado: rechaza la operación si la cuenta es el último administrador activo. */
    void exigirOtroAdministrador(long usuarioAfectado, String operacion) {
        if (concesiones.contarVigentesEnCuentasActivasSalvo(TipoRol.ADMINISTRADOR, usuarioAfectado) == 0) {
            throw NegocioException.regla("No se puede " + operacion + ": es la última cuenta activa con ADMINISTRADOR "
                    + "vigente y el sistema nunca debe quedarse sin administrador. Incorpore antes a otro administrador.");
        }
    }
}
