package com.feder.presentacion.correo.mime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Lectura de cabeceras con bytes de 8 bits sin codificar: la decisión se toma cabecera por cabecera. En el asunto,
 * cualquier byte de 8 bits que no sea UTF-8 se informa como error (no se puede saber qué escribió el usuario);
 * en las demás cabeceras se tolera leyéndolo como ISO-8859-1.
 */
class CabecerasOchoBitsTest {

    private final EmailMessageParser parser = new EmailMessageParser();

    /** Mensaje armado línea a línea con los bytes exactos de cada cabecera. */
    private static byte[] mensaje(byte[]... lineas) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] linea : lineas) {
            salida.writeBytes(linea);
            salida.writeBytes(new byte[] {'\r', '\n'});
        }
        salida.writeBytes("\r\nCuerpo\r\n".getBytes(StandardCharsets.US_ASCII));
        return salida.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] latin1(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static byte[] unir(byte[]... partes) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            salida.writeBytes(parte);
        }
        return salida.toByteArray();
    }

    @Test
    void unByteExtranoEnOtraCabeceraNoDeformaUnAsuntoUtf8() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(
                ascii("From: ana@x.example"),
                unir(ascii("X-Nota: "), new byte[] {(byte) 0xFF}),
                unir(ascii("Subject: "), utf8("INSCAT[\"Café\",\"Ñandú\"]"))));
        assertEquals("INSCAT[\"Café\",\"Ñandú\"]", c.subject());
        assertNull(c.errorCodificacionAsunto());
    }

    @Test
    void unRemitenteLatin1JuntoAUnAsuntoUtf8SeLeeCadaUnoConSuCodificacion() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(
                unir(ascii("From: "), latin1("Begoña Núñez"), ascii(" <begona@x.example>")),
                unir(ascii("Subject: "), utf8("LISPRO[\"Año\"]"))));
        assertEquals("Begoña Núñez", c.fromNombre());
        assertEquals("begona@x.example", c.from());
        assertEquals("LISPRO[\"Año\"]", c.subject());
        assertNull(c.errorCodificacionAsunto());
    }

    @Test
    void unAsuntoDeOchoBitsQueNoEsUtf8SeMarcaComoErrorPorqueNoSePuedeSaberQueSeEscribio() throws MensajeInvalidoException {
        // Latin-1 de un cliente antiguo, UTF-8 truncado, UTF-8 dañado y KOI8-R: los bytes no dicen cuál es cuál.
        for (byte[] asunto : List.of(
                latin1("LISPRO[\"Jabón de piña\"]"),
                unir(ascii("INSCAT[\"Caf"), new byte[] {(byte) 0xC3}, ascii("\",\"x\"]")),
                unir(utf8("INSCAT[\"Ñandú\",\""), new byte[] {(byte) 0xFF}, ascii("\"]")),
                latin1("INSCAT[\"AÇAÍ®\",\"x\"]"),
                "INSCAT[\"Привет\",\"x\"]".getBytes(java.nio.charset.Charset.forName("KOI8-R")))) {
            CorreoEntrante c = parser.analizar(mensaje(ascii("From: ana@x.example"), unir(ascii("Subject: "), asunto)));
            assertEquals(LectorCabeceras.BYTES_NO_UTF8, c.errorCodificacionAsunto());
            assertFalse(c.subject().contains("\uFFFD"));
        }
    }

    @Test
    void unRemitenteUtf8DanadoNoImpideLeerElAsunto() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(
                unir(ascii("From: "), utf8("Peña"), new byte[] {(byte) 0xFF}, ascii(" <pena@x.example>")),
                ascii("Subject: HELP[\"*\"]")));
        assertEquals("pena@x.example", c.from());
        assertEquals("HELP[\"*\"]", c.subject());
        assertNull(c.errorCodificacionAsunto());
    }

    @Test
    void unaPalabraRfc2047DeLasCabecerasPlegadasSeDecodificaPorCabecera() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(ascii("From: ana@x.example"),
                ascii("Subject: =?UTF-8?Q?INSCAT[\"Caf=C3?="),
                ascii("\t=?UTF-8?Q?=A9\",\"Mieles_de_la_regi=C3=B3n\"]?="),
                unir(ascii("X-Despues: "), latin1("Año"))));
        assertEquals("INSCAT[\"Café\",\"Mieles de la región\"]", c.subject());
        assertNull(c.errorCodificacionAsunto());
    }

    @Test
    void elDesplegadoConservaElEspacioDeLaContinuacion() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(ascii("From: ana@x.example"), unir(ascii("Subject: LISPRO["), utf8("\"Ñandú")),
                ascii(" \"]")));
        // La continuación empieza con un espacio, que forma parte del valor.
        assertEquals("LISPRO[\"Ñandú \"]", c.subject());
    }

    @Test
    void unEspacioUnicodeAntesDeLosDosPuntosSeTrataComoAntes() throws MensajeInvalidoException {
        // Entrada no conforme que la versión anterior aceptaba: se conserva el mismo comportamiento.
        CorreoEntrante c = parser.analizar(utf8("From: a@b.example\r\nSubject\u2003: HELP\r\n\r\n"));
        assertEquals("HELP", c.subject());
        assertEquals(List.of("From", "Subject"), c.nombresCabeceras());
        assertThrows(MensajeInvalidoException.class,
                () -> parser.analizar(utf8("From: a@b.example\r\nSubject: HELP\r\nSubject\u3000: X\r\n\r\n")));
    }

    @Test
    void remitentesConNombresInternacionales() throws MensajeInvalidoException {
        CorreoEntrante codificado = parser.analizar(mensaje(ascii("From: =?UTF-8?Q?Jos=C3=A9_Pe=C3=B1a?= <jose@x.example>"),
                ascii("Subject: x")));
        assertEquals("José Peña", codificado.fromNombre());
        CorreoEntrante latin1 = parser.analizar(mensaje(ascii("From: =?ISO-8859-1?Q?Mar=EDa_Ib=E1=F1ez?= <maria@x.example>"),
                ascii("Subject: x")));
        assertEquals("María Ibáñez", latin1.fromNombre());
        CorreoEntrante crudo = parser.analizar(mensaje(unir(ascii("From: \""), utf8("Zoë Ñuflo"), ascii("\" <zoe@x.example>")),
                ascii("Subject: x")));
        assertEquals("Zoë Ñuflo", crudo.fromNombre());
    }

    @Test
    void unNombreDeRemitenteMalCodificadoQuedaLiteralYNoAfectaAlComando() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(ascii("From: =?UTF-8?B?Qf8=?= <ana@x.example>"), ascii("Subject: HELP[\"*\"]")));
        assertEquals("=?UTF-8?B?Qf8=?=", c.fromNombre());
        assertEquals("ana@x.example", c.from());
        assertNull(c.errorCodificacionAsunto());
    }

    @Test
    void elErrorRfc2047DelAsuntoSeInformaEnElCorreo() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(ascii("From: ana@x.example"), ascii("Subject: =?UTF-8?B?SU5TQ0FUWyJDYWb/Il0=?=")));
        assertEquals("los bytes no son texto válido en el juego de caracteres declarado (UTF-8)", c.errorCodificacionAsunto());
        assertEquals("=?UTF-8?B?SU5TQ0FUWyJDYWb/Il0=?=", c.subject());
    }

    @Test
    void sinAsuntoNoHayError() throws MensajeInvalidoException {
        CorreoEntrante c = parser.analizar(mensaje(ascii("From: ana@x.example")));
        assertEquals("", c.subject());
        assertNull(c.errorCodificacionAsunto());
    }
}
