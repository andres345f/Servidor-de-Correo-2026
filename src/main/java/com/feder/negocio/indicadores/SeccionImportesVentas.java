package com.feder.negocio.indicadores;

import com.feder.datos.reportes.IndicadoresCajaRepositorio.CobroPorMetodo;
import com.feder.datos.reportes.IndicadoresCajaRepositorio.ReintegroPorMetodo;
import com.feder.datos.reportes.ReporteVentasRepositorio.CantidadVendida;
import com.feder.datos.reportes.ReporteVentasRepositorio.SaldoPedido;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Tabla;
import com.feder.negocio.indicadores.FiltrosReporte.Clave;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Importes agregados del reporte de ventas, solo para un ADMINISTRADOR vigente. No tiene fórmulas propias:
 * presenta los indicadores de CU7 del mismo período. Las ventas no se agrupan por método de pago; solo los
 * cobros y los reintegros.
 *
 * Con filtros solo se presenta lo que las fórmulas de CU7 permiten separar: los indicadores de CU7 no se
 * calculan por pedido (tipo, modalidad o estado), el ingreso se separa por producto y categoría pero el envío
 * y la caja no, y el método de pago solo filtra los cobros y los reintegros.
 */
final class SeccionImportesVentas {

    private static final String SIN_ATRIBUCION = "No se presenta con este filtro";

    private SeccionImportesVentas() {
    }

    static void agregar(ResultadoOperacion resultado, GeneradorReporteVentas.ImportesVentas importes,
                        FiltrosReporte filtros, List<CantidadVendida> vendidas, List<SaldoPedido> saldos) {
        BigDecimal saldoTotal = saldos.stream().map(GeneradorReporteVentas::saldo).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean filtroDePedido = filtros.tieneAlguno(Clave.TIPO, Clave.MODALIDAD, Clave.ESTADO);
        boolean filtroDeProducto = filtros.tieneAlguno(Clave.PRODUCTO, Clave.CATEGORIA);
        if (filtroDePedido) {
            resultado.detalle("Indicadores de CU7", "No se presentan con filtros de tipo, modalidad o estado del pedido: "
                    + "los indicadores financieros no se calculan por pedido.");
        } else {
            agregarIngresos(resultado, importes.comercial(), filtros, filtroDeProducto);
            agregarCaja(resultado, importes.caja(), filtros.valor(Clave.METODO), filtroDeProducto);
        }
        resultado.detalle("Saldo pendiente de cobro de pedidos vigentes (Bs)", Formatos.monto(saldoTotal));
        resultado.tabla(rankingBruto(vendidas));
    }

    private static void agregarIngresos(ResultadoOperacion resultado, AnalisisComercial comercial, FiltrosReporte filtros,
                                        boolean filtroDeProducto) {
        List<DesgloseComercial> productos = comercial.productos().stream().filter(d -> cumple(d, filtros)).toList();
        BigDecimal ingresos = filtroDeProducto
                ? productos.stream().map(DesgloseComercial::ingreso).reduce(BigDecimal.ZERO, BigDecimal::add)
                : comercial.ingresosReconocidos();
        resultado.detalle("Ingresos reconocidos (Bs)", Formatos.monto(ingresos))
                .detalle("Importe cobrado por envío, aparte (Bs)",
                        filtroDeProducto ? SIN_ATRIBUCION : Formatos.monto(comercial.envioCobrado()));
        resultado.tabla(ingresoNeto(productos));
    }

    private static boolean cumple(DesgloseComercial d, FiltrosReporte filtros) {
        String producto = filtros.valor(Clave.PRODUCTO);
        String categoria = filtros.valor(Clave.CATEGORIA);
        return (producto == null || producto.equalsIgnoreCase(d.codigo()))
                && (categoria == null || categoria.equalsIgnoreCase(d.categoria()));
    }

    private static void agregarCaja(ResultadoOperacion resultado, AnalisisCaja caja, String metodo, boolean filtroDeProducto) {
        if (filtroDeProducto) {
            resultado.detalle("Cobros y reintegros", SIN_ATRIBUCION + ": la caja no se separa por producto.");
            return;
        }
        List<CobroPorMetodo> cobros = caja.cobros().stream().filter(c -> metodo == null || metodo.equals(c.metodo())).toList();
        List<ReintegroPorMetodo> reintegros = caja.reintegros().stream()
                .filter(r -> metodo == null || metodo.equals(r.metodo())).toList();
        AnalisisCaja filtrada = new AnalisisCaja(caja.periodo(), cobros, reintegros);
        resultado.detalle("Cobros confirmados (Bs)", Formatos.monto(filtrada.cobrosConfirmados()))
                .detalle("Reintegros ejecutados (Bs)", Formatos.monto(filtrada.reintegrosEjecutados()));
        resultado.tabla(cajaPorMetodo(filtrada));
    }

    private static Tabla rankingBruto(List<CantidadVendida> vendidas) {
        Tabla tabla = new Tabla("Ranking por importe bruto (subtotal entregado, por fecha de entrega)",
                List.of("Posición", "Código", "Producto", "Subtotal entregado (Bs)"));
        vendidas.stream().sorted(Comparator.comparingInt(CantidadVendida::posicionPorImporte)
                        .thenComparing(CantidadVendida::codigo))
                .forEach(v -> tabla.fila(v.posicionPorImporte(), v.codigo(), v.nombre(), Formatos.monto(v.subtotalEntregado())));
        return tabla;
    }

    private static Tabla ingresoNeto(List<DesgloseComercial> productos) {
        Tabla tabla = new Tabla("Ingreso neto por producto (ingresos reconocidos)",
                List.of("Código", "Producto", "Categoría", "Ingreso reconocido (Bs)"));
        productos.stream()
                .sorted(Comparator.comparing(DesgloseComercial::ingreso).reversed())
                .forEach(d -> tabla.fila(d.codigo(), d.nombre(), d.categoria(), Formatos.monto(d.ingreso())));
        return tabla;
    }

    private static Tabla cajaPorMetodo(AnalisisCaja caja) {
        Map<String, BigDecimal[]> metodos = new TreeMap<>();
        for (CobroPorMetodo cobro : caja.cobros()) {
            importes(metodos, cobro.metodo())[0] = cobro.importe();
        }
        for (ReintegroPorMetodo reintegro : caja.reintegros()) {
            importes(metodos, reintegro.metodo())[reintegro.porDevolucion() ? 2 : 1] = reintegro.importe();
        }
        Tabla tabla = new Tabla("Cobros y reintegros por método de pago", List.of("Método", "Cobros confirmados (Bs)",
                "Reintegros por cancelación (Bs)", "Reintegros por devolución (Bs)"));
        metodos.forEach((metodo, v) -> tabla.fila(metodo, Formatos.monto(v[0]), Formatos.monto(v[1]), Formatos.monto(v[2])));
        return tabla;
    }

    private static BigDecimal[] importes(Map<String, BigDecimal[]> metodos, String metodo) {
        return metodos.computeIfAbsent(metodo, m -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
    }
}
