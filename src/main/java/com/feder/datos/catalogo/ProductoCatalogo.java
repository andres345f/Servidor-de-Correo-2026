package com.feder.datos.catalogo;

import com.feder.modelo.Producto;

/** Producto con el nombre y el estado de su categoría, tal como se muestra en el catálogo. */
public record ProductoCatalogo(Producto producto, String categoria, boolean categoriaActiva) {

    /** Se publica en la tienda solo si el producto y su categoría están activos. */
    public boolean publicado() {
        return Boolean.TRUE.equals(producto.getActivo()) && categoriaActiva;
    }
}
