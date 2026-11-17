package com.feder.negocio.posventa;

import com.feder.negocio.catalogo.Importes;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * DETALLE_PEDIDO «Redondeo monetario» (R018): importe reembolsable de una devolución que se aprueba con
 * REEMBOLSO, atribuido sobre la línea sin superar su saldo y sin depender de los reintegros ya hechos.
 *
 * importe = min(subtotal - atribuido, max(0, round(subtotal x acumulada / cantidad, 2) - atribuido)), donde
 * acumulada es la cantidad aprobada con REEMBOLSO de la línea más la que se aprueba (nunca mayor que la cantidad).
 */
public final class AtribucionImporte {

    private AtribucionImporte() {
    }

    /**
     * @param subtotalLinea     DETALLE_PEDIDO.subtotal
     * @param cantidadLinea     DETALLE_PEDIDO.cantidad
     * @param aprobadaAnterior  cantidad aprobada de las devoluciones REEMBOLSO APROBADA o COMPLETADA de la línea
     * @param atribuido         importe reembolsable ya fijado en esas devoluciones
     * @param aprobadaNueva     cantidad aprobada de la devolución que se aprueba
     */
    public static BigDecimal calcular(BigDecimal subtotalLinea, BigDecimal cantidadLinea, BigDecimal aprobadaAnterior,
                                      BigDecimal atribuido, BigDecimal aprobadaNueva) {
        BigDecimal acumulada = aprobadaAnterior.add(aprobadaNueva).min(cantidadLinea);
        BigDecimal proporcional = subtotalLinea.multiply(acumulada).divide(cantidadLinea, 2, RoundingMode.HALF_UP);
        BigDecimal porAtribuir = proporcional.subtract(atribuido).max(BigDecimal.ZERO);
        return Importes.redondear(porAtribuir.min(subtotalLinea.subtract(atribuido)).max(BigDecimal.ZERO));
    }
}
