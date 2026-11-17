package com.feder.datos.catalogo;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.Producto;
import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Acceso a PRODUCTO. El stock no se guarda aquí: se deriva de los lotes de FEDER (R070). */
public class ProductoRepositorio extends RepositorioJdbc {

    /** Columnas del producto y su categoría con los alias que espera {@link #mapearCatalogo}. */
    public static final String COLUMNAS_CATALOGO =
            "p.id, p.categoria_id, p.codigo, p.nombre, p.descripcion, p.presentacion, p.unidad_medida, "
                    + "p.precio_venta, p.stock_minimo, p.imagen_url, p.permite_encargo, p.activo, "
                    + "c.nombre AS categoria, c.activa AS categoria_activa ";

    private static final String SELECT_CATALOGO =
            "SELECT " + COLUMNAS_CATALOGO + "FROM producto p JOIN categoria c ON c.id = p.categoria_id ";

    public Optional<ProductoCatalogo> buscarPorCodigo(String codigo) {
        return consultarUno(SELECT_CATALOGO + "WHERE p.codigo = ?", ProductoRepositorio::mapearCatalogo, codigo);
    }

    public Optional<ProductoCatalogo> buscarPorId(long id) {
        return consultarUno(SELECT_CATALOGO + "WHERE p.id = ?", ProductoRepositorio::mapearCatalogo, id);
    }

    /** Bloquea la fila del producto (no la de su categoría) para modificarlo sin carreras. */
    public Optional<ProductoCatalogo> bloquearPorCodigo(String codigo) {
        return consultarUno(SELECT_CATALOGO + "WHERE p.codigo = ? FOR UPDATE OF p", ProductoRepositorio::mapearCatalogo, codigo);
    }

    /**
     * Bloqueo FOR KEY SHARE de la fila del producto: el mismo que toma una fila nueva que lo referencia (por ejemplo,
     * una NOTIFICACION de alerta). No impide leerlo ni que otros lo referencien; solo espera a quien lo modifica con
     * {@link #bloquearPorCodigo}.
     */
    public void bloquearReferencia(long id) {
        consultar("SELECT id FROM producto WHERE id = ? FOR KEY SHARE", fila -> fila.getLong("id"), id);
    }

    /**
     * @param filtro texto buscado en el código, el nombre o la categoría; nulo para todos.
     * @param soloPublicados true para la tienda: producto y categoría activos.
     */
    public List<ProductoCatalogo> listar(String filtro, boolean soloPublicados) {
        return consultar(SELECT_CATALOGO
                        + "WHERE (? = FALSE OR (p.activo AND c.activa)) "
                        + "AND (?::text IS NULL OR p.codigo ILIKE '%' || ? || '%' OR p.nombre ILIKE '%' || ? || '%' "
                        + "     OR c.nombre ILIKE '%' || ? || '%') "
                        + "ORDER BY c.nombre, p.nombre",
                ProductoRepositorio::mapearCatalogo, soloPublicados, filtro, filtro, filtro, filtro);
    }

    public long insertar(Producto p) {
        return insertar("INSERT INTO producto (categoria_id, codigo, nombre, descripcion, presentacion, unidad_medida, "
                        + "precio_venta, stock_minimo, imagen_url, permite_encargo, activo) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.getCategoriaId(), p.getCodigo(), p.getNombre(), p.getDescripcion(), p.getPresentacion(),
                p.getUnidadMedida(), p.getPrecioVenta(), p.getStockMinimo(), p.getImagenUrl(), p.getPermiteEncargo(),
                p.getActivo());
    }

    /** Actualiza los datos descriptivos; el precio y el estado tienen operaciones propias. */
    public void actualizarDatos(Producto p) {
        actualizar("UPDATE producto SET categoria_id = ?, nombre = ?, descripcion = ?, presentacion = ?, unidad_medida = ?, "
                        + "stock_minimo = ?, imagen_url = ?, permite_encargo = ? WHERE id = ?",
                p.getCategoriaId(), p.getNombre(), p.getDescripcion(), p.getPresentacion(), p.getUnidadMedida(),
                p.getStockMinimo(), p.getImagenUrl(), p.getPermiteEncargo(), p.getId());
    }

    public void actualizarPrecio(long id, BigDecimal precio) {
        actualizar("UPDATE producto SET precio_venta = ? WHERE id = ?", precio, id);
    }

    public void cambiarActivo(long id, boolean activo) {
        actualizar("UPDATE producto SET activo = ? WHERE id = ?", activo, id);
    }

    /** Mapea las columnas de SELECT_CATALOGO; lo reutilizan las consultas que unen el producto con otra tabla. */
    public static ProductoCatalogo mapearCatalogo(ResultSet fila) throws SQLException {
        Producto p = new Producto();
        p.setId(fila.getLong("id"));
        p.setCategoriaId(fila.getLong("categoria_id"));
        p.setCodigo(fila.getString("codigo"));
        p.setNombre(fila.getString("nombre"));
        p.setDescripcion(fila.getString("descripcion"));
        p.setPresentacion(fila.getString("presentacion"));
        p.setUnidadMedida(enumeracion(fila, "unidad_medida", UnidadMedida.class));
        p.setPrecioVenta(fila.getBigDecimal("precio_venta"));
        p.setStockMinimo(fila.getBigDecimal("stock_minimo"));
        p.setImagenUrl(fila.getString("imagen_url"));
        p.setPermiteEncargo(fila.getBoolean("permite_encargo"));
        p.setActivo(fila.getBoolean("activo"));
        return new ProductoCatalogo(p, fila.getString("categoria"), fila.getBoolean("categoria_activa"));
    }
}
