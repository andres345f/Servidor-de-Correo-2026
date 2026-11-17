package com.feder.negocio.indicadores;

import com.feder.datos.reportes.FiltrosConsulta;
import com.feder.datos.reportes.ReporteVentasRepositorio;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Tabla;

import java.util.List;

/**
 * Apartado de posventa del reporte de ventas: devoluciones con sus hechos fechados y su estado a la fecha
 * hasta, conteo por estado y resolución, cantidades vigentes por producto y reembolsos por estado.
 * Una reposición nunca se cuenta como venta.
 */
final class SeccionPosventa {

    private SeccionPosventa() {
    }

    static void agregar(ResultadoOperacion resultado, ReporteVentasRepositorio repositorio, Periodo periodo,
                        FiltrosConsulta.Ventas filtro) {
        resultado.tabla(devoluciones(repositorio, periodo, filtro))
                .tabla(porEstado(repositorio, periodo, filtro))
                .tabla(porProducto(repositorio, periodo, filtro))
                .tabla(reembolsos(repositorio, periodo, filtro));
    }

    private static Tabla devoluciones(ReporteVentasRepositorio repositorio, Periodo periodo, FiltrosConsulta.Ventas filtro) {
        Tabla tabla = new Tabla("Devoluciones con hechos en el período (estado al " + periodo.hasta() + ")",
                List.of("Código", "Pedido", "Producto", "Estado", "Resolución", "Rectifica a", "Solicitud", "Cantidad",
                        "Fecha de resolución", "Aprobada", "Fecha de recepción", "Recibida", "Apta", "Fecha de anulación"));
        repositorio.devoluciones(periodo.desde(), periodo.hasta(), filtro).forEach(d -> tabla.fila(d.codigo(), d.pedido(),
                d.producto(), d.estado(), d.resolucion(), d.rectificaA(), Formatos.dia(d.fechaSolicitud()),
                Formatos.cantidad(d.cantidad()), Formatos.dia(d.fechaResolucion()), Formatos.cantidad(d.cantidadAprobada()),
                Formatos.dia(d.fechaRecepcion()), Formatos.cantidad(d.cantidadRecibida()),
                Formatos.cantidad(d.cantidadApta()), Formatos.dia(d.fechaAnulacion())));
        return tabla;
    }

    private static Tabla porEstado(ReporteVentasRepositorio repositorio, Periodo periodo, FiltrosConsulta.Ventas filtro) {
        Tabla tabla = new Tabla("Devoluciones por estado y resolución al " + periodo.hasta(),
                List.of("Estado", "Resolución", "Devoluciones"));
        repositorio.devolucionesPorEstado(periodo.desde(), periodo.hasta(), filtro)
                .forEach(d -> tabla.fila(d.estado(), d.resolucion(), d.cantidad()));
        return tabla;
    }

    private static Tabla porProducto(ReporteVentasRepositorio repositorio, Periodo periodo, FiltrosConsulta.Ventas filtro) {
        Tabla tabla = new Tabla("Posventa por producto en el saldo vigente (cada cantidad por la fecha de su hecho)",
                List.of("Producto", "Unidad", "Solicitada", "Aprobada", "Recibida", "Reincorporada al inventario",
                        "Repuesta al cliente"));
        repositorio.posventaPorProducto(periodo.desde(), periodo.hasta(), filtro).forEach(p -> tabla.fila(p.producto(),
                p.unidad(), Formatos.cantidad(p.solicitada()), Formatos.cantidad(p.aprobada()),
                Formatos.cantidad(p.recibida()), Formatos.cantidad(p.reincorporada()), Formatos.cantidad(p.repuesta())));
        return tabla;
    }

    private static Tabla reembolsos(ReporteVentasRepositorio repositorio, Periodo periodo, FiltrosConsulta.Ventas filtro) {
        Tabla tabla = new Tabla("Reembolsos solicitados en el período por estado al " + periodo.hasta()
                + " (un FALLIDO se informa con su estado actual)", List.of("Estado", "Reembolsos"));
        repositorio.reembolsosPorEstado(periodo.desde(), periodo.hasta(), filtro)
                .forEach(r -> tabla.fila(r.estado(), r.cantidad()));
        return tabla;
    }
}
