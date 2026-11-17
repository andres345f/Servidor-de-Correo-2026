package com.feder.negocio.inventario;

import com.feder.datos.compras.DetalleCompraVista;
import com.feder.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de una operación sobre una línea de compra (recepción, cancelación o anulación de una
 * aceptación). Si {@code yaRegistrada} es true, la clave de operación ya se había confirmado antes y
 * no se modificó nada: se devuelve la operación registrada. {@code abastecidos} lista los pedidos que
 * recibieron reservas con lo aceptado en esta recepción (R056).
 */
public record ResultadoLinea(String codigoCompra, DetalleCompraVista linea, String codigoLoteFeder,
                             EstadoCompra estadoCompra, BigDecimal aceptada, BigDecimal rechazada,
                             BigDecimal cancelada, BigDecimal anulada, boolean yaRegistrada,
                             List<PedidoAbastecido> abastecidos) {
}
