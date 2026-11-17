package com.feder.datos.catalogo;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.Categoria;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Acceso a CATEGORIA. Las categorías no se borran: se desactivan (baja lógica). */
public class CategoriaRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT id, nombre, descripcion, activa FROM categoria ";

    /** @param filtro texto a buscar en el nombre; nulo para todas. */
    public List<Categoria> listar(boolean soloActivas, String filtro) {
        return consultar(COLUMNAS + "WHERE (? = FALSE OR activa) AND (?::text IS NULL OR nombre ILIKE '%' || ? || '%') "
                        + "ORDER BY nombre",
                CategoriaRepositorio::mapear, soloActivas, filtro, filtro);
    }

    /** Búsqueda sin distinguir mayúsculas, para evitar categorías que solo difieren en ellas. */
    public Optional<Categoria> buscarPorNombre(String nombre) {
        return consultarUno(COLUMNAS + "WHERE lower(nombre) = lower(?)", CategoriaRepositorio::mapear, nombre);
    }

    /** Bloquea la fila para modificarla o desactivarla sin carreras con altas de productos. */
    public Optional<Categoria> bloquearPorNombre(String nombre) {
        return consultarUno(COLUMNAS + "WHERE lower(nombre) = lower(?) FOR UPDATE", CategoriaRepositorio::mapear, nombre);
    }

    /** Bloqueo compartido: impide que la categoría se desactive mientras se le asigna un producto activo. */
    public Optional<Categoria> bloquearCompartidaPorNombre(String nombre) {
        return consultarUno(COLUMNAS + "WHERE lower(nombre) = lower(?) FOR SHARE", CategoriaRepositorio::mapear, nombre);
    }

    public Optional<Categoria> bloquearCompartida(long id) {
        return consultarUno(COLUMNAS + "WHERE id = ? FOR SHARE", CategoriaRepositorio::mapear, id);
    }

    public long insertar(Categoria categoria) {
        return insertar("INSERT INTO categoria (nombre, descripcion, activa) VALUES (?, ?, ?)",
                categoria.getNombre(), categoria.getDescripcion(), categoria.getActiva());
    }

    public void actualizar(long id, String nombre, String descripcion) {
        actualizar("UPDATE categoria SET nombre = ?, descripcion = ? WHERE id = ?", nombre, descripcion, id);
    }

    public void cambiarActiva(long id, boolean activa) {
        actualizar("UPDATE categoria SET activa = ? WHERE id = ?", activa, id);
    }

    public int contarProductosActivos(long categoriaId) {
        return consultarUno("SELECT count(*) AS n FROM producto WHERE categoria_id = ? AND activo",
                fila -> fila.getInt("n"), categoriaId).orElse(0);
    }

    private static Categoria mapear(ResultSet fila) throws SQLException {
        Categoria c = new Categoria();
        c.setId(fila.getLong("id"));
        c.setNombre(fila.getString("nombre"));
        c.setDescripcion(fila.getString("descripcion"));
        c.setActiva(fila.getBoolean("activa"));
        return c;
    }
}
