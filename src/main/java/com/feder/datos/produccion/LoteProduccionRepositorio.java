package com.feder.datos.produccion;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoLoteProduccion;
import com.feder.modelo.LoteProduccion;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a lote_produccion. No expone DELETE ni el cambio de producción o de código del lote
 * (R040, también protegido por tg_lote_produccion_integridad).
 */
public class LoteProduccionRepositorio extends RepositorioJdbc {

    /** Lote con su producción y producto, y la cantidad ya comprometida en compras de FEDER (R039). */
    public record LoteResumen(LoteProduccion lote, String codigoProduccion, String codigoProducto, String nombreProducto,
                              BigDecimal comprometido) {
    }

    private static final String COLUMNAS = "lp.id, lp.produccion_id, lp.codigo_lote, lp.fecha_elaboracion, lp.fecha_vencimiento, "
            + "lp.cantidad_producida, lp.cantidad_ofrecida, lp.precio_ofrecido, lp.estado";

    // Lo aceptado, lo pendiente y lo rechazado siguen ocupando la oferta; solo lo cancelado la libera (R039).
    private static final String SQL_COMPROMETIDO = "(SELECT COALESCE(sum(dc.cantidad_comprada - dc.cantidad_cancelada), 0) "
            + "FROM detalle_compra dc WHERE dc.lote_origen_id = lp.id)";

    public long reservarId() {
        return consultarUno("SELECT nextval(pg_get_serial_sequence('lote_produccion', 'id')) AS id", fila -> fila.getLong("id"))
                .orElseThrow();
    }

    public void insertar(LoteProduccion l) {
        actualizar("INSERT INTO lote_produccion (id, produccion_id, codigo_lote, fecha_elaboracion, fecha_vencimiento, "
                        + "cantidad_producida, cantidad_ofrecida, precio_ofrecido, estado) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                l.getId(), l.getProduccionId(), l.getCodigoLote(), l.getFechaElaboracion(), l.getFechaVencimiento(),
                l.getCantidadProducida(), l.getCantidadOfrecida(), l.getPrecioOfrecido(), l.getEstado());
    }

    /** Bloquea el lote: las compras de CU4 también lo bloquean al registrar un detalle (R039). */
    public Optional<LoteProduccion> bloquearPorCodigo(String codigo) {
        return consultarUno("SELECT " + COLUMNAS + " FROM lote_produccion lp WHERE lp.codigo_lote = ? FOR UPDATE",
                LoteProduccionRepositorio::mapear, codigo);
    }

    public void actualizar(LoteProduccion l) {
        actualizar("UPDATE lote_produccion SET fecha_elaboracion = ?, fecha_vencimiento = ?, cantidad_producida = ?, "
                        + "cantidad_ofrecida = ?, precio_ofrecido = ?, estado = ? WHERE id = ?",
                l.getFechaElaboracion(), l.getFechaVencimiento(), l.getCantidadProducida(), l.getCantidadOfrecida(),
                l.getPrecioOfrecido(), l.getEstado(), l.getId());
    }

    public BigDecimal comprometido(long loteId) {
        return consultarUno("SELECT " + SQL_COMPROMETIDO + " AS comprometido FROM lote_produccion lp WHERE lp.id = ?",
                fila -> fila.getBigDecimal("comprometido"), loteId).orElse(BigDecimal.ZERO);
    }

    public boolean tieneCompras(long loteId) {
        return consultarUno("SELECT EXISTS (SELECT 1 FROM detalle_compra WHERE lote_origen_id = ?) AS hay",
                fila -> fila.getBoolean("hay"), loteId).orElse(false);
    }

    /** Lotes de un productor (o de todos si es nulo), opcionalmente de una producción. */
    public List<LoteResumen> listar(Long productorId, String codigoProduccion) {
        return consultar("SELECT " + COLUMNAS + ", pr.codigo AS codigo_produccion, p.codigo AS codigo_producto, "
                        + "p.nombre AS nombre_producto, " + SQL_COMPROMETIDO + " AS comprometido "
                        + "FROM lote_produccion lp JOIN produccion pr ON pr.id = lp.produccion_id "
                        + "JOIN producto p ON p.id = pr.producto_id "
                        + "WHERE (?::bigint IS NULL OR pr.productor_id = ?) AND (?::text IS NULL OR pr.codigo = ?) "
                        + "ORDER BY lp.id",
                fila -> new LoteResumen(mapear(fila), fila.getString("codigo_produccion"), fila.getString("codigo_producto"),
                        fila.getString("nombre_producto"), fila.getBigDecimal("comprometido")),
                productorId, productorId, codigoProduccion, codigoProduccion);
    }

    static LoteProduccion mapear(ResultSet fila) throws SQLException {
        LoteProduccion l = new LoteProduccion();
        l.setId(fila.getLong("id"));
        l.setProduccionId(fila.getLong("produccion_id"));
        l.setCodigoLote(fila.getString("codigo_lote"));
        l.setFechaElaboracion(fecha(fila, "fecha_elaboracion"));
        l.setFechaVencimiento(fecha(fila, "fecha_vencimiento"));
        l.setCantidadProducida(fila.getBigDecimal("cantidad_producida"));
        l.setCantidadOfrecida(fila.getBigDecimal("cantidad_ofrecida"));
        l.setPrecioOfrecido(fila.getBigDecimal("precio_ofrecido"));
        l.setEstado(enumeracion(fila, "estado", EstadoLoteProduccion.class));
        return l;
    }
}
