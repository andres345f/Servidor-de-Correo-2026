package com.feder.datos.usuarios;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a la tabla usuario. Nunca devuelve password_hash: solo informa si la cuenta
 * tiene una credencial registrada, que es lo único que necesita la capa de negocio.
 */
public class CuentaRepositorio extends RepositorioJdbc {

    /** Cuenta con sus roles vigentes ya agregados, para los listados administrativos. */
    public record CuentaResumen(Usuario usuario, String rolesVigentes) {
    }

    private static final String COLUMNAS = "u.id, u.correo, u.nombres, u.apellidos, u.ci, u.ci_complemento, u.telefono, "
            + "u.activo, u.fecha_registro, u.ultimo_acceso, u.fecha_aceptacion_terminos";

    public Optional<Usuario> buscarPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario u WHERE u.id = ?", CuentaRepositorio::mapear, id);
    }

    /** El correo es único sin distinguir mayúsculas (R090). */
    public Optional<Usuario> buscarPorCorreo(String correo) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario u WHERE lower(u.correo) = lower(?)",
                CuentaRepositorio::mapear, correo);
    }

    /** La identidad es la pareja (ci, ci_complemento), con el complemento ausente como valor propio (R095). */
    public Optional<Usuario> buscarPorCi(int ci, String complemento) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario u WHERE u.ci = ? AND COALESCE(u.ci_complemento, '') = ?",
                CuentaRepositorio::mapear, ci, complemento == null ? "" : complemento);
    }

    public Optional<Usuario> bloquearPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario u WHERE u.id = ? FOR UPDATE", CuentaRepositorio::mapear, id);
    }

    public Optional<Usuario> bloquearPorCorreo(String correo) {
        return consultarUno("SELECT " + COLUMNAS + " FROM usuario u WHERE lower(u.correo) = lower(?) FOR UPDATE",
                CuentaRepositorio::mapear, correo);
    }

    /** Inserta la cuenta con las fechas indicadas; credencial nula solo para una cuenta invitada inactiva. */
    public long insertar(Usuario u, String credencial) {
        return insertar("INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, ci_complemento, telefono, "
                        + "activo, fecha_registro, fecha_aceptacion_terminos) VALUES (lower(?), ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                u.getCorreo(), credencial, u.getNombres(), u.getApellidos(), u.getCi(), u.getCiComplemento(),
                u.getTelefono(), u.getActivo(), u.getFechaRegistro(), u.getFechaAceptacionTerminos());
    }

    public void actualizarContacto(long id, String nombres, String apellidos, int telefono) {
        actualizar("UPDATE usuario SET nombres = ?, apellidos = ?, telefono = ? WHERE id = ?", nombres, apellidos, telefono, id);
    }

    public void cambiarActivo(long id, boolean activo) {
        actualizar("UPDATE usuario SET activo = ? WHERE id = ?", activo, id);
    }

    /** Activa la cuenta; si nunca tuvo credencial, guarda la indicada en la misma sentencia (ck_usuario_password_hash). */
    public void activarConCredencial(long id, String credencialSiFalta) {
        actualizar("UPDATE usuario SET activo = TRUE, password_hash = COALESCE(password_hash, ?) WHERE id = ?",
                credencialSiFalta, id);
    }

    public void fijarAceptacionTerminos(long id, OffsetDateTime fecha) {
        actualizar("UPDATE usuario SET fecha_aceptacion_terminos = ? WHERE id = ?", fecha, id);
    }

    /** Una cuenta sin credencial es una cuenta invitada que nunca se activó (R091). */
    public boolean tieneCredencial(long id) {
        return consultarUno("SELECT password_hash IS NOT NULL AS tiene FROM usuario WHERE id = ?",
                fila -> fila.getBoolean("tiene"), id).orElse(false);
    }

    /**
     * Listado administrativo. El filtro (opcional) busca en correo, nombres y apellidos sin comodines de LIKE;
     * el rol (opcional) deja solo las cuentas que lo tienen VIGENTE. Devuelve como máximo {@code limite} filas.
     */
    public List<CuentaResumen> listar(String filtro, TipoRol rol, int limite) {
        String codigoRol = rol == null ? null : rol.name();
        return consultar("SELECT " + COLUMNAS + ", "
                        + "(SELECT string_agg(r.codigo, ', ' ORDER BY r.id) FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                        + " WHERE ur.usuario_id = u.id AND ur.estado = 'VIGENTE') AS roles "
                        + "FROM usuario u "
                        + "WHERE (?::text IS NULL OR strpos(lower(u.correo || ' ' || u.nombres || ' ' || u.apellidos), lower(?)) > 0) "
                        + "AND (?::text IS NULL OR EXISTS (SELECT 1 FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                        + " WHERE ur.usuario_id = u.id AND ur.estado = 'VIGENTE' AND r.codigo = ?)) "
                        + "ORDER BY u.id LIMIT ?",
                fila -> new CuentaResumen(mapear(fila), fila.getString("roles")), filtro, filtro, codigoRol, codigoRol, limite);
    }

    private static Usuario mapear(ResultSet fila) throws SQLException {
        Usuario u = new Usuario();
        u.setId(fila.getLong("id"));
        u.setCorreo(fila.getString("correo"));
        u.setNombres(fila.getString("nombres"));
        u.setApellidos(fila.getString("apellidos"));
        u.setCi(fila.getInt("ci"));
        u.setCiComplemento(fila.getString("ci_complemento"));
        u.setTelefono(fila.getInt("telefono"));
        u.setActivo(fila.getBoolean("activo"));
        u.setFechaRegistro(fechaHora(fila, "fecha_registro"));
        u.setUltimoAcceso(fechaHora(fila, "ultimo_acceso"));
        u.setFechaAceptacionTerminos(fechaHora(fila, "fecha_aceptacion_terminos"));
        return u;
    }
}
