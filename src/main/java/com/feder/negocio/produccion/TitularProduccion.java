package com.feder.negocio.produccion;

import com.feder.datos.productores.ProductorRepositorio;
import com.feder.modelo.EstadoSolicitudProductor;
import com.feder.modelo.Productor;
import com.feder.modelo.TipoRol;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.usuarios.Permisos;

import java.util.Optional;

/**
 * Titularidad de los recursos agrícolas (R061, R088): solo el propio PRODUCTOR registra o modifica sus
 * producciones y lotes; no existe registro delegado. El personal interno consulta todo.
 */
class TitularProduccion {

    private final ProductorRepositorio productores = new ProductorRepositorio();

    /**
     * Perfil del productor que opera: concesión PRODUCTOR vigente, perfil APROBADA y activo. Se lee con FOR SHARE
     * para que una revocación simultánea no deje registrar producción de un perfil ya inactivo (R061, R073).
     */
    Productor operativo(Solicitante solicitante) {
        Permisos.exigirRol(solicitante, TipoRol.PRODUCTOR, "registrar o modificar producción agrícola");
        Productor perfil = productores.compartirPorUsuario(solicitante.usuarioId())
                .orElseThrow(() -> NegocioException.sinPermiso("Su cuenta no tiene un perfil de productor."));
        if (perfil.getEstadoSolicitud() != EstadoSolicitudProductor.APROBADA || !perfil.getActivo()) {
            throw NegocioException.regla("Su perfil de productor no está activo: un productor inactivo no registra "
                    + "producción ni ventas nuevas.");
        }
        return perfil;
    }

    /** Si la cuenta tiene PRODUCTOR vigente y es la titular del perfil indicado (sin exigir que esté operativo). */
    boolean esTitular(Solicitante solicitante, long productorDelRecurso) {
        return solicitante.estaRegistrado() && solicitante.tieneRol(TipoRol.PRODUCTOR)
                && productores.buscarPorUsuario(solicitante.usuarioId()).map(p -> p.getId() == productorDelRecurso).orElse(false);
    }

    /** Exige que el recurso sea del productor que opera. */
    void exigirPropio(Productor operativo, long productorDelRecurso) {
        if (operativo.getId() != productorDelRecurso) {
            throw NegocioException.sinPermiso("Solo el productor titular registra o modifica su propia producción y sus lotes.");
        }
    }

    /**
     * Ámbito de consulta: vacío para el personal interno (todo); el propio perfil para un PRODUCTOR.
     * La unión de roles no amplía el ámbito: un PRODUCTOR que además es CLIENTE sigue viendo solo lo suyo.
     */
    Optional<Long> ambitoDeConsulta(Solicitante solicitante) {
        Permisos.exigirRegistrado(solicitante);
        if (solicitante.esPersonalInterno()) {
            return Optional.empty();
        }
        if (!solicitante.tieneRol(TipoRol.PRODUCTOR)) {
            throw NegocioException.sinPermiso("Solo el personal de FEDER o el productor titular consultan la producción.");
        }
        return Optional.of(productores.buscarPorUsuario(solicitante.usuarioId())
                .orElseThrow(() -> NegocioException.sinPermiso("Su cuenta no tiene un perfil de productor."))
                .getId());
    }

    void exigirConsultable(Solicitante solicitante, long productorDelRecurso) {
        Optional<Long> ambito = ambitoDeConsulta(solicitante);
        if (ambito.isPresent() && ambito.get() != productorDelRecurso) {
            throw NegocioException.sinPermiso("Solo puede consultar su propia producción.");
        }
    }

    /** Si quien consulta es el personal interno o el titular (vista completa de la trazabilidad). */
    boolean veDatosDelProductor(Solicitante solicitante, long usuarioProductorId) {
        return solicitante.estaRegistrado()
                && (solicitante.esPersonalInterno() || solicitante.usuarioId() == usuarioProductorId);
    }
}
