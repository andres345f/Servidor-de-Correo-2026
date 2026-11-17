package com.feder.presentacion.correo.mime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class EmailMessageParserTest {

    private final EmailMessageParser parser = new EmailMessageParser();

    private static byte[] mensaje(String... cabeceras) {
        return (String.join("\r\n", cabeceras) + "\r\n\r\nCuerpo del mensaje\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private CorreoEntrante analizar(String... cabeceras) throws MensajeInvalidoException {
        return parser.analizar(mensaje(cabeceras));
    }

    @Test
    void decodificaPalabraCodificadaB() throws MensajeInvalidoException {
        String b64 = Base64.getEncoder().encodeToString("INSCAT[\"Jabón\",\"Ñandú\"]".getBytes(StandardCharsets.UTF_8));
        CorreoEntrante c = analizar("From: ana@universidad.example", "Subject: =?UTF-8?B?" + b64 + "?=");
        assertEquals("INSCAT[\"Jabón\",\"Ñandú\"]", c.subject());
    }

    @Test
    void decodificaPalabraCodificadaQConGuionBajoComoEspacio() throws MensajeInvalidoException {
        CorreoEntrante c = analizar("From: ana@universidad.example", "Subject: =?utf-8?q?Miel_de_ca=C3=B1a?=");
        assertEquals("Miel de caña", c.subject());
    }

    @Test
    void decodificaIso88591() throws MensajeInvalidoException {
        CorreoEntrante c = analizar("From: ana@universidad.example", "Subject: =?ISO-8859-1?Q?Jab=F3n_de_ma=F1ana?=");
        assertEquals("Jabón de mañana", c.subject());
    }

    @Test
    void unePalabrasContiguasYRespetaTextoPlano() throws MensajeInvalidoException {
        CorreoEntrante c = analizar("From: ana@universidad.example",
                "Subject: Re: =?UTF-8?Q?caf=C3=A9?= =?UTF-8?Q?_listo?= ya");
        assertEquals("Re: café listo ya", c.subject());
        // Un carácter UTF-8 partido entre dos palabras se reconstruye al unir sus bytes.
        CorreoEntrante partido = analizar("From: ana@universidad.example", "Subject: =?UTF-8?Q?a=C3?=  =?UTF-8?Q?=B1b?=");
        assertEquals("añb", partido.subject());
    }

    @Test
    void despliegaCabecerasPlegadas() throws MensajeInvalidoException {
        CorreoEntrante c = analizar("From: ana@universidad.example", "Subject: LISPER[", " \"*\"]");
        assertEquals("LISPER[ \"*\"]", c.subject());
        CorreoEntrante codificado = analizar("From: ana@universidad.example", "Subject: =?UTF-8?Q?Hola_?=", "\t=?UTF-8?Q?mundo?=");
        assertEquals("Hola mundo", codificado.subject());
    }

    @Test
    void rechazaSubjectOFromDuplicados() {
        assertThrows(MensajeInvalidoException.class,
                () -> analizar("From: ana@universidad.example", "Subject: LISPER[\"*\"]", "subject: ELIPER[\"1\"]"));
        assertThrows(MensajeInvalidoException.class,
                () -> analizar("From: ana@universidad.example", "From: admin@universidad.example", "Subject: x"));
    }

    @Test
    void extraeRemitenteConNombre() throws MensajeInvalidoException {
        CorreoEntrante c = analizar("From: \"Pérez, Juan\" <Juan.Perez@UNIVERSIDAD.example>", "Subject: x");
        // La dirección se conserva tal cual; las comparaciones del sistema no distinguen mayúsculas.
        assertEquals("Juan.Perez@UNIVERSIDAD.example", c.from());
        assertEquals("Pérez, Juan", c.fromNombre());

        CorreoEntrante codificado = analizar("From: =?UTF-8?Q?Jos=C3=A9?= <jose@x.example>", "Subject: x");
        assertEquals("José", codificado.fromNombre());

        CorreoEntrante simple = analizar("From: ana@universidad.example (Ana)", "Subject: x");
        assertEquals("ana@universidad.example", simple.from());
        assertNull(simple.fromNombre());
    }

    @Test
    void rechazaRemitenteAusenteMultipleOInvalido() {
        assertThrows(MensajeInvalidoException.class, () -> analizar("Subject: x"));
        assertThrows(MensajeInvalidoException.class, () -> analizar("From: a@x.example, b@x.example", "Subject: x"));
        assertThrows(MensajeInvalidoException.class, () -> analizar("From: Nadie <sin-arroba>", "Subject: x"));
    }

    @Test
    void extraeCabecerasAuxiliares() throws MensajeInvalidoException {
        CorreoEntrante c = analizar("Return-Path: <ana@universidad.example>", "From: Ana <ana@universidad.example>",
                "Reply-To: Otra <Otra@x.example>", "Message-ID:  <abc.123@universidad.example> ", "Date: Sat, 15 Nov 2026 10:00:00 -0400",
                "Subject: LISPER[\"*\"]");
        assertEquals("<abc.123@universidad.example>", c.messageId());
        assertEquals("Otra@x.example", c.replyTo());
        assertEquals("<ana@universidad.example>", c.returnPath());
        assertEquals("Sat, 15 Nov 2026 10:00:00 -0400", c.fecha());
        assertEquals(List.of("Return-Path", "From", "Reply-To", "Message-ID", "Date", "Subject"), c.nombresCabeceras());
        assertFalse(c.esRespuestaAutomatica());
    }

    @Test
    void descartaMessageIdMalFormado() throws MensajeInvalidoException {
        assertNull(analizar("From: ana@x.example", "Message-ID: <a b@x>", "Subject: x").messageId());
    }

    @Test
    void detectaRespuestasAutomaticas() throws MensajeInvalidoException {
        assertTrue(analizar("From: ana@x.example", "Auto-Submitted: auto-replied", "Subject: x").esRespuestaAutomatica());
        assertFalse(analizar("From: ana@x.example", "Auto-Submitted: no", "Subject: x").esRespuestaAutomatica());
        assertTrue(analizar("From: ana@x.example", "Precedence: bulk", "Subject: x").esRespuestaAutomatica());
        assertTrue(analizar("From: ana@x.example", "Precedence: list", "Subject: x").esRespuestaAutomatica());
        assertTrue(analizar("Return-Path: <>", "From: ana@x.example", "Subject: x").esRespuestaAutomatica());
        assertTrue(analizar("From: MAILER-DAEMON@x.example", "Subject: x").esRespuestaAutomatica());
        assertTrue(analizar("From: postmaster@x.example", "Subject: x").esRespuestaAutomatica());
        assertTrue(analizar("From: ana@x.example", "Content-Type: multipart/report; report-type=delivery-status",
                "Subject: x").esRespuestaAutomatica());
    }

    @Test
    void aceptaCabecerasEnUtf8CrudoYEnLatin1() throws MensajeInvalidoException {
        assertEquals("Ñandú", analizar("From: ana@x.example", "Subject: Ñandú").subject());
        ByteArrayOutputStream latin1 = new ByteArrayOutputStream();
        latin1.writeBytes("From: ana@x.example\r\nSubject: ".getBytes(StandardCharsets.US_ASCII));
        latin1.writeBytes("Jabón".getBytes(StandardCharsets.ISO_8859_1));
        latin1.writeBytes("\r\n\r\ncuerpo\r\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals("Jabón", parser.analizar(latin1.toByteArray()).subject());
    }

    @Test
    void aceptaSaltosLfYMensajeSinCuerpo() throws MensajeInvalidoException {
        byte[] lf = "From: ana@x.example\nSubject: LISPER[\"*\"]\n\ncuerpo\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals("LISPER[\"*\"]", parser.analizar(lf).subject());
        byte[] sinCuerpo = "From: ana@x.example\r\nSubject: HELP[\"*\"]".getBytes(StandardCharsets.US_ASCII);
        assertEquals("HELP[\"*\"]", parser.analizar(sinCuerpo).subject());
    }

    @Test
    void ignoraCabecerasDelCuerpoYLimitaElTamano() throws MensajeInvalidoException {
        byte[] conCuerpo = "From: ana@x.example\r\nSubject: a\r\n\r\nSubject: b\r\n".getBytes(StandardCharsets.US_ASCII);
        assertEquals("a", parser.analizar(conCuerpo).subject());
        String enorme = "From: ana@x.example\r\nX-Relleno: " + "a".repeat(EmailMessageParser.MAXIMO_BYTES_CABECERAS) + "\r\n\r\n";
        assertThrows(MensajeInvalidoException.class, () -> parser.analizar(enorme.getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void dejaLiteralUnaPalabraMalFormada() throws MensajeInvalidoException {
        assertEquals("=?x-desconocido?Q?hola?= =?UTF-8?B?***?=",
                analizar("From: ana@x.example", "Subject: =?x-desconocido?Q?hola?= =?UTF-8?B?***?=").subject());
    }
}
