package com.feder.negocio.inventario;

import com.feder.datos.inventario.LoteFederVista;
import com.feder.modelo.EstadoLoteFeder;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de pasar un lote a BLOQUEADO o VENCIDO (R035): reservas liberadas, cantidad reasignada con
 * FEFO desde otros lotes y pedidos que quedaron con líneas sin cubrir.
 */
public record LoteInhabilitado(LoteFederVista lote, EstadoLoteFeder estadoAnterior, int reservasLiberadas,
                               BigDecimal liberado, BigDecimal reasignado, List<String> pedidosSinCobertura) {
}
