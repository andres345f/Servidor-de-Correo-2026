package com.feder.datos.compras;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.CompraFeder;
import com.feder.modelo.EstadoCompra;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Acceso a COMPRA_FEDER. Las compras no se borran: se cierran como RECIBIDA o CANCELADA. */
public class CompraRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT c.id, c.productor_id, c.codigo, c.fecha_compra, c.estado, c.total, "
            + "c.fecha_pago_productor, c.observacion FROM compra_feder c ";

    /** Compra con los datos visibles de su productor, para los listados. */
    public record CompraResumen(CompraFeder compra, String codigoProductor, String nombreProductor) {
    }

    public long siguienteId() {
        return consultarUno("SELECT nextval(pg_get_serial_sequence('compra_feder', 'id')) AS id", fila -> fila.getLong("id"))
                .orElseThrow(() -> new AccesoDatosException("No se obtuvo el identificador de la compra"));
    }

    public void insertar(CompraFeder c) {
        actualizar("INSERT INTO compra_feder (id, productor_id, codigo, fecha_compra, estado, total, observacion) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                c.getId(), c.getProductorId(), c.getCodigo(), c.getFechaCompra(), c.getEstado(), c.getTotal(), c.getObservacion());
    }

    public Optional<CompraFeder> buscarPorCodigo(String codigo) {
        return consultarUno(COLUMNAS + "WHERE c.codigo = ?", CompraRepositorio::mapear, codigo);
    }

    public Optional<CompraFeder> bloquear(long id) {
        return consultarUno(COLUMNAS + "WHERE c.id = ? FOR UPDATE", CompraRepositorio::mapear, id);
    }

    public Optional<CompraFeder> bloquearPorCodigo(String codigo) {
        return consultarUno(COLUMNAS + "WHERE c.codigo = ? FOR UPDATE", CompraRepositorio::mapear, codigo);
    }

    /** @param productorId nulo para todas; @param estado nulo para todos. */
    public List<CompraResumen> listar(Long productorId, EstadoCompra estado) {
        return consultar("SELECT c.id, c.productor_id, c.codigo, c.fecha_compra, c.estado, c.total, c.fecha_pago_productor, "
                        + "c.observacion, p.codigo_productor, u.nombres || ' ' || u.apellidos AS nombre "
                        + "FROM compra_feder c JOIN productor p ON p.id = c.productor_id JOIN usuario u ON u.id = p.usuario_id "
                        + "WHERE (?::bigint IS NULL OR c.productor_id = ?) AND (?::text IS NULL OR c.estado = ?) "
                        + "ORDER BY c.fecha_compra DESC, c.id DESC",
                fila -> new CompraResumen(mapear(fila), fila.getString("codigo_productor"), fila.getString("nombre")),
                productorId, productorId, estado, estado);
    }

    public void actualizarEstado(long id, EstadoCompra estado) {
        actualizar("UPDATE compra_feder SET estado = ? WHERE id = ?", estado, id);
    }

    public void actualizarTotal(long id, BigDecimal total) {
        actualizar("UPDATE compra_feder SET total = ? WHERE id = ?", total, id);
    }

    /** R006: el pago se registra una sola vez y solo con la compra RECIBIDA. */
    public boolean registrarPago(long id, OffsetDateTime fecha) {
        return actualizar("UPDATE compra_feder SET fecha_pago_productor = ? "
                + "WHERE id = ? AND estado = 'RECIBIDA' AND fecha_pago_productor IS NULL", fecha, id) == 1;
    }

    private static CompraFeder mapear(ResultSet fila) throws SQLException {
        CompraFeder c = new CompraFeder();
        c.setId(fila.getLong("id"));
        c.setProductorId(fila.getLong("productor_id"));
        c.setCodigo(fila.getString("codigo"));
        c.setFechaCompra(fechaHora(fila, "fecha_compra"));
        c.setEstado(enumeracion(fila, "estado", EstadoCompra.class));
        c.setTotal(fila.getBigDecimal("total"));
        c.setFechaPagoProductor(fechaHora(fila, "fecha_pago_productor"));
        c.setObservacion(fila.getString("observacion"));
        return c;
    }
}
