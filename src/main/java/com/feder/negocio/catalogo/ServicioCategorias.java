package com.feder.negocio.catalogo;

import com.feder.datos.catalogo.CategoriaRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Categoria;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.util.List;
import java.util.Objects;

/**
 * Gestión de categorías del catálogo unificado (CATEGORIA «Gestión de categorías», R003):
 * la crean, modifican y desactivan PERSONAL_FEDER o un ADMINISTRADOR vigente, con registro en BITACORA.
 */
public class ServicioCategorias {

    private static final String ENTIDAD = "CATEGORIA";

    private final ContextoNegocio ctx;
    private final CategoriaRepositorio categorias;

    public ServicioCategorias(ContextoNegocio ctx, CategoriaRepositorio categorias) {
        this.ctx = ctx;
        this.categorias = categorias;
    }

    /** El público ve solo las categorías activas; el personal ve también las inactivas. */
    public List<Categoria> listar(Solicitante solicitante, String filtro) {
        boolean soloActivas = !solicitante.esPersonalInterno();
        return ctx.transacciones().enTransaccion(() -> categorias.listar(soloActivas, filtro));
    }

    /** Categoría con la cantidad de productos activos que agrupa. */
    public record CategoriaConProductos(Categoria categoria, int productosActivos) {
    }

    /**
     * Detalle de una categoría por su nombre (VERCAT, plan fase C, C3). Igual que el listado: el público solo ve las
     * activas (una inactiva se responde como inexistente); el personal ve todas.
     */
    public CategoriaConProductos ver(Solicitante solicitante, String nombre) {
        boolean personal = solicitante.esPersonalInterno();
        return ctx.transacciones().enTransaccion(() -> {
            Categoria c = categorias.buscarPorNombre(nombre.trim()).filter(x -> personal || Boolean.TRUE.equals(x.getActiva()))
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe la categoría «" + nombre.trim()
                            + "». Consulte LISCAT[\"*\"]."));
            return new CategoriaConProductos(c, categorias.contarProductosActivos(c.getId()));
        });
    }

    public Categoria crear(Solicitante solicitante, String nombre, String descripcion) {
        Permisos.exigirPersonal(solicitante, "crear categorías");
        String nombreLimpio = Textos.obligatorio(nombre, "nombre de la categoría");
        return ctx.transacciones().enTransaccion(() -> {
            exigirNombreLibre(nombreLimpio, null);
            Categoria categoria = new Categoria();
            categoria.setNombre(nombreLimpio);
            categoria.setDescripcion(Textos.opcional(descripcion));
            categoria.setActiva(true);
            categoria.setId(categorias.insertar(categoria));
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, ENTIDAD, categoria.getId(),
                    DetalleJson.nuevo().con("nombre", nombreLimpio).con("descripcion", categoria.getDescripcion()));
            return categoria;
        });
    }

    /** Parámetros nulos conservan el valor actual. */
    public Categoria modificar(Solicitante solicitante, String nombreActual, String nuevoNombre, String nuevaDescripcion) {
        Permisos.exigirPersonal(solicitante, "modificar categorías");
        return ctx.transacciones().enTransaccion(() -> {
            Categoria categoria = bloquear(nombreActual);
            String nombre = nuevoNombre == null ? categoria.getNombre() : Textos.obligatorio(nuevoNombre, "nuevo nombre");
            String descripcion = nuevaDescripcion == null ? categoria.getDescripcion() : Textos.opcional(nuevaDescripcion);
            if (nombre.equals(categoria.getNombre()) && Objects.equals(descripcion, categoria.getDescripcion())) {
                throw NegocioException.regla("Los datos indicados son iguales a los actuales: no hay nada que modificar.");
            }
            if (!nombre.equalsIgnoreCase(categoria.getNombre())) {
                exigirNombreLibre(nombre, categoria.getId());
            }
            categorias.actualizar(categoria.getId(), nombre, descripcion);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, categoria.getId(),
                    DetalleJson.nuevo().con("nombre_anterior", categoria.getNombre()).con("nombre_nuevo", nombre)
                            .con("descripcion_anterior", categoria.getDescripcion()).con("descripcion_nueva", descripcion));
            categoria.setNombre(nombre);
            categoria.setDescripcion(descripcion);
            return categoria;
        });
    }

    /** R003: no se desactiva una categoría con productos activos. */
    public Categoria desactivar(Solicitante solicitante, String nombre, String motivo) {
        return cambiarActiva(solicitante, nombre, false, motivo);
    }

    public Categoria activar(Solicitante solicitante, String nombre, String motivo) {
        return cambiarActiva(solicitante, nombre, true, motivo);
    }

    private Categoria cambiarActiva(Solicitante solicitante, String nombre, boolean activa, String motivo) {
        Permisos.exigirPersonal(solicitante, activa ? "activar categorías" : "desactivar categorías");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        return ctx.transacciones().enTransaccion(() -> {
            // El bloqueo exclusivo espera a las altas o activaciones de productos de esta categoría,
            // que la leen con bloqueo compartido; así el conteo de productos activos es fiable.
            Categoria categoria = bloquear(nombre);
            if (categoria.getActiva() == activa) {
                throw NegocioException.estadoInvalido("La categoría " + categoria.getNombre() + " ya está "
                        + (activa ? "activa." : "inactiva."));
            }
            int productosActivos = activa ? 0 : categorias.contarProductosActivos(categoria.getId());
            if (productosActivos > 0) {
                throw NegocioException.regla("No se puede desactivar la categoría " + categoria.getNombre()
                        + " porque tiene " + productosActivos + " producto(s) activo(s). Desactívelos o cámbielos de categoría antes.");
            }
            categorias.cambiarActiva(categoria.getId(), activa);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, categoria.getId(),
                    DetalleJson.nuevo().con("activa_anterior", !activa).con("activa_nueva", activa).con("motivo", motivoLimpio));
            categoria.setActiva(activa);
            return categoria;
        });
    }

    private Categoria bloquear(String nombre) {
        return categorias.bloquearPorNombre(Textos.obligatorio(nombre, "nombre de la categoría"))
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la categoría «" + nombre.trim()
                        + "». Consulte LISCAT[\"*\"]."));
    }

    private void exigirNombreLibre(String nombre, Long excepto) {
        categorias.buscarPorNombre(nombre).filter(c -> !c.getId().equals(excepto)).ifPresent(c -> {
            throw NegocioException.duplicado("Ya existe la categoría «" + c.getNombre() + "».");
        });
    }
}
