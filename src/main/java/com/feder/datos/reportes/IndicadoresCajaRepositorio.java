package com.feder.datos.reportes;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Consultas de caja y anticipos (CU7, apartados 4 y 5): cobros por fecha_pago, reintegros por
 * fecha_ejecucion y anticipos a una fecha de corte. Nunca se mezclan con las fechas de entrega.
 */
public class IndicadoresCajaRepositorio extends RepositorioJdbc {

    /** Cobros PAGADO de un método de pago en el período. */
    public record CobroPorMetodo(String metodo, BigDecimal importe, long cantidad) {
    }

    /** Reintegros EJECUTADO de un método (el de su PAGO), separados por cancelación o devolución. */
    public record ReintegroPorMetodo(String metodo, boolean porDevolucion, BigDecimal importe, long cantidad) {
    }

    private static final String SQL_COBROS = "WITH " + FragmentosSql.PERIODO + " "
            + "SELECT pg.metodo, SUM(pg.monto) AS importe, COUNT(*) AS cantidad FROM pago pg, periodo p "
            + " WHERE pg.estado = 'PAGADO' AND pg.fecha_pago >= p.ini AND pg.fecha_pago < p.fin "
            + " GROUP BY pg.metodo ORDER BY pg.metodo";

    private static final String SQL_REINTEGROS = "WITH " + FragmentosSql.PERIODO + " "
            + "SELECT pg.metodo, r.devolucion_id IS NOT NULL AS por_devolucion, SUM(r.monto) AS importe, COUNT(*) AS cantidad "
            + "  FROM reembolso r JOIN pago pg ON pg.id = r.pago_id, periodo p "
            + " WHERE r.estado = 'EJECUTADO' AND r.fecha_ejecucion >= p.ini AND r.fecha_ejecucion < p.fin "
            + " GROUP BY pg.metodo, r.devolucion_id IS NOT NULL ORDER BY pg.metodo, por_devolucion";

    // Anticipos: lo cobrado menos lo reintegrado hasta el corte de los pedidos que a esa fecha todavía no
    // tienen entrega (incluye los cancelados con reintegro pendiente). Son pasivo, no ingreso (R059).
    private static final String SQL_ANTICIPOS = "WITH " + FragmentosSql.CORTE + ", "
            + "sin_entregar AS (SELECT e.pedido_id FROM entrega e, corte c "
            + " WHERE e.fecha_entrega IS NULL OR e.fecha_entrega >= c.fin) "
            + "SELECT (SELECT COALESCE(SUM(pg.monto), 0) FROM pago pg JOIN sin_entregar s ON s.pedido_id = pg.pedido_id, corte c "
            + "         WHERE pg.estado = 'PAGADO' AND pg.fecha_pago < c.fin) "
            + "     - (SELECT COALESCE(SUM(r.monto), 0) FROM reembolso r JOIN pago pg ON pg.id = r.pago_id "
            + "          JOIN sin_entregar s ON s.pedido_id = pg.pedido_id, corte c "
            + "         WHERE r.estado = 'EJECUTADO' AND r.fecha_ejecucion < c.fin) AS anticipos";

    // Dato informativo: importe reembolsable de las devoluciones aprobadas hasta el corte y no anuladas a esa
    // fecha, menos sus reembolsos ejecutados hasta el corte. No resta ingreso.
    private static final String SQL_DEVOLUCIONES_PENDIENTES = "WITH " + FragmentosSql.CORTE + " "
            + "SELECT COALESCE(SUM(d.importe_reembolsable - COALESCE((SELECT SUM(r.monto) FROM reembolso r "
            + "          WHERE r.devolucion_id = d.id AND r.estado = 'EJECUTADO' AND r.fecha_ejecucion < c.fin), 0)), 0) AS pendiente "
            + "  FROM devolucion d, corte c "
            + " WHERE d.resolucion = 'REEMBOLSO' AND d.importe_reembolsable IS NOT NULL "
            + "   AND d.fecha_resolucion < c.fin AND (d.fecha_anulacion IS NULL OR d.fecha_anulacion >= c.fin)";

    public List<CobroPorMetodo> cobrosPorMetodo(LocalDate desde, LocalDate hasta) {
        return consultar(SQL_COBROS, fila -> new CobroPorMetodo(fila.getString("metodo"), fila.getBigDecimal("importe"),
                fila.getLong("cantidad")), desde, hasta);
    }

    public List<ReintegroPorMetodo> reintegrosPorMetodo(LocalDate desde, LocalDate hasta) {
        return consultar(SQL_REINTEGROS, fila -> new ReintegroPorMetodo(fila.getString("metodo"),
                fila.getBoolean("por_devolucion"), fila.getBigDecimal("importe"), fila.getLong("cantidad")), desde, hasta);
    }

    public BigDecimal anticiposPendientes(LocalDate corte) {
        return consultarUno(SQL_ANTICIPOS, fila -> fila.getBigDecimal("anticipos"), corte).orElseThrow();
    }

    public BigDecimal devolucionesPendientesDeReintegro(LocalDate corte) {
        return consultarUno(SQL_DEVOLUCIONES_PENDIENTES, fila -> fila.getBigDecimal("pendiente"), corte).orElseThrow();
    }
}
