package com.feder.datos.compras;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoSolicitudProductor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/**
 * Consulta del PRODUCTOR que vende a FEDER (A11). Solo lectura: el perfil de productor pertenece a CU2.
 * Nunca devuelve datos personales (cédula, teléfono, correo).
 */
public class ProductorCompraRepositorio extends RepositorioJdbc {

    private static final String SELECT = "SELECT p.id, p.codigo_productor, p.estado_solicitud, p.activo, p.usuario_id, "
            + "u.activo AS cuenta_activa, u.nombres || ' ' || u.apellidos AS nombre "
            + "FROM productor p JOIN usuario u ON u.id = p.usuario_id ";

    public record Proveedor(long id, String codigo, String nombre, EstadoSolicitudProductor estado, boolean activo,
                            boolean cuentaActiva, long usuarioId) {

        /** R004: solo se compra a un productor aprobado, con perfil y cuenta activos. */
        public boolean habilitado() {
            return estado == EstadoSolicitudProductor.APROBADA && activo && cuentaActiva;
        }
    }

    public Optional<Proveedor> buscarPorCodigo(String codigoProductor) {
        return consultarUno(SELECT + "WHERE p.codigo_productor = ?", ProductorCompraRepositorio::mapear, codigoProductor);
    }

    public Optional<Proveedor> buscarPorId(long id) {
        return consultarUno(SELECT + "WHERE p.id = ?", ProductorCompraRepositorio::mapear, id);
    }

    /** Perfil de productor de una cuenta, para limitar al PRODUCTOR a sus propias compras (R088). */
    public Optional<Proveedor> buscarPorUsuario(long usuarioId) {
        return consultarUno(SELECT + "WHERE p.usuario_id = ?", ProductorCompraRepositorio::mapear, usuarioId);
    }

    private static Proveedor mapear(ResultSet fila) throws SQLException {
        return new Proveedor(fila.getLong("id"), fila.getString("codigo_productor"), fila.getString("nombre"),
                enumeracion(fila, "estado_solicitud", EstadoSolicitudProductor.class), fila.getBoolean("activo"),
                fila.getBoolean("cuenta_activa"), fila.getLong("usuario_id"));
    }
}
