package com.feder.negocio.inventario;

import com.feder.datos.inventario.LoteFederVista;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Reparto FEFO de una cantidad entre lotes (LOTE_FEDER «Reserva atómica», R037): primero el lote cuyo
 * origen vence antes, los lotes sin fecha al final; después la fecha de ingreso y por último el id.
 * Es un cálculo puro: no bloquea ni modifica nada, trabaja con los valores ya bloqueados que recibe.
 */
public final class AsignadorFefo {

    /** Parte de la cantidad que se toma de un lote. */
    public record Asignacion(long loteFederId, BigDecimal cantidad) {
    }

    public static final Comparator<LoteFederVista> ORDEN_FEFO = Comparator
            .comparing(LoteFederVista::fechaVencimiento, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(LoteFederVista::fechaIngreso)
            .thenComparingLong(LoteFederVista::id);

    private AsignadorFefo() {
    }

    /**
     * @param lotes  lotes elegibles (vendibles y del mismo producto), en cualquier orden.
     * @param libres stock libre restante por lote; se descuenta lo asignado para repartir varias líneas.
     * @return las asignaciones; su suma puede ser menor que la cantidad si no alcanza el stock.
     */
    public static List<Asignacion> asignar(List<LoteFederVista> lotes, Map<Long, BigDecimal> libres, BigDecimal cantidad) {
        List<Asignacion> resultado = new ArrayList<>();
        BigDecimal pendiente = cantidad;
        for (LoteFederVista lote : lotes.stream().sorted(ORDEN_FEFO).toList()) {
            if (pendiente.signum() <= 0) {
                break;
            }
            BigDecimal libre = libres.getOrDefault(lote.id(), BigDecimal.ZERO);
            if (libre.signum() <= 0) {
                continue;
            }
            BigDecimal tomada = libre.min(pendiente);
            resultado.add(new Asignacion(lote.id(), tomada));
            libres.put(lote.id(), libre.subtract(tomada));
            pendiente = pendiente.subtract(tomada);
        }
        return resultado;
    }

    public static BigDecimal total(List<Asignacion> asignaciones) {
        return asignaciones.stream().map(Asignacion::cantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
