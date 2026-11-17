package com.feder.negocio.catalogo;

import com.feder.datos.catalogo.CategoriaRepositorio;
import com.feder.datos.catalogo.ProductoCatalogo;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.StockRepositorio;
import com.feder.modelo.Categoria;
import com.feder.modelo.Producto;
import com.feder.modelo.TipoRol;
import com.feder.modelo.UnidadMedida;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

import java.util.List;

/**
 * Comandos de CU4 · Catálogo unificado: categorías y productos.
 * Los handlers solo convierten parámetros, llaman al servicio y arman la respuesta.
 */
public final class ModuloCatalogo implements ModuloComandos {

    private static final String CU = "CU4";
    private static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};

    private final ServicioCategorias categorias;
    private final ServicioProductos productos;

    public ModuloCatalogo(ContextoNegocio ctx) {
        ProductoRepositorio repoProductos = new ProductoRepositorio();
        CategoriaRepositorio repoCategorias = new CategoriaRepositorio();
        ServicioStock stock = new ServicioStock(ctx, new StockRepositorio(), repoProductos);
        this.categorias = new ServicioCategorias(ctx, repoCategorias);
        this.productos = new ServicioProductos(ctx, repoProductos, repoCategorias, stock);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        registrarCategorias(catalogo);
        registrarConsultasProducto(catalogo);
        registrarGestionProducto(catalogo);
    }

    private void registrarCategorias(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("LISCAT").casoDeUso(CU)
                .descripcion("Lista las categorías del catálogo (el personal ve también las inactivas)")
                .parametros(ParametroSpec.texto("filtro", 60).conComodin()).publico()
                .ejemplo("LISCAT[\"*\"]").handler(this::listarCategorias).construir());
        catalogo.registrar(ComandoSpec.comando("VERCAT").casoDeUso(CU)
                .descripcion("Muestra una categoría del catálogo por su nombre, con la cantidad de productos activos "
                        + "(el personal ve también las inactivas)")
                .parametros(ParametroSpec.texto("nombre", 60)).publico()
                .ejemplo("VERCAT[\"Miel y derivados\"]").handler(this::verCategoria).construir());
        catalogo.registrar(ComandoSpec.comando("INSCAT").casoDeUso(CU)
                .descripcion("Registra una categoría del catálogo")
                .parametros(ParametroSpec.texto("nombre", 60), ParametroSpec.texto("descripcion", 500).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("INSCAT[\"Mieles\",\"Productos apícolas\"]")
                .handler((p, s) -> categoria("Categoría registrada", categorias.crear(s, p.texto("nombre"), p.texto("descripcion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("MODCAT").casoDeUso(CU)
                .descripcion("Modifica el nombre o la descripción de una categoría (vacío = sin cambio)")
                .parametros(ParametroSpec.texto("nombre_actual", 60), ParametroSpec.texto("nuevo_nombre", 60).opcional(),
                        ParametroSpec.texto("descripcion", 500).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("MODCAT[\"Harinas\",\"\",\"Harinas de frutos, granos y tubérculos de la región\"]")
                .handler((p, s) -> categoria("Categoría modificada",
                        categorias.modificar(s, p.texto("nombre_actual"), p.texto("nuevo_nombre"), p.texto("descripcion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("DESCAT").casoDeUso(CU)
                .descripcion("Desactiva una categoría sin productos activos")
                .parametros(ParametroSpec.texto("nombre", 60), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("DESCAT[\"Bebidas artesanales\",\"Línea de bebidas suspendida\"]")
                .handler((p, s) -> categoria("Categoría desactivada", categorias.desactivar(s, p.texto("nombre"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ACTCAT").casoDeUso(CU)
                .descripcion("Vuelve a activar una categoría")
                .parametros(ParametroSpec.texto("nombre", 60), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ACTCAT[\"Bebidas artesanales\",\"Se incorpora la línea de bebidas\"]")
                .handler((p, s) -> categoria("Categoría activada", categorias.activar(s, p.texto("nombre"), p.texto("motivo"))))
                .construir());
    }

    private void registrarConsultasProducto(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("LISPRO").casoDeUso(CU)
                .descripcion("Lista los productos del catálogo con su stock vendible (filtro por código, nombre o categoría)")
                .parametros(ParametroSpec.texto("filtro", 60).conComodin()).publico()
                .ejemplo("LISPRO[\"miel\"]").handler(this::listarProductos).construir());
        catalogo.registrar(ComandoSpec.comando("VERPRO").casoDeUso(CU)
                .descripcion("Muestra el detalle de un producto y su stock vendible")
                .parametros(ParametroSpec.codigo("codigo", 30)).publico()
                .ejemplo("VERPRO[\"FED-ACE-020\"]").handler(this::verProducto).construir());
    }

    private void registrarGestionProducto(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("INSPRO").casoDeUso(CU)
                .descripcion("Registra un producto en el catálogo")
                .parametros(ParametroSpec.codigo("codigo", 30), ParametroSpec.texto("nombre", 120),
                        ParametroSpec.texto("categoria", 60), ParametroSpec.texto("presentacion", 60),
                        ParametroSpec.enumeracion("unidad_medida", UnidadMedida.class), ParametroSpec.decimal("precio_venta"),
                        ParametroSpec.decimal("stock_minimo"), ParametroSpec.booleano("permite_encargo"),
                        ParametroSpec.texto("descripcion", 500).opcional(), ParametroSpec.texto("imagen_url", 255).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("INSPRO[\"FED-MIE-250\",\"Miel de abeja 250 g\",\"Miel y derivados\",\"Frasco de vidrio de 250 g\","
                        + "\"UNIDAD\",\"25.00\",\"10\",\"true\",\"Miel multifloral\",\"\"]")
                .handler((p, s) -> producto("Producto registrado", productos.crear(s, datos(p, true))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("MODPRO").casoDeUso(CU)
                .descripcion("Modifica los datos de un producto (vacío = sin cambio; el precio se cambia con PREPRO)")
                .parametros(ParametroSpec.codigo("codigo", 30), ParametroSpec.texto("nombre", 120).opcional(),
                        ParametroSpec.texto("categoria", 60).opcional(), ParametroSpec.texto("presentacion", 60).opcional(),
                        ParametroSpec.enumeracion("unidad_medida", UnidadMedida.class).opcional(),
                        ParametroSpec.decimal("stock_minimo").opcional(), ParametroSpec.booleano("permite_encargo").opcional(),
                        ParametroSpec.texto("descripcion", 500).opcional(), ParametroSpec.texto("imagen_url", 255).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("MODPRO[\"FED-JAB-090\",\"\",\"\",\"\",\"\",\"40\",\"\",\"\",\"\"]")
                .handler((p, s) -> producto("Producto modificado", productos.modificar(s, p.texto("codigo"), datos(p, false))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("PREPRO").casoDeUso(CU)
                .descripcion("Cambia el precio de venta de un producto (queda en la bitácora como CAMBIO_PRECIO)")
                .parametros(ParametroSpec.codigo("codigo", 30), ParametroSpec.decimal("precio_venta"),
                        ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("PREPRO[\"FED-ACE-020\",\"26.00\",\"Actualización de precios de fin de año\"]")
                .handler((p, s) -> producto("Precio actualizado",
                        productos.cambiarPrecio(s, p.texto("codigo"), p.decimal("precio_venta"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("DESPRO").casoDeUso(CU)
                .descripcion("Desactiva un producto: deja de publicarse y de venderse")
                .parametros(ParametroSpec.codigo("codigo", 30), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("DESPRO[\"FED-MER-GUA\",\"Fuera de temporada\"]")
                .handler((p, s) -> producto("Producto desactivado", productos.desactivar(s, p.texto("codigo"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ACTPRO").casoDeUso(CU)
                .descripcion("Vuelve a activar un producto del catálogo")
                .parametros(ParametroSpec.codigo("codigo", 30), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ACTPRO[\"FED-MER-MAN\",\"Inicio de la cosecha de mango\"]")
                .handler((p, s) -> producto("Producto activado", productos.activar(s, p.texto("codigo"), p.texto("motivo"))))
                .construir());
    }

    private ResultadoOperacion verCategoria(Parametros p, Solicitante s) {
        ServicioCategorias.CategoriaConProductos x = categorias.ver(s, p.texto("nombre"));
        Categoria c = x.categoria();
        return ResultadoOperacion.exito("Categoría " + c.getNombre(),
                        "Envíe LISPRO[\"" + c.getNombre() + "\"] para ver sus productos.")
                .detalle("N.º", c.getId())
                .detalle("Nombre", c.getNombre())
                .detalle("Descripción", c.getDescripcion())
                .detalle("Activa", c.getActiva() ? "Sí" : "No")
                .detalle("Productos activos", x.productosActivos());
    }

    private ResultadoOperacion listarCategorias(Parametros p, Solicitante s) {
        List<Categoria> obtenidas = categorias.listar(s, filtro(p));
        List<Categoria> lista = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Categorías", List.of("Nombre", "Descripción", "Activa"));
        lista.forEach(c -> tabla.fila(c.getNombre(), c.getDescripcion(), siNo(c.getActiva())));
        return ResultadoOperacion.exito("Categorías del catálogo", lista.size() + " categoría(s)." + Listados.aviso(obtenidas))
                .tabla(tabla);
    }

    private ResultadoOperacion listarProductos(Parametros p, Solicitante s) {
        List<ServicioProductos.ProductoConStock> obtenidos = productos.listar(s, filtro(p));
        List<ServicioProductos.ProductoConStock> lista = Listados.recortar(obtenidos);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Productos", List.of("Código", "Producto",
                "Categoría", "Presentación", "Unidad", "Precio (Bs)", "Stock vendible", "Acepta encargo", "Publicado"));
        lista.forEach(x -> {
            Producto pr = x.catalogo().producto();
            tabla.fila(pr.getCodigo(), pr.getNombre(), x.catalogo().categoria(), pr.getPresentacion(), pr.getUnidadMedida(),
                    Importes.formatear(pr.getPrecioVenta()), Cantidades.formatear(x.vendible()),
                    siNo(pr.getPermiteEncargo()), siNo(x.catalogo().publicado()));
        });
        return ResultadoOperacion.exito("Catálogo de productos", lista.size() + " producto(s). Los precios incluyen impuestos."
                        + Listados.aviso(obtenidos))
                .tabla(tabla);
    }

    private ResultadoOperacion verProducto(Parametros p, Solicitante s) {
        ServicioProductos.ProductoConStock x = productos.ver(s, p.texto("codigo"));
        ProductoCatalogo c = x.catalogo();
        return detalleProducto(ResultadoOperacion.exito("Producto " + c.producto().getCodigo(), c.producto().getNombre()),
                c.producto(), c.categoria())
                .detalle("Stock vendible", Cantidades.formatear(x.vendible()) + " " + c.producto().getUnidadMedida())
                .detalle("Publicado", siNo(c.publicado()));
    }

    private static ResultadoOperacion categoria(String titulo, Categoria c) {
        return ResultadoOperacion.exito(titulo, "Operación registrada en la bitácora.")
                .detalle("Nombre", c.getNombre()).detalle("Descripción", c.getDescripcion())
                .detalle("Activa", siNo(c.getActiva()));
    }

    private static ResultadoOperacion producto(String titulo, Producto p) {
        return detalleProducto(ResultadoOperacion.exito(titulo, "Operación registrada en la bitácora."), p, null);
    }

    private static ResultadoOperacion detalleProducto(ResultadoOperacion r, Producto p, String categoria) {
        r.detalle("Código", p.getCodigo()).detalle("Nombre", p.getNombre());
        if (categoria != null) {
            r.detalle("Categoría", categoria);
        }
        return r.detalle("Descripción", p.getDescripcion()).detalle("Presentación", p.getPresentacion())
                .detalle("Unidad", p.getUnidadMedida()).detalle("Precio (Bs)", Importes.formatear(p.getPrecioVenta()))
                .detalle("Stock mínimo", Cantidades.formatear(p.getStockMinimo()))
                .detalle("Acepta encargo", siNo(p.getPermiteEncargo())).detalle("Imagen", p.getImagenUrl())
                .detalle("Activo", siNo(p.getActivo()));
    }

    private static DatosProducto datos(Parametros p, boolean alta) {
        return new DatosProducto(alta ? p.texto("codigo") : null, p.texto("nombre"), p.texto("categoria"),
                p.texto("presentacion"), p.enumeracion("unidad_medida", UnidadMedida.class),
                alta ? p.decimal("precio_venta") : null, p.decimal("stock_minimo"), p.booleano("permite_encargo"),
                p.texto("descripcion"), p.texto("imagen_url"));
    }

    static String filtro(Parametros p) {
        return p.tieneValor("filtro") ? p.texto("filtro") : null;
    }

    static String siNo(Boolean valor) {
        return Boolean.TRUE.equals(valor) ? "Sí" : "No";
    }
}
