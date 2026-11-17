package com.feder.negocio.ventas;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.ModuloCatalogo;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ValidadorParametros;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.inventario.ModuloInventario;
import com.feder.presentacion.comandos.CommandParser;
import com.feder.presentacion.comandos.ComandoAnalizado;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comandos de CU4 y CU5 de extremo a extremo por el despachador real: lista blanca, acceso por rol,
 * confirmación por correo de las escrituras y ejemplos de ayuda que se pueden copiar y pegar.
 */
class ModulosCu45ComandosTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher despachador;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        despachador = despachador(ctx, new ModuloCatalogo(ctx), new ModuloInventario(ctx), new ModuloVentas(ctx));
    }

    @Test
    @DisplayName("Los ejemplos de todos los comandos de CU4 y CU5 se analizan y validan sin errores")
    void ejemplosValidos() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        CatalogoComandos catalogo = catalogo(new ModuloCatalogo(contexto), new ModuloInventario(contexto), new ModuloVentas(contexto));
        CommandParser parser = new CommandParser();
        List<ComandoSpec> propios = catalogo.todos().stream().filter(c -> c.casoDeUso().matches("CU[45]")).toList();
        assertEquals(45, propios.size(), propios.stream().map(ComandoSpec::nombre).collect(Collectors.joining(",")));
        for (ComandoSpec c : propios) {
            assertTrue(c.nombre().matches("[A-Z]{6}"), c.nombre());
            ComandoAnalizado analizado = assertDoesNotThrow(() -> parser.analizar(c.ejemplo()), c.ejemplo());
            assertEquals(c.nombre(), analizado.nombre());
            assertDoesNotThrow(() -> ValidadorParametros.validar(c, analizado.parametros()), c.ejemplo());
        }
        Set<String> escrituras = propios.stream().filter(ComandoSpec::modificaDatos).map(ComandoSpec::nombre).collect(Collectors.toSet());
        assertTrue(escrituras.containsAll(Set.of("INSCAT", "PREPRO", "INSCMP", "RECCMP", "AJUSTK", "AGRCAR", "PEDINM",
                "CANPED", "ANUREC", "PRECMP")));
        assertFalse(escrituras.contains("LISPRO") || escrituras.contains("LISSTK") || escrituras.contains("VERCAR")
                || escrituras.contains("VERCAT"));
    }

    @Test
    @DisplayName("Flujo del cliente: AGRCAR y PEDINM piden confirmación; CONFIRMAR crea el pedido con su reserva")
    void flujoDeCompraPorCorreo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion agregado = confirmarComando(despachador, "AGRCAR", List.of("FED-MIE-500", "2"), ANDRES);
            assertTrue(agregado.esExito(), agregado.mensaje());
            ResultadoOperacion carrito = despachador.despachar("VERCAR", List.of("*"), ANDRES).resultado;
            assertEquals("174.00", detalle(carrito, "Subtotal de productos disponibles (Bs)"));
            ResultadoOperacion pedido = confirmarComando(despachador, "PEDINM", List.of("RECOJO", "", "", "Andrés Molina Soliz",
                    "70000114", "*", "174.00", ""), ANDRES);
            assertTrue(pedido.esExito(), pedido.mensaje());
            String codigo = detalle(pedido, "Código");
            assertEquals("PENDIENTE", detalle(pedido, "Estado"));
            assertEquals("174.00", detalle(pedido, "Total (Bs)"));
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                    + "JOIN pedido p ON p.id = dp.pedido_id WHERE p.codigo = ? AND rs.estado = 'ACTIVA'", codigo));
            ResultadoOperacion ver = despachador.despachar("VERPED", List.of(codigo), ANDRES).resultado;
            assertTrue(ver.esExito());
            assertEquals("No encontrado", despachador.despachar("VERPED", List.of(codigo), ELMER).resultado.titulo());
        });
    }

    @Test
    @DisplayName("Un remitente sin cuenta consulta el catálogo pero no usa el carrito; un cliente no ve el inventario")
    void accesoPorRol() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertTrue(despachador.despachar("VERPRO", List.of("FED-ACE-020"), "visitante@correo.example").resultado.esExito());
            assertEquals("Remitente no identificado",
                    despachador.despachar("VERCAR", List.of("*"), "visitante@correo.example").resultado.titulo());
            assertEquals("Acceso denegado", despachador.despachar("LISSTK", List.of("*"), ELMER).resultado.titulo());
            assertEquals("Acceso denegado", despachador.despachar("RECCMP", List.of("CMP-2026-005", "LP-2026-011", "1", "0",
                    "", "", "", "REC-X-1"), MARIA).resultado.titulo());
        });
    }

    @Test
    @DisplayName("Flujo del almacén: RECCMP confirmado ingresa el stock y LISSTK lo muestra como vendible")
    void recepcionPorCorreo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = confirmarComando(despachador, "RECCMP", List.of("CMP-2026-005", "LP-2026-011", "28", "2",
                    "Estante A-03", "Dos botellas con el sello roto", "NE-MQ-0002", "REC-CMP005-LP011-01"), CARLA);
            assertTrue(r.esExito(), r.mensaje());
            assertEquals("RECIBIDA", detalle(r, "Estado de la compra"));
            ResultadoOperacion stock = despachador.despachar("LISSTK", List.of("FED-ACE-100"), CARLA).resultado;
            // 12 de las 28 unidades quedaron reservadas para el encargo PED-2026-0008 (R056).
            assertEquals("16", stock.tablas().get(0).filas().get(0).get(5));
            assertTrue(detalle(r, "Pedidos abastecidos con lo aceptado").startsWith("PED-2026-0008 (12 reservadas, encargo CONFIRMADO)"),
                    detalle(r, "Pedidos abastecidos con lo aceptado"));
            ResultadoOperacion repetida = confirmarComando(despachador, "RECCMP", List.of("CMP-2026-005", "LP-2026-011", "28", "2",
                    "Estante A-03", "Dos botellas con el sello roto", "NE-MQ-0002", "REC-CMP005-LP011-01"), CARLA);
            assertEquals("Operación ya registrada", repetida.titulo());
            ResultadoOperacion alertas = despachador.despachar("ALESTK", List.of("*"), JORGE).resultado;
            assertTrue(alertas.tablas().get(0).filas().stream().anyMatch(f -> f.get(0).equals("FED-CHO-100")));
        });
    }

    @Test
    @DisplayName("Un error de la base (regla de un disparador) llega al usuario como mensaje claro y no modifica nada")
    void errorDeBaseTraducido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = confirmarComando(despachador, "AJUSTK", List.of("LF-2026-001", "AJUSTE_POSITIVO", "9",
                    "Recuento", "AP-X-1", "Acta INV-9"), ADMIN);
            assertFalse(r.esExito());
            assertTrue(r.mensaje().contains("AJUSTE_POSITIVO solo restituye"), r.mensaje());
            assertIgual("183", decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 1"));
        });
    }
}
