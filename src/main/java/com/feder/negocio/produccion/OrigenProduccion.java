package com.feder.negocio.produccion;

import com.feder.datos.productores.ComunidadRepositorio;
import com.feder.datos.productores.HistorialComunidadRepositorio;
import com.feder.datos.productores.HistorialComunidadRepositorio.CambioComunidad;
import com.feder.datos.productores.HistorialComunidadRepositorio.ValidacionOrigen;
import com.feder.modelo.ComunidadAgricola;
import com.feder.modelo.Productor;
import com.feder.negocio.comandos.ValidadorParametros;
import com.feder.negocio.comun.NegocioException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * «Procedencia histórica» (R066): decide la comunidad de origen de una producción nueva.
 *
 * Por defecto es la comunidad vigente del productor, aunque esté inactiva. Si el productor declara otra
 * comunidad para un producto ya elaborado, se admite solo si el historial de cambios de comunidad de BITACORA
 * muestra que pertenecía a ella en la fecha de elaboración, o si un ADMINISTRADOR la validó antes con motivo y
 * evidencia (VALORI). La procedencia queda como declarada y validada, nunca como comprobada por el sistema.
 */
class OrigenProduccion {

    static final String ORIGEN_VIGENTE = "Comunidad vigente del productor al registrar la producción";
    static final String ORIGEN_HISTORIAL = "Declarada por el productor y validada con el historial de cambios de comunidad";
    static final String ORIGEN_ADMINISTRADOR = "Declarada por el productor y validada por un ADMINISTRADOR con motivo y evidencia";

    /** Comunidad elegida y la base con que se admitió. */
    record Origen(ComunidadAgricola comunidad, String base, Long evidenciaBitacoraId, Long validadoPorId) {
    }

    private final ComunidadRepositorio comunidades = new ComunidadRepositorio();
    private final HistorialComunidadRepositorio historial = new HistorialComunidadRepositorio();

    Origen resolver(Productor productor, Long comunidadDeclarada, LocalDate fechaElaboracion) {
        if (comunidadDeclarada == null || comunidadDeclarada.equals(productor.getComunidadAgricolaId())) {
            return new Origen(comunidad(productor.getComunidadAgricolaId()), ORIGEN_VIGENTE, null, null);
        }
        ComunidadAgricola declarada = comunidad(comunidadDeclarada);
        if (fechaElaboracion == null) {
            throw NegocioException.regla("Para declarar una comunidad de origen distinta de la suya indique la fecha de "
                    + "elaboración del producto: con ella se comprueba a qué comunidad pertenecía.");
        }
        Optional<CambioComunidad> cambio = cambioQueLaDejo(productor, comunidadDeclarada, fechaElaboracion);
        if (cambio.isPresent()) {
            return new Origen(declarada, ORIGEN_HISTORIAL, cambio.get().bitacoraId(), null);
        }
        Optional<ValidacionOrigen> validacion = historial.validacion(productor.getId(), comunidadDeclarada, fechaElaboracion);
        if (validacion.isPresent()) {
            return new Origen(declarada, ORIGEN_ADMINISTRADOR, validacion.get().bitacoraId(), validacion.get().administradorId());
        }
        throw NegocioException.regla("Su historial no muestra que perteneciera a la comunidad " + declarada.getNombre()
                + " el " + fechaElaboracion + ". Un ADMINISTRADOR debe validar antes ese origen con motivo y evidencia (VALORI).");
    }

    /**
     * La comunidad en una fecha es la «anterior» del primer cambio registrado después de ese día. Ese tramo empieza
     * en el cambio previo o, si es el primero, en la afiliación: antes de afiliarse el historial no muestra nada, así
     * que una fecha anterior no se da por respaldada y necesita la validación de un ADMINISTRADOR. La afiliación es
     * posterior a cualquier reapertura de la solicitud, por eso esas reaperturas no forman parte del tramo.
     * Devuelve el cambio que lo demuestra, si la comunidad en esa fecha es la declarada.
     */
    private Optional<CambioComunidad> cambioQueLaDejo(Productor productor, long declarada, LocalDate fecha) {
        OffsetDateTime finDelDia = fecha.plusDays(1).atStartOfDay(ValidadorParametros.ZONA_BOLIVIA).toOffsetDateTime();
        List<CambioComunidad> cambios = historial.cambiosDe(productor.getId());
        for (int i = 0; i < cambios.size(); i++) {
            CambioComunidad cambio = cambios.get(i);
            if (!cambio.fecha().isBefore(finDelDia)) {
                boolean primerTramo = i == 0;
                boolean dentroDelTramo = !primerTramo || afiliadoEn(productor, fecha);
                return dentroDelTramo && cambio.comunidadAnteriorId() == declarada ? Optional.of(cambio) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static boolean afiliadoEn(Productor productor, LocalDate fecha) {
        return productor.getFechaAfiliacion() != null && !fecha.isBefore(productor.getFechaAfiliacion());
    }

    private ComunidadAgricola comunidad(long id) {
        return comunidades.buscarPorId(id)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la comunidad agrícola " + id + "."));
    }
}
