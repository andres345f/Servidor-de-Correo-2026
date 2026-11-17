package com.feder.presentacion.comandos;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandParserTest {

    private final CommandParser parser = new CommandParser();

    @Test
    void analizaComandoSimple() throws ErrorSintaxisException {
        ComandoAnalizado c = parser.analizar("LISPER[\"*\"]");
        assertEquals("LISPER", c.nombre());
        assertEquals(List.of("*"), c.parametros());
        assertEquals("LISPER[\"*\"]", c.textoOriginal());
    }

    @Test
    void aceptaEspaciosEntreTokens() throws ErrorSintaxisException {
        ComandoAnalizado c = parser.analizar("  LISPER [ \"*\" ]  ");
        assertEquals("LISPER", c.nombre());
        assertEquals(List.of("*"), c.parametros());
    }

    /** Mismo formato que el ejemplo de INSPER del enunciado, con datos ficticios. */
    @Test
    void analizaElEjemploOficialDeInsper() throws ErrorSintaxisException {
        ComandoAnalizado c = parser.analizar("INSPER[\"9900090\",\"Ana Lucía\",\"Ejemplo Ficticio\",\"Estudiante\","
                + "\"70000190\",\"70000191\",\"ana.ejemplo@universidad.example\"]");
        assertEquals("INSPER", c.nombre());
        assertEquals(List.of("9900090", "Ana Lucía", "Ejemplo Ficticio", "Estudiante", "70000190", "70000191",
                "ana.ejemplo@universidad.example"), c.parametros());
    }

    @Test
    void interpretaComillasEscapadas() throws ErrorSintaxisException {
        ComandoAnalizado c = parser.analizar("INSCAT[\"Jabón \\\"Totaí\\\"\",\"x\"]");
        assertEquals(List.of("Jabón \"Totaí\"", "x"), c.parametros());
    }

    @Test
    void interpretaBarraInvertidaEscapada() throws ErrorSintaxisException {
        ComandoAnalizado c = parser.analizar("INSCAT[\"C:\\\\ruta\"]");
        assertEquals(List.of("C:\\ruta"), c.parametros());
    }

    @Test
    void conservaAcentosEñesYEspaciosInternos() throws ErrorSintaxisException {
        ComandoAnalizado c = parser.analizar("INSCAT[\"  Miel de Ñandú  \",\"Productos apícolas\"]");
        assertEquals(List.of("  Miel de Ñandú  ", "Productos apícolas"), c.parametros());
    }

    @Test
    void aceptaParametroVacio() throws ErrorSintaxisException {
        assertEquals(List.of("", "x"), parser.analizar("MODCAT[\"\",\"x\"]").parametros());
    }

    @Test
    void normalizaElNombreAMayusculas() throws ErrorSintaxisException {
        assertEquals("LISPER", parser.analizar("lisPer[\"*\"]").nombre());
    }

    @Test
    void rechazaComillaSinCerrar() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class,
                () -> parser.analizar("INSPER[\"1\",\"Ana]"));
        assertEquals(12, e.posicion());
        assertTrue(e.getMessage().contains("comilla de cierre"), e.getMessage());
    }

    @Test
    void rechazaCorcheteSinCerrar() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class, () -> parser.analizar("LISPER[\"*\""));
        assertEquals("Se esperaba ',' o ']' en la posición 11, pero se encontró el final del texto", e.getMessage());
    }

    @Test
    void rechazaCorchetesVacios() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class, () -> parser.analizar("LISPER[]"));
        assertEquals(8, e.posicion());
        assertTrue(e.getMessage().contains("corchetes vacíos"), e.getMessage());
    }

    @Test
    void rechazaParametroSinComillas() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class,
                () -> parser.analizar("INSPER[1,\"Ana\"]"));
        assertEquals(8, e.posicion());
        assertTrue(e.getMessage().startsWith("Se esperaba un parámetro entre comillas en la posición 8"), e.getMessage());
    }

    @Test
    void rechazaTextoDespuesDelCorchete() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class,
                () -> parser.analizar("LISPER[\"*\"] gracias"));
        assertEquals(13, e.posicion());
    }

    @Test
    void rechazaEscapeDesconocido() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class,
                () -> parser.analizar("INSCAT[\"a\\nb\"]"));
        assertEquals(10, e.posicion());
        assertTrue(e.getMessage().contains("escape no válida"), e.getMessage());
    }

    @Test
    void rechazaCaracteresDeControlDentroDeValores() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class,
                () -> parser.analizar("INSCAT[\"a\u0001b\"]"));
        assertEquals(10, e.posicion());
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("INSCAT[\"a\tb\"]"));
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("INSCAT[\"a\u007Fb\"]"));
        // Controles C1 (U+0080-U+009F): tampoco son texto.
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("INSCAT[\"a\u0085b\"]"));
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("INSCAT[\"a\u0093b\u0094\"]"));
    }

    @Test
    void rechazaElCaracterDeReemplazoPorqueIndicaTextoDanado() {
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class,
                () -> parser.analizar("INSCAT[\"Caf\uFFFD\",\"x\"]"));
        assertEquals(12, e.posicion());
        assertTrue(e.getMessage().contains("U+FFFD"), e.getMessage());
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("INSCAT\uFFFD[\"x\",\"y\"]"));
        // Los demás caracteres no ASCII siguen admitiéndose.
        assertDoesNotThrow(() -> parser.analizar("INSCAT[\"Café € «Ñandú»\",\"x\"]"));
    }

    @Test
    void rechazaAsuntoDemasiadoLargo() {
        String largo = "INSCAT[\"" + "a".repeat(1000) + "\"]";
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar(largo));
    }

    @Test
    void rechazaMasDeSesentaParametros() throws ErrorSintaxisException {
        String sesenta = "INSCAT[" + "\"a\",".repeat(59) + "\"a\"]";
        assertEquals(60, parser.analizar(sesenta).parametros().size());
        String sesentaYUno = "INSCAT[" + "\"a\",".repeat(60) + "\"a\"]";
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar(sesentaYUno));
    }

    @Test
    void rechazaNombresFueraDeRango() {
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("LI[\"*\"]"));
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("ABCDEFGHIJKLM[\"*\"]"));
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("LIS2PER[\"*\"]"));
    }

    @Test
    void rechazaAsuntoVacioOSinCorchete() {
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar(""));
        assertThrows(ErrorSintaxisException.class, () -> parser.analizar("   "));
        ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class, () -> parser.analizar("LISPER \"*\""));
        assertTrue(e.getMessage().contains("'['"), e.getMessage());
    }
}
