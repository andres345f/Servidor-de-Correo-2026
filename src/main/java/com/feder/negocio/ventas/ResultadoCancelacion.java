package com.feder.negocio.ventas;

import com.feder.datos.ventas.PedidoCompleto;

import java.math.BigDecimal;
import java.util.List;

/** Resultado de cancelar un pedido: reservas liberadas e importes de los reintegros solicitados. */
public record ResultadoCancelacion(PedidoCompleto pedido, int reservasLiberadas, List<BigDecimal> reintegros) {
}
