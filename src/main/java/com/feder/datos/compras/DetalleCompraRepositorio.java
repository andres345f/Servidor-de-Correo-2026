package com.feder.datos.compras;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.DetalleCompra;
import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a DETALLE_COMPRA. No expone DELETE: los detalles nunca se borran (R011); una línea con un
 * origen equivocado se cancela y se registra otra.
 */
public class DetalleCompraRepositorio extends RepositorioJdbc {

    private static final String SELECT_VISTA =
            "SELECT dc.id, dc.compra_id, dc.lote_origen_id, dc.cantidad_comprada, dc.precio_unitario, dc.cantidad_recibida, "
                    + "dc.cantidad_rechazada, dc.cantidad_cancelada, lp.codigo_lote, p.id AS producto_id, "
                    + "p.codigo AS codigo_producto, p.nombre AS nombre_producto, p.unidad_medida "
                    + "FROM detalle_compra dc JOIN lote_produccion lp ON lp.id = dc.lote_origen_id "
                    + "JOIN produccion pr ON pr.id = lp.produccion_id JOIN producto p ON p.id = pr.producto_id ";

    public long insertar(DetalleCompra d) {
        return insertar("INSERT INTO detalle_compra (compra_id, lote_origen_id, cantidad_comprada, precio_unitario) "
                + "VALUES (?, ?, ?, ?)", d.getCompraId(), d.getLoteOrigenId(), d.getCantidadComprada(), d.getPrecioUnitario());
    }

    public List<DetalleCompraVista> deCompra(long compraId) {
        return consultar(SELECT_VISTA + "WHERE dc.compra_id = ? ORDER BY dc.id", DetalleCompraRepositorio::mapear, compraId);
    }

    public Optional<DetalleCompraVista> buscarPorCompraYLote(long compraId, String codigoLoteOrigen) {
        return consultarUno(SELECT_VISTA + "WHERE dc.compra_id = ? AND lp.codigo_lote = ?",
                DetalleCompraRepositorio::mapear, compraId, codigoLoteOrigen);
    }

    /** Bloquea la línea (FOR UPDATE): las recepciones y cancelaciones de una misma línea se serializan. */
    public Optional<DetalleCompraVista> bloquear(long id) {
        return consultarUno(SELECT_VISTA + "WHERE dc.id = ? FOR UPDATE OF dc", DetalleCompraRepositorio::mapear, id);
    }

    /**
     * R014 (4): incremento condicional en una sola sentencia. Devuelve false si lo resuelto superaría
     * lo comprado, y entonces la operación no se aplica.
     */
    public boolean registrarResolucion(long id, BigDecimal aceptada, BigDecimal rechazada, BigDecimal cancelada) {
        return actualizar("UPDATE detalle_compra SET cantidad_recibida = cantidad_recibida + ?, "
                        + "cantidad_rechazada = cantidad_rechazada + ?, cantidad_cancelada = cantidad_cancelada + ? "
                        + "WHERE id = ? AND cantidad_recibida + cantidad_rechazada + cantidad_cancelada + ? + ? + ? <= cantidad_comprada",
                aceptada, rechazada, cancelada, id, aceptada, rechazada, cancelada) == 1;
    }

    /**
     * R009: una aceptación registrada de más vuelve a quedar pendiente o, si la mercancía no debía
     * aceptarse, pasa a rechazada. Condicional: nunca deja cantidad_recibida negativa.
     */
    public boolean anularAceptacion(long id, BigDecimal cantidad, boolean reclasificarComoRechazo) {
        return actualizar("UPDATE detalle_compra SET cantidad_recibida = cantidad_recibida - ?, "
                        + "cantidad_rechazada = cantidad_rechazada + ? WHERE id = ? AND cantidad_recibida >= ?",
                cantidad, reclasificarComoRechazo ? cantidad : BigDecimal.ZERO, id, cantidad) == 1;
    }

    /** R012: corrección directa del precio pactado (el disparador la rechaza tras la primera aceptación o el pago). */
    public void actualizarPrecio(long id, BigDecimal precio) {
        actualizar("UPDATE detalle_compra SET precio_unitario = ? WHERE id = ?", precio, id);
    }

    /** Importe acordado de la compra: suma de round(cantidad_comprada x precio_unitario, 2) de sus líneas. */
    public BigDecimal totalAcordado(long compraId) {
        return consultarUno("SELECT COALESCE(sum(round(cantidad_comprada * precio_unitario, 2)), 0) AS total "
                + "FROM detalle_compra WHERE compra_id = ?", fila -> fila.getBigDecimal("total"), compraId).orElse(BigDecimal.ZERO);
    }

    /** R039: lo que ocupa la oferta de un lote de origen es lo comprado menos lo cancelado, en cualquier compra. */
    public BigDecimal comprometidoDeLote(long loteOrigenId) {
        return consultarUno("SELECT COALESCE(sum(cantidad_comprada - cantidad_cancelada), 0) AS total FROM detalle_compra "
                + "WHERE lote_origen_id = ?", fila -> fila.getBigDecimal("total"), loteOrigenId).orElse(BigDecimal.ZERO);
    }

    private static DetalleCompraVista mapear(ResultSet fila) throws SQLException {
        DetalleCompra d = new DetalleCompra();
        d.setId(fila.getLong("id"));
        d.setCompraId(fila.getLong("compra_id"));
        d.setLoteOrigenId(fila.getLong("lote_origen_id"));
        d.setCantidadComprada(fila.getBigDecimal("cantidad_comprada"));
        d.setPrecioUnitario(fila.getBigDecimal("precio_unitario"));
        d.setCantidadRecibida(fila.getBigDecimal("cantidad_recibida"));
        d.setCantidadRechazada(fila.getBigDecimal("cantidad_rechazada"));
        d.setCantidadCancelada(fila.getBigDecimal("cantidad_cancelada"));
        return new DetalleCompraVista(d, fila.getString("codigo_lote"), fila.getLong("producto_id"),
                fila.getString("codigo_producto"), fila.getString("nombre_producto"),
                enumeracion(fila, "unidad_medida", UnidadMedida.class));
    }
}
