package com.feder.modelo;

/** Enumeración EstadoEntrega del modelo FEDER: avance logístico de una entrega. */
public enum EstadoEntrega {
    PENDIENTE,
    LISTA_PARA_RECOJO,
    EN_CAMINO,
    ENTREGADA,
    FALLIDA,
    RETORNADA,
    NO_RECUPERADA,
    CANCELADA
}
