package com.feder.negocio.produccion;

/**
 * Datos de una producción que solo corrige un ADMINISTRADOR, con motivo: la ubicación de elaboración
 * una vez que hay lotes (R065) y la instantánea de la comunidad de origen (R066).
 */
public enum CampoCorregible {
    UBICACION_PRODUCCION,
    NOMBRE_COMUNIDAD_ORIGEN,
    MUNICIPIO_ORIGEN,
    DEPARTAMENTO_ORIGEN
}
