package com.feder.modelo;

/** Enumeración EstadoPedido del modelo FEDER: estados de un pedido inmediato o de un encargo. */
public enum EstadoPedido {
    PENDIENTE,
    ESPERANDO_ABASTECIMIENTO,
    CONFIRMADO,
    EN_PREPARACION,
    DESPACHADO,
    ENTREGADO,
    CANCELADO
}
