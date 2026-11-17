package com.feder.datos.ventas;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoProduccion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Consultas de PRODUCCION para respaldar encargos (A25, PRODUCCION «Alta de respaldo» R062 y
 * «Balance de cantidades» R063). Solo lectura y bloqueo: la producción pertenece a CU3.
 */
public class ProduccionRespaldoRepositorio extends RepositorioJdbc {

    public record Produccion(long id, String codigo, long productoId, EstadoProduccion estado,
                             LocalDate fechaDisponibilidadEstimada, BigDecimal cantidadEstimada) {
    }

    /** R062: la fila de la producción se bloquea antes de calcular su capacidad. */
    public Optional<Produccion> bloquearPorCodigo(String codigo) {
        return consultarUno("SELECT id, codigo, producto_id, estado, fecha_disponibilidad_estimada, cantidad_estimada "
                        + "FROM produccion WHERE codigo = ? FOR UPDATE",
                fila -> new Produccion(fila.getLong("id"), fila.getString("codigo"), fila.getLong("producto_id"),
                        enumeracion(fila, "estado", EstadoProduccion.class), fecha(fila, "fecha_disponibilidad_estimada"),
                        fila.getBigDecimal("cantidad_estimada")), codigo);
    }

    /** R063: compromiso vigente = suma de lo pendiente de cubrir de las líneas que respalda, en pedidos no cancelados. */
    public BigDecimal compromisoVigente(long produccionId) {
        return consultarUno("SELECT COALESCE(sum(dp.cantidad - COALESCE((SELECT sum(rs.cantidad) FROM reserva_stock rs "
                        + "  WHERE rs.detalle_pedido_id = dp.id AND rs.estado IN ('ACTIVA', 'CONSUMIDA')), 0)), 0) AS total "
                        + "FROM detalle_pedido dp JOIN pedido p ON p.id = dp.pedido_id "
                        + "WHERE dp.produccion_id = ? AND p.estado <> 'CANCELADO'",
                fila -> fila.getBigDecimal("total"), produccionId).orElse(BigDecimal.ZERO);
    }

    /** R063: recibida = suma de la cantidad aceptada neta de los lotes de la producción. */
    public BigDecimal recibida(long produccionId) {
        return consultarUno("SELECT COALESCE(sum(dc.cantidad_recibida), 0) AS total FROM detalle_compra dc "
                        + "JOIN lote_produccion lp ON lp.id = dc.lote_origen_id WHERE lp.produccion_id = ?",
                fila -> fila.getBigDecimal("total"), produccionId).orElse(BigDecimal.ZERO);
    }
}
