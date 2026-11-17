package com.feder.datos.pagos;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoFactura;
import com.feder.modelo.Factura;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Acceso a FACTURA. Una por pedido y con su mismo total (uq_factura_pedido, fk_factura_pedido_total).
 * Una factura no se borra: solo puede pasar a ANULADA con su fecha (R030).
 */
public class FacturaRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT id, pedido_id, numero, fecha_emision, nit_ci, razon_social, total, "
            + "codigo_autorizacion, estado, fecha_anulacion FROM factura ";

    /** Reserva el identificador antes de insertar, para formar el número visible con él. */
    public long siguienteId() {
        return consultarUno("SELECT nextval(pg_get_serial_sequence('factura', 'id')) AS id", fila -> fila.getLong("id"))
                .orElseThrow(() -> new AccesoDatosException("No se obtuvo el identificador de la factura"));
    }

    public void insertar(Factura f) {
        actualizar("INSERT INTO factura (id, pedido_id, numero, fecha_emision, nit_ci, razon_social, total, codigo_autorizacion, "
                        + "estado) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'VIGENTE')",
                f.getId(), f.getPedidoId(), f.getNumero(), f.getFechaEmision(), f.getNitCi(), f.getRazonSocial(), f.getTotal(),
                f.getCodigoAutorizacion());
    }

    public Optional<Factura> dePedido(long pedidoId) {
        return consultarUno(COLUMNAS + "WHERE pedido_id = ?", FacturaRepositorio::mapear, pedidoId);
    }

    public Optional<Factura> buscarPorNumero(String numero) {
        return consultarUno(COLUMNAS + "WHERE numero = ?", FacturaRepositorio::mapear, numero);
    }

    /** VIGENTE -> ANULADA con su fecha; false si ya estaba anulada. */
    public boolean anular(long id, OffsetDateTime fecha) {
        return actualizar("UPDATE factura SET estado = 'ANULADA', fecha_anulacion = ? WHERE id = ? AND estado = 'VIGENTE'",
                fecha, id) == 1;
    }

    private static Factura mapear(ResultSet fila) throws SQLException {
        Factura f = new Factura();
        f.setId(fila.getLong("id"));
        f.setPedidoId(fila.getLong("pedido_id"));
        f.setNumero(fila.getString("numero"));
        f.setFechaEmision(fechaHora(fila, "fecha_emision"));
        f.setNitCi(fila.getString("nit_ci"));
        f.setRazonSocial(fila.getString("razon_social"));
        f.setTotal(fila.getBigDecimal("total"));
        f.setCodigoAutorizacion(fila.getString("codigo_autorizacion"));
        f.setEstado(enumeracion(fila, "estado", EstadoFactura.class));
        f.setFechaAnulacion(fechaHora(fila, "fecha_anulacion"));
        return f;
    }
}
