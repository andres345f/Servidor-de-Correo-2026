package com.feder.presentacion.correo.mime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EmailResponseBuilderTest {

    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-11-15T14:30:00Z"), ZoneId.of("America/La_Paz"));
    private final EmailResponseBuilder builder =
            new EmailResponseBuilder("feder@feder.local", "Sistema FEDER", "feder.local", RELOJ);

    private static String texto(byte[] bytes) {
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    @Test
    void elAsuntoUtf8SobreviveAlIdaYVuelta() throws MensajeInvalidoException {
        String asunto = "FEDER - Éxito: Categoría «Mieles» registrada con código CAT-0001 y ñandú, "
                + "texto largo para obligar a plegar la cabecera en varias palabras codificadas";
        byte[] bytes = builder.construir(new CorreoSaliente("ana@universidad.example", asunto, "Hola", "<p>Hola</p>",
                null, "<orig.1@universidad.example>"));
        CorreoEntrante leido = new EmailMessageParser().analizar(bytes);
        assertEquals(asunto, leido.subject());
        assertEquals("feder@feder.local", leido.from());
        assertEquals("Sistema FEDER", leido.fromNombre());
        assertTrue(leido.esRespuestaAutomatica(), "la propia respuesta debe marcarse como automática");
        for (String linea : texto(bytes).split("\r\n")) {
            assertTrue(linea.length() <= 78, "línea demasiado larga: " + linea);
        }
    }

    @Test
    void incluyeLasCabecerasObligatorias() {
        String m = texto(builder.construir(new CorreoSaliente("ana@universidad.example", "LISPER", "t", "<b>h</b>",
                "<id.1@feder.local>", "<orig.1@universidad.example>")));
        assertTrue(m.startsWith("Date: Sun, 15 Nov 2026 10:30:00 -0400\r\n"), m);
        assertTrue(m.contains("\r\nFrom: \"Sistema FEDER\" <feder@feder.local>\r\n"));
        assertTrue(m.contains("\r\nTo: <ana@universidad.example>\r\n"));
        assertTrue(m.contains("\r\nSubject: LISPER\r\n"));
        assertTrue(m.contains("\r\nMessage-ID: <id.1@feder.local>\r\n"));
        assertTrue(m.contains("\r\nIn-Reply-To: <orig.1@universidad.example>\r\n"));
        assertTrue(m.contains("\r\nReferences: <orig.1@universidad.example>\r\n"));
        assertTrue(m.contains("\r\nMIME-Version: 1.0\r\n"));
        assertTrue(m.contains("\r\nAuto-Submitted: auto-replied\r\n"));
        assertTrue(m.contains("\r\nX-Auto-Response-Suppress: All\r\n"));
        assertTrue(m.contains("\r\nContent-Type: multipart/alternative;\r\n boundary=\"=_FEDER_"));
        assertFalse(m.replace("\r\n", "").contains("\n"), "no debe haber LF sueltos");
    }

    @Test
    void codificaAmbosCuerposEnBase64Utf8() {
        String plano = "Línea 1\nLínea 2 con ñ";
        String html = "<p>Categoría <b>creada</b></p>";
        String m = texto(builder.construir(new CorreoSaliente("ana@x.example", "x", plano, html, null, null)));
        Matcher separador = Pattern.compile("boundary=\"([^\"]+)\"").matcher(m);
        assertTrue(separador.find());
        String[] partes = m.split("--" + Pattern.quote(separador.group(1)));
        assertEquals(4, partes.length, "preámbulo, dos partes y cierre");
        assertTrue(partes[1].contains("Content-Type: text/plain; charset=UTF-8"));
        assertTrue(partes[2].contains("Content-Type: text/html; charset=UTF-8"));
        assertEquals("Línea 1\r\nLínea 2 con ñ", decodificarParte(partes[1]));
        assertEquals(html, decodificarParte(partes[2]));
        assertTrue(partes[3].startsWith("--"));
    }

    @Test
    void sinHtmlEnviaSoloTextoPlano() {
        String m = texto(builder.construir(new CorreoSaliente("ana@x.example", "x", "solo texto", null, null, null)));
        assertFalse(m.contains("multipart"));
        assertTrue(m.contains("Content-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n"));
        assertEquals("solo texto", decodificarParte(m));
    }

    @Test
    void rechazaInyeccionDeCabeceras() {
        assertThrows(IllegalArgumentException.class, () -> builder.construir(
                new CorreoSaliente("ana@x.example", "Hola\r\nBcc: victima@x.example", "t", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> builder.construir(
                new CorreoSaliente("ana@x.example\r\nBcc: victima@x.example", "x", "t", null, null, null)));
        assertThrows(IllegalArgumentException.class, () -> builder.construir(
                new CorreoSaliente("ana@x.example", "x", "t", null, null, "<a@b>\r\nBcc: v@x.example")));
        assertThrows(IllegalArgumentException.class, () -> builder.construir(
                new CorreoSaliente("Ana <ana@x.example>", "x", "t", null, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> new EmailResponseBuilder("feder@feder.local", "FEDER\r\nBcc: x@y.example"));
    }

    private static String decodificarParte(String parte) {
        String cuerpo = parte.substring(parte.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
        return new String(Base64.getDecoder().decode(cuerpo), StandardCharsets.UTF_8);
    }
}
