package com.feder.datos.entregas;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.util.List;

/**
 * Consultas de la mercancía despachada de un pedido y de lo que volvió a FEDER (ENTREGA «Retorno de
 * mercancía», R029): por cada reserva CONSUMIDA, su SALIDA_VENTA, sus retornos y las bajas de lo retornado.
 */
public class RetornoRepositorio extends RepositorioJdbc {

    /** Reserva despachada con lo que salió, lo que retornó y lo que se dio de baja de lo retornado. */
    public record ReservaDespachada(long reservaId, long loteFederId, String codigoLote, String descripcion,
                                    BigDecimal salida, BigDecimal retornado, BigDecimal bajas) {

        /** Lo que todavía está fuera de FEDER (la pérdida identificada si la entrega queda NO_RECUPERADA). */
        public BigDecimal noRecuperado() {
            return salida.subtract(retornado);
        }
    }

    public List<ReservaDespachada> despachadasDePedido(long pedidoId) {
        return consultar("SELECT rs.id, rs.lote_feder_id, lf.codigo, dp.descripcion_venta, "
                        + "(SELECT COALESCE(sum(m.cantidad), 0) FROM movimiento_inventario m WHERE m.reserva_id = rs.id "
                        + "  AND m.tipo = 'SALIDA_VENTA') AS salida, "
                        + "(SELECT COALESCE(sum(m.cantidad), 0) FROM movimiento_inventario m WHERE m.reserva_id = rs.id "
                        + "  AND m.tipo = 'ENTRADA_RETORNO_ENTREGA') AS retornado, "
                        + "(SELECT COALESCE(sum(m.cantidad), 0) FROM movimiento_inventario m WHERE m.reserva_id = rs.id "
                        + "  AND m.tipo IN ('BAJA_VENCIDO', 'BAJA_DANADO', 'BAJA_DEFECTUOSO')) AS bajas "
                        + "FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                        + "JOIN lote_feder lf ON lf.id = rs.lote_feder_id "
                        + "WHERE dp.pedido_id = ? AND rs.estado = 'CONSUMIDA' ORDER BY rs.id",
                fila -> new ReservaDespachada(fila.getLong("id"), fila.getLong("lote_feder_id"), fila.getString("codigo"),
                        fila.getString("descripcion_venta"), fila.getBigDecimal("salida"), fila.getBigDecimal("retornado"),
                        fila.getBigDecimal("bajas")),
                pedidoId);
    }

    /** Retornos ya registrados de una reserva: sirve para numerar la clave de un retorno nuevo. */
    public int contarRetornos(long reservaId) {
        return consultarUno("SELECT count(*) AS n FROM movimiento_inventario WHERE reserva_id = ? AND tipo = 'ENTRADA_RETORNO_ENTREGA'",
                fila -> fila.getInt("n"), reservaId).orElse(0);
    }

    /** R029: desde el primer retorno la entrega ya no pasa a EN_CAMINO ni a ENTREGADA. */
    public boolean tieneRetornos(long pedidoId) {
        return consultarUno("SELECT count(*) AS n FROM movimiento_inventario m JOIN reserva_stock rs ON rs.id = m.reserva_id "
                        + "JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                        + "WHERE dp.pedido_id = ? AND m.tipo = 'ENTRADA_RETORNO_ENTREGA'",
                fila -> fila.getLong("n"), pedidoId).orElse(0L) > 0;
    }
}
