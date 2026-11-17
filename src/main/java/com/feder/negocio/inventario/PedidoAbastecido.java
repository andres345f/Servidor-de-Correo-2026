package com.feder.negocio.inventario;

import java.math.BigDecimal;

/**
 * Resultado del abastecimiento de un pedido (R056): unidades reservadas en esta operación, si todas sus
 * líneas quedaron cubiertas y si el encargo pasó a CONFIRMADO con ello.
 */
public record PedidoAbastecido(String codigoPedido, BigDecimal reservado, boolean cubierto, boolean confirmado) {
}
