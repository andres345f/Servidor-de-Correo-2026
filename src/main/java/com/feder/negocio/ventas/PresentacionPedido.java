package com.feder.negocio.ventas;

import com.feder.datos.ventas.PedidoCompleto;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.Entrega;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoPedido;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.comun.ResultadoOperacion;

import java.util.List;

/** Resumen de un pedido con su entrega y sus líneas, común a las respuestas de CU5 y CU6. */
public final class PresentacionPedido {

    private PresentacionPedido() {
    }

    public static ResultadoOperacion resumen(String titulo, PedidoCompleto c) {
        Pedido p = c.pedido();
        Entrega e = c.entrega();
        ResultadoOperacion r = ResultadoOperacion.exito(titulo, "")
                .detalle("Código", p.getCodigo()).detalle("Tipo", p.getTipo()).detalle("Estado", p.getEstado())
                .detalle("Fecha", FechasBolivia.formatear(p.getFechaCreacion()))
                .detalle("Subtotal (Bs)", Importes.formatear(p.getSubtotal()))
                .detalle("Costo de envío (Bs)", Importes.formatear(p.getCostoEnvio()))
                .detalle("Total (Bs)", Importes.formatear(p.getTotal()));
        if (p.getTipo() == TipoPedido.ENCARGO) {
            r.detalle("Fecha estimada de disponibilidad", p.getFechaEstimadaDisponibilidad() == null
                    ? "Por definir" : p.getFechaEstimadaDisponibilidad() + " (estimación, no garantizada)");
        }
        if (e != null) {
            r.detalle("Entrega", e.getModalidad() + " - " + e.getEstado()).detalle("Dirección", e.getDireccion())
                    .detalle("Referencia", e.getReferencia()).detalle("Recibe", e.getNombreReceptor())
                    .detalle("Teléfono", e.getTelefonoContacto());
        }
        r.detalle("Observación", p.getObservacion());
        ResultadoOperacion.Tabla lineas = new ResultadoOperacion.Tabla("Líneas", List.of("Producto", "Cantidad",
                "Precio unitario (Bs)", "Subtotal (Bs)"));
        for (DetallePedido d : c.lineas()) {
            lineas.fila(d.getDescripcionVenta(), Cantidades.formatear(d.getCantidad()), Importes.formatear(d.getPrecioUnitario()),
                    Importes.formatear(d.getSubtotal()));
        }
        return r.tabla(lineas);
    }
}
