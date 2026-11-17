package com.feder.negocio.indicadores;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Redondeo y conversión a texto de importes, cantidades y fechas de los indicadores y reportes.
 * Los cálculos se hacen a precisión completa y solo el valor presentado se redondea half-up a 2 decimales.
 * Los números se escriben con punto decimal y sin separador de miles para que la hoja de cálculo
 * los reconozca como números.
 */
public final class Formatos {

    public static final ZoneId ZONA_BOLIVIA = ZoneId.of("America/La_Paz");

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private Formatos() {
    }

    public static BigDecimal redondear(BigDecimal importe) {
        return importe.setScale(2, RoundingMode.HALF_UP);
    }

    public static String monto(BigDecimal importe) {
        return importe == null ? "" : redondear(importe).toPlainString();
    }

    /** Cantidad en la unidad del producto, sin ceros decimales sobrantes (4, 2.5, 45.9). */
    public static String cantidad(BigDecimal cantidad) {
        if (cantidad == null) {
            return "";
        }
        return cantidad.signum() == 0 ? "0" : cantidad.stripTrailingZeros().toPlainString();
    }

    /** Porcentaje parte / total x 100, redondeado a 2 decimales. El llamador garantiza total distinto de 0. */
    public static BigDecimal porcentaje(BigDecimal parte, BigDecimal total) {
        return parte.multiply(CIEN).divide(total, 2, RoundingMode.HALF_UP);
    }

    public static String fechaHora(OffsetDateTime momento) {
        return momento == null ? "" : momento.atZoneSameInstant(ZONA_BOLIVIA).format(FECHA_HORA);
    }

    /** Solo el día (hora de Bolivia) de un momento; se usa en tablas anchas para que quepan en la página. */
    public static String dia(OffsetDateTime momento) {
        return momento == null ? "" : momento.atZoneSameInstant(ZONA_BOLIVIA).toLocalDate().toString();
    }

    public static String fecha(LocalDate fecha) {
        return fecha == null ? "" : fecha.toString();
    }

    public static String siNo(boolean valor) {
        return valor ? "Sí" : "No";
    }

    /** Fecha actual en Bolivia según el reloj del contexto. */
    public static LocalDate hoy(Clock reloj) {
        return LocalDate.ofInstant(reloj.instant(), ZONA_BOLIVIA);
    }
}
