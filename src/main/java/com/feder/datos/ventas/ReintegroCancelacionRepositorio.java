package com.feder.datos.ventas;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Pagos cobrados de un pedido y reintegros que genera su cancelación (R031, R052). Solo crea
 * REEMBOLSO PENDIENTE sin devolución; su ejecución y la conciliación con la pasarela pertenecen a CU6.
 */
public class ReintegroCancelacionRepositorio extends RepositorioJdbc {

    /** Pago PAGADO con lo ya reintegrado (reembolsos PENDIENTE o EJECUTADO). */
    public record PagoCobrado(long id, BigDecimal monto, BigDecimal reintegrado) {

        public BigDecimal saldo() {
            return monto.subtract(reintegrado);
        }
    }

    /** Bloquea los pagos cobrados del pedido para no reintegrar dos veces el mismo importe (R078). */
    public List<PagoCobrado> pagosCobrados(long pedidoId) {
        return consultar("SELECT pg.id, pg.monto, (SELECT COALESCE(sum(r.monto), 0) FROM reembolso r "
                        + "WHERE r.pago_id = pg.id AND r.estado IN ('PENDIENTE', 'EJECUTADO')) AS reintegrado "
                        + "FROM pago pg WHERE pg.pedido_id = ? AND pg.estado = 'PAGADO' ORDER BY pg.id FOR UPDATE OF pg",
                fila -> new PagoCobrado(fila.getLong("id"), fila.getBigDecimal("monto"), fila.getBigDecimal("reintegrado")),
                pedidoId);
    }

    public long registrarReintegro(long pagoId, BigDecimal monto, String motivo, String claveIdempotencia,
                                   OffsetDateTime fecha) {
        return insertar("INSERT INTO reembolso (pago_id, devolucion_id, monto, estado, motivo, fecha_solicitud, clave_idempotencia) "
                + "VALUES (?, NULL, ?, 'PENDIENTE', ?, ?, ?)", pagoId, monto, motivo, fecha, claveIdempotencia);
    }

    /**
     * R049: el efectivo contra entrega de un pedido que se cancela termina sin cobro (RECHAZADO).
     * Devuelve los pagos que cambió.
     */
    public List<Long> rechazarEfectivoPendiente(long pedidoId) {
        return consultar("UPDATE pago SET estado = 'RECHAZADO' WHERE pedido_id = ? AND metodo = 'EFECTIVO' "
                + "AND estado = 'PENDIENTE' RETURNING id", fila -> fila.getLong("id"), pedidoId);
    }
}
