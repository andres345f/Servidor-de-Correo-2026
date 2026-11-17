package com.feder.negocio.usuarios;

import com.feder.negocio.comandos.ValidadorParametros;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Conversión a texto de fechas, cantidades e importes para las tablas y detalles de las respuestas.
 * Las fechas y horas se muestran siempre en hora de Bolivia (America/La_Paz).
 */
public final class FormatoNegocio {

    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private FormatoNegocio() {
    }

    public static String fechaHora(OffsetDateTime valor) {
        return valor == null ? "" : valor.atZoneSameInstant(ValidadorParametros.ZONA_BOLIVIA).format(FECHA_HORA);
    }

    public static String fecha(LocalDate valor) {
        return valor == null ? "" : valor.toString();
    }

    public static String cantidad(BigDecimal valor) {
        if (valor == null) {
            return "";
        }
        BigDecimal limpio = valor.stripTrailingZeros();
        return limpio.scale() < 0 ? limpio.setScale(0).toPlainString() : limpio.toPlainString();
    }

    public static String dinero(BigDecimal valor) {
        return valor == null ? "" : valor.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    public static String siNo(Boolean valor) {
        return Boolean.TRUE.equals(valor) ? "Sí" : "No";
    }

    /** Fecha calendario actual en Bolivia según el reloj del contexto. */
    public static LocalDate hoy(Clock reloj) {
        return LocalDate.now(reloj.withZone(ValidadorParametros.ZONA_BOLIVIA));
    }
}
