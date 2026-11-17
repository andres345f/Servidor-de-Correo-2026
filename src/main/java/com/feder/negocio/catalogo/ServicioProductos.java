package com.feder.negocio.catalogo;

import com.feder.datos.catalogo.CategoriaRepositorio;
import com.feder.datos.catalogo.ProductoCatalogo;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Categoria;
import com.feder.modelo.Producto;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Gestión del catálogo de productos (PRODUCTO «Gestión del catálogo» R068 e «Historial de precios» R069).
 * El PRODUCTOR no modifica el catálogo; toda alta y modificación queda en BITACORA.
 */
public class ServicioProductos {

    private static final String ENTIDAD = "PRODUCTO";

    private final ContextoNegocio ctx;
    private final ProductoRepositorio productos;
    private final CategoriaRepositorio categorias;
    private final ServicioStock stock;

    public ServicioProductos(ContextoNegocio ctx, ProductoRepositorio productos, CategoriaRepositorio categorias,
                             ServicioStock stock) {
        this.ctx = ctx;
        this.productos = productos;
        this.categorias = categorias;
        this.stock = stock;
    }

    /** Producto con su stock vendible calculado en el momento de la consulta. */
    public record ProductoConStock(ProductoCatalogo catalogo, BigDecimal vendible) {
    }

    /** El público ve los productos publicados; el personal ve todo el catálogo. */
    public List<ProductoConStock> listar(Solicitante solicitante, String filtro) {
        boolean soloPublicados = !solicitante.esPersonalInterno();
        return ctx.transacciones().enTransaccion(() -> {
            Map<Long, BigDecimal> vendibles = stock.vendiblePorProducto();
            return productos.listar(filtro, soloPublicados).stream()
                    .map(p -> new ProductoConStock(p, vendibles.getOrDefault(p.producto().getId(), BigDecimal.ZERO)))
                    .toList();
        });
    }

    public ProductoConStock ver(Solicitante solicitante, String codigo) {
        return ctx.transacciones().enTransaccion(() -> {
            ProductoCatalogo p = productos.buscarPorCodigo(codigo)
                    .filter(encontrado -> encontrado.publicado() || solicitante.esPersonalInterno())
                    .orElseThrow(() -> noEncontrado(codigo));
            return new ProductoConStock(p, stock.vendible(p.producto().getId()));
        });
    }

    public Producto crear(Solicitante solicitante, DatosProducto datos) {
        Permisos.exigirPersonal(solicitante, "registrar productos en el catálogo");
        return ctx.transacciones().enTransaccion(() -> {
            if (productos.buscarPorCodigo(datos.codigo()).isPresent()) {
                throw NegocioException.duplicado("Ya existe un producto con el código " + datos.codigo() + ".");
            }
            Categoria categoria = categoriaActivaCompartida(datos.categoria());
            Producto p = new Producto();
            p.setCodigo(datos.codigo());
            p.setCategoriaId(categoria.getId());
            p.setNombre(Textos.obligatorio(datos.nombre(), "nombre"));
            p.setPresentacion(Textos.obligatorio(datos.presentacion(), "presentación"));
            p.setUnidadMedida(Objects.requireNonNull(datos.unidad()));
            p.setPrecioVenta(Importes.validarPrecio(datos.precioVenta(), false, "precio de venta"));
            p.setStockMinimo(Cantidades.validarNoNegativa(datos.stockMinimo(), datos.unidad(), "cantidad de stock mínimo"));
            p.setPermiteEncargo(Boolean.TRUE.equals(datos.permiteEncargo()));
            p.setDescripcion(Textos.opcional(datos.descripcion()));
            p.setImagenUrl(Textos.opcional(datos.imagenUrl()));
            p.setActivo(true);
            p.setId(productos.insertar(p));
            // Un producto recién creado no tiene stock: por decisión documentada no genera alerta al crearse.
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, ENTIDAD, p.getId(),
                    DetalleJson.nuevo().con("codigo", p.getCodigo()).con("precio_venta", p.getPrecioVenta())
                            .con("unidad_medida", p.getUnidadMedida()).con("categoria_id", p.getCategoriaId()));
            return p;
        });
    }

    /** Modifica los datos descriptivos; los campos nulos de {@code cambios} se conservan. */
    public Producto modificar(Solicitante solicitante, String codigo, DatosProducto cambios) {
        Permisos.exigirPersonal(solicitante, "modificar productos del catálogo");
        return ctx.transacciones().enTransaccion(() -> {
            Producto actual = bloquear(codigo).producto();
            Producto nuevo = aplicarCambios(actual, cambios);
            DetalleJson detalle = DetalleJson.nuevo();
            boolean hayCambios = registrarCambios(detalle, actual, nuevo);
            if (!hayCambios) {
                throw NegocioException.regla("Los datos indicados son iguales a los actuales: no hay nada que modificar.");
            }
            // Un cambio de stock_minimo puede poner el producto en alerta (R070).
            stock.vigilando(List.of(actual.getId()), () -> {
                productos.actualizarDatos(nuevo);
                return null;
            });
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, actual.getId(), detalle);
            return nuevo;
        });
    }

    /** R069: el cambio de precio no altera ventas pasadas y se registra como CAMBIO_PRECIO. */
    public Producto cambiarPrecio(Solicitante solicitante, String codigo, BigDecimal precioNuevo, String motivo) {
        Permisos.exigirPersonal(solicitante, "cambiar precios");
        BigDecimal precio = Importes.validarPrecio(precioNuevo, false, "precio de venta");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        return ctx.transacciones().enTransaccion(() -> {
            Producto p = bloquear(codigo).producto();
            BigDecimal anterior = p.getPrecioVenta();
            if (anterior.compareTo(precio) == 0) {
                throw NegocioException.regla("El precio indicado (" + Importes.formatear(precio)
                        + ") es igual al precio vigente: no se registró ningún cambio.");
            }
            productos.actualizarPrecio(p.getId(), precio);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CAMBIO_PRECIO, ENTIDAD, p.getId(),
                    DetalleJson.nuevo().con("precio_anterior", anterior).con("precio_nuevo", precio).con("motivo", motivoLimpio));
            p.setPrecioVenta(precio);
            return p;
        });
    }

    public Producto desactivar(Solicitante solicitante, String codigo, String motivo) {
        return cambiarActivo(solicitante, codigo, false, motivo);
    }

    public Producto activar(Solicitante solicitante, String codigo, String motivo) {
        return cambiarActivo(solicitante, codigo, true, motivo);
    }

    private Producto cambiarActivo(Solicitante solicitante, String codigo, boolean activo, String motivo) {
        Permisos.exigirPersonal(solicitante, activo ? "activar productos" : "desactivar productos");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        return ctx.transacciones().enTransaccion(() -> {
            Producto p = bloquear(codigo).producto();
            if (p.getActivo() == activo) {
                throw NegocioException.estadoInvalido("El producto " + p.getCodigo() + " ya está " + (activo ? "activo." : "inactivo."));
            }
            if (activo) {
                categoriaActivaCompartida(p.getCategoriaId());
            }
            // Al activar se evalúa la alerta de stock (R070): un producto inactivo nunca está en alerta.
            stock.vigilando(List.of(p.getId()), () -> {
                productos.cambiarActivo(p.getId(), activo);
                return null;
            });
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, p.getId(),
                    DetalleJson.nuevo().con("activo_anterior", !activo).con("activo_nuevo", activo).con("motivo", motivoLimpio));
            p.setActivo(activo);
            return p;
        });
    }

    private Producto aplicarCambios(Producto actual, DatosProducto c) {
        Producto nuevo = new Producto();
        nuevo.setId(actual.getId());
        nuevo.setCodigo(actual.getCodigo());
        nuevo.setPrecioVenta(actual.getPrecioVenta());
        nuevo.setActivo(actual.getActivo());
        nuevo.setNombre(c.nombre() == null ? actual.getNombre() : Textos.obligatorio(c.nombre(), "nombre"));
        nuevo.setDescripcion(c.descripcion() == null ? actual.getDescripcion() : Textos.opcional(c.descripcion()));
        nuevo.setPresentacion(c.presentacion() == null ? actual.getPresentacion() : Textos.obligatorio(c.presentacion(), "presentación"));
        nuevo.setUnidadMedida(c.unidad() == null ? actual.getUnidadMedida() : c.unidad());
        nuevo.setStockMinimo(c.stockMinimo() == null ? actual.getStockMinimo() : c.stockMinimo());
        Cantidades.validarNoNegativa(nuevo.getStockMinimo(), nuevo.getUnidadMedida(), "cantidad de stock mínimo");
        nuevo.setImagenUrl(c.imagenUrl() == null ? actual.getImagenUrl() : Textos.opcional(c.imagenUrl()));
        nuevo.setPermiteEncargo(c.permiteEncargo() == null ? actual.getPermiteEncargo() : c.permiteEncargo());
        if (c.categoria() == null) {
            nuevo.setCategoriaId(actual.getCategoriaId());
        } else {
            Categoria categoria = Boolean.TRUE.equals(actual.getActivo())
                    ? categoriaActivaCompartida(c.categoria()) : categoriaCompartida(c.categoria());
            nuevo.setCategoriaId(categoria.getId());
        }
        return nuevo;
    }

    /** Agrega al detalle de bitácora cada campo que cambia, con su valor anterior y nuevo. */
    private static boolean registrarCambios(DetalleJson detalle, Producto a, Producto n) {
        boolean cambio = false;
        cambio |= campo(detalle, "nombre", a.getNombre(), n.getNombre());
        cambio |= campo(detalle, "descripcion", a.getDescripcion(), n.getDescripcion());
        cambio |= campo(detalle, "presentacion", a.getPresentacion(), n.getPresentacion());
        cambio |= campo(detalle, "unidad_medida", a.getUnidadMedida(), n.getUnidadMedida());
        cambio |= campo(detalle, "categoria_id", a.getCategoriaId(), n.getCategoriaId());
        cambio |= campo(detalle, "imagen_url", a.getImagenUrl(), n.getImagenUrl());
        cambio |= campo(detalle, "permite_encargo", a.getPermiteEncargo(), n.getPermiteEncargo());
        if (a.getStockMinimo().compareTo(n.getStockMinimo()) != 0) {
            detalle.con("stock_minimo_anterior", a.getStockMinimo()).con("stock_minimo_nuevo", n.getStockMinimo());
            cambio = true;
        }
        return cambio;
    }

    private static boolean campo(DetalleJson detalle, String nombre, Object anterior, Object nuevo) {
        if (Objects.equals(anterior, nuevo)) {
            return false;
        }
        detalle.con(nombre + "_anterior", anterior).con(nombre + "_nuevo", nuevo);
        return true;
    }

    private ProductoCatalogo bloquear(String codigo) {
        return productos.bloquearPorCodigo(codigo).orElseThrow(() -> noEncontrado(codigo));
    }

    /** La categoría se lee con bloqueo compartido para que no se desactive mientras recibe un producto activo. */
    private Categoria categoriaActivaCompartida(String nombre) {
        Categoria categoria = categoriaCompartida(nombre);
        exigirActiva(categoria);
        return categoria;
    }

    private Categoria categoriaActivaCompartida(long id) {
        Categoria categoria = categorias.bloquearCompartida(id)
                .orElseThrow(() -> NegocioException.noEncontrado("La categoría del producto no existe."));
        exigirActiva(categoria);
        return categoria;
    }

    private Categoria categoriaCompartida(String nombre) {
        return categorias.bloquearCompartidaPorNombre(Textos.obligatorio(nombre, "categoría"))
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la categoría «" + nombre.trim()
                        + "». Consulte LISCAT[\"*\"]."));
    }

    private static void exigirActiva(Categoria categoria) {
        if (!Boolean.TRUE.equals(categoria.getActiva())) {
            throw NegocioException.regla("La categoría «" + categoria.getNombre()
                    + "» está inactiva: actívela antes de asignarle productos activos.");
        }
    }

    private static NegocioException noEncontrado(String codigo) {
        return NegocioException.noEncontrado("No se encontró el producto con el código " + codigo
                + ". Consulte LISPRO[\"*\"].");
    }
}
