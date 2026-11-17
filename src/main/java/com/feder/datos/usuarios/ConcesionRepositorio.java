package com.feder.datos.usuarios;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoConcesion;
import com.feder.modelo.TipoRol;
import com.feder.modelo.UsuarioRol;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a usuario_rol (concesiones) y a las filas del catálogo cerrado rol.
 * No expone DELETE: una concesión nunca se borra (R096). Las transiciones son UPDATE
 * condicionales sobre el estado de origen, así una transición solo ocurre una vez.
 */
public class ConcesionRepositorio extends RepositorioJdbc {

    /** Concesión con el código de su rol. */
    public record Concesion(UsuarioRol datos, TipoRol rol) {
        public long id() {
            return datos.getId();
        }

        public EstadoConcesion estado() {
            return datos.getEstado();
        }
    }

    /** Concesión con el correo de su cuenta, para el listado de todas las cuentas. */
    public record ConcesionDeCuenta(Concesion concesion, String correo) {
    }

    private static final String COLUMNAS = "ur.id, ur.usuario_id, ur.rol_id, ur.asignado_por_id, ur.revocado_por_id, ur.estado, "
            + "ur.fecha_asignacion, ur.fecha_activacion, ur.fecha_expiracion, ur.fecha_revocacion, ur.motivo, "
            + "ur.motivo_revocacion, r.codigo";

    /** Identificador de la fila ROL de un código (el catálogo lo crea 01_schema.sql). */
    public long idRol(TipoRol rol) {
        return consultarUno("SELECT id FROM rol WHERE codigo = ?", fila -> fila.getLong("id"), rol)
                .orElseThrow(() -> new AccesoDatosException("Falta la fila del rol " + rol + " en el catálogo"));
    }

    /** Bloquea la fila ROL indicada (R099 y R102 serializan con la fila de ADMINISTRADOR). */
    public long bloquearRol(TipoRol rol) {
        return consultarUno("SELECT id FROM rol WHERE codigo = ? FOR UPDATE", fila -> fila.getLong("id"), rol)
                .orElseThrow(() -> new AccesoDatosException("Falta la fila del rol " + rol + " en el catálogo"));
    }

    /** Si existe alguna concesión del rol, en cualquier estado. */
    public boolean existeAlgunaDeRol(TipoRol rol) {
        return consultarUno("SELECT EXISTS (SELECT 1 FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id WHERE r.codigo = ?) AS hay",
                fila -> fila.getBoolean("hay"), rol).orElse(false);
    }

    /** Cuentas activas con el rol VIGENTE, sin contar la cuenta indicada. */
    public int contarVigentesEnCuentasActivasSalvo(TipoRol rol, long usuarioExcluido) {
        return consultarUno("SELECT count(DISTINCT ur.usuario_id) AS n FROM usuario_rol ur "
                        + "JOIN rol r ON r.id = ur.rol_id JOIN usuario u ON u.id = ur.usuario_id "
                        + "WHERE r.codigo = ? AND ur.estado = 'VIGENTE' AND u.activo AND ur.usuario_id <> ?",
                fila -> fila.getInt("n"), rol, usuarioExcluido).orElse(0);
    }

    /** Concesión PENDIENTE o VIGENTE de la pareja (usuario, rol), bloqueada para cambiarla. */
    public Optional<Concesion> bloquearAbierta(long usuarioId, TipoRol rol) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                        + "WHERE ur.usuario_id = ? AND r.codigo = ? AND ur.estado IN ('PENDIENTE', 'VIGENTE') FOR UPDATE OF ur",
                ConcesionRepositorio::mapear, usuarioId, rol);
    }

    public Optional<Concesion> buscarAbierta(long usuarioId, TipoRol rol) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                        + "WHERE ur.usuario_id = ? AND r.codigo = ? AND ur.estado IN ('PENDIENTE', 'VIGENTE')",
                ConcesionRepositorio::mapear, usuarioId, rol);
    }

    /** Concesión indicada, bloqueada para cambiar su estado. */
    public Optional<Concesion> bloquearPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id WHERE ur.id = ? FOR UPDATE OF ur",
                ConcesionRepositorio::mapear, id);
    }

    /** Invitaciones PENDIENTE de una cuenta, bloqueadas (en orden de id para bloquear siempre igual). */
    public List<Concesion> bloquearPendientesDe(long usuarioId) {
        return consultar("SELECT " + COLUMNAS + " FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                + "WHERE ur.usuario_id = ? AND ur.estado = 'PENDIENTE' ORDER BY ur.id FOR UPDATE OF ur",
                ConcesionRepositorio::mapear, usuarioId);
    }

    /**
     * Invitaciones PENDIENTE cuya fecha de expiración ya pasó, bloqueadas. SKIP LOCKED evita esperar
     * a una invitación que alguien está aceptando o cancelando en ese mismo momento.
     */
    public List<Concesion> bloquearInvitacionesVencidas(int maximo) {
        return consultar("SELECT " + COLUMNAS + " FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                        + "WHERE ur.estado = 'PENDIENTE' AND ur.fecha_expiracion <= now() "
                        + "ORDER BY ur.id LIMIT ? FOR UPDATE OF ur SKIP LOCKED",
                ConcesionRepositorio::mapear, maximo);
    }

    public Optional<Concesion> buscarPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id WHERE ur.id = ?",
                ConcesionRepositorio::mapear, id);
    }

    public List<Concesion> deUsuario(long usuarioId) {
        return consultar("SELECT " + COLUMNAS + " FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                + "WHERE ur.usuario_id = ? ORDER BY ur.fecha_asignacion, ur.id", ConcesionRepositorio::mapear, usuarioId);
    }

    /** Concesiones de todas las cuentas, de la más reciente a la más antigua. */
    public List<ConcesionDeCuenta> todas(int limite) {
        return consultar("SELECT " + COLUMNAS + ", u.correo FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                        + "JOIN usuario u ON u.id = ur.usuario_id ORDER BY ur.fecha_asignacion DESC, ur.id DESC LIMIT ?",
                fila -> new ConcesionDeCuenta(mapear(fila), fila.getString("correo")), limite);
    }

    /** Alta VIGENTE directa: fecha_activacion igual a fecha_asignacion (R096). */
    public long insertarVigente(long usuarioId, long rolId, Long asignadoPorId, OffsetDateTime fecha, String motivo) {
        return insertar("INSERT INTO usuario_rol (usuario_id, rol_id, asignado_por_id, estado, fecha_asignacion, "
                        + "fecha_activacion, motivo) VALUES (?, ?, ?, 'VIGENTE', ?, ?, ?)",
                usuarioId, rolId, asignadoPorId, fecha, fecha, motivo);
    }

    /** Invitación: queda PENDIENTE hasta su aceptación, con plazo (R100). */
    public long insertarPendiente(long usuarioId, long rolId, long asignadoPorId, OffsetDateTime fecha,
                                  OffsetDateTime expiracion, String motivo) {
        return insertar("INSERT INTO usuario_rol (usuario_id, rol_id, asignado_por_id, estado, fecha_asignacion, "
                        + "fecha_expiracion, motivo) VALUES (?, ?, ?, 'PENDIENTE', ?, ?, ?)",
                usuarioId, rolId, asignadoPorId, fecha, expiracion, motivo);
    }

    public boolean activar(long id, OffsetDateTime fecha) {
        return actualizar("UPDATE usuario_rol SET estado = 'VIGENTE', fecha_activacion = ? WHERE id = ? AND estado = 'PENDIENTE'",
                fecha, id) == 1;
    }

    public boolean revocar(long id, long revocadoPorId, OffsetDateTime fecha, String motivo) {
        return actualizar("UPDATE usuario_rol SET estado = 'REVOCADA', revocado_por_id = ?, fecha_revocacion = ?, "
                + "motivo_revocacion = ? WHERE id = ? AND estado = 'VIGENTE'", revocadoPorId, fecha, motivo, id) == 1;
    }

    /** PENDIENTE -> ANULADA; revocado_por es nulo solo cuando la invitación vence (R101). */
    public boolean anular(long id, Long revocadoPorId, OffsetDateTime fecha, String motivo) {
        return actualizar("UPDATE usuario_rol SET estado = 'ANULADA', revocado_por_id = ?, fecha_revocacion = ?, "
                + "motivo_revocacion = ? WHERE id = ? AND estado = 'PENDIENTE'", revocadoPorId, fecha, motivo, id) == 1;
    }

    private static Concesion mapear(ResultSet fila) throws SQLException {
        UsuarioRol ur = new UsuarioRol();
        ur.setId(fila.getLong("id"));
        ur.setUsuarioId(fila.getLong("usuario_id"));
        ur.setRolId(fila.getLong("rol_id"));
        ur.setAsignadoPorId(largoONulo(fila, "asignado_por_id"));
        ur.setRevocadoPorId(largoONulo(fila, "revocado_por_id"));
        ur.setEstado(enumeracion(fila, "estado", EstadoConcesion.class));
        ur.setFechaAsignacion(fechaHora(fila, "fecha_asignacion"));
        ur.setFechaActivacion(fechaHora(fila, "fecha_activacion"));
        ur.setFechaExpiracion(fechaHora(fila, "fecha_expiracion"));
        ur.setFechaRevocacion(fechaHora(fila, "fecha_revocacion"));
        ur.setMotivo(fila.getString("motivo"));
        ur.setMotivoRevocacion(fila.getString("motivo_revocacion"));
        return new Concesion(ur, enumeracion(fila, "codigo", TipoRol.class));
    }
}
