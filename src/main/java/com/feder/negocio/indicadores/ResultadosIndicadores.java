package com.feder.negocio.indicadores;

import com.feder.datos.reportes.IndicadoresCajaRepositorio.CobroPorMetodo;
import com.feder.datos.reportes.IndicadoresCajaRepositorio.ReintegroPorMetodo;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio.MercanciaDespachada;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio.ValorProducto;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Tabla;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Convierte los indicadores de CU7 en resultados para el correo: un dato principal en los detalles y
 * sus desgloses en tablas. No calcula: solo presenta lo que devuelve el servicio.
 */
final class ResultadosIndicadores {

    private static final String NOTA_IMPORTES = "Importes en bolivianos, impuestos incluidos, redondeados a 2 decimales.";
    private static final String NO_APLICA = "No aplica";

    private ResultadosIndicadores() {
    }

    static ResultadoOperacion ingresos(AnalisisComercial a) {
        ResultadoOperacion r = ResultadoOperacion.exito("Ingresos reconocidos", "Subtotal de los pedidos entregados en el "
                        + "período menos los reembolsos ejecutados por devolución. Los reintegros por cancelación no restan "
                        + "ingreso: devuelven anticipos. " + NOTA_IMPORTES)
                .detalle("Período", a.periodo().describir())
                .detalle("Subtotal de pedidos entregados (Bs)", Formatos.monto(a.ventas().subtotalEntregado()))
                .detalle("Reembolsos ejecutados por devolución (Bs)", Formatos.monto(a.ventas().reembolsosPorDevolucion()))
                .detalle("Ingresos reconocidos (Bs)", Formatos.monto(a.ingresosReconocidos()))
                .detalle("Importe cobrado por envío, aparte (Bs)", Formatos.monto(a.envioCobrado()));
        return r.tabla(desglose("Ingresos por producto", a.productos(), true, Columna.INGRESO))
                .tabla(desglose("Ingresos por categoría actual", a.porCategoria(), false, Columna.INGRESO));
    }

    static ResultadoOperacion costos(AnalisisComercial a) {
        ResultadoOperacion r = ResultadoOperacion.exito("Costo de ventas", "Costo de lo entregado en el período menos los "
                        + "reingresos por devolución, y costo de posventa de las reposiciones. Las mermas y lo devuelto no "
                        + "apto son informativos y no entran en el margen; lo devuelto no apto ya está en el costo de ventas "
                        + "o de posventa y no se resta otra vez. " + NOTA_IMPORTES)
                .detalle("Período", a.periodo().describir())
                .detalle("Costo de ventas (Bs)", Formatos.monto(a.costoDeVentas()))
                .detalle("Costo de posventa (Bs)", Formatos.monto(a.costoPosventa()))
                .detalle("Mermas, informativo (Bs)", Formatos.monto(a.mermas()))
                .detalle("Devuelto no apto por fecha de inspección, informativo (Bs)", Formatos.monto(a.devueltoNoApto()));
        Columna[] columnas = {Columna.COSTO_VENTAS, Columna.COSTO_POSVENTA, Columna.MERMAS, Columna.NO_APTO};
        return r.tabla(desglose("Costos por producto", a.productos(), true, columnas))
                .tabla(desglose("Costos por categoría actual", a.porCategoria(), false, columnas));
    }

    static ResultadoOperacion margen(AnalisisComercial a) {
        ResultadoOperacion r = ResultadoOperacion.exito("Margen bruto comercial", "Ingreso reconocido menos costo de ventas "
                        + "y costo de posventa. No es utilidad neta: no incluye el envío cobrado, ni descuenta el costo del "
                        + "envío, los gastos de operación, los impuestos ni las mermas. El margen % solo se calcula si el "
                        + "ingreso reconocido es mayor que 0. " + NOTA_IMPORTES)
                .detalle("Período", a.periodo().describir())
                .detalle("Ingresos reconocidos (Bs)", Formatos.monto(a.ingresosReconocidos()))
                .detalle("Costo de ventas (Bs)", Formatos.monto(a.costoDeVentas()))
                .detalle("Costo de posventa (Bs)", Formatos.monto(a.costoPosventa()))
                .detalle("Margen bruto (Bs)", Formatos.monto(a.margenBruto()))
                .detalle("Margen (%)", porcentaje(a.margenPorcentaje(),
                        "No aplica: el ingreso reconocido no es mayor que 0"));
        Columna[] columnas = {Columna.INGRESO, Columna.COSTO_VENTAS, Columna.COSTO_POSVENTA, Columna.MARGEN,
                Columna.MARGEN_PORCENTAJE};
        return r.tabla(desglose("Margen por producto", a.productos(), true, columnas))
                .tabla(desglose("Margen por categoría actual", a.porCategoria(), false, columnas));
    }

    static ResultadoOperacion anticipos(LocalDate corte, BigDecimal anticipos, BigDecimal devolucionesPendientes) {
        return ResultadoOperacion.exito("Anticipos pendientes", "Cobros confirmados menos reintegros ejecutados, hasta el "
                        + "corte, de los pedidos que a esa fecha no están entregados. Son pasivo, no ingreso. " + NOTA_IMPORTES)
                .detalle("Fecha de corte", corte + " (todo el día, hora de Bolivia)")
                .detalle("Anticipos pendientes (Bs)", Formatos.monto(anticipos))
                .detalle("Devoluciones aprobadas pendientes de reintegro, informativo (Bs)", Formatos.monto(devolucionesPendientes));
    }

    static ResultadoOperacion inventario(LocalDate corte, List<ValorProducto> productos, SituacionDespachos despachos) {
        BigDecimal total = productos.stream().map(ValorProducto::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
        ResultadoOperacion r = ResultadoOperacion.exito("Valor del inventario", "Costo de las entradas menos el de las "
                        + "salidas de cada lote de FEDER hasta el corte. La mercancía despachada sin entregar no está en el "
                        + "inventario ni en el costo de ventas. " + NOTA_IMPORTES)
                .detalle("Fecha de corte", corte + " (todo el día, hora de Bolivia)")
                .detalle("Valor del inventario (Bs)", Formatos.monto(total))
                .detalle("Mercancía despachada pendiente de entrega (Bs)",
                        despachos.costoPendienteDeEntrega().map(Formatos::monto).orElse(despachos.describirNoDeterminable()));
        Tabla tabla = new Tabla("Valor por producto", List.of("Código", "Producto", "Unidad", "Cantidad", "Valor (Bs)"));
        productos.forEach(p -> tabla.fila(p.codigo(), p.nombre(), p.unidad(), Formatos.cantidad(p.cantidad()),
                Formatos.monto(p.valor())));
        return r.tabla(tabla);
    }

    static ResultadoOperacion cobros(AnalisisCaja c) {
        Tabla tabla = new Tabla("Cobros por método de pago", List.of("Método", "Pagos", "Importe (Bs)"));
        for (CobroPorMetodo cobro : c.cobros()) {
            tabla.fila(cobro.metodo(), cobro.cantidad(), Formatos.monto(cobro.importe()));
        }
        return ResultadoOperacion.exito("Cobros confirmados", "Pagos PAGADO cuya fecha de pago cae en el período. Son caja, "
                        + "no ventas: no se suman con los ingresos reconocidos. " + NOTA_IMPORTES)
                .detalle("Período", c.periodo().describir())
                .detalle("Cobros confirmados (Bs)", Formatos.monto(c.cobrosConfirmados()))
                .detalle("Cobro neto de reintegros (Bs)", Formatos.monto(c.cobroNeto()))
                .tabla(tabla);
    }

    static ResultadoOperacion reintegros(AnalisisCaja c) {
        Tabla tabla = new Tabla("Reintegros por método de pago", List.of("Método", "Motivo", "Reembolsos", "Importe (Bs)"));
        for (ReintegroPorMetodo r : c.reintegros()) {
            tabla.fila(r.metodo(), r.porDevolucion() ? "Devolución" : "Cancelación", r.cantidad(), Formatos.monto(r.importe()));
        }
        return ResultadoOperacion.exito("Reintegros ejecutados", "Reembolsos EJECUTADO cuya fecha de ejecución cae en el "
                        + "período; los PENDIENTE y FALLIDO no cuentan. " + NOTA_IMPORTES)
                .detalle("Período", c.periodo().describir())
                .detalle("Reintegros ejecutados (Bs)", Formatos.monto(c.reintegrosEjecutados()))
                .detalle("Por cancelación (Bs)", Formatos.monto(c.reintegrosPorCancelacion()))
                .detalle("Por devolución (Bs)", Formatos.monto(c.reintegrosPorDevolucion()))
                .tabla(tabla);
    }

    static ResultadoOperacion compras(AnalisisCompras c) {
        return ResultadoOperacion.exito("Compras aceptadas", "Costo de los ingresos por compra menos las anulaciones de "
                        + "recepción del período. Lo rechazado y lo cancelado no cuentan. Es distinto del importe acordado y "
                        + "de lo pagado a productores. " + NOTA_IMPORTES)
                .detalle("Período", c.periodo().describir())
                .detalle("Compras aceptadas (Bs)", Formatos.monto(c.comprasAceptadas()))
                .tabla(importes("Por productor", "Productor", c.porProductor()))
                .tabla(importes("Por comunidad de origen", "Comunidad de origen", c.porComunidadDeOrigen()))
                .tabla(importes("Por producto", "Producto", c.porProducto()));
    }

    static ResultadoOperacion costoNoRecuperado(LocalDate corte, SituacionDespachos despachos) {
        ResultadoOperacion r = ResultadoOperacion.exito("Costo no recuperado pendiente de cancelación", "Costo de lo que no "
                        + "volvió de las entregas NO_RECUPERADA cuyo pedido aún no se canceló. Es informativo: no es merma "
                        + "ni entra en el margen. " + NOTA_IMPORTES)
                .detalle("Fecha de corte", corte + " (todo el día, hora de Bolivia)")
                .detalle("Costo no recuperado pendiente (Bs)",
                        despachos.costoNoRecuperado().map(Formatos::monto).orElse(despachos.describirNoDeterminable()));
        if (!despachos.esDeterminable()) {
            return r;
        }
        Tabla tabla = new Tabla("Entregas afectadas", List.of("Pedido", "Producto", "Unidad", "Unidades no recuperadas",
                "Costo (Bs)"));
        for (MercanciaDespachada m : despachos.noRecuperadas()) {
            tabla.fila(m.pedido(), m.productoCodigo() + " " + m.productoNombre(), m.unidad(),
                    Formatos.cantidad(m.unidades()), Formatos.monto(m.costo()));
        }
        return r.tabla(tabla);
    }

    static ResultadoOperacion resumen(AnalisisComercial ventas, AnalisisCaja caja, AnalisisCompras compras,
                                      BigDecimal anticipos, BigDecimal valorInventario, SituacionDespachos despachos) {
        LocalDate corte = ventas.periodo().hasta();
        String periodo = ventas.periodo().describir();
        String alCorte = "Al " + corte;
        Tabla tabla = new Tabla("Indicadores financieros", List.of("Indicador", "Importe (Bs)", "Referencia"));
        tabla.fila("Ingresos reconocidos", Formatos.monto(ventas.ingresosReconocidos()), periodo + ", fecha de entrega")
                .fila("Importe cobrado por envío (aparte)", Formatos.monto(ventas.envioCobrado()), periodo + ", fecha de entrega")
                .fila("Costo de ventas", Formatos.monto(ventas.costoDeVentas()), periodo)
                .fila("Costo de posventa", Formatos.monto(ventas.costoPosventa()), periodo)
                .fila("Margen bruto", Formatos.monto(ventas.margenBruto()), periodo)
                .fila("Margen (%)", porcentaje(ventas.margenPorcentaje(), NO_APLICA), periodo)
                .fila("Mermas (informativo)", Formatos.monto(ventas.mermas()), periodo)
                .fila("Devuelto no apto (informativo)", Formatos.monto(ventas.devueltoNoApto()), periodo + ", fecha de inspección")
                .fila("Cobros confirmados", Formatos.monto(caja.cobrosConfirmados()), periodo + ", fecha de pago")
                .fila("Reintegros ejecutados", Formatos.monto(caja.reintegrosEjecutados()), periodo + ", fecha de ejecución")
                .fila("Compras aceptadas", Formatos.monto(compras.comprasAceptadas()), periodo + ", fecha del movimiento")
                .fila("Anticipos pendientes", Formatos.monto(anticipos), alCorte)
                .fila("Valor del inventario", Formatos.monto(valorInventario), alCorte)
                .fila("Costo no recuperado pendiente", despachos.costoNoRecuperado().map(Formatos::monto)
                        .orElse("No determinable"), alCorte);
        return ResultadoOperacion.exito("Resumen de indicadores financieros", "Los indicadores de ventas usan la fecha de "
                        + "entrega y los de caja las fechas de cobro y de reintegro: no son equivalentes y no se suman entre "
                        + "sí. Los datos a una fecha de corte usan la fecha hasta. " + NOTA_IMPORTES)
                .detalle("Período", periodo)
                .tabla(tabla);
    }

    static ResultadoOperacion serieMensual(Periodo periodo, List<IndicadoresDelMes> meses) {
        Tabla tabla = new Tabla("Indicadores por mes", List.of("Mes", "Ingresos reconocidos (Bs)", "Envío cobrado (Bs)",
                "Costo de ventas (Bs)", "Costo de posventa (Bs)", "Margen bruto (Bs)", "Margen (%)", "Mermas (Bs)",
                "Cobros confirmados (Bs)", "Reintegros ejecutados (Bs)", "Compras aceptadas (Bs)"));
        for (IndicadoresDelMes mes : meses) {
            AnalisisComercial v = mes.ventas();
            tabla.fila(mes.etiqueta(), Formatos.monto(v.ingresosReconocidos()), Formatos.monto(v.envioCobrado()),
                    Formatos.monto(v.costoDeVentas()), Formatos.monto(v.costoPosventa()), Formatos.monto(v.margenBruto()),
                    porcentaje(v.margenPorcentaje(), NO_APLICA), Formatos.monto(v.mermas()),
                    Formatos.monto(mes.caja().cobrosConfirmados()), Formatos.monto(mes.caja().reintegrosEjecutados()),
                    Formatos.monto(mes.compras().comprasAceptadas()));
        }
        return ResultadoOperacion.exito("Serie mensual de indicadores financieros", "Cada mes usa las mismas fórmulas y "
                        + "fechas que los indicadores del período; el primer y el último mes pueden ser parciales. "
                        + NOTA_IMPORTES)
                .detalle("Período", periodo.describir())
                .tabla(tabla);
    }

    static ResultadoOperacion conciliaciones(ConciliacionControl c) {
        Tabla caja = new Tabla("Conciliación de caja (acumulada hasta el corte)", List.of("Concepto", "Importe (Bs)"));
        caja.fila("Cobros confirmados", Formatos.monto(c.cobros()))
                .fila("Reintegros ejecutados", Formatos.monto(c.reintegros()))
                .fila("Cobros - reintegros", Formatos.monto(c.caja()))
                .fila("Ingresos reconocidos", Formatos.monto(c.ingresos()))
                .fila("Importe cobrado por envío", Formatos.monto(c.envio()))
                .fila("Anticipos pendientes", Formatos.monto(c.anticipos()))
                .fila("Ingresos + envío + anticipos", Formatos.monto(c.ventasMasAnticipos()))
                .fila("Diferencia", Formatos.monto(c.diferenciaCaja()));
        Tabla inventario = new Tabla("Conciliación de inventario a costo (acumulada hasta el corte)",
                List.of("Concepto", "Importe (Bs)"));
        inventario.fila("Inventario inicial", Formatos.monto(BigDecimal.ZERO))
                .fila("Entradas", Formatos.monto(c.entradas()))
                .fila("Salidas", Formatos.monto(c.salidas()))
                .fila("Inicial + entradas - salidas", Formatos.monto(c.inventarioCalculado()))
                .fila("Valor del inventario al corte", Formatos.monto(c.valorInventario()))
                .fila("Diferencia", Formatos.monto(c.diferenciaInventario()));
        return ResultadoOperacion.exito("Conciliaciones de control", "Controles con valores acumulados desde el primer "
                        + "registro hasta el corte. Una diferencia de caja distinta de 0 indica un pedido entregado sin "
                        + "cobro completo o un registro incoherente que debe revisarse. " + NOTA_IMPORTES)
                .detalle("Fecha de corte", c.corte() + " (todo el día, hora de Bolivia)")
                .detalle("Caja conciliada", Formatos.siNo(c.cajaConcilia()))
                .detalle("Inventario conciliado", Formatos.siNo(c.inventarioConcilia()))
                .tabla(caja)
                .tabla(inventario);
    }

    /** Tabla de importes agrupados por una clave; la usan CU7 y el reporte de abastecimiento. */
    static Tabla importes(String titulo, String columna, Map<String, BigDecimal> importes) {
        Tabla tabla = new Tabla(titulo, List.of(columna, "Costo aceptado (Bs)"));
        importes.forEach((clave, importe) -> tabla.fila(clave, Formatos.monto(importe)));
        return tabla;
    }

    // ---- Apoyo ----

    private static String porcentaje(Optional<BigDecimal> porcentaje, String siNoAplica) {
        return porcentaje.map(BigDecimal::toPlainString).orElse(siNoAplica);
    }

    private enum Columna {
        INGRESO("Ingreso reconocido (Bs)", d -> Formatos.monto(d.ingreso())),
        COSTO_VENTAS("Costo de ventas (Bs)", d -> Formatos.monto(d.costoVentas())),
        COSTO_POSVENTA("Costo de posventa (Bs)", d -> Formatos.monto(d.costoPosventa())),
        MARGEN("Margen bruto (Bs)", d -> Formatos.monto(d.margen())),
        MARGEN_PORCENTAJE("Margen (%)", d -> porcentaje(d.margenPorcentaje(), NO_APLICA)),
        MERMAS("Mermas, informativo (Bs)", d -> Formatos.monto(d.mermas())),
        NO_APTO("Devuelto no apto, informativo (Bs)", d -> Formatos.monto(d.devueltoNoApto()));

        private final String titulo;
        private final Function<DesgloseComercial, String> valor;

        Columna(String titulo, Function<DesgloseComercial, String> valor) {
            this.titulo = titulo;
            this.valor = valor;
        }
    }

    private static Tabla desglose(String titulo, List<DesgloseComercial> filas, boolean porProducto, Columna... columnas) {
        List<String> encabezados = new ArrayList<>(porProducto
                ? List.of("Código", "Producto", "Categoría") : List.of("Categoría"));
        for (Columna columna : columnas) {
            encabezados.add(columna.titulo);
        }
        Tabla tabla = new Tabla(titulo, encabezados);
        for (DesgloseComercial d : filas) {
            List<Object> fila = new ArrayList<>(porProducto
                    ? List.of(d.codigo(), d.nombre(), d.categoria()) : List.of(d.categoria()));
            for (Columna columna : columnas) {
                fila.add(columna.valor.apply(d));
            }
            tabla.fila(fila.toArray());
        }
        return tabla;
    }
}
