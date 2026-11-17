package com.feder.negocio.produccion;

import com.feder.modelo.EstadoProduccion;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Máquina de estados de PRODUCCION: PLANIFICADA -> EN_CURSO -> TERMINADA, y cancelación solo desde
 * PLANIFICADA o EN_CURSO (R065: una producción TERMINADA no se cancela). TERMINADA y CANCELADA son finales.
 */
final class TransicionesProduccion {

    private static final Map<EstadoProduccion, Set<EstadoProduccion>> PERMITIDAS = new EnumMap<>(EstadoProduccion.class);

    static {
        PERMITIDAS.put(EstadoProduccion.PLANIFICADA, EnumSet.of(EstadoProduccion.EN_CURSO, EstadoProduccion.CANCELADA));
        PERMITIDAS.put(EstadoProduccion.EN_CURSO, EnumSet.of(EstadoProduccion.TERMINADA, EstadoProduccion.CANCELADA));
        PERMITIDAS.put(EstadoProduccion.TERMINADA, EnumSet.noneOf(EstadoProduccion.class));
        PERMITIDAS.put(EstadoProduccion.CANCELADA, EnumSet.noneOf(EstadoProduccion.class));
    }

    private TransicionesProduccion() {
    }

    static boolean permitida(EstadoProduccion desde, EstadoProduccion hacia) {
        return PERMITIDAS.get(desde).contains(hacia);
    }

    static Set<EstadoProduccion> siguientes(EstadoProduccion desde) {
        return PERMITIDAS.get(desde);
    }

    /** Estados en que la producción sigue abierta y admite cambios de plan. */
    static boolean abierta(EstadoProduccion estado) {
        return estado == EstadoProduccion.PLANIFICADA || estado == EstadoProduccion.EN_CURSO;
    }
}
