package com.feder.negocio.indicadores;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Acumula duraciones en horas entre dos hitos de HISTORIAL_PEDIDO. Un tramo sin su evento final se
 * muestra «abierto» y no entra en el promedio.
 */
final class PromedioHoras {

    static final String ABIERTO = "abierto";
    private static final BigDecimal MINUTOS_POR_HORA = BigDecimal.valueOf(60);

    private BigDecimal totalHoras = BigDecimal.ZERO;
    private int tramos;

    /** Devuelve el texto del tramo: horas con 2 decimales, «abierto» o vacío si no empezó. */
    String registrar(OffsetDateTime inicio, OffsetDateTime fin) {
        if (inicio == null) {
            return "";
        }
        if (fin == null) {
            return ABIERTO;
        }
        BigDecimal horas = horas(inicio, fin);
        totalHoras = totalHoras.add(horas);
        tramos++;
        return horas.toPlainString();
    }

    String texto() {
        return tramos == 0 ? "Sin tramos cerrados"
                : totalHoras.divide(BigDecimal.valueOf(tramos), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static BigDecimal horas(OffsetDateTime inicio, OffsetDateTime fin) {
        long minutos = Duration.between(inicio, fin).toMinutes();
        return BigDecimal.valueOf(minutos).divide(MINUTOS_POR_HORA, 2, RoundingMode.HALF_UP);
    }
}
