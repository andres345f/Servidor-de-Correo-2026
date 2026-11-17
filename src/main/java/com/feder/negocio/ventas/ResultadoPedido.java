package com.feder.negocio.ventas;

import com.feder.datos.ventas.PedidoCompleto;

/** Pedido creado por la confirmación del carrito; {@code yaExistia} si la misma confirmación ya lo había creado. */
public record ResultadoPedido(PedidoCompleto pedido, boolean yaExistia) {
}
