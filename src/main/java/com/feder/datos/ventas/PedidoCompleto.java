package com.feder.datos.ventas;

import com.feder.modelo.DetallePedido;
import com.feder.modelo.Entrega;
import com.feder.modelo.Pedido;

import java.util.List;

/** Pedido con sus líneas (A23) y su única entrega (A30). */
public record PedidoCompleto(Pedido pedido, List<DetallePedido> lineas, Entrega entrega) {
}
