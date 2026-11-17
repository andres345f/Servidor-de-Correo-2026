package com.feder.datos.seguridad;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.TipoRol;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Consultas para identificar al remitente: cuenta activa por correo y sus concesiones VIGENTE. */
public class IdentidadRepositorio extends RepositorioJdbc {

    /** Datos mínimos de una cuenta para autorizar una petición. */
    public static final class CuentaActiva {
        public final long id;
        public final String correo;
        public final String nombreCompleto;

        CuentaActiva(long id, String correo, String nombreCompleto) {
            this.id = id;
            this.correo = correo;
            this.nombreCompleto = nombreCompleto;
        }
    }

    // El correo es único sin distinguir mayúsculas (R090), por eso comparamos en minúsculas.
    private static final String SQL_CUENTA_ACTIVA =
            "SELECT id, correo, nombres || ' ' || apellidos AS nombre FROM usuario "
                    + "WHERE lower(correo) = lower(?) AND activo = TRUE";

    // R088: los permisos se consultan en cada petición; solo cuentan las concesiones VIGENTE.
    private static final String SQL_ROLES_VIGENTES =
            "SELECT r.codigo FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                    + "WHERE ur.usuario_id = ? AND ur.estado = 'VIGENTE'";

    public Optional<CuentaActiva> buscarCuentaActiva(String correo) {
        return consultarUno(SQL_CUENTA_ACTIVA,
                fila -> new CuentaActiva(fila.getLong("id"), fila.getString("correo"), fila.getString("nombre")),
                correo);
    }

    /** Dirección registrada de una cuenta, esté activa o no (para avisos como una invitación). */
    public Optional<String> correoDeCuenta(long usuarioId) {
        return consultarUno("SELECT correo FROM usuario WHERE id = ?", fila -> fila.getString("correo"), usuarioId);
    }

    public Set<TipoRol> rolesVigentes(long usuarioId) {
        List<TipoRol> roles = consultar(SQL_ROLES_VIGENTES, fila -> TipoRol.valueOf(fila.getString("codigo")), usuarioId);
        return roles.isEmpty() ? EnumSet.noneOf(TipoRol.class) : EnumSet.copyOf(roles);
    }
}
