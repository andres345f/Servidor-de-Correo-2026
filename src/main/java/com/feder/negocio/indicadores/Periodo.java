package com.feder.negocio.indicadores;

import com.feder.negocio.comun.NegocioException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Período [desde, hasta] de los indicadores y reportes: desde las 00:00 de desde hasta antes de las
 * 00:00 del día siguiente a hasta, en hora de Bolivia. Los límites horarios se aplican en SQL.
 */
public record Periodo(LocalDate desde, LocalDate hasta) {

    /** Fecha anterior a cualquier registro del sistema; marca el inicio de los valores acumulados. */
    static final LocalDate INICIO_REGISTROS = LocalDate.of(2000, 1, 1);

    /** Máximo de meses de una serie mensual, para que una consulta por correo no sea excesiva. */
    static final int MAXIMO_MESES_SERIE = 24;

    public Periodo {
        if (desde == null || hasta == null) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "Indique las fechas desde y hasta del período con el formato AAAA-MM-DD.");
        }
        if (desde.isAfter(hasta)) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "La fecha desde (" + desde + ") es posterior a la fecha hasta (" + hasta + ").");
        }
    }

    /** Desde el primer registro hasta el corte: valores acumulados de las conciliaciones de control. */
    static Periodo acumuladoHasta(LocalDate corte) {
        return new Periodo(INICIO_REGISTROS, exigirCorte(corte));
    }

    public String describir() {
        return "Del " + desde + " al " + hasta + " (hora de Bolivia)";
    }

    /** Fragmento seguro para el nombre de un archivo adjunto. */
    public String sufijoArchivo() {
        return desde + "_" + hasta;
    }

    /** Meses calendario del período; el primero y el último se recortan a desde y hasta. */
    List<Periodo> meses() {
        YearMonth primero = YearMonth.from(desde);
        YearMonth ultimo = YearMonth.from(hasta);
        long cantidad = primero.until(ultimo, ChronoUnit.MONTHS) + 1;
        if (cantidad > MAXIMO_MESES_SERIE) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO, "La serie mensual admite hasta "
                    + MAXIMO_MESES_SERIE + " meses; el período pedido abarca " + cantidad + ".");
        }
        List<Periodo> meses = new ArrayList<>();
        for (YearMonth mes = primero; !mes.isAfter(ultimo); mes = mes.plusMonths(1)) {
            LocalDate inicio = mes.atDay(1).isBefore(desde) ? desde : mes.atDay(1);
            LocalDate fin = mes.atEndOfMonth().isAfter(hasta) ? hasta : mes.atEndOfMonth();
            meses.add(new Periodo(inicio, fin));
        }
        return meses;
    }

    static LocalDate exigirCorte(LocalDate corte) {
        if (corte == null) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "Indique la fecha de corte con el formato AAAA-MM-DD.");
        }
        return corte;
    }
}
