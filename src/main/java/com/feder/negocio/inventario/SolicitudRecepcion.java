package com.feder.negocio.inventario;

import java.math.BigDecimal;

/**
 * Una entrega física de una línea de compra: lo aceptado entra al inventario y lo rechazado no.
 * La clave de operación se genera una sola vez y se repite sin cambios en cualquier reintento (R013).
 */
public record SolicitudRecepcion(String codigoCompra, String codigoLoteOrigen, BigDecimal aceptada, BigDecimal rechazada,
                                 String ubicacion, String motivo, String documentoEntrega, String claveOperacion) {
}
