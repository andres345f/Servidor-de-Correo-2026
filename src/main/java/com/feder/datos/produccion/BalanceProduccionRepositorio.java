package com.feder.datos.produccion;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sumas del «Balance de cantidades» de una producción (R063) y los encargos que respalda,
 * para la reacción ante una cancelación o reducción (R064).
 */
public class BalanceProduccionRepositorio extends RepositorioJdbc {

    /**
     * Sumas brutas: compromiso vigente de los encargos respaldados, cantidad adquirida (aceptada más pendiente
     * comprometida, en compras no canceladas) y cantidad recibida.
     */
    public record Sumas(BigDecimal compromisoVigente, BigDecimal adquirida, BigDecimal recibida) {
    }

    /** Encargo respaldado por la producción, con su fecha estimada vigente. */
    public record EncargoRespaldado(long pedidoId, long clienteId, String codigo, LocalDate fechaEstimada) {
    }

    // Comprometida pendiente de una línea = solicitada - cubierta (reservas ACTIVA o CONSUMIDA), si el pedido no está CANCELADO.
    private static final String SQL_CUBIERTA = "(SELECT COALESCE(sum(rs.cantidad), 0) FROM reserva_stock rs "
            + "WHERE rs.detalle_pedido_id = dp.id AND rs.estado IN ('ACTIVA', 'CONSUMIDA'))";

    public Sumas sumas(long produccionId) {
        return consultarUno("SELECT "
                        + "(SELECT COALESCE(sum(GREATEST(dp.cantidad - " + SQL_CUBIERTA + ", 0)), 0) FROM detalle_pedido dp "
                        + " JOIN pedido p ON p.id = dp.pedido_id WHERE dp.produccion_id = ? AND p.estado <> 'CANCELADO') AS compromiso, "
                        + "(SELECT COALESCE(sum(dc.cantidad_comprada - dc.cantidad_rechazada - dc.cantidad_cancelada), 0) "
                        + " FROM detalle_compra dc JOIN lote_produccion lp ON lp.id = dc.lote_origen_id "
                        + " JOIN compra_feder c ON c.id = dc.compra_id WHERE lp.produccion_id = ? AND c.estado <> 'CANCELADA') AS adquirida, "
                        + "(SELECT COALESCE(sum(dc.cantidad_recibida), 0) FROM detalle_compra dc "
                        + " JOIN lote_produccion lp ON lp.id = dc.lote_origen_id WHERE lp.produccion_id = ?) AS recibida",
                fila -> new Sumas(fila.getBigDecimal("compromiso"), fila.getBigDecimal("adquirida"), fila.getBigDecimal("recibida")),
                produccionId, produccionId, produccionId).orElseThrow();
    }

    /** Encargos con compromiso pendiente respaldado por la producción y con fecha estimada, bloqueados. */
    public List<EncargoRespaldado> encargosConFechaEstimada(long produccionId) {
        return consultar("SELECT p.id, p.usuario_id, p.codigo, p.fecha_estimada_disponibilidad FROM pedido p "
                        + "WHERE p.estado <> 'CANCELADO' AND p.fecha_estimada_disponibilidad IS NOT NULL "
                        + "AND p.id IN (SELECT dp.pedido_id FROM detalle_pedido dp WHERE dp.produccion_id = ? "
                        + "  AND dp.cantidad > " + SQL_CUBIERTA + ") "
                        + "ORDER BY p.id FOR UPDATE OF p",
                fila -> new EncargoRespaldado(fila.getLong("id"), fila.getLong("usuario_id"), fila.getString("codigo"),
                        fecha(fila, "fecha_estimada_disponibilidad")),
                produccionId);
    }

    public void quitarFechaEstimada(long pedidoId) {
        actualizar("UPDATE pedido SET fecha_estimada_disponibilidad = NULL WHERE id = ?", pedidoId);
    }
}
