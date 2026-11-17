package com.feder.negocio.produccion;

import com.feder.datos.produccion.BalanceProduccionRepositorio.Sumas;

import java.math.BigDecimal;

/**
 * Definiciones del «Balance de cantidades» de una producción (R063), calculadas a partir de las sumas
 * de la base: en tránsito = adquirida - recibida; pendiente de adquirir = max(0, compromiso - en tránsito);
 * capacidad para respaldos = max(0, cantidad estimada - recibida).
 */
public record BalanceProduccion(BigDecimal compromisoVigente, BigDecimal adquirida, BigDecimal recibida,
                                BigDecimal enTransito, BigDecimal pendienteDeAdquirir, BigDecimal capacidad) {

    static BalanceProduccion de(Sumas sumas, BigDecimal cantidadEstimada) {
        BigDecimal enTransito = sumas.adquirida().subtract(sumas.recibida()).max(BigDecimal.ZERO);
        BigDecimal pendiente = sumas.compromisoVigente().subtract(enTransito).max(BigDecimal.ZERO);
        BigDecimal estimada = cantidadEstimada == null ? BigDecimal.ZERO : cantidadEstimada;
        BigDecimal capacidad = estimada.subtract(sumas.recibida()).max(BigDecimal.ZERO);
        return new BalanceProduccion(sumas.compromisoVigente(), sumas.adquirida(), sumas.recibida(), enTransito, pendiente,
                capacidad);
    }

    /** Tras reducir la estimación: la capacidad ya no cubre el compromiso vigente. */
    boolean capacidadInsuficiente() {
        return capacidad.compareTo(compromisoVigente) < 0;
    }

    /** Tras cancelar: lo comprometido supera lo que ya está en camino a FEDER. */
    boolean compromisoSinTransito() {
        return compromisoVigente.compareTo(enTransito) > 0;
    }
}
