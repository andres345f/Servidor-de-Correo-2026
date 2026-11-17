package com.feder.negocio.indicadores;

import com.feder.datos.reportes.ReporteTrazabilidadRepositorio;
import com.feder.datos.reportes.ReporteTrazabilidadRepositorio.AdvertenciaOrigen;
import com.feder.datos.reportes.ReporteTrazabilidadRepositorio.OrigenReserva;
import com.feder.datos.reportes.ReporteTrazabilidadRepositorio.PedidoTrazado;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Tabla;

import java.util.List;

/**
 * Trazabilidad de un pedido (CU8): de cada reserva consumida hasta la comunidad de origen histórica,
 * con retornos, bajas y reposiciones. La procedencia es la declarada y validada en CU3; el reporte no
 * incluye costos, márgenes ni datos personales del cliente o del productor.
 */
final class GeneradorReporteTrazabilidad {

    private final ReporteTrazabilidadRepositorio repositorio;

    GeneradorReporteTrazabilidad(ReporteTrazabilidadRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    ResultadoOperacion generar(String codigoPedido) {
        // Buscamos el pedido antes de armar el reporte: si el código no existe, respondemos con un error
        // claro en lugar de un documento vacío.
        PedidoTrazado pedido = repositorio.pedido(codigoPedido).orElseThrow(() -> NegocioException.noEncontrado(
                "No existe un pedido con el código " + codigoPedido + "."));
        List<OrigenReserva> origenes = repositorio.origenes(pedido.id());
        ResultadoOperacion resultado = ResultadoOperacion.exito("Trazabilidad del pedido " + pedido.codigo(),
                        "La procedencia mostrada es la declarada y validada al registrar la producción (CU3), "
                                + "no una comprobación del sistema.")
                .detalle("Pedido", pedido.codigo())
                .detalle("Tipo", pedido.tipo())
                .detalle("Estado actual", pedido.estado())
                .detalle("Creado", Formatos.fechaHora(pedido.fechaCreacion()));
        if (origenes.isEmpty()) {
            resultado.detalle("Despachos", "El pedido todavía no tiene reservas consumidas: no hay mercancía despachada que trazar.");
        }
        resultado.tabla(lineas(pedido.id())).tabla(cadena(origenes)).tabla(retornos(pedido.id())).tabla(reposiciones(pedido.id()));
        agregarAdvertencias(resultado, repositorio.advertenciasDeOrigen(pedido.id()));
        return resultado;
    }

    private Tabla lineas(long pedidoId) {
        Tabla tabla = new Tabla("Líneas del pedido", List.of("Descripción de venta", "Cantidad", "Despachado"));
        repositorio.lineas(pedidoId).forEach(l -> tabla.fila(l.descripcionVenta(), Formatos.cantidad(l.cantidad()),
                Formatos.cantidad(l.despachado())));
        return tabla;
    }

    private static Tabla cadena(List<OrigenReserva> origenes) {
        Tabla tabla = new Tabla("Origen de lo despachado", List.of("Reserva", "Línea", "Cantidad", "Salida de venta",
                "Lote FEDER", "Compra", "Productor", "Lote de origen", "Producción", "Comunidad de origen", "Municipio",
                "Departamento", "Comunidad activa hoy"));
        origenes.forEach(o -> tabla.fila(o.reservaId(), o.descripcionVenta(), Formatos.cantidad(o.cantidad()),
                Formatos.fechaHora(o.fechaSalida()), o.loteFeder(), o.compra(), o.productor(), o.loteOrigen(),
                o.produccion(), o.comunidad(), o.municipio(), o.departamento(), Formatos.siNo(o.comunidadActiva())));
        return tabla;
    }

    private Tabla retornos(long pedidoId) {
        Tabla tabla = new Tabla("Retornos y bajas de las reservas", List.of("Reserva", "Tipo", "Cantidad", "Fecha", "Motivo"));
        repositorio.retornosYBajas(pedidoId).forEach(m -> tabla.fila(m.reservaId(), m.tipo(), Formatos.cantidad(m.cantidad()),
                Formatos.fechaHora(m.fecha()), m.motivo()));
        return tabla;
    }

    private Tabla reposiciones(long pedidoId) {
        Tabla tabla = new Tabla("Reposiciones por devolución", List.of("Devolución", "Despacho devuelto",
                "Tipo del despacho devuelto", "Lote FEDER de la reposición", "Cantidad", "Fecha", "Origen de la reposición"));
        repositorio.reposiciones(pedidoId).forEach(r -> tabla.fila(r.devolucion(), r.despachoDevueltoId(),
                r.tipoDespachoDevuelto(), r.loteFeder(), Formatos.cantidad(r.cantidad()), Formatos.fechaHora(r.fecha()),
                r.comunidad()));
        return tabla;
    }

    private static void agregarAdvertencias(ResultadoOperacion resultado, List<AdvertenciaOrigen> advertencias) {
        for (AdvertenciaOrigen a : advertencias) {
            resultado.detalle("Advertencia de origen", "El " + Formatos.fechaHora(a.fecha()) + " se documentó un error de "
                    + "lote de origen en " + a.referencia() + ": " + a.motivo()
                    + ". Lo vendido conserva el origen registrado.");
        }
    }
}
