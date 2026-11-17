package com.feder.datos.posventa;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.Devolucion;
import com.feder.modelo.EstadoDevolucion;
import com.feder.modelo.ResolucionDevolucion;
import com.feder.modelo.TipoMovimiento;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a DEVOLUCION y a las sumas que limitan sus cantidades e importes (R018, R020, R022, R024-R026).
 * Cada cambio de estado o de los hechos de la devolución (recepción, inspección) es condicional, y la base
 * impide modificar sus datos fijos desde la aprobación (rg_devolucion_datos_fijos_desde_aprobacion).
 */
public class DevolucionRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT d.id, d.movimiento_inventario_id, d.devolucion_anulada_id, d.codigo, "
            + "d.cantidad, d.motivo, d.estado, d.resolucion, d.cantidad_aprobada, d.cantidad_recibida, d.cantidad_apta, "
            + "d.importe_reembolsable, d.fecha_solicitud, d.fecha_recepcion, d.fecha_inspeccion, d.fecha_resolucion, "
            + "d.fecha_cierre, d.fecha_anulacion, d.motivo_anulacion FROM devolucion d ";

    /**
     * Despachos de una línea de pedido: las SALIDA_VENTA de sus reservas y, siguiendo la cadena, las
     * SALIDA_REPOSICION generadas por devoluciones de esos despachos («Devoluciones de la línea», R018).
     */
    private static final String DESPACHOS_DE_LINEA = "WITH RECURSIVE despachos(id) AS ("
            + "SELECT m.id FROM movimiento_inventario m JOIN reserva_stock rs ON rs.id = m.reserva_id "
            + "WHERE m.tipo = 'SALIDA_VENTA' AND rs.detalle_pedido_id = ? "
            + "UNION SELECT m.id FROM despachos ds JOIN devolucion dv ON dv.movimiento_inventario_id = ds.id "
            + "JOIN movimiento_inventario m ON m.devolucion_id = dv.id AND m.tipo = 'SALIDA_REPOSICION') ";

    /** Despacho físico que puede devolverse, con la línea de pedido a la que pertenece. */
    public record Despacho(long movimientoId, TipoMovimiento tipo, long loteFederId, BigDecimal cantidad,
                           long detallePedidoId) {
    }

    /** Devolución con su pedido, su cliente y la línea vendida, para las respuestas y los listados. */
    public record DevolucionVista(Devolucion devolucion, long pedidoId, String codigoPedido, long clienteId,
                                  long detallePedidoId, String descripcion) {
    }

    public long siguienteId() {
        return consultarUno("SELECT nextval(pg_get_serial_sequence('devolucion', 'id')) AS id", fila -> fila.getLong("id"))
                .orElseThrow(() -> new AccesoDatosException("No se obtuvo el identificador de la devolución"));
    }

    public void insertar(Devolucion d) {
        actualizar("INSERT INTO devolucion (id, movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado, "
                        + "cantidad_recibida, cantidad_apta, fecha_solicitud, fecha_recepcion, fecha_inspeccion) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'SOLICITADA', ?, ?, ?, ?, ?)",
                d.getId(), d.getMovimientoInventarioId(), d.getDevolucionAnuladaId(), d.getCodigo(), d.getCantidad(), d.getMotivo(),
                d.getCantidadRecibida(), d.getCantidadApta(), d.getFechaSolicitud(), d.getFechaRecepcion(), d.getFechaInspeccion());
    }

    public Optional<DevolucionVista> buscarPorCodigo(String codigo) {
        return consultarUno(VISTA + "WHERE d.codigo = ?", DevolucionRepositorio::mapearVista, codigo);
    }

    /** Bloqueo de la devolución: después del PEDIDO y del DETALLE_PEDIDO, antes de PAGO (R048, R080). */
    public Optional<Devolucion> bloquear(long id) {
        return consultarUno(COLUMNAS + "WHERE d.id = ? FOR UPDATE", DevolucionRepositorio::mapear, id);
    }

    /** @param clienteId nulo para todos; @param estado nulo para todos los estados. */
    public List<DevolucionVista> listar(Long clienteId, EstadoDevolucion estado, int limite) {
        return consultar(VISTA + "WHERE (?::bigint IS NULL OR p.usuario_id = ?) AND (?::text IS NULL OR d.estado = ?) "
                        + "ORDER BY d.fecha_solicitud DESC, d.id DESC LIMIT ?",
                DevolucionRepositorio::mapearVista, clienteId, clienteId, estado, estado, limite);
    }

    // ------------------------------------------------------------------ despachos

    public List<Despacho> despachosDeLinea(long detallePedidoId) {
        return consultar(DESPACHOS_DE_LINEA + "SELECT m.id, m.tipo, m.lote_feder_id, m.cantidad FROM movimiento_inventario m "
                        + "WHERE m.id IN (SELECT id FROM despachos) ORDER BY m.id",
                fila -> new Despacho(fila.getLong("id"), enumeracion(fila, "tipo", TipoMovimiento.class),
                        fila.getLong("lote_feder_id"), fila.getBigDecimal("cantidad"), detallePedidoId),
                detallePedidoId);
    }

    /** R022: bloquea la fila del despacho devuelto (es inmutable: el bloqueo solo serializa las devoluciones). */
    public void bloquearDespacho(long movimientoId) {
        consultar("SELECT id FROM movimiento_inventario WHERE id = ? FOR UPDATE", fila -> fila.getLong("id"), movimientoId);
    }

    /** R022 (a): cantidad de las SOLICITADA más la aprobada de las APROBADA o COMPLETADA del despacho. */
    public BigDecimal comprometidoDeDespacho(long movimientoId) {
        return suma("SELECT COALESCE(sum(CASE WHEN estado = 'SOLICITADA' THEN cantidad ELSE cantidad_aprobada END), 0) AS total "
                + "FROM devolucion WHERE movimiento_inventario_id = ? AND estado IN ('SOLICITADA', 'APROBADA', 'COMPLETADA')",
                movimientoId);
    }

    /**
     * R022 (b): lo recibido físicamente de todas las devoluciones del despacho, salvo las ANULADA cuya
     * mercancía pasó a su rectificación (A39): una unidad física solo vuelve una vez.
     */
    public BigDecimal recibidoDeDespacho(long movimientoId) {
        return suma("SELECT COALESCE(sum(d.cantidad_recibida), 0) AS total FROM devolucion d WHERE d.movimiento_inventario_id = ? "
                + "AND NOT (d.estado = 'ANULADA' AND EXISTS (SELECT 1 FROM devolucion r WHERE r.devolucion_anulada_id = d.id))",
                movimientoId);
    }

    // ------------------------------------------------------------------ atribución del importe (R018, R020)

    /** Devoluciones con REEMBOLSO APROBADA o COMPLETADA de la línea: cantidad aprobada e importe ya atribuidos. */
    public record Atribuido(BigDecimal cantidadAprobada, BigDecimal importe) {
    }

    public Atribuido atribuidoDeLinea(long detallePedidoId) {
        return consultarUno(DESPACHOS_DE_LINEA + "SELECT COALESCE(sum(d.cantidad_aprobada), 0) AS cantidad, "
                        + "COALESCE(sum(d.importe_reembolsable), 0) AS importe FROM devolucion d "
                        + "WHERE d.movimiento_inventario_id IN (SELECT id FROM despachos) AND d.resolucion = 'REEMBOLSO' "
                        + "AND d.estado IN ('APROBADA', 'COMPLETADA')",
                fila -> new Atribuido(fila.getBigDecimal("cantidad"), fila.getBigDecimal("importe")), detallePedidoId)
                .orElse(new Atribuido(BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /** R020: importe atribuido a las devoluciones no ANULADA de todo el pedido. */
    public BigDecimal atribuidoDePedido(long pedidoId) {
        return suma("WITH RECURSIVE despachos(id) AS ("
                + "SELECT m.id FROM movimiento_inventario m JOIN reserva_stock rs ON rs.id = m.reserva_id "
                + "JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id WHERE m.tipo = 'SALIDA_VENTA' AND dp.pedido_id = ? "
                + "UNION SELECT m.id FROM despachos ds JOIN devolucion dv ON dv.movimiento_inventario_id = ds.id "
                + "JOIN movimiento_inventario m ON m.devolucion_id = dv.id AND m.tipo = 'SALIDA_REPOSICION') "
                + "SELECT COALESCE(sum(d.importe_reembolsable), 0) AS total FROM devolucion d "
                + "WHERE d.movimiento_inventario_id IN (SELECT id FROM despachos) AND d.estado <> 'ANULADA'", pedidoId);
    }

    // ------------------------------------------------------------------ movimientos generados (A35)

    /** Suma de los movimientos de un tipo generados por la devolución. */
    public BigDecimal sumaMovimientos(long devolucionId, TipoMovimiento tipo) {
        return consultarUno("SELECT COALESCE(sum(cantidad), 0) AS total FROM movimiento_inventario "
                + "WHERE devolucion_id = ? AND tipo = ?", fila -> fila.getBigDecimal("total"), devolucionId, tipo)
                .orElse(BigDecimal.ZERO);
    }

    public boolean existeMovimientoConClave(String claveOperacion) {
        return consultarUno("SELECT 1 AS x FROM movimiento_inventario WHERE clave_operacion = ?", fila -> 1, claveOperacion).isPresent();
    }

    public int contarMovimientos(long devolucionId, TipoMovimiento tipo) {
        return consultarUno("SELECT count(*) AS n FROM movimiento_inventario WHERE devolucion_id = ? AND tipo = ?",
                fila -> fila.getInt("n"), devolucionId, tipo).orElse(0);
    }

    /** Lote y cantidad del despacho devuelto: el reingreso se registra en ese mismo lote (R044). */
    public Despacho despacho(long movimientoId, long detallePedidoId) {
        return consultarUno("SELECT id, tipo, lote_feder_id, cantidad FROM movimiento_inventario WHERE id = ?",
                fila -> new Despacho(fila.getLong("id"), enumeracion(fila, "tipo", TipoMovimiento.class),
                        fila.getLong("lote_feder_id"), fila.getBigDecimal("cantidad"), detallePedidoId), movimientoId)
                .orElseThrow(() -> new AccesoDatosException("No existe el despacho " + movimientoId));
    }

    /** Reingreso de una devolución por lote, para compensarlo en una rectificación (R025 c). */
    public record Reingreso(long loteFederId, BigDecimal cantidad) {
    }

    public List<Reingreso> reingresosPorLote(long devolucionId) {
        return consultar("SELECT lote_feder_id, sum(CASE WHEN tipo = 'ENTRADA_DEVOLUCION' THEN cantidad ELSE -cantidad END) AS neto "
                        + "FROM movimiento_inventario WHERE devolucion_id = ? AND tipo IN ('ENTRADA_DEVOLUCION', 'AJUSTE_NEGATIVO') "
                        + "GROUP BY lote_feder_id ORDER BY lote_feder_id",
                fila -> new Reingreso(fila.getLong("lote_feder_id"), fila.getBigDecimal("neto")), devolucionId);
    }

    // ------------------------------------------------------------------ hechos y transiciones

    /** Recepción física: una sola vez por devolución. */
    public boolean registrarRecepcion(long id, BigDecimal cantidad, OffsetDateTime fecha) {
        return actualizar("UPDATE devolucion SET cantidad_recibida = ?, fecha_recepcion = ? "
                + "WHERE id = ? AND cantidad_recibida IS NULL AND estado <> 'ANULADA'", cantidad, fecha, id) == 1;
    }

    /** Inspección: fija las unidades aptas de lo recibido, una sola vez. */
    public boolean registrarInspeccion(long id, BigDecimal apta, OffsetDateTime fecha) {
        return actualizar("UPDATE devolucion SET cantidad_apta = ?, fecha_inspeccion = ? WHERE id = ? AND cantidad_apta IS NULL "
                + "AND cantidad_recibida IS NOT NULL AND estado <> 'ANULADA'", apta, fecha, id) == 1;
    }

    /** SOLICITADA -> APROBADA con su resolución, cantidad aprobada e importe (nulo en REPOSICION). */
    public boolean aprobar(long id, ResolucionDevolucion resolucion, BigDecimal cantidadAprobada, BigDecimal importe,
                           OffsetDateTime fecha) {
        return actualizar("UPDATE devolucion SET estado = 'APROBADA', resolucion = ?, cantidad_aprobada = ?, "
                        + "importe_reembolsable = ?, fecha_resolucion = ? WHERE id = ? AND estado = 'SOLICITADA'",
                resolucion, cantidadAprobada, importe, fecha, id) == 1;
    }

    public boolean rechazar(long id, OffsetDateTime fecha) {
        return actualizar("UPDATE devolucion SET estado = 'RECHAZADA', fecha_resolucion = ? WHERE id = ? AND estado = 'SOLICITADA'",
                fecha, id) == 1;
    }

    public boolean completar(long id, OffsetDateTime fecha) {
        return actualizar("UPDATE devolucion SET estado = 'COMPLETADA', fecha_cierre = ? WHERE id = ? AND estado = 'APROBADA'",
                fecha, id) == 1;
    }

    public boolean anular(long id, OffsetDateTime fecha, String motivo) {
        return actualizar("UPDATE devolucion SET estado = 'ANULADA', fecha_anulacion = ?, motivo_anulacion = ? "
                + "WHERE id = ? AND estado = 'APROBADA'", fecha, motivo, id) == 1;
    }

    private static final String VISTA = "SELECT d.id, d.movimiento_inventario_id, d.devolucion_anulada_id, d.codigo, d.cantidad, "
            + "d.motivo, d.estado, d.resolucion, d.cantidad_aprobada, d.cantidad_recibida, d.cantidad_apta, d.importe_reembolsable, "
            + "d.fecha_solicitud, d.fecha_recepcion, d.fecha_inspeccion, d.fecha_resolucion, d.fecha_cierre, d.fecha_anulacion, "
            + "d.motivo_anulacion, p.id AS pedido_id, p.codigo AS codigo_pedido, p.usuario_id, dp.id AS detalle_pedido_id, "
            + "dp.descripcion_venta FROM devolucion d "
            + "JOIN LATERAL (WITH RECURSIVE cadena(mov, reserva, dev, paso) AS ("
            + "  SELECT m.id, m.reserva_id, m.devolucion_id, 0 FROM movimiento_inventario m WHERE m.id = d.movimiento_inventario_id "
            + "  UNION ALL SELECT m.id, m.reserva_id, m.devolucion_id, c.paso + 1 FROM cadena c "
            + "  JOIN devolucion dv ON dv.id = c.dev JOIN movimiento_inventario m ON m.id = dv.movimiento_inventario_id "
            + "  WHERE c.reserva IS NULL AND c.paso < 64) "
            + "SELECT reserva FROM cadena WHERE reserva IS NOT NULL LIMIT 1) origen ON true "
            + "JOIN reserva_stock rs ON rs.id = origen.reserva JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
            + "JOIN pedido p ON p.id = dp.pedido_id ";

    private BigDecimal suma(String sql, long id) {
        return consultarUno(sql, fila -> fila.getBigDecimal("total"), id).orElse(BigDecimal.ZERO);
    }

    private static DevolucionVista mapearVista(ResultSet fila) throws SQLException {
        return new DevolucionVista(mapear(fila), fila.getLong("pedido_id"), fila.getString("codigo_pedido"),
                fila.getLong("usuario_id"), fila.getLong("detalle_pedido_id"), fila.getString("descripcion_venta"));
    }

    private static Devolucion mapear(ResultSet fila) throws SQLException {
        Devolucion d = new Devolucion();
        d.setId(fila.getLong("id"));
        d.setMovimientoInventarioId(fila.getLong("movimiento_inventario_id"));
        d.setDevolucionAnuladaId(largoONulo(fila, "devolucion_anulada_id"));
        d.setCodigo(fila.getString("codigo"));
        d.setCantidad(fila.getBigDecimal("cantidad"));
        d.setMotivo(fila.getString("motivo"));
        d.setEstado(enumeracion(fila, "estado", EstadoDevolucion.class));
        d.setResolucion(enumeracion(fila, "resolucion", ResolucionDevolucion.class));
        d.setCantidadAprobada(fila.getBigDecimal("cantidad_aprobada"));
        d.setCantidadRecibida(fila.getBigDecimal("cantidad_recibida"));
        d.setCantidadApta(fila.getBigDecimal("cantidad_apta"));
        d.setImporteReembolsable(fila.getBigDecimal("importe_reembolsable"));
        d.setFechaSolicitud(fechaHora(fila, "fecha_solicitud"));
        d.setFechaRecepcion(fechaHora(fila, "fecha_recepcion"));
        d.setFechaInspeccion(fechaHora(fila, "fecha_inspeccion"));
        d.setFechaResolucion(fechaHora(fila, "fecha_resolucion"));
        d.setFechaCierre(fechaHora(fila, "fecha_cierre"));
        d.setFechaAnulacion(fechaHora(fila, "fecha_anulacion"));
        d.setMotivoAnulacion(fila.getString("motivo_anulacion"));
        return d;
    }
}
