package com.feder.negocio.ventas;

import com.feder.modelo.EstadoPedido;
import com.feder.modelo.TipoPedido;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Máquina de estados de PEDIDO según su tipo (HISTORIAL_PEDIDO «Transición válida», R032).
 * INMEDIATO: PENDIENTE -> CONFIRMADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO.
 * ENCARGO: PENDIENTE -> ESPERANDO_ABASTECIMIENTO -> CONFIRMADO -> EN_PREPARACION -> DESPACHADO -> ENTREGADO.
 * Desde cualquier estado anterior a ENTREGADO se admite CANCELADO; quién puede cancelar en cada estado
 * lo decide el servicio de cancelación (R031, R052).
 */
public final class TransicionesPedido {

    private static final Map<EstadoPedido, Set<EstadoPedido>> COMUNES = new EnumMap<>(EstadoPedido.class);

    static {
        COMUNES.put(EstadoPedido.CONFIRMADO, EnumSet.of(EstadoPedido.EN_PREPARACION, EstadoPedido.CANCELADO));
        COMUNES.put(EstadoPedido.EN_PREPARACION, EnumSet.of(EstadoPedido.DESPACHADO, EstadoPedido.CANCELADO));
        COMUNES.put(EstadoPedido.DESPACHADO, EnumSet.of(EstadoPedido.ENTREGADO, EstadoPedido.CANCELADO));
    }

    private TransicionesPedido() {
    }

    public static boolean permitida(TipoPedido tipo, EstadoPedido desde, EstadoPedido hacia) {
        if (desde == EstadoPedido.PENDIENTE) {
            return hacia == EstadoPedido.CANCELADO
                    || hacia == (tipo == TipoPedido.ENCARGO ? EstadoPedido.ESPERANDO_ABASTECIMIENTO : EstadoPedido.CONFIRMADO);
        }
        if (desde == EstadoPedido.ESPERANDO_ABASTECIMIENTO) {
            return tipo == TipoPedido.ENCARGO && (hacia == EstadoPedido.CONFIRMADO || hacia == EstadoPedido.CANCELADO);
        }
        return COMUNES.getOrDefault(desde, Set.of()).contains(hacia);
    }
}
