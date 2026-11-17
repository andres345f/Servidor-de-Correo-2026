package com.feder.negocio.inventario;

import java.math.BigDecimal;

/** Cantidad que debe reservarse para una línea de pedido (DETALLE_PEDIDO) ya insertada. */
public record LineaPorReservar(long detallePedidoId, BigDecimal cantidad) {
}
