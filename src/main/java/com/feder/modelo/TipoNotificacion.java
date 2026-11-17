package com.feder.modelo;

/** Enumeración TipoNotificacion del modelo FEDER: motivo de una notificación. */
public enum TipoNotificacion {
    CAMBIO_ESTADO_PEDIDO,
    PAGO_CONFIRMADO,
    PAGO_RECHAZADO,
    ENCARGO_ABASTECIDO,
    ALERTA_STOCK,
    DEVOLUCION_RESUELTA,
    FECHA_ESTIMADA_ACTUALIZADA,
    REEMBOLSO_EJECUTADO
}
