package com.feder.negocio.indicadores;

import com.feder.datos.reportes.FiltrosConsulta;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio.MercanciaDespachada;
import com.feder.datos.reportes.ReporteVentasRepositorio;
import com.feder.datos.reportes.ReporteVentasRepositorio.CantidadVendida;
import com.feder.datos.reportes.ReporteVentasRepositorio.EntregaAbierta;
import com.feder.datos.reportes.ReporteVentasRepositorio.FallosEntrega;
import com.feder.datos.reportes.ReporteVentasRepositorio.SaldoPedido;
import com.feder.datos.reportes.ReporteVentasRepositorio.TiemposPedido;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Tabla;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reporte de ventas y pedidos (CU8). El contenido operativo (cantidades, conteos, estados e importes
 * individuales de cada pedido) lo ven ADMINISTRADOR y PERSONAL_FEDER; los importes agregados solo se
 * añaden cuando llegan {@link ImportesVentas}, es decir, para un ADMINISTRADOR vigente. Solo lo usa el
 * servicio, después de comprobar los permisos.
 */
final class GeneradorReporteVentas {

    /** Importes agregados que solo ve el ADMINISTRADOR, calculados con las fórmulas de CU7. */
    record ImportesVentas(AnalisisComercial comercial, AnalisisCaja caja) {
    }

    private final ReporteVentasRepositorio repositorio;

    GeneradorReporteVentas(ReporteVentasRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    ResultadoOperacion generar(Periodo periodo, FiltrosReporte filtros, SituacionDespachos despachos,
                               Optional<ImportesVentas> importes) {
        LocalDate desde = periodo.desde();
        LocalDate hasta = periodo.hasta();
        FiltrosConsulta.Ventas filtro = filtros.aVentas();
        ResultadoOperacion resultado = ResultadoOperacion.exito("Reporte de ventas y pedidos", mensaje(importes.isPresent()))
                .detalle("Período", periodo.describir())
                .detalle("Estados evaluados al", hasta + " (fin del día, hora de Bolivia)")
                .detalle("Filtros", filtros.describir());
        List<CantidadVendida> vendidas = repositorio.cantidadesVendidas(desde, hasta, filtro);
        resultado.tabla(cantidadesVendidas(vendidas));
        resultado.tabla(pedidosPorEstado(desde, hasta, filtro)).tabla(eventosDelPeriodo(desde, hasta, filtro));
        resultado.tabla(encargosEnEspera(desde, hasta, filtro));
        List<EntregaAbierta> entregas = repositorio.entregasAbiertas(desde, hasta, filtro);
        resultado.tabla(entregasAbiertas(entregas));
        agregarFallos(resultado, repositorio.fallosEntrega(desde, hasta, filtro));
        agregarNoRecuperadas(resultado, despachos, pedidos(entregas));
        SeccionPosventa.agregar(resultado, repositorio, periodo, filtro);
        List<SaldoPedido> saldos = repositorio.saldosPendientes(desde, hasta, filtro);
        resultado.tabla(saldosPendientes(saldos));
        agregarTiempos(resultado, repositorio.tiempos(desde, hasta, filtro));
        importes.ifPresent(i -> SeccionImportesVentas.agregar(resultado, i, filtros, vendidas, saldos));
        return resultado;
    }

    private static String mensaje(boolean conImportes) {
        String base = "Cada dato usa la fecha de su propio evento. Las cantidades se expresan en la unidad de medida "
                + "de cada producto y las devoluciones se informan aparte, sin restarse de lo entregado. Un filtro de "
                + "producto o categoría selecciona los pedidos que tienen alguna línea de ese producto o categoría.";
        return conImportes ? base + " Incluye los importes agregados del ADMINISTRADOR."
                : base + " Contenido operativo: sin importes agregados, costos ni márgenes.";
    }

    private static Tabla cantidadesVendidas(List<CantidadVendida> vendidas) {
        Tabla tabla = new Tabla("Cantidades entregadas en el período",
                List.of("Código", "Producto", "Unidad", "Cantidad entregada", "Posición en su unidad"));
        vendidas.forEach(v -> tabla.fila(v.codigo(), v.nombre(), v.unidad(), Formatos.cantidad(v.cantidad()),
                v.posicionEnUnidad()));
        return tabla;
    }

    private Tabla pedidosPorEstado(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        Tabla tabla = new Tabla("Pedidos por estado al " + hasta, List.of("Tipo", "Estado", "Pedidos"));
        repositorio.pedidosPorEstado(desde, hasta, filtro).forEach(p -> tabla.fila(p.tipo(), p.estado(), p.cantidad()));
        return tabla;
    }

    private Tabla eventosDelPeriodo(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        Tabla tabla = new Tabla("Eventos de pedidos en el período", List.of("Tipo", "Creados", "Entregados",
                "Cancelados antes del despacho", "Cancelados tras el despacho"));
        repositorio.eventosPorTipo(desde, hasta, filtro).forEach(e -> tabla.fila(e.tipo(), e.creados(), e.entregados(),
                e.canceladosAntesDelDespacho(), e.canceladosTrasElDespacho()));
        return tabla;
    }

    private Tabla encargosEnEspera(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        Tabla tabla = new Tabla("Encargos en espera de abastecimiento", List.of("Pedido", "Producto", "Unidad",
                "Cantidad", "Cubierta", "Pendiente", "Producción de respaldo", "Estado de la producción",
                "Disponibilidad estimada de la producción", "Fecha estimada del encargo"));
        repositorio.encargosEnEspera(desde, hasta, filtro).forEach(e -> tabla.fila(e.pedido(), e.producto(), e.unidad(),
                Formatos.cantidad(e.cantidad()), Formatos.cantidad(e.cubierta()),
                Formatos.cantidad(e.cantidad().subtract(e.cubierta()).max(BigDecimal.ZERO)),
                e.produccion() == null ? "Sin producción vinculada" : e.produccion(),
                e.estadoProduccion(), Formatos.fecha(e.disponibilidadProduccion()), Formatos.fecha(e.fechaEstimada())));
        return tabla;
    }

    private static Tabla entregasAbiertas(List<EntregaAbierta> entregas) {
        Tabla tabla = new Tabla("Entregas pendientes y despachos sin entregar", List.of("Pedido", "Tipo", "Modalidad",
                "Estado del pedido", "Estado de la entrega", "Situación", "Fecha programada"));
        entregas.forEach(e -> tabla.fila(e.pedido(), e.tipo(), e.modalidad(), e.estadoPedido(), e.estadoEntrega(),
                situacion(e), Formatos.fechaHora(e.fechaProgramada())));
        return tabla;
    }

    static String situacion(EntregaAbierta entrega) {
        if (!"DESPACHADO".equals(entrega.estadoPedido())) {
            return "Pendiente antes del despacho";
        }
        if (ReporteVentasRepositorio.ENTREGA_NO_DETERMINABLE.equals(entrega.estadoEntrega())) {
            return "No determinable: faltan las transiciones de la entrega en la bitácora";
        }
        return "NO_RECUPERADA".equals(entrega.estadoEntrega()) ? "No recuperada, pendiente de cancelación"
                : "Despachado sin entregar";
    }

    private static Set<String> pedidos(List<EntregaAbierta> entregas) {
        return entregas.stream().map(EntregaAbierta::pedido).collect(Collectors.toSet());
    }

    private static void agregarFallos(ResultadoOperacion resultado, FallosEntrega fallos) {
        resultado.detalle("Entregas fallidas en el período", fallos.fallos())
                .detalle("Reintentos de entrega en el período", fallos.reintentos());
    }

    /** Las entregas NO_RECUPERADA se limitan a los pedidos que pasan los filtros del reporte. */
    private static void agregarNoRecuperadas(ResultadoOperacion resultado, SituacionDespachos despachos,
                                             Set<String> pedidosFiltrados) {
        if (!despachos.esDeterminable()) {
            resultado.detalle("Entregas no recuperadas", despachos.describirNoDeterminable());
            return;
        }
        Tabla tabla = new Tabla("Entregas NO_RECUPERADA pendientes de cancelación",
                List.of("Pedido", "Producto", "Unidad", "Unidades no recuperadas"));
        for (MercanciaDespachada m : despachos.noRecuperadas()) {
            if (pedidosFiltrados.contains(m.pedido())) {
                tabla.fila(m.pedido(), m.productoCodigo() + " " + m.productoNombre(), m.unidad(),
                        Formatos.cantidad(m.unidades()));
            }
        }
        resultado.tabla(tabla);
    }

    private static Tabla saldosPendientes(List<SaldoPedido> saldos) {
        Tabla tabla = new Tabla("Saldo pendiente de cobro de pedidos vigentes (información operativa, no exigible)",
                List.of("Pedido", "Tipo", "Estado", "Situación del cobro", "Total (Bs)", "Pagado (Bs)", "Saldo (Bs)"));
        saldos.forEach(s -> tabla.fila(s.pedido(), s.tipo(), s.estado(), situacionCobro(s), Formatos.monto(s.total()),
                Formatos.monto(s.pagado()), Formatos.monto(saldo(s))));
        return tabla;
    }

    static BigDecimal saldo(SaldoPedido pedido) {
        return pedido.total().subtract(pedido.pagado()).max(BigDecimal.ZERO);
    }

    private static String situacionCobro(SaldoPedido pedido) {
        if (pedido.efectivoContraEntrega()) {
            return "Efectivo contra entrega";
        }
        if (pedido.pagado().signum() == 0) {
            return "ENCARGO".equals(pedido.tipo()) ? "Encargo sin anticipo" : "Sin cobro confirmado";
        }
        return saldo(pedido).signum() == 0 ? "Cobrado por anticipado" : "Cobro parcial";
    }

    private static void agregarTiempos(ResultadoOperacion resultado, List<TiemposPedido> tiempos) {
        Tabla tabla = new Tabla("Tiempos de preparación y entrega", List.of("Pedido", "En preparación", "Despachado",
                "Entregado", "Horas de preparación", "Horas de entrega"));
        PromedioHoras preparacion = new PromedioHoras();
        PromedioHoras entrega = new PromedioHoras();
        for (TiemposPedido t : tiempos) {
            tabla.fila(t.pedido(), Formatos.fechaHora(t.enPreparacion()), Formatos.fechaHora(t.despachado()),
                    Formatos.fechaHora(t.entregado()), preparacion.registrar(t.enPreparacion(), t.despachado()),
                    entrega.registrar(t.despachado(), t.entregado()));
        }
        resultado.tabla(tabla)
                .detalle("Promedio de preparación (horas, solo pedidos despachados)", preparacion.texto())
                .detalle("Promedio de entrega (horas, solo pedidos entregados)", entrega.texto());
    }
}
