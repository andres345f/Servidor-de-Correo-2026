package com.feder.datos.productores;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoSolicitudProductor;
import com.feder.modelo.Productor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Acceso a la tabla productor (perfil y solicitud de alta). */
public class ProductorRepositorio extends RepositorioJdbc {

    /** Perfil con los datos de su titular y de su comunidad actual, para consultas y listados. */
    public record PerfilProductor(Productor productor, String nombreTitular, String correoTitular,
                                  String comunidad, String municipio, boolean comunidadActiva) {
    }

    private static final String COLUMNAS = "p.id, p.usuario_id, p.comunidad_agricola_id, p.codigo_productor, p.estado_solicitud, "
            + "p.fecha_solicitud, p.fecha_revision, p.motivo_rechazo, p.ubicacion_actividad, p.fecha_afiliacion, p.activo";

    private static final String SQL_PERFIL = "SELECT " + COLUMNAS + ", u.nombres || ' ' || u.apellidos AS titular, u.correo, "
            + "c.nombre AS comunidad, c.municipio, c.activa AS comunidad_activa "
            + "FROM productor p JOIN usuario u ON u.id = p.usuario_id "
            + "JOIN comunidad_agricola c ON c.id = p.comunidad_agricola_id ";

    public Optional<Productor> bloquearPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM productor p WHERE p.id = ? FOR UPDATE", ProductorRepositorio::mapear, id);
    }

    public Optional<Productor> buscarPorUsuario(long usuarioId) {
        return consultarUno("SELECT " + COLUMNAS + " FROM productor p WHERE p.usuario_id = ?", ProductorRepositorio::mapear,
                usuarioId);
    }

    /**
     * Lectura con FOR SHARE del perfil: una revocación de PRODUCTOR en curso (que lo bloquea FOR UPDATE) termina
     * antes y aquí se ve el perfil ya inactivo; varias operaciones del mismo productor no se bloquean entre sí.
     */
    public Optional<Productor> compartirPorUsuario(long usuarioId) {
        return consultarUno("SELECT " + COLUMNAS + " FROM productor p WHERE p.usuario_id = ? FOR SHARE",
                ProductorRepositorio::mapear, usuarioId);
    }

    public Optional<Productor> bloquearPorUsuario(long usuarioId) {
        return consultarUno("SELECT " + COLUMNAS + " FROM productor p WHERE p.usuario_id = ? FOR UPDATE",
                ProductorRepositorio::mapear, usuarioId);
    }

    public Optional<PerfilProductor> perfilPorId(long id) {
        return consultarUno(SQL_PERFIL + "WHERE p.id = ?", ProductorRepositorio::mapearPerfil, id);
    }

    public Optional<PerfilProductor> perfilPorCodigo(String codigoProductor) {
        return consultarUno(SQL_PERFIL + "WHERE p.codigo_productor = ?", ProductorRepositorio::mapearPerfil, codigoProductor);
    }

    public Optional<PerfilProductor> perfilPorUsuario(long usuarioId) {
        return consultarUno(SQL_PERFIL + "WHERE p.usuario_id = ?", ProductorRepositorio::mapearPerfil, usuarioId);
    }

    /** Listado administrativo, opcionalmente filtrado por estado de la solicitud. */
    public List<PerfilProductor> listar(EstadoSolicitudProductor estado) {
        return consultar(SQL_PERFIL + "WHERE ?::text IS NULL OR p.estado_solicitud = ? ORDER BY p.fecha_solicitud, p.id",
                ProductorRepositorio::mapearPerfil, estado, estado);
    }

    /** Nueva solicitud: PENDIENTE e inactiva, sin rol PRODUCTOR (R075). */
    public long insertarSolicitud(long usuarioId, long comunidadId, String ubicacion, OffsetDateTime fecha) {
        return insertar("INSERT INTO productor (usuario_id, comunidad_agricola_id, estado_solicitud, fecha_solicitud, "
                        + "ubicacion_actividad, activo) VALUES (?, ?, 'PENDIENTE', ?, ?, FALSE)",
                usuarioId, comunidadId, fecha, ubicacion);
    }

    /** Tras un rechazo se reabre el mismo perfil con los datos corregidos (R075). */
    public boolean reabrirSolicitud(long id, long comunidadId, String ubicacion, OffsetDateTime fecha) {
        return actualizar("UPDATE productor SET estado_solicitud = 'PENDIENTE', fecha_solicitud = ?, fecha_revision = NULL, "
                        + "motivo_rechazo = NULL, comunidad_agricola_id = ?, ubicacion_actividad = ? "
                        + "WHERE id = ? AND estado_solicitud = 'RECHAZADA'",
                fecha, comunidadId, ubicacion, id) == 1;
    }

    /** El código y la fecha de afiliación solo se asignan en la primera aprobación (COALESCE). */
    public boolean aprobar(long id, OffsetDateTime fecha, String codigoSiFalta, LocalDate afiliacionSiFalta) {
        return actualizar("UPDATE productor SET estado_solicitud = 'APROBADA', fecha_revision = ?, motivo_rechazo = NULL, "
                        + "codigo_productor = COALESCE(codigo_productor, ?), fecha_afiliacion = COALESCE(fecha_afiliacion, ?), "
                        + "activo = TRUE WHERE id = ? AND estado_solicitud = 'PENDIENTE'",
                fecha, codigoSiFalta, afiliacionSiFalta, id) == 1;
    }

    public boolean rechazar(long id, OffsetDateTime fecha, String motivo) {
        return actualizar("UPDATE productor SET estado_solicitud = 'RECHAZADA', fecha_revision = ?, motivo_rechazo = ? "
                + "WHERE id = ? AND estado_solicitud = 'PENDIENTE'", fecha, motivo, id) == 1;
    }

    public void cambiarActivo(long id, boolean activo) {
        actualizar("UPDATE productor SET activo = ? WHERE id = ?", activo, id);
    }

    public void cambiarComunidad(long id, long comunidadId) {
        actualizar("UPDATE productor SET comunidad_agricola_id = ? WHERE id = ?", comunidadId, id);
    }

    public void actualizarUbicacion(long id, String ubicacion) {
        actualizar("UPDATE productor SET ubicacion_actividad = ? WHERE id = ?", ubicacion, id);
    }

    private static Productor mapear(ResultSet fila) throws SQLException {
        Productor p = new Productor();
        p.setId(fila.getLong("id"));
        p.setUsuarioId(fila.getLong("usuario_id"));
        p.setComunidadAgricolaId(fila.getLong("comunidad_agricola_id"));
        p.setCodigoProductor(fila.getString("codigo_productor"));
        p.setEstadoSolicitud(enumeracion(fila, "estado_solicitud", EstadoSolicitudProductor.class));
        p.setFechaSolicitud(fechaHora(fila, "fecha_solicitud"));
        p.setFechaRevision(fechaHora(fila, "fecha_revision"));
        p.setMotivoRechazo(fila.getString("motivo_rechazo"));
        p.setUbicacionActividad(fila.getString("ubicacion_actividad"));
        p.setFechaAfiliacion(fecha(fila, "fecha_afiliacion"));
        p.setActivo(fila.getBoolean("activo"));
        return p;
    }

    private static PerfilProductor mapearPerfil(ResultSet fila) throws SQLException {
        return new PerfilProductor(mapear(fila), fila.getString("titular"), fila.getString("correo"),
                fila.getString("comunidad"), fila.getString("municipio"), fila.getBoolean("comunidad_activa"));
    }
}
