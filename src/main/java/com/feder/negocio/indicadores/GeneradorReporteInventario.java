package com.feder.negocio.indicadores;

import com.feder.datos.reportes.FiltrosConsulta;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio.MercanciaDespachada;
import com.feder.datos.reportes.ReporteInventarioRepositorio;
import com.feder.datos.reportes.ReporteInventarioRepositorio.LoteAlCorte;
import com.feder.datos.reportes.ReporteInventarioRepositorio.LoteConVencimiento;
import com.feder.datos.reportes.ReporteInventarioRepositorio.ProductoCatalogo;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Tabla;
import com.feder.negocio.indicadores.FiltrosReporte.Clave;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reporte de inventario de FEDER (CU8) a una fecha de corte. PERSONAL_FEDER ve solo cantidades; el valor a
 * costo, el costo de lo pendiente de entrega y el costo no recuperado se añaden solo cuando llega el valor
 * del inventario, es decir, para un ADMINISTRADOR vigente. Solo lo usa el servicio, después de comprobar
 * los permisos.
 */
final class GeneradorReporteInventario {

    private final ReporteInventarioRepositorio repositorio;
    private final Clock reloj;
    private final int diasProximoVencimiento;

    GeneradorReporteInventario(ReporteInventarioRepositorio repositorio, Clock reloj, int diasProximoVencimiento) {
        if (diasProximoVencimiento < 0) {
            throw new IllegalArgumentException("Los días de aviso de vencimiento no pueden ser negativos");
        }
        this.repositorio = repositorio;
        this.reloj = reloj;
        this.diasProximoVencimiento = diasProximoVencimiento;
    }

    /**
     * @param despachos       mercancía despachada al corte, ya limitada a los lotes que cumplen los filtros.
     * @param valorInventario presente solo para un ADMINISTRADOR; si falta, el reporte no muestra costos.
     */
    ResultadoOperacion generar(LocalDate corte, FiltrosReporte filtros, SituacionDespachos despachos,
                               Optional<BigDecimal> valorInventario) {
        boolean conCostos = valorInventario.isPresent();
        LocalDate hoy = Formatos.hoy(reloj);
        FiltrosConsulta.Lotes filtro = filtros.aLotes();
        List<LoteAlCorte> lotes = repositorio.lotes(corte, filtro);
        Periodo movimientos = periodoDeMovimientos(corte, filtros);
        ResultadoOperacion resultado = ResultadoOperacion.exito("Reporte de inventario de FEDER", mensaje(conCostos))
                .detalle("Fecha de corte", corte + " (todo el día, hora de Bolivia)")
                .detalle("Filtros", filtros.describir())
                .detalle("Aviso de vencimiento", "Lotes con saldo hoy que vencen hasta el " + hoy.plusDays(diasProximoVencimiento)
                        + " (hoy es " + hoy + ")");
        resultado.tabla(stockPorProducto(repositorio.productos(filtro), lotes, corte));
        resultado.tabla(lotes(lotes));
        resultado.tabla(avisosDeVencimiento(repositorio.lotesQueVencenHasta(hoy.plusDays(diasProximoVencimiento), filtro), hoy));
        resultado.tabla(movimientos(movimientos, filtro));
        agregarDespachos(resultado, despachos, conCostos);
        valorInventario.ifPresent(valor -> agregarCostos(resultado, valor, despachos));
        return resultado;
    }

    private static String mensaje(boolean conCostos) {
        String base = "Solo cuenta el inventario de FEDER (LOTE_FEDER); las existencias del productor no se suman. "
                + "Las cantidades físicas y reservadas son exactas a la fecha de corte. El estado BLOQUEADO, el stock "
                + "mínimo y la categoría son los actuales, y el vencimiento es el registrado en el lote de origen. Los "
                + "avisos de vencimiento son de hoy y usan el saldo actual de cada lote.";
        return conCostos ? base + " Incluye los costos del ADMINISTRADOR." : base + " Contenido sin valor a costo ni costos.";
    }

    /** Período de los movimientos: el del filtro, sin pasar del corte; sin filtro, todo hasta el corte. */
    private static Periodo periodoDeMovimientos(LocalDate corte, FiltrosReporte filtros) {
        LocalDate desde = filtros.fecha(Clave.DESDE) == null ? Periodo.INICIO_REGISTROS : filtros.fecha(Clave.DESDE);
        LocalDate hasta = filtros.fecha(Clave.HASTA) == null || filtros.fecha(Clave.HASTA).isAfter(corte)
                ? corte : filtros.fecha(Clave.HASTA);
        return new Periodo(desde, hasta);
    }

    private static Tabla stockPorProducto(List<ProductoCatalogo> productos, List<LoteAlCorte> lotes, LocalDate corte) {
        Tabla tabla = new Tabla("Stock vendible por producto", List.of("Código", "Producto", "Categoría", "Unidad",
                "Stock vendible", "Stock mínimo", "Alerta"));
        for (ProductoCatalogo producto : productos) {
            BigDecimal vendible = stockVendible(producto.id(), lotes, corte);
            tabla.fila(producto.codigo(), producto.nombre(), producto.categoria(), producto.unidad(),
                    Formatos.cantidad(vendible), Formatos.cantidad(producto.stockMinimo()), alerta(producto, vendible));
        }
        return tabla;
    }

    /** PRODUCTO «Stock vendible derivado»: saldo libre de los lotes no bloqueados ni vencidos. */
    static BigDecimal stockVendible(long productoId, List<LoteAlCorte> lotes, LocalDate corte) {
        BigDecimal total = BigDecimal.ZERO;
        for (LoteAlCorte lote : lotes) {
            if (lote.productoId() == productoId && esVendible(lote, corte)) {
                total = total.add(lote.saldo().subtract(lote.reservado()).max(BigDecimal.ZERO));
            }
        }
        return total;
    }

    private static boolean esVendible(LoteAlCorte lote, LocalDate corte) {
        boolean vencido = lote.vencimiento() != null && lote.vencimiento().isBefore(corte);
        return !"BLOQUEADO".equals(lote.estadoActual()) && !vencido;
    }

    private static String alerta(ProductoCatalogo producto, BigDecimal vendible) {
        String alerta = vendible.signum() == 0 ? "Agotado"
                : vendible.compareTo(producto.stockMinimo()) < 0 ? "Bajo mínimo" : "";
        return producto.activo() ? alerta : (alerta + " (producto inactivo)").strip();
    }

    private static Tabla lotes(List<LoteAlCorte> lotes) {
        Tabla tabla = new Tabla("Lotes de FEDER al corte", List.of("Lote", "Producto", "Productor", "Comunidad de origen",
                "Saldo", "Reservado", "Estado actual", "Ubicación", "Ingreso", "Vencimiento de origen"));
        for (LoteAlCorte l : lotes) {
            tabla.fila(l.codigo(), l.productoCodigo(), l.productor(), l.comunidadOrigen(), Formatos.cantidad(l.saldo()),
                    Formatos.cantidad(l.reservado()), l.estadoActual(), l.ubicacion(), Formatos.dia(l.fechaIngreso()),
                    Formatos.fecha(l.vencimiento()));
        }
        return tabla;
    }

    /** Avisos de hoy (R070): saldo actual mayor que 0 y vencimiento de origen vencido o dentro de N días. */
    private static Tabla avisosDeVencimiento(List<LoteConVencimiento> lotes, LocalDate hoy) {
        Tabla tabla = new Tabla("Avisos de vencimiento a hoy (" + hoy + ")", List.of("Lote", "Producto", "Saldo actual",
                "Estado actual", "Vencimiento de origen", "Aviso"));
        for (LoteConVencimiento l : lotes) {
            tabla.fila(l.codigo(), l.productoCodigo(), Formatos.cantidad(l.saldoActual()), l.estadoActual(),
                    Formatos.fecha(l.vencimiento()), aviso(l.vencimiento(), hoy));
        }
        return tabla;
    }

    static String aviso(LocalDate vencimiento, LocalDate hoy) {
        return vencimiento.isBefore(hoy) ? "Vencido pendiente de baja" : "Próximo a vencer";
    }

    private Tabla movimientos(Periodo periodo, FiltrosConsulta.Lotes filtro) {
        String titulo = Periodo.INICIO_REGISTROS.equals(periodo.desde())
                ? "Movimientos hasta el " + periodo.hasta() + " por tipo, producto y lote"
                : "Movimientos del " + periodo.desde() + " al " + periodo.hasta() + " por tipo, producto y lote";
        Tabla tabla = new Tabla(titulo, List.of("Tipo", "Producto", "Lote", "Cantidad", "Movimientos"));
        repositorio.movimientos(periodo.desde(), periodo.hasta(), filtro).forEach(m -> tabla.fila(m.tipo(),
                m.productoCodigo(), m.lote(), Formatos.cantidad(m.cantidad()), m.movimientos()));
        return tabla;
    }

    private static void agregarDespachos(ResultadoOperacion resultado, SituacionDespachos despachos, boolean conCostos) {
        if (!despachos.esDeterminable()) {
            resultado.detalle("Mercancía despachada sin entregar", despachos.describirNoDeterminable());
            return;
        }
        resultado.tabla(tablaDespachos("Mercancía despachada pendiente de entrega", despachos.pendientesDeEntrega(), conCostos));
        resultado.tabla(tablaDespachos("Entregas NO_RECUPERADA pendientes de cancelación", despachos.noRecuperadas(), conCostos));
    }

    private static Tabla tablaDespachos(String titulo, List<MercanciaDespachada> filas, boolean conCostos) {
        List<String> columnas = new ArrayList<>(List.of("Pedido", "Estado de la entrega", "Producto", "Unidad", "Unidades"));
        if (conCostos) {
            columnas.add("Costo (Bs)");
        }
        Tabla tabla = new Tabla(titulo, columnas);
        for (MercanciaDespachada m : filas) {
            List<Object> fila = new ArrayList<>(List.of(m.pedido(), m.estadoEntrega(), m.productoCodigo() + " " + m.productoNombre(),
                    m.unidad(), Formatos.cantidad(m.unidades())));
            if (conCostos) {
                fila.add(Formatos.monto(m.costo()));
            }
            tabla.fila(fila.toArray());
        }
        return tabla;
    }

    private static void agregarCostos(ResultadoOperacion resultado, BigDecimal valor, SituacionDespachos despachos) {
        resultado.detalle("Valor del inventario a costo (Bs)", Formatos.monto(valor))
                .detalle("Costo de la mercancía pendiente de entrega (Bs)",
                        despachos.costoPendienteDeEntrega().map(Formatos::monto).orElse("No determinable"))
                .detalle("Costo no recuperado pendiente de cancelación (Bs)",
                        despachos.costoNoRecuperado().map(Formatos::monto).orElse("No determinable"));
    }
}
