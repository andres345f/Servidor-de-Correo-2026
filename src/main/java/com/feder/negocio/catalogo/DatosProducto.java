package com.feder.negocio.catalogo;

import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;

/**
 * Datos de un producto del catálogo. En el alta todos los obligatorios deben venir;
 * en una modificación, un campo nulo significa «conservar el valor actual».
 */
public record DatosProducto(String codigo, String nombre, String categoria, String presentacion, UnidadMedida unidad,
                            BigDecimal precioVenta, BigDecimal stockMinimo, Boolean permiteEncargo,
                            String descripcion, String imagenUrl) {
}
