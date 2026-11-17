package com.feder.negocio.catalogo;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Comandos del catálogo de extremo a extremo por el despachador real (identidad y confirmación por correo). */
class ModuloCatalogoComandosTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher despachador;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        despachador = despachador(ctx, new ModuloCatalogo(ctx));
    }

    @Test
    @DisplayName("LISCAT y LISPRO son públicos: un remitente sin cuenta ve solo lo publicado")
    void consultasPublicas() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = despachador.despachar("LISCAT", List.of("*"), "visitante@correo.example").resultado;
            assertTrue(r.esExito(), r.mensaje());
            assertEquals(6, r.tablas().get(0).filas().size());
            ResultadoOperacion p = despachador.despachar("LISPRO", List.of("miel"), "visitante@correo.example").resultado;
            assertTrue(p.esExito());
            assertEquals(List.of("Código", "Producto", "Categoría", "Presentación", "Unidad", "Precio (Bs)", "Stock vendible",
                    "Acepta encargo", "Publicado"), p.tablas().get(0).columnas());
            assertEquals(2, p.tablas().get(0).filas().size());
        });
    }

    @Test
    @DisplayName("INSCAT pide confirmación por correo y CONFIRMAR con el código la ejecuta")
    void altaDeCategoriaConConfirmacion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long antes = numero("SELECT count(*) FROM categoria");
            ResultadoOperacion r = confirmarComando(despachador, "INSCAT", List.of("Mieles", "Productos apícolas"), CARLA);
            assertTrue(r.esExito(), r.mensaje());
            assertEquals("Categoría registrada", r.titulo());
            assertEquals(antes + 1, numero("SELECT count(*) FROM categoria"));
            assertEquals("Productos apícolas", texto("SELECT descripcion FROM categoria WHERE nombre = 'Mieles'"));
        });
    }

    @Test
    @DisplayName("Un PRODUCTOR o CLIENTE no puede usar INSCAT ni PREPRO (acceso denegado sin pedir confirmación)")
    void productorSinAcceso() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = despachador.despachar("INSCAT", List.of("Mieles", ""), MARIA).resultado;
            assertFalse(r.esExito());
            assertEquals("Acceso denegado", r.titulo());
            ResultadoOperacion p = despachador.despachar("PREPRO", List.of("FED-ACE-020", "1.00", "x"), ELMER).resultado;
            assertEquals("Acceso denegado", p.titulo());
        });
    }

    @Test
    @DisplayName("PREPRO admite el mayor precio de NUMERIC(12,2) y rechaza un centavo más como regla de negocio, sin error interno")
    void precioEnElLimiteDeLaColumna() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion excedido = confirmarComando(despachador, "PREPRO",
                    List.of("FED-ACE-020", "10000000000.00", "Precio fuera de rango"), JORGE);
            assertEquals("Regla de negocio", excedido.titulo());
            assertTrue(excedido.mensaje().contains("9999999999.99"), excedido.mensaje());
            assertEquals("25.00", texto("SELECT precio_venta FROM producto WHERE codigo = 'FED-ACE-020'"));
            ResultadoOperacion maximo = confirmarComando(despachador, "PREPRO",
                    List.of("FED-ACE-020", "9999999999.99", "Precio en el límite"), JORGE);
            assertTrue(maximo.esExito(), maximo.mensaje());
            assertEquals("9999999999.99", texto("SELECT precio_venta FROM producto WHERE codigo = 'FED-ACE-020'"));
        });
    }

    @Test
    @DisplayName("DESCAT de una categoría con productos activos responde con la regla y no cambia nada")
    void desactivarCategoriaConProductos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = confirmarComando(despachador, "DESCAT", List.of("Café y cacao", "Prueba"), JORGE);
            assertFalse(r.esExito());
            assertTrue(r.mensaje().contains("producto(s) activo(s)"), r.mensaje());
            assertEquals(Boolean.TRUE, valor("SELECT activa FROM categoria WHERE nombre = 'Café y cacao'"));
        });
    }
}
