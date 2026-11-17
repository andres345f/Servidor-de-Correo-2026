package com.feder.negocio.indicadores;

import com.feder.datos.reportes.IndicadoresComercialesRepositorio.ComponentesProducto;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Ingreso, costos, margen y mermas de un producto (o de una categoría) en un período, a precisión completa.
 * Aplica las fórmulas de CU7: costo de ventas = salidas de venta - reingresos de ventas + ajustes que los
 * compensan; costo de posventa = reposiciones - reingresos de reposiciones + ajustes que los compensan.
 * Las mermas y lo devuelto no apto son informativos: no entran en el margen.
 */
public record DesgloseComercial(String codigo, String nombre, String categoria, BigDecimal ingreso,
                                BigDecimal costoVentas, BigDecimal costoPosventa, BigDecimal mermas,
                                BigDecimal devueltoNoApto) {

    public static DesgloseComercial de(ComponentesProducto c) {
        BigDecimal ingreso = c.subtotalEntregado().subtract(c.reembolsos());
        BigDecimal costoVentas = c.salidasVenta().subtract(c.reingresosVenta()).add(c.ajustesVenta());
        BigDecimal costoPosventa = c.salidasReposicion().subtract(c.reingresosReposicion()).add(c.ajustesReposicion());
        return new DesgloseComercial(c.codigo(), c.nombre(), c.categoria(), ingreso, costoVentas, costoPosventa,
                c.mermas(), c.devueltoNoApto());
    }

    /** Desglose vacío de una categoría, punto de partida para sumar sus productos. */
    static DesgloseComercial vacio(String categoria) {
        return new DesgloseComercial("", categoria, categoria, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal margen() {
        return ingreso.subtract(costoVentas).subtract(costoPosventa);
    }

    /** Margen % de la fila, solo si su ingreso reconocido es mayor que 0. */
    public Optional<BigDecimal> margenPorcentaje() {
        return ingreso.signum() > 0 ? Optional.of(Formatos.porcentaje(margen(), ingreso)) : Optional.empty();
    }

    /** Suma dos desgloses de la misma categoría. */
    DesgloseComercial sumar(DesgloseComercial otro) {
        return new DesgloseComercial(codigo, nombre, categoria, ingreso.add(otro.ingreso),
                costoVentas.add(otro.costoVentas), costoPosventa.add(otro.costoPosventa), mermas.add(otro.mermas),
                devueltoNoApto.add(otro.devueltoNoApto));
    }
}
