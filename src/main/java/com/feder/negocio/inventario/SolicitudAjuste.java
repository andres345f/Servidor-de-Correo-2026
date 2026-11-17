package com.feder.negocio.inventario;

import com.feder.modelo.TipoMovimiento;

import java.math.BigDecimal;

/**
 * Ajuste o baja manual de un lote de FEDER (AJUSTE_POSITIVO, AJUSTE_NEGATIVO, BAJA_VENCIDO, BAJA_DANADO
 * o BAJA_DEFECTUOSO). La evidencia del conteo físico solo se exige en el ajuste positivo (R041).
 */
public record SolicitudAjuste(String codigoLote, TipoMovimiento tipo, BigDecimal cantidad, String motivo,
                              String claveOperacion, String evidencia) {
}
