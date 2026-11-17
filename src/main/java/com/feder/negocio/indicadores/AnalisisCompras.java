package com.feder.negocio.indicadores;

import com.feder.datos.reportes.IndicadoresInventarioRepositorio.AceptadoPorOrigen;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Costo de adquisición de las compras aceptadas de un período (CU7, apartado 6): ENTRADA_COMPRA menos
 * ANULACION_RECEPCION por la fecha del movimiento. Lo rechazado y lo cancelado no generan movimiento.
 */
public record AnalisisCompras(Periodo periodo, List<AceptadoPorOrigen> lineas) {

    public BigDecimal comprasAceptadas() {
        return lineas.stream().map(AnalisisCompras::neto).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Desglose por productor (A11). */
    public Map<String, BigDecimal> porProductor() {
        return agrupar(AceptadoPorOrigen::productor);
    }

    /** Desglose por comunidad de origen histórica (instantánea de PRODUCCION, nunca la comunidad actual). */
    public Map<String, BigDecimal> porComunidadDeOrigen() {
        return agrupar(l -> l.comunidad() + " (" + l.municipio() + ", " + l.departamento() + ")");
    }

    public Map<String, BigDecimal> porProducto() {
        return agrupar(l -> l.productoCodigo() + " " + l.productoNombre());
    }

    static BigDecimal neto(AceptadoPorOrigen linea) {
        return linea.costoEntrada().subtract(linea.costoAnulado());
    }

    private Map<String, BigDecimal> agrupar(Function<AceptadoPorOrigen, String> clave) {
        Map<String, BigDecimal> grupos = new TreeMap<>();
        lineas.forEach(l -> grupos.merge(clave.apply(l), neto(l), BigDecimal::add));
        return grupos;
    }
}
