package com.feder.datos.productores;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.ComunidadAgricola;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a comunidad_agricola. No expone DELETE: la baja es lógica (activa = false) y la base
 * impide borrar una comunidad referenciada (R008).
 */
public class ComunidadRepositorio extends RepositorioJdbc {

    /** Comunidad con la cantidad de productores afiliados, para las vistas administrativas. */
    public record ComunidadResumen(ComunidadAgricola comunidad, int productores) {
    }

    private static final String COLUMNAS = "c.id, c.nombre, c.municipio, c.departamento, c.descripcion, c.activa";

    public Optional<ComunidadAgricola> buscarPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM comunidad_agricola c WHERE c.id = ?", ComunidadRepositorio::mapear, id);
    }

    public Optional<ComunidadAgricola> bloquearPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM comunidad_agricola c WHERE c.id = ? FOR UPDATE",
                ComunidadRepositorio::mapear, id);
    }

    /**
     * Lectura con FOR SHARE: quien asocia algo a la comunidad espera a una desactivación en curso (que toma
     * FOR UPDATE) y ve su resultado confirmado, sin bloquear a otros que también solo la leen.
     */
    public Optional<ComunidadAgricola> compartirPorId(long id) {
        return consultarUno("SELECT " + COLUMNAS + " FROM comunidad_agricola c WHERE c.id = ? FOR SHARE",
                ComunidadRepositorio::mapear, id);
    }

    /** Duplicado de (nombre, municipio) sin distinguir mayúsculas, excluyendo opcionalmente una comunidad. */
    public boolean existeNombreEnMunicipio(String nombre, String municipio, Long excluirId) {
        return consultarUno("SELECT EXISTS (SELECT 1 FROM comunidad_agricola WHERE lower(nombre) = lower(?) "
                        + "AND lower(municipio) = lower(?) AND (?::bigint IS NULL OR id <> ?)) AS hay",
                fila -> fila.getBoolean("hay"), nombre, municipio, excluirId, excluirId).orElse(false);
    }

    public List<ComunidadResumen> listar(boolean soloActivas, String filtro) {
        return consultar("SELECT " + COLUMNAS + ", (SELECT count(*) FROM productor p WHERE p.comunidad_agricola_id = c.id) AS n "
                        + "FROM comunidad_agricola c WHERE (c.activa OR NOT ?) "
                        + "AND (?::text IS NULL OR strpos(lower(c.nombre || ' ' || c.municipio || ' ' || c.departamento), lower(?)) > 0) "
                        + "ORDER BY c.nombre, c.municipio",
                fila -> new ComunidadResumen(mapear(fila), fila.getInt("n")), soloActivas, filtro, filtro);
    }

    public long insertar(ComunidadAgricola c) {
        return insertar("INSERT INTO comunidad_agricola (nombre, municipio, departamento, descripcion, activa) "
                + "VALUES (?, ?, ?, ?, TRUE)", c.getNombre(), c.getMunicipio(), c.getDepartamento(), c.getDescripcion());
    }

    public void actualizar(ComunidadAgricola c) {
        actualizar("UPDATE comunidad_agricola SET nombre = ?, municipio = ?, departamento = ?, descripcion = ? WHERE id = ?",
                c.getNombre(), c.getMunicipio(), c.getDepartamento(), c.getDescripcion(), c.getId());
    }

    public void cambiarActiva(long id, boolean activa) {
        actualizar("UPDATE comunidad_agricola SET activa = ? WHERE id = ?", activa, id);
    }

    private static ComunidadAgricola mapear(ResultSet fila) throws SQLException {
        ComunidadAgricola c = new ComunidadAgricola();
        c.setId(fila.getLong("id"));
        c.setNombre(fila.getString("nombre"));
        c.setMunicipio(fila.getString("municipio"));
        c.setDepartamento(fila.getString("departamento"));
        c.setDescripcion(fila.getString("descripcion"));
        c.setActiva(fila.getBoolean("activa"));
        return c;
    }
}
