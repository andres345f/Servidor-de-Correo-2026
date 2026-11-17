package com.feder.negocio.indicadores;

import com.feder.datos.reportes.FiltrosConsulta;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio.AceptadoPorOrigen;
import com.feder.datos.reportes.ReporteAbastecimientoRepositorio;
import com.feder.datos.reportes.ReporteAbastecimientoRepositorio.ImportesCompra;
import com.feder.datos.reportes.ReporteAbastecimientoRepositorio.LineaCompra;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Tabla;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Reporte de abastecimiento de FEDER (CU8): compras a productores, lo aceptado por origen y los productores
 * registrados. Es suministro a FEDER, no venta. Los importes solo se añaden para un ADMINISTRADOR vigente.
 * Solo lo usa el servicio, después de comprobar los permisos.
 */
final class GeneradorReporteAbastecimiento {

    /** Aporte neto de un productor o de una comunidad a un producto, con su participación si corresponde. */
    record Participacion(String producto, String unidad, String origen, BigDecimal neto, Optional<BigDecimal> porcentaje) {
    }

    private final ReporteAbastecimientoRepositorio repositorio;

    GeneradorReporteAbastecimiento(ReporteAbastecimientoRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    ResultadoOperacion generar(Periodo periodo, FiltrosReporte filtros, List<AceptadoPorOrigen> aceptado,
                               boolean conImportes) {
        FiltrosConsulta.Compras filtro = filtros.aCompras();
        ResultadoOperacion resultado = ResultadoOperacion.exito("Reporte de abastecimiento", mensaje(conImportes))
                .detalle("Período", periodo.describir())
                .detalle("Filtros", filtros.describir());
        resultado.tabla(compras(repositorio.lineasDeCompra(periodo.desde(), periodo.hasta(), filtro), conImportes));
        resultado.tabla(aceptadoPorOrigen(aceptado));
        resultado.tabla(tablaParticipacion("Participación de cada productor en el producto", "Productor",
                participaciones(aceptado, AceptadoPorOrigen::productor)));
        resultado.tabla(tablaParticipacion("Participación de cada comunidad de origen en el producto", "Comunidad de origen",
                participaciones(aceptado, GeneradorReporteAbastecimiento::comunidad)));
        resultado.tabla(productores(filtro));
        if (conImportes) {
            agregarImportes(resultado, new AnalisisCompras(periodo, aceptado),
                    repositorio.importes(periodo.desde(), periodo.hasta(), filtro));
        }
        return resultado;
    }

    private static String mensaje(boolean conImportes) {
        String base = "Las cantidades proceden de recepciones idempotentes; dos registros con claves distintas de una "
                + "misma entrega física no se detectan por la clave y se concilian con el conteo físico. La comunidad "
                + "de origen es la histórica de cada producción, no la comunidad actual del productor. Las cantidades "
                + "solo se comparan dentro de un mismo producto.";
        return conImportes ? base + " Incluye los importes del ADMINISTRADOR." : base + " Contenido sin importes.";
    }

    private static String comunidad(AceptadoPorOrigen a) {
        return a.comunidad() + " (" + a.municipio() + ")";
    }

    private static Tabla compras(List<LineaCompra> lineas, boolean conImportes) {
        List<String> columnas = new ArrayList<>(List.of("Compra", "Fecha", "Productor", "Estado", "Producto", "Unidad",
                "Lote de origen", "Comprada", "Aceptada", "Rechazada", "Cancelada", "Pendiente"));
        if (conImportes) {
            columnas.add("Precio unitario (Bs)");
        }
        Tabla tabla = new Tabla("Compras del período (por fecha de compra)", columnas);
        for (LineaCompra l : lineas) {
            BigDecimal pendiente = l.comprada().subtract(l.aceptada()).subtract(l.rechazada()).subtract(l.cancelada());
            List<Object> fila = new ArrayList<>(List.of(l.compra(), Formatos.fechaHora(l.fechaCompra()), l.productor(),
                    l.estado(), l.producto(), l.unidad(), l.loteOrigen(), Formatos.cantidad(l.comprada()),
                    Formatos.cantidad(l.aceptada()), Formatos.cantidad(l.rechazada()), Formatos.cantidad(l.cancelada()),
                    Formatos.cantidad(pendiente)));
            if (conImportes) {
                fila.add(Formatos.monto(l.precioUnitario()));
            }
            tabla.fila(fila.toArray());
        }
        return tabla;
    }

    private static Tabla aceptadoPorOrigen(List<AceptadoPorOrigen> aceptado) {
        Tabla tabla = new Tabla("Aceptado en el período por fecha del movimiento", List.of("Producto", "Unidad", "Productor",
                "Comunidad de origen", "Entradas de compra", "Anulaciones de recepción", "Neto"));
        for (AceptadoPorOrigen a : aceptado) {
            tabla.fila(a.productoCodigo() + " " + a.productoNombre(), a.unidad(), a.productor(), comunidad(a),
                    Formatos.cantidad(a.cantidadEntrada()), Formatos.cantidad(a.cantidadAnulada()), Formatos.cantidad(neto(a)));
        }
        return tabla;
    }

    private static BigDecimal neto(AceptadoPorOrigen a) {
        return a.cantidadEntrada().subtract(a.cantidadAnulada());
    }

    /**
     * Agrupa lo aceptado por producto y por el origen indicado (productor o comunidad). El porcentaje se calcula
     * solo si el total neto del producto es positivo y ningún aporte agrupado es negativo; si no, solo se
     * informan las cantidades.
     */
    static List<Participacion> participaciones(List<AceptadoPorOrigen> aceptado, Function<AceptadoPorOrigen, String> origen) {
        Map<String, Map<String, BigDecimal>> porProducto = new TreeMap<>();
        Map<String, String> unidades = new LinkedHashMap<>();
        for (AceptadoPorOrigen a : aceptado) {
            String producto = a.productoCodigo() + " " + a.productoNombre();
            unidades.put(producto, a.unidad());
            porProducto.computeIfAbsent(producto, p -> new TreeMap<>()).merge(origen.apply(a), neto(a), BigDecimal::add);
        }
        List<Participacion> filas = new ArrayList<>();
        porProducto.forEach((producto, aportes) -> {
            BigDecimal total = aportes.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean calculable = total.signum() > 0 && aportes.values().stream().noneMatch(v -> v.signum() < 0);
            aportes.forEach((clave, neto) -> filas.add(new Participacion(producto, unidades.get(producto), clave, neto,
                    calculable ? Optional.of(Formatos.porcentaje(neto, total)) : Optional.empty())));
        });
        return filas;
    }

    private static Tabla tablaParticipacion(String titulo, String columna, List<Participacion> filas) {
        Tabla tabla = new Tabla(titulo, List.of("Producto", "Unidad", columna, "Neto aceptado", "Participación (%)"));
        filas.forEach(p -> tabla.fila(p.producto(), p.unidad(), p.origen(), Formatos.cantidad(p.neto()),
                p.porcentaje().map(BigDecimal::toPlainString).orElse("")));
        return tabla;
    }

    private Tabla productores(FiltrosConsulta.Compras filtro) {
        Tabla tabla = new Tabla("Productores registrados", List.of("Código", "Comunidad actual", "Fecha de afiliación",
                "Estado de la solicitud", "Activo", "Con compras", "Con suministros recibidos"));
        repositorio.productores(filtro).forEach(p -> tabla.fila(p.codigo(), p.comunidadActual(),
                Formatos.fecha(p.fechaAfiliacion()), p.estadoSolicitud(), Formatos.siNo(p.activo()),
                Formatos.siNo(p.tieneCompras()), Formatos.siNo(p.tieneSuministros())));
        return tabla;
    }

    private static void agregarImportes(ResultadoOperacion resultado, AnalisisCompras compras, ImportesCompra importes) {
        resultado.detalle("Compras aceptadas a costo (Bs)", Formatos.monto(compras.comprasAceptadas()))
                .detalle("Importe acordado de las compras del período (Bs)", Formatos.monto(importes.acordado()))
                .detalle("Importe pagado a productores en el período (Bs)", Formatos.monto(importes.pagado()));
        resultado.tabla(ResultadosIndicadores.importes("Compras aceptadas por productor", "Productor", compras.porProductor()))
                .tabla(ResultadosIndicadores.importes("Compras aceptadas por producto", "Producto", compras.porProducto()))
                .tabla(ResultadosIndicadores.importes("Compras aceptadas por comunidad de origen", "Comunidad de origen",
                        compras.porComunidadDeOrigen()));
    }
}
