package com.feder.datos.produccion;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoProduccion;
import com.feder.modelo.Produccion;
import com.feder.modelo.UnidadMedida;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a la tabla produccion. No expone DELETE ni UPDATE de comunidad_origen_id: la procedencia
 * se fija al crear la producción (R066, también protegido por tg_produccion_integridad).
 */
public class ProduccionRepositorio extends RepositorioJdbc {

    /** Producción con los datos de su producto, su productor y el estado actual de su comunidad de origen. */
    public record ProduccionResumen(Produccion produccion, String codigoProducto, String nombreProducto, UnidadMedida unidad,
                                    long usuarioProductorId, String codigoProductor, String nombreProductor,
                                    boolean comunidadOrigenActiva) {
    }

    private static final String COLUMNAS = "pr.id, pr.productor_id, pr.producto_id, pr.comunidad_origen_id, pr.codigo, "
            + "pr.fecha_inicio, pr.fecha_disponibilidad_estimada, pr.cantidad_estimada, pr.ubicacion_produccion, pr.estado, "
            + "pr.observacion, pr.nombre_comunidad_origen, pr.municipio_origen, pr.departamento_origen";

    private static final String SQL_RESUMEN = "SELECT " + COLUMNAS + ", p.codigo AS codigo_producto, p.nombre AS nombre_producto, "
            + "p.unidad_medida, o.usuario_id, o.codigo_productor, u.nombres || ' ' || u.apellidos AS nombre_productor, "
            + "c.activa AS comunidad_activa FROM produccion pr "
            + "JOIN producto p ON p.id = pr.producto_id JOIN productor o ON o.id = pr.productor_id "
            + "JOIN usuario u ON u.id = o.usuario_id JOIN comunidad_agricola c ON c.id = pr.comunidad_origen_id ";

    /** Reserva el siguiente identificador para formar el código antes de insertar (PRD-año-id). */
    public long reservarId() {
        return consultarUno("SELECT nextval(pg_get_serial_sequence('produccion', 'id')) AS id", fila -> fila.getLong("id"))
                .orElseThrow();
    }

    public void insertar(Produccion p) {
        actualizar("INSERT INTO produccion (id, productor_id, producto_id, comunidad_origen_id, codigo, fecha_inicio, "
                        + "fecha_disponibilidad_estimada, cantidad_estimada, ubicacion_produccion, estado, observacion, "
                        + "nombre_comunidad_origen, municipio_origen, departamento_origen) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.getId(), p.getProductorId(), p.getProductoId(), p.getComunidadOrigenId(), p.getCodigo(), p.getFechaInicio(),
                p.getFechaDisponibilidadEstimada(), p.getCantidadEstimada(), p.getUbicacionProduccion(), p.getEstado(),
                p.getObservacion(), p.getNombreComunidadOrigen(), p.getMunicipioOrigen(), p.getDepartamentoOrigen());
    }

    public Optional<Produccion> bloquearPorCodigo(String codigo) {
        return consultarUno("SELECT " + COLUMNAS + " FROM produccion pr WHERE pr.codigo = ? FOR UPDATE",
                ProduccionRepositorio::mapear, codigo);
    }

    public Optional<ProduccionResumen> resumenPorCodigo(String codigo) {
        return consultarUno(SQL_RESUMEN + "WHERE pr.codigo = ?", ProduccionRepositorio::mapearResumen, codigo);
    }

    public Optional<ProduccionResumen> resumenPorId(long id) {
        return consultarUno(SQL_RESUMEN + "WHERE pr.id = ?", ProduccionRepositorio::mapearResumen, id);
    }

    /** Producciones de un productor (o de todos si es nulo), opcionalmente por estado. */
    public List<ProduccionResumen> listar(Long productorId, EstadoProduccion estado) {
        return consultar(SQL_RESUMEN + "WHERE (?::bigint IS NULL OR pr.productor_id = ?) AND (?::text IS NULL OR pr.estado = ?) "
                        + "ORDER BY pr.id",
                ProduccionRepositorio::mapearResumen, productorId, productorId, estado, estado);
    }

    public void actualizarEstado(long id, EstadoProduccion estado) {
        actualizar("UPDATE produccion SET estado = ? WHERE id = ?", estado, id);
    }

    /** Datos editables; productor, producto y comunidad de origen no se tocan. */
    public void actualizarDatos(Produccion p) {
        actualizar("UPDATE produccion SET fecha_inicio = ?, fecha_disponibilidad_estimada = ?, cantidad_estimada = ?, "
                        + "ubicacion_produccion = ?, observacion = ? WHERE id = ?",
                p.getFechaInicio(), p.getFechaDisponibilidadEstimada(), p.getCantidadEstimada(), p.getUbicacionProduccion(),
                p.getObservacion(), p.getId());
    }

    /** Corrección de la instantánea de origen (solo ADMINISTRADOR con motivo, R066). */
    public void actualizarInstantanea(long id, String nombre, String municipio, String departamento) {
        actualizar("UPDATE produccion SET nombre_comunidad_origen = ?, municipio_origen = ?, departamento_origen = ? WHERE id = ?",
                nombre, municipio, departamento, id);
    }

    public boolean tieneLotes(long id) {
        return consultarUno("SELECT EXISTS (SELECT 1 FROM lote_produccion WHERE produccion_id = ?) AS hay",
                fila -> fila.getBoolean("hay"), id).orElse(false);
    }

    /** Fecha de elaboración más temprana de sus lotes (para no dejar la fecha de inicio posterior a ella). */
    public Optional<LocalDate> primeraElaboracion(long id) {
        return consultarUno("SELECT min(fecha_elaboracion) AS primera FROM lote_produccion WHERE produccion_id = ?",
                fila -> fecha(fila, "primera"), id).filter(java.util.Objects::nonNull);
    }

    static Produccion mapear(ResultSet fila) throws SQLException {
        Produccion p = new Produccion();
        p.setId(fila.getLong("id"));
        p.setProductorId(fila.getLong("productor_id"));
        p.setProductoId(fila.getLong("producto_id"));
        p.setComunidadOrigenId(fila.getLong("comunidad_origen_id"));
        p.setCodigo(fila.getString("codigo"));
        p.setFechaInicio(fecha(fila, "fecha_inicio"));
        p.setFechaDisponibilidadEstimada(fecha(fila, "fecha_disponibilidad_estimada"));
        p.setCantidadEstimada(fila.getBigDecimal("cantidad_estimada"));
        p.setUbicacionProduccion(fila.getString("ubicacion_produccion"));
        p.setEstado(enumeracion(fila, "estado", EstadoProduccion.class));
        p.setObservacion(fila.getString("observacion"));
        p.setNombreComunidadOrigen(fila.getString("nombre_comunidad_origen"));
        p.setMunicipioOrigen(fila.getString("municipio_origen"));
        p.setDepartamentoOrigen(fila.getString("departamento_origen"));
        return p;
    }

    private static ProduccionResumen mapearResumen(ResultSet fila) throws SQLException {
        return new ProduccionResumen(mapear(fila), fila.getString("codigo_producto"), fila.getString("nombre_producto"),
                enumeracion(fila, "unidad_medida", UnidadMedida.class), fila.getLong("usuario_id"),
                fila.getString("codigo_productor"), fila.getString("nombre_productor"), fila.getBoolean("comunidad_activa"));
    }
}
