package com.feder.datos.produccion;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.UnidadMedida;

import java.util.Optional;

/** Lectura de los datos de PRODUCTO que necesita CU3 (activo y unidad comercial). No modifica el catálogo. */
public class ProductoReferenciaRepositorio extends RepositorioJdbc {

    public record ProductoReferencia(long id, String codigo, String nombre, UnidadMedida unidad, boolean activo) {
    }

    public Optional<ProductoReferencia> buscarPorCodigo(String codigo) {
        return consultarUno("SELECT id, codigo, nombre, unidad_medida, activo FROM producto WHERE codigo = ?",
                fila -> new ProductoReferencia(fila.getLong("id"), fila.getString("codigo"), fila.getString("nombre"),
                        enumeracion(fila, "unidad_medida", UnidadMedida.class), fila.getBoolean("activo")),
                codigo);
    }

    public Optional<ProductoReferencia> buscarPorId(long id) {
        return consultarUno("SELECT id, codigo, nombre, unidad_medida, activo FROM producto WHERE id = ?",
                fila -> new ProductoReferencia(fila.getLong("id"), fila.getString("codigo"), fila.getString("nombre"),
                        enumeracion(fila, "unidad_medida", UnidadMedida.class), fila.getBoolean("activo")),
                id);
    }
}
