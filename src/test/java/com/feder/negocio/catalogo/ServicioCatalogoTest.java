package com.feder.negocio.catalogo;

import com.feder.datos.catalogo.CategoriaRepositorio;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.StockRepositorio;
import com.feder.modelo.Categoria;
import com.feder.modelo.Producto;
import com.feder.modelo.UnidadMedida;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reglas de CU4 · Catálogo (R003, R068, R069, R070) contra la base de pruebas, siempre revertidas. */
class ServicioCatalogoTest {

    private ContextoNegocio ctx;
    private ServicioCategorias categorias;
    private ServicioProductos productos;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        ProductoRepositorio repo = new ProductoRepositorio();
        categorias = new ServicioCategorias(ctx, new CategoriaRepositorio());
        productos = new ServicioProductos(ctx, repo, new CategoriaRepositorio(), new ServicioStock(ctx, new StockRepositorio(), repo));
    }

    private static Solicitante quien(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    private static DatosProducto nuevo(String codigo, String categoria, String stockMinimo) {
        return new DatosProducto(codigo, "Miel de abeja 250 g", categoria, "Frasco de 250 g", UnidadMedida.UNIDAD,
                new BigDecimal("25.00"), new BigDecimal(stockMinimo), true, "Miel multifloral", null);
    }

    @Test
    @DisplayName("El personal crea una categoría con bitácora; un nombre repetido (sin distinguir mayúsculas) se rechaza")
    void creaCategoriaYRechazaDuplicado() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long antes = numero("SELECT count(*) FROM bitacora WHERE entidad = 'CATEGORIA' AND accion = 'CREAR'");
            Categoria c = categorias.crear(quien(CARLA), "  Bebidas de frutas  ", "Jugos naturales");
            assertEquals("Bebidas de frutas", c.getNombre());
            assertEquals(antes + 1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'CATEGORIA' AND accion = 'CREAR'"));
            assertNegocio(Codigo.DUPLICADO, "Ya existe", () -> categorias.crear(quien(JORGE), "BEBIDAS DE FRUTAS", null));
        });
    }

    @Test
    @DisplayName("El PRODUCTOR no administra el catálogo (R003, R068)")
    void productorNoAdministraCatalogo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER", () -> categorias.crear(quien(MARIA), "Otra", null));
            assertNegocio(Codigo.SIN_PERMISO, "PERSONAL_FEDER",
                    () -> productos.cambiarPrecio(quien(MARIA), "FED-ACE-020", new BigDecimal("30"), "subir"));
        });
    }

    @Test
    @DisplayName("No se desactiva una categoría con productos activos; sin ellos sí, y deja de verse en el catálogo público")
    void desactivacionDeCategorias() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "producto(s) activo(s)",
                    () -> categorias.desactivar(quien(CARLA), "Harinas", "Prueba"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya está inactiva",
                    () -> categorias.desactivar(quien(CARLA), "Bebidas artesanales", "Prueba"));
            categorias.crear(quien(CARLA), "Temporal", null);
            categorias.desactivar(quien(CARLA), "Temporal", "Ya no se usa");
            Solicitante anonimo = Solicitante.anonimo("visitante@correo.example");
            assertFalse(categorias.listar(anonimo, null).stream().anyMatch(c -> c.getNombre().equals("Temporal")));
            assertTrue(categorias.listar(quien(CARLA), null).stream().anyMatch(c -> c.getNombre().equals("Temporal")));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "no hay nada que modificar",
                    () -> categorias.modificar(quien(CARLA), "Harinas", null, null));
        });
    }

    @Test
    @DisplayName("Alta de producto: código único, categoría activa y stock mínimo entero en productos por UNIDAD")
    void altaDeProducto() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            Producto p = productos.crear(quien(CARLA), nuevo("FED-MIE-250", "Miel y derivados", "10"));
            assertTrue(p.getActivo());
            assertEquals("CREAR", texto("SELECT accion FROM bitacora WHERE entidad = 'PRODUCTO' AND entidad_id = ?", p.getId()));
            assertNegocio(Codigo.DUPLICADO, "FED-MIE-250", () -> productos.crear(quien(CARLA), nuevo("FED-MIE-250", "Miel y derivados", "10")));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "inactiva", () -> productos.crear(quien(CARLA), nuevo("FED-BEB-001", "Bebidas artesanales", "1")));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "entero", () -> productos.crear(quien(CARLA), nuevo("FED-MIE-251", "Miel y derivados", "2.5")));
        });
    }

    @Test
    @DisplayName("PREPRO registra CAMBIO_PRECIO con precio anterior y nuevo, y no altera las ventas pasadas (R069)")
    void cambioDePrecio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            productos.cambiarPrecio(quien(JORGE), "FED-ACE-020", new BigDecimal("27.50"), "Ajuste de fin de año");
            assertIgual("27.50", decimal("SELECT precio_venta FROM producto WHERE codigo = 'FED-ACE-020'"));
            String detalle = texto("SELECT detalle::text FROM bitacora WHERE accion = 'CAMBIO_PRECIO' AND entidad_id = 1 ORDER BY id DESC LIMIT 1");
            assertTrue(detalle.contains("\"precio_anterior\": 25.00") && detalle.contains("\"precio_nuevo\": 27.50"), detalle);
            assertIgual("25.00", decimal("SELECT precio_unitario FROM detalle_pedido WHERE id = 11"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "igual al precio vigente",
                    () -> productos.cambiarPrecio(quien(JORGE), "FED-ACE-020", new BigDecimal("27.5"), "otra vez"));
            assertNegocio(Codigo.REGLA_DE_NEGOCIO, "mayor que 0",
                    () -> productos.cambiarPrecio(quien(JORGE), "FED-ACE-020", BigDecimal.ZERO, "gratis"));
        });
    }

    @Test
    @DisplayName("Un producto desactivado no se publica; al activarlo sin stock se avisa al personal (R070)")
    void activacionYAlertaDeStock() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            Solicitante anonimo = Solicitante.anonimo("visitante@correo.example");
            productos.desactivar(quien(CARLA), "FED-MER-GUA", "Fuera de temporada");
            assertFalse(productos.listar(anonimo, null).stream()
                    .anyMatch(p -> p.catalogo().producto().getCodigo().equals("FED-MER-GUA")));
            assertNegocio(Codigo.NO_ENCONTRADO, "FED-MER-GUA", () -> productos.ver(anonimo, "FED-MER-GUA"));

            long alertas = numero("SELECT count(*) FROM notificacion WHERE tipo = 'ALERTA_STOCK' AND producto_id = 11");
            productos.activar(quien(CARLA), "FED-MER-MAN", "Inicio de la cosecha");
            long personal = numero("SELECT count(DISTINCT u.id) FROM usuario u JOIN usuario_rol ur ON ur.usuario_id = u.id "
                    + "JOIN rol r ON r.id = ur.rol_id WHERE u.activo AND ur.estado = 'VIGENTE' AND r.codigo IN ('ADMINISTRADOR','PERSONAL_FEDER')");
            assertEquals(alertas + personal, numero("SELECT count(*) FROM notificacion WHERE tipo = 'ALERTA_STOCK' AND producto_id = 11"));
            assertNegocio(Codigo.ESTADO_INVALIDO, "ya está activo", () -> productos.activar(quien(CARLA), "FED-MER-MAN", "otra"));
        });
    }

    @Test
    @DisplayName("Subir stock_minimo por encima del stock vendible genera una alerta una sola vez mientras siga en alerta")
    void alertaPorStockMinimoNoSeRepite() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            String contar = "SELECT count(*) FROM notificacion WHERE tipo = 'ALERTA_STOCK' AND producto_id = 10";
            long inicial = numero(contar);
            productos.modificar(quien(CARLA), "FED-MER-GUA", cambioDeMinimo("100"));
            long trasEntrar = numero(contar);
            assertTrue(trasEntrar > inicial);
            String detalle = texto("SELECT mensaje FROM notificacion WHERE tipo = 'ALERTA_STOCK' AND producto_id = 10 ORDER BY id DESC LIMIT 1");
            assertTrue(detalle.contains("bajo el mínimo (100)"), detalle);
            productos.modificar(quien(CARLA), "FED-MER-GUA", cambioDeMinimo("120"));
            assertEquals(trasEntrar, numero(contar));
            assertIgual("120", decimal("SELECT stock_minimo FROM producto WHERE id = 10"));
        });
    }

    @Test
    @DisplayName("El catálogo público muestra el stock vendible derivado de los lotes (disponible - reservado)")
    void listadoPublicoConStockVendible() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            List<ServicioProductos.ProductoConStock> lista = productos.listar(Solicitante.anonimo("x@correo.example"), "aceite");
            ServicioProductos.ProductoConStock aceite = lista.stream()
                    .filter(p -> p.catalogo().producto().getCodigo().equals("FED-ACE-020")).findFirst().orElseThrow();
            BigDecimal esperado = decimal("SELECT cantidad_disponible - cantidad_reservada FROM lote_feder WHERE id = 1");
            assertEquals(0, esperado.compareTo(aceite.vendible()));
            assertTrue(lista.stream().noneMatch(p -> p.catalogo().producto().getCodigo().equals("FED-MER-MAN")));
        });
    }

    private static DatosProducto cambioDeMinimo(String minimo) {
        return new DatosProducto(null, null, null, null, null, null, new BigDecimal(minimo), null, null, null);
    }
}
