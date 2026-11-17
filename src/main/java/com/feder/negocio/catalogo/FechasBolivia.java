package com.feder.negocio.catalogo;

import com.feder.negocio.comandos.ValidadorParametros;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Fechas de negocio en hora de Bolivia (America/La_Paz): el «hoy» con el que se decide si un lote
 * está vencido (R035) y el formato con que se muestran los momentos en las respuestas.
 */
public final class FechasBolivia {

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private FechasBolivia() {
    }

    public static LocalDate hoy(Clock reloj) {
        return LocalDate.now(reloj.withZone(ValidadorParametros.ZONA_BOLIVIA));
    }

    public static String formatear(OffsetDateTime momento) {
        return momento == null ? "" : momento.atZoneSameInstant(ValidadorParametros.ZONA_BOLIVIA).format(FORMATO);
    }

    public static String formatear(LocalDate fecha) {
        return fecha == null ? "" : fecha.toString();
    }
}
