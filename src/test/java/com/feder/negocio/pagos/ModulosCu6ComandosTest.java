package com.feder.negocio.pagos;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ValidadorParametros;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.presentacion.comandos.CommandParser;
import com.feder.presentacion.comandos.ComandoAnalizado;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static com.feder.negocio.pagos.ApoyoCu6.leer;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Catálogo de comandos de CU6: ejemplos copiables, confirmación de las escrituras y acceso por rol. */
class ModulosCu6ComandosTest {

    private static final Set<String> LECTURAS = Set.of("LISPAG", "VERFAC", "LISREM", "LISENT", "VERENT", "LISDEV", "VERDEV", "LISNOT");

    @Test
    @DisplayName("Los ejemplos de todos los comandos de CU6 se analizan y validan, y solo las consultas no piden confirmación")
    void ejemplosValidos() {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        CatalogoComandos catalogo = ApoyoCu45.catalogo(ApoyoCu6.modulos(ctx));
        List<ComandoSpec> propios = catalogo.todos().stream().filter(c -> c.casoDeUso().equals("CU6")).toList();
        assertEquals(31, propios.size(), propios.stream().map(ComandoSpec::nombre).collect(Collectors.joining(",")));
        CommandParser parser = new CommandParser();
        for (ComandoSpec c : propios) {
            assertTrue(c.nombre().matches("[A-Z]{6}"), c.nombre());
            ComandoAnalizado analizado = assertDoesNotThrow(() -> parser.analizar(c.ejemplo()), c.ejemplo());
            assertEquals(c.nombre(), analizado.nombre());
            assertDoesNotThrow(() -> ValidadorParametros.validar(c, analizado.parametros()), c.ejemplo());
            assertEquals(!LECTURAS.contains(c.nombre()), c.modificaDatos(), "modificaDatos de " + c.nombre());
        }
    }

    @Test
    @DisplayName("El cliente no verifica pagos, no gestiona entregas ni devoluciones ajenas; un remitente sin cuenta no ve nada")
    void accesoPorRol() {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        ComandoDispatcher d = ApoyoCu6.despachador(ctx);
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals("Acceso denegado", leer(d, ANDRES, "CONPAG", "pago-ped0011-01", "QR-1", "143.00").titulo());
            assertEquals("Acceso denegado", leer(d, ANDRES, "ESTENT", "PED-2026-0011", "FALLIDA", "x").titulo());
            assertEquals("Acceso denegado", leer(d, DANIELA, "APRDEV", "DEV-2026-0003", "REEMBOLSO", "1", "").titulo());
            assertEquals("Acceso denegado", leer(d, CARLA, "ANUFAC", "FAC-2026-000008", "x").titulo());
            assertEquals("Remitente no identificado", leer(d, "visitante@correo.example", "LISNOT", "*").titulo());
            assertEquals("No encontrado", leer(d, ELMER, "LISPAG", "PED-2026-0011").titulo());
            assertEquals("No encontrado", leer(d, ELMER, "VERFAC", "PED-2026-0010").titulo());
            assertEquals("No encontrado", leer(d, ELMER, "VERENT", "PED-2026-0009").titulo());
        });
    }
}
