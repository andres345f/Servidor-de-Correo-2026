package com.feder.presentacion.correo.mime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Casos límite de cabeceras detectados en la revisión independiente. */
class RobustezEncabezadosTest {

    private static CorreoEntrante analizar(String cabeceras) throws Exception {
        return new EmailMessageParser().analizar((cabeceras + "\r\n\r\ncuerpo\r\n").getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void palabraCodificadaPegadaAlTextoNoSeDecodifica() {
        // Dentro de las comillas de un parámetro debe llegar literal, no convertida en "*".
        assertEquals("HELP[\"=?UTF-8?Q?=2A?=\"]", DecodificadorRfc2047.decodificar("HELP[\"=?UTF-8?Q?=2A?=\"]"));
    }

    @Test
    void palabraCodificadaSeparadaPorEspaciosSiSeDecodifica() {
        assertEquals("INSCAT café", DecodificadorRfc2047.decodificar("INSCAT =?UTF-8?Q?caf=C3=A9?="));
    }

    @Test
    void utf16SeDecodificaPalabraPorPalabra() {
        // Cada palabra UTF-16 trae su propia marca de orden de bytes (BOM).
        String uno = "=?UTF-16?B?/v8AYQ==?=";
        String dos = "=?UTF-16?B?/v8AYg==?=";
        assertEquals("ab", DecodificadorRfc2047.decodificar(uno + " " + dos));
    }

    @Test
    void rutaDeRetornoNulaConComentarioEsRebote() throws Exception {
        CorreoEntrante correo = analizar("From: <alguien@feder.example>\r\nReturn-Path: (rebote) <>\r\nSubject: hola");
        assertTrue(correo.esRespuestaAutomatica());
    }

    @Test
    void rutaDeRetornoNulaConComentariosAnidadosEsRebote() throws Exception {
        CorreoEntrante correo = analizar("From: <alguien@feder.example>\r\nReturn-Path: (externo (interno) \\)) <>\r\nSubject: hola");
        assertTrue(correo.esRespuestaAutomatica());
    }

    @Test
    void laDireccionDelRemitenteConservaSusMayusculas() throws Exception {
        CorreoEntrante correo = analizar("From: Ana <Ana.Perez@Feder.example>\r\nSubject: HELP[\"*\"]");
        assertEquals("Ana.Perez@Feder.example", correo.from());
    }

    @Test
    void autoSubmittedRepetidoSeTrataComoAutomatico() throws Exception {
        CorreoEntrante correo = analizar("From: <alguien@feder.example>\r\nAuto-Submitted: no\r\n"
                + "Auto-Submitted: auto-replied\r\nSubject: hola");
        assertTrue(correo.esRespuestaAutomatica());
    }
}
