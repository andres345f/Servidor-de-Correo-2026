package com.feder.modelo;

/** Enumeración TipoMovimiento del modelo FEDER: naturaleza de un movimiento de inventario (entrada o salida). */
public enum TipoMovimiento {
    ENTRADA_COMPRA,
    ANULACION_RECEPCION,
    SALIDA_VENTA,
    ENTRADA_RETORNO_ENTREGA,
    ENTRADA_DEVOLUCION,
    SALIDA_REPOSICION,
    AJUSTE_POSITIVO,
    AJUSTE_NEGATIVO,
    BAJA_VENCIDO,
    BAJA_DANADO,
    BAJA_DEFECTUOSO
}
