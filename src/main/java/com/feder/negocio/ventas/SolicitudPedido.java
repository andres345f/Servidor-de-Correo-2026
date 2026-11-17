package com.feder.negocio.ventas;

import com.feder.modelo.ModalidadEntrega;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Confirmación del carrito como pedido (R054): datos de la entrega y qué productos del carrito se
 * convierten. {@code productos} vacío significa todo el carrito. {@code subtotalEsperado} es obligatorio:
 * es el subtotal que el cliente vio con VERCAR para esa selección; si los precios cambiaron, no se confirma.
 */
public record SolicitudPedido(ModalidadEntrega modalidad, String direccion, String referencia, String nombreReceptor,
                              String telefonoContacto, Set<String> productos, BigDecimal subtotalEsperado,
                              String observacion) {
}
