package com.feder.negocio.indicadores;

import com.feder.datos.reportes.IndicadoresCajaRepositorio.CobroPorMetodo;
import com.feder.datos.reportes.IndicadoresCajaRepositorio.ReintegroPorMetodo;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Predicate;

/**
 * Caja de un período (CU7, apartado 5): cobros por fecha_pago y reintegros por fecha_ejecucion,
 * por método de pago. No son equivalentes a las ventas y no se suman con ellas.
 */
public record AnalisisCaja(Periodo periodo, List<CobroPorMetodo> cobros, List<ReintegroPorMetodo> reintegros) {

    public BigDecimal cobrosConfirmados() {
        return cobros.stream().map(CobroPorMetodo::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal reintegrosEjecutados() {
        return sumarReintegros(r -> true);
    }

    /** Reintegros sin DEVOLUCION: devuelven anticipos de pedidos cancelados. */
    public BigDecimal reintegrosPorCancelacion() {
        return sumarReintegros(r -> !r.porDevolucion());
    }

    public BigDecimal reintegrosPorDevolucion() {
        return sumarReintegros(ReintegroPorMetodo::porDevolucion);
    }

    public BigDecimal cobroNeto() {
        return cobrosConfirmados().subtract(reintegrosEjecutados());
    }

    private BigDecimal sumarReintegros(Predicate<ReintegroPorMetodo> filtro) {
        return reintegros.stream().filter(filtro).map(ReintegroPorMetodo::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
