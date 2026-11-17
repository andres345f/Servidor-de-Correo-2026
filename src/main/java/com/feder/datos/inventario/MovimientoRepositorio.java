package com.feder.datos.inventario;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.TipoMovimiento;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a MOVIMIENTO_INVENTARIO. Solo inserta y consulta: la base rechaza UPDATE y DELETE
 * (tg_movimiento_inventario_inmutable, R042); un error se corrige con un movimiento compensatorio.
 */
public class MovimientoRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT id, lote_feder_id, usuario_id, reserva_id, devolucion_id, tipo, "
            + "cantidad, fecha, motivo, clave_operacion FROM movimiento_inventario ";

    public long insertar(MovimientoInventario m) {
        return insertar("INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, devolucion_id, tipo, "
                        + "cantidad, fecha, motivo, clave_operacion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                m.getLoteFederId(), m.getUsuarioId(), m.getReservaId(), m.getDevolucionId(), m.getTipo(),
                m.getCantidad(), m.getFecha(), m.getMotivo(), m.getClaveOperacion());
    }

    public Optional<MovimientoInventario> buscarPorClave(String claveOperacion) {
        return consultarUno(COLUMNAS + "WHERE clave_operacion = ?", MovimientoRepositorio::mapear, claveOperacion);
    }

    public List<MovimientoInventario> deLote(long loteFederId) {
        return consultar(COLUMNAS + "WHERE lote_feder_id = ? ORDER BY fecha, id", MovimientoRepositorio::mapear, loteFederId);
    }

    /** Movimiento con el código de su lote de FEDER, para el listado de todos los lotes. */
    public record MovimientoConLote(MovimientoInventario movimiento, String codigoLote) {
    }

    /** Movimientos de todos los lotes, del más reciente al más antiguo (como máximo {@code maximo} filas). */
    public List<MovimientoConLote> recientes(int maximo) {
        return consultar("SELECT m.id, m.lote_feder_id, m.usuario_id, m.reserva_id, m.devolucion_id, m.tipo, m.cantidad, "
                        + "m.fecha, m.motivo, m.clave_operacion, lf.codigo AS codigo_lote FROM movimiento_inventario m "
                        + "JOIN lote_feder lf ON lf.id = m.lote_feder_id ORDER BY m.fecha DESC, m.id DESC LIMIT ?",
                fila -> new MovimientoConLote(mapear(fila), fila.getString("codigo_lote")), maximo);
    }

    public Optional<MovimientoInventario> salidaVentaDeReserva(long reservaId) {
        return consultarUno(COLUMNAS + "WHERE reserva_id = ? AND tipo = 'SALIDA_VENTA'", MovimientoRepositorio::mapear, reservaId);
    }

    /** Suma de los movimientos de un tipo vinculados a una reserva (p. ej. los retornos de una entrega). */
    public BigDecimal sumaDeReserva(long reservaId, TipoMovimiento tipo) {
        return consultarUno("SELECT COALESCE(sum(cantidad), 0) AS total FROM movimiento_inventario "
                        + "WHERE reserva_id = ? AND tipo = ?", fila -> fila.getBigDecimal("total"), reservaId, tipo)
                .orElse(BigDecimal.ZERO);
    }

    private static MovimientoInventario mapear(ResultSet fila) throws SQLException {
        MovimientoInventario m = new MovimientoInventario();
        m.setId(fila.getLong("id"));
        m.setLoteFederId(fila.getLong("lote_feder_id"));
        m.setUsuarioId(fila.getLong("usuario_id"));
        m.setReservaId(largoONulo(fila, "reserva_id"));
        m.setDevolucionId(largoONulo(fila, "devolucion_id"));
        m.setTipo(enumeracion(fila, "tipo", TipoMovimiento.class));
        m.setCantidad(fila.getBigDecimal("cantidad"));
        m.setFecha(fechaHora(fila, "fecha"));
        m.setMotivo(fila.getString("motivo"));
        m.setClaveOperacion(fila.getString("clave_operacion"));
        return m;
    }
}
