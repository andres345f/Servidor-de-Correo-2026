package com.feder.datos.reportes;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Consultas de la trazabilidad de un pedido (CU8): PEDIDO -> DETALLE_PEDIDO -> RESERVA_STOCK CONSUMIDA ->
 * SALIDA_VENTA y LOTE_FEDER -> DETALLE_COMPRA -> COMPRA_FEDER y PRODUCTOR -> LOTE_PRODUCCION -> PRODUCCION
 * -> comunidad de origen histórica (instantánea de PRODUCCION). No consulta costos.
 */
public class ReporteTrazabilidadRepositorio extends RepositorioJdbc {

    /**
     * Operación de BITACORA (detalle.operacion) con la que un ADMINISTRADOR documenta un error de lote de origen
     * (R009), sobre el DETALLE_COMPRA o sobre el LOTE_FEDER afectado. El modelo no fija la clave: CU4 debe usar
     * esta misma constante al registrar la constancia.
     */
    public static final String OPERACION_ERROR_LOTE_ORIGEN = "ERROR_LOTE_ORIGEN";

    /** Datos generales del pedido. */
    public record PedidoTrazado(long id, String codigo, String tipo, String estado, OffsetDateTime fechaCreacion) {
    }

    /** Línea del pedido con lo que se despachó de ella. */
    public record LineaPedido(String descripcionVenta, BigDecimal cantidad, BigDecimal despachado) {
    }

    /** Eslabón completo de una reserva consumida hasta la comunidad de origen. */
    public record OrigenReserva(long reservaId, String descripcionVenta, BigDecimal cantidad, OffsetDateTime fechaSalida,
                                String loteFeder, String compra, String productor, String loteOrigen, String produccion,
                                String comunidad, String municipio, String departamento, boolean comunidadActiva,
                                long detalleCompraId) {
    }

    /** Retorno o baja vinculados a una reserva del pedido. */
    public record MovimientoReserva(long reservaId, String tipo, BigDecimal cantidad, OffsetDateTime fecha, String motivo) {
    }

    /** Reposición enlazada con su devolución (A35) y el despacho devuelto (A34). */
    public record Reposicion(String devolucion, long despachoDevueltoId, String tipoDespachoDevuelto,
                             String loteFeder, BigDecimal cantidad, OffsetDateTime fecha, String comunidad) {
    }

    /** Discrepancia de lote de origen documentada en BITACORA para un detalle de compra o un lote de FEDER. */
    public record AdvertenciaOrigen(String referencia, OffsetDateTime fecha, String motivo) {
    }

    private static final String SQL_PEDIDO = "SELECT id, codigo, tipo, estado, fecha_creacion FROM pedido WHERE codigo = ?";

    private static final String SQL_LINEAS = "SELECT dp.descripcion_venta, dp.cantidad, "
            + "       COALESCE((SELECT SUM(m.cantidad) FROM reserva_stock rs JOIN movimiento_inventario m "
            + "          ON m.reserva_id = rs.id AND m.tipo = 'SALIDA_VENTA' WHERE rs.detalle_pedido_id = dp.id), 0) AS despachado "
            + "  FROM detalle_pedido dp WHERE dp.pedido_id = ? ORDER BY dp.id";

    private static final String SQL_ORIGEN = "SELECT rs.id AS reserva_id, dp.descripcion_venta, sv.cantidad, sv.fecha, "
            + "       lf.codigo AS lote_feder, cf.codigo AS compra, " + FragmentosSql.ETIQUETA_PRODUCTOR + " AS productor, "
            + "       lp.codigo_lote, pd.codigo AS produccion, pd.nombre_comunidad_origen, pd.municipio_origen, "
            + "       pd.departamento_origen, ca.activa, dc.id AS detalle_compra_id "
            + "  FROM detalle_pedido dp "
            + "  JOIN reserva_stock rs ON rs.detalle_pedido_id = dp.id AND rs.estado = 'CONSUMIDA' "
            + "  JOIN movimiento_inventario sv ON sv.reserva_id = rs.id AND sv.tipo = 'SALIDA_VENTA' "
            + "  JOIN lote_feder lf ON lf.id = rs.lote_feder_id "
            + "  JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
            + "  JOIN compra_feder cf ON cf.id = dc.compra_id "
            + "  JOIN productor po ON po.id = cf.productor_id "
            + "  JOIN lote_produccion lp ON lp.id = dc.lote_origen_id "
            + "  JOIN produccion pd ON pd.id = lp.produccion_id "
            + "  JOIN comunidad_agricola ca ON ca.id = pd.comunidad_origen_id "
            + " WHERE dp.pedido_id = ? ORDER BY dp.id, rs.id";

    private static final String SQL_MOVIMIENTOS_RESERVA = "SELECT m.reserva_id, m.tipo, m.cantidad, m.fecha, m.motivo "
            + "  FROM movimiento_inventario m JOIN reserva_stock rs ON rs.id = m.reserva_id "
            + "  JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
            + " WHERE dp.pedido_id = ? AND m.tipo <> 'SALIDA_VENTA' ORDER BY m.fecha, m.id";

    private static final String SQL_REPOSICIONES = "SELECT d.codigo AS devolucion, desp.id AS despacho_id, desp.tipo AS despacho_tipo, "
            + "       lf.codigo AS lote_feder, m.cantidad, m.fecha, "
            + "       pd.nombre_comunidad_origen || ' (' || pd.municipio_origen || ', ' || pd.departamento_origen || ')' AS comunidad "
            + "  FROM movimiento_inventario m JOIN devolucion d ON d.id = m.devolucion_id "
            + "  JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id "
            + "  JOIN lote_feder lf ON lf.id = m.lote_feder_id "
            + "  JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
            + "  JOIN lote_produccion lp ON lp.id = dc.lote_origen_id JOIN produccion pd ON pd.id = lp.produccion_id "
            + " WHERE m.tipo = 'SALIDA_REPOSICION' AND feder_pedido_de_movimiento(m.id) = ? ORDER BY m.fecha, m.id";

    // Lotes de lo vendido en el pedido: los de sus reservas consumidas y los de sus reposiciones (A35).
    private static final String SQL_ADVERTENCIAS = "WITH lote_trazado AS ("
            + " SELECT rs.lote_feder_id AS id FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
            + "  WHERE dp.pedido_id = ? AND rs.estado = 'CONSUMIDA' "
            + " UNION "
            + " SELECT m.lote_feder_id FROM movimiento_inventario m "
            + "  WHERE m.tipo = 'SALIDA_REPOSICION' AND feder_pedido_de_movimiento(m.id) = ?) "
            + "SELECT b.fecha, COALESCE(b.detalle ->> 'motivo', '') AS motivo, "
            + "       CASE WHEN b.entidad = 'LOTE_FEDER' THEN 'el lote FEDER ' || lf.codigo "
            + "            ELSE 'el detalle de compra ' || b.entidad_id END AS referencia "
            + "  FROM bitacora b LEFT JOIN lote_feder lf ON b.entidad = 'LOTE_FEDER' AND lf.id = b.entidad_id "
            + " WHERE b.detalle ->> 'operacion' = ? "
            + "   AND ((b.entidad = 'DETALLE_COMPRA' AND b.entidad_id IN (SELECT l.detalle_compra_id FROM lote_feder l "
            + "          WHERE l.id IN (SELECT id FROM lote_trazado))) "
            + "        OR (b.entidad = 'LOTE_FEDER' AND b.entidad_id IN (SELECT id FROM lote_trazado))) "
            + " ORDER BY b.fecha, b.id";

    public Optional<PedidoTrazado> pedido(String codigo) {
        return consultarUno(SQL_PEDIDO, fila -> new PedidoTrazado(fila.getLong("id"), fila.getString("codigo"),
                fila.getString("tipo"), fila.getString("estado"), fechaHora(fila, "fecha_creacion")), codigo);
    }

    public List<LineaPedido> lineas(long pedidoId) {
        return consultar(SQL_LINEAS, fila -> new LineaPedido(fila.getString("descripcion_venta"),
                fila.getBigDecimal("cantidad"), fila.getBigDecimal("despachado")), pedidoId);
    }

    public List<OrigenReserva> origenes(long pedidoId) {
        return consultar(SQL_ORIGEN, fila -> new OrigenReserva(fila.getLong("reserva_id"), fila.getString("descripcion_venta"),
                fila.getBigDecimal("cantidad"), fechaHora(fila, "fecha"), fila.getString("lote_feder"),
                fila.getString("compra"), fila.getString("productor"), fila.getString("codigo_lote"),
                fila.getString("produccion"), fila.getString("nombre_comunidad_origen"), fila.getString("municipio_origen"),
                fila.getString("departamento_origen"), fila.getBoolean("activa"), fila.getLong("detalle_compra_id")),
                pedidoId);
    }

    public List<MovimientoReserva> retornosYBajas(long pedidoId) {
        return consultar(SQL_MOVIMIENTOS_RESERVA, fila -> new MovimientoReserva(fila.getLong("reserva_id"),
                fila.getString("tipo"), fila.getBigDecimal("cantidad"), fechaHora(fila, "fecha"), fila.getString("motivo")),
                pedidoId);
    }

    public List<Reposicion> reposiciones(long pedidoId) {
        return consultar(SQL_REPOSICIONES, fila -> new Reposicion(fila.getString("devolucion"), fila.getLong("despacho_id"),
                fila.getString("despacho_tipo"), fila.getString("lote_feder"), fila.getBigDecimal("cantidad"),
                fechaHora(fila, "fecha"), fila.getString("comunidad")), pedidoId);
    }

    public List<AdvertenciaOrigen> advertenciasDeOrigen(long pedidoId) {
        return consultar(SQL_ADVERTENCIAS, fila -> new AdvertenciaOrigen(fila.getString("referencia"),
                fechaHora(fila, "fecha"), fila.getString("motivo")), pedidoId, pedidoId, OPERACION_ERROR_LOTE_ORIGEN);
    }
}
