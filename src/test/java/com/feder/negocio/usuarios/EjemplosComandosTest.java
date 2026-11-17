package com.feder.negocio.usuarios;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ValidadorParametros;
import com.feder.presentacion.comandos.CommandParser;
import com.feder.presentacion.comandos.ComandoAnalizado;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Cada comando de CU1 a CU3 tiene un ejemplo que se analiza y valida tal como se copiaría en el asunto. */
class EjemplosComandosTest {

    private static final Set<String> OBLIGATORIOS = Set.of("REGCLI", "INVUSU", "ACEINV", "LISUSU");

    @Test
    @DisplayName("Los ejemplos son copiables: el analizador y el validador los aceptan sin cambios")
    void ejemplosValidos() {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        CatalogoComandos catalogo = ApoyoPruebas.catalogo(ctx);
        List<ComandoSpec> propios = catalogo.todos().stream()
                .filter(c -> Set.of("CU1", "CU2", "CU3").contains(c.casoDeUso()))
                .collect(Collectors.toList());
        assertTrue(propios.size() >= 40, "Se esperaban los comandos de CU1 a CU3");
        CommandParser parser = new CommandParser();
        for (ComandoSpec c : propios) {
            ComandoAnalizado analizado = assertDoesNotThrow(() -> parser.analizar(c.ejemplo()), "Ejemplo de " + c.nombre());
            assertEquals(c.nombre(), analizado.nombre().toUpperCase(java.util.Locale.ROOT));
            assertDoesNotThrow(() -> ValidadorParametros.validar(c, analizado.parametros()), "Parámetros del ejemplo de " + c.nombre());
            assertTrue(c.nombre().matches("[A-Z]{6}"), "Nombre de 6 letras: " + c.nombre());
        }
        Set<String> nombres = propios.stream().map(ComandoSpec::nombre).collect(Collectors.toSet());
        assertTrue(nombres.containsAll(OBLIGATORIOS), "Faltan comandos obligatorios del núcleo");
    }

    @Test
    @DisplayName("Los comandos de consulta no exigen confirmación y los de escritura sí")
    void escriturasConConfirmacion() {
        CatalogoComandos catalogo = ApoyoPruebas.catalogo(BaseDatosPrueba.contexto());
        Set<String> lecturas = Set.of("VERUSU", "LISUSU", "LISROL", "LISBIT", "LISCOM", "LISPRD", "VERPRD", "LISPCN", "VERPCN",
                "LISLOT", "TRZLOT", "VERCOM");
        for (ComandoSpec c : catalogo.todos()) {
            if (Set.of("CU1", "CU2", "CU3").contains(c.casoDeUso())) {
                assertEquals(!lecturas.contains(c.nombre()), c.modificaDatos(), "modificaDatos de " + c.nombre());
            }
        }
    }
}
