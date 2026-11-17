package com.feder.datos.compras;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoLoteProduccion;
import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Lectura y bloqueo de LOTE_PRODUCCION desde CU4. El lote de origen pertenece a CU3; aquí solo se
 * consulta lo que una compra necesita y se sincroniza su estado de oferta (DISPONIBLE/AGOTADO).
 */
public class LoteOrigenRepositorio extends RepositorioJdbc {

    private static final String SELECT =
            "SELECT lp.id, lp.codigo_lote, lp.estado, lp.cantidad_ofrecida, lp.precio_ofrecido, lp.fecha_vencimiento, "
                    + "pr.productor_id, pr.producto_id, p.codigo AS codigo_producto, p.nombre AS nombre_producto, "
                    + "p.unidad_medida, p.activo AS producto_activo "
                    + "FROM lote_produccion lp JOIN produccion pr ON pr.id = lp.produccion_id "
                    + "JOIN producto p ON p.id = pr.producto_id ";

    /** Lote de origen con su productor y su producto (A09, A07, A08). */
    public record LoteOrigen(long id, String codigo, EstadoLoteProduccion estado, BigDecimal cantidadOfrecida,
                             BigDecimal precioOfrecido, LocalDate fechaVencimiento, long productorId, long productoId,
                             String codigoProducto, String nombreProducto, UnidadMedida unidad, boolean productoActivo) {
    }

    /** R039: la fila del lote se bloquea al registrar un detalle de compra. */
    public Optional<LoteOrigen> bloquearPorCodigo(String codigoLote) {
        return consultarUno(SELECT + "WHERE lp.codigo_lote = ? FOR UPDATE OF lp", LoteOrigenRepositorio::mapear, codigoLote);
    }

    public Optional<LoteOrigen> bloquear(long id) {
        return consultarUno(SELECT + "WHERE lp.id = ? FOR UPDATE OF lp", LoteOrigenRepositorio::mapear, id);
    }

    /** Bloqueo compartido: la fecha de vencimiento no cambia mientras se recibe la mercancía (R035). */
    public Optional<LoteOrigen> bloquearCompartido(long id) {
        return consultarUno(SELECT + "WHERE lp.id = ? FOR SHARE OF lp", LoteOrigenRepositorio::mapear, id);
    }

    /**
     * Refleja en el estado de la oferta si todavía queda cantidad sin comprometer: AGOTADO cuando lo
     * comprado (menos lo cancelado) alcanza lo ofrecido. Un lote RETIRADO por su productor no cambia.
     */
    public void sincronizarEstadoOferta(long id) {
        actualizar("UPDATE lote_produccion lp SET estado = CASE WHEN "
                + "(SELECT COALESCE(sum(dc.cantidad_comprada - dc.cantidad_cancelada), 0) FROM detalle_compra dc "
                + " WHERE dc.lote_origen_id = lp.id) >= lp.cantidad_ofrecida THEN 'AGOTADO' ELSE 'DISPONIBLE' END "
                + "WHERE lp.id = ? AND lp.estado <> 'RETIRADO'", id);
    }

    private static LoteOrigen mapear(ResultSet fila) throws SQLException {
        return new LoteOrigen(fila.getLong("id"), fila.getString("codigo_lote"),
                enumeracion(fila, "estado", EstadoLoteProduccion.class), fila.getBigDecimal("cantidad_ofrecida"),
                fila.getBigDecimal("precio_ofrecido"), fecha(fila, "fecha_vencimiento"), fila.getLong("productor_id"),
                fila.getLong("producto_id"), fila.getString("codigo_producto"), fila.getString("nombre_producto"),
                enumeracion(fila, "unidad_medida", UnidadMedida.class), fila.getBoolean("producto_activo"));
    }
}
