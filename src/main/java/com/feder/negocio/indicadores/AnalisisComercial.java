package com.feder.negocio.indicadores;

import com.feder.datos.reportes.IndicadoresComercialesRepositorio.ImportesVenta;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Ventas, costos, margen bruto y mermas de un período (CU7, apartados 1 a 3), a precisión completa.
 *
 * El ingreso total usa PEDIDO.subtotal de los pedidos entregados en el período (fórmula del modelo); el
 * desglose por producto usa los subtotales de sus líneas. Los costos y las mermas totales son la suma del
 * desglose, porque todo movimiento pertenece a un lote y todo lote a un producto.
 */
public record AnalisisComercial(Periodo periodo, ImportesVenta ventas, List<DesgloseComercial> productos) {

    public BigDecimal ingresosReconocidos() {
        return ventas.subtotalEntregado().subtract(ventas.reembolsosPorDevolucion());
    }

    public BigDecimal envioCobrado() {
        return ventas.envioCobrado();
    }

    public BigDecimal costoDeVentas() {
        return sumar(DesgloseComercial::costoVentas);
    }

    public BigDecimal costoPosventa() {
        return sumar(DesgloseComercial::costoPosventa);
    }

    /** Mermas informativas: no entran en el margen. */
    public BigDecimal mermas() {
        return sumar(DesgloseComercial::mermas);
    }

    /** Devuelto no apto (informativo): ya está en el costo de ventas o de posventa y no se resta otra vez. */
    public BigDecimal devueltoNoApto() {
        return sumar(DesgloseComercial::devueltoNoApto);
    }

    /** Margen bruto = ingreso reconocido - costo de ventas - costo de posventa; no resta las mermas. */
    public BigDecimal margenBruto() {
        return ingresosReconocidos().subtract(costoDeVentas()).subtract(costoPosventa());
    }

    /** Margen % solo si el ingreso reconocido es mayor que 0. */
    public Optional<BigDecimal> margenPorcentaje() {
        BigDecimal ingreso = ingresosReconocidos();
        return ingreso.signum() > 0 ? Optional.of(Formatos.porcentaje(margenBruto(), ingreso)) : Optional.empty();
    }

    /** Mismas fórmulas agrupadas por la categoría actual del producto (A10, sin historial de categorías). */
    public List<DesgloseComercial> porCategoria() {
        Map<String, DesgloseComercial> categorias = new LinkedHashMap<>();
        for (DesgloseComercial producto : productos) {
            categorias.merge(producto.categoria(), DesgloseComercial.vacio(producto.categoria()).sumar(producto),
                    DesgloseComercial::sumar);
        }
        return new ArrayList<>(categorias.values());
    }

    private BigDecimal sumar(Function<DesgloseComercial, BigDecimal> componente) {
        return productos.stream().map(componente).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
