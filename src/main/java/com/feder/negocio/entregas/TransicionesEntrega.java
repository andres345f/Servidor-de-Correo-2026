package com.feder.negocio.entregas;

import com.feder.modelo.EstadoEntrega;
import com.feder.modelo.ModalidadEntrega;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Máquina de estados de ENTREGA según su modalidad (nota de EstadoEntrega, R027-R029).
 * RECOJO: PENDIENTE -> LISTA_PARA_RECOJO -> ENTREGADA | RETORNADA (no recogido).
 * DELIVERY: PENDIENTE -> EN_CAMINO -> ENTREGADA | FALLIDA; FALLIDA -> EN_CAMINO (reintento) | RETORNADA | NO_RECUPERADA.
 * En ambas, CANCELADA solo antes del despacho (desde PENDIENTE).
 */
public final class TransicionesEntrega {

    private static final Map<EstadoEntrega, Set<EstadoEntrega>> RECOJO = new EnumMap<>(EstadoEntrega.class);
    private static final Map<EstadoEntrega, Set<EstadoEntrega>> DELIVERY = new EnumMap<>(EstadoEntrega.class);

    static {
        RECOJO.put(EstadoEntrega.PENDIENTE, EnumSet.of(EstadoEntrega.LISTA_PARA_RECOJO, EstadoEntrega.CANCELADA));
        RECOJO.put(EstadoEntrega.LISTA_PARA_RECOJO, EnumSet.of(EstadoEntrega.ENTREGADA, EstadoEntrega.RETORNADA));
        DELIVERY.put(EstadoEntrega.PENDIENTE, EnumSet.of(EstadoEntrega.EN_CAMINO, EstadoEntrega.CANCELADA));
        DELIVERY.put(EstadoEntrega.EN_CAMINO, EnumSet.of(EstadoEntrega.ENTREGADA, EstadoEntrega.FALLIDA));
        DELIVERY.put(EstadoEntrega.FALLIDA, EnumSet.of(EstadoEntrega.EN_CAMINO, EstadoEntrega.RETORNADA, EstadoEntrega.NO_RECUPERADA));
    }

    private TransicionesEntrega() {
    }

    public static boolean permitida(ModalidadEntrega modalidad, EstadoEntrega desde, EstadoEntrega hacia) {
        Map<EstadoEntrega, Set<EstadoEntrega>> tabla = modalidad == ModalidadEntrega.RECOJO ? RECOJO : DELIVERY;
        return tabla.getOrDefault(desde, Set.of()).contains(hacia);
    }

    /** Estado al que pasa la entrega con el despacho (R027). */
    public static EstadoEntrega trasDespacho(ModalidadEntrega modalidad) {
        return modalidad == ModalidadEntrega.RECOJO ? EstadoEntrega.LISTA_PARA_RECOJO : EstadoEntrega.EN_CAMINO;
    }
}
