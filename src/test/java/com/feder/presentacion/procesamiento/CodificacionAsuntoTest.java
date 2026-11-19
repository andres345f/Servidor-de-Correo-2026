package com.feder.presentacion.procesamiento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.feder.negocio.mensajeria.SolicitudCorreo;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Camino completo de un asunto desde los bytes crudos del correo (tal como los entrega POP3) hasta la solicitud
 * para la capa de negocio: lo válido llega como comando; lo mal codificado se rechaza antes, con su motivo.
 */
class CodificacionAsuntoTest {

    private static final String SISTEMA = "sistema@feder.local";
    private static final String BYTES_UTF8 = "los bytes no son texto válido en el juego de caracteres declarado (UTF-8)";
    private static final String OCHO_BITS = "el asunto contiene bytes de 8 bits sin codificar que no son UTF-8 válido";
    private final InterpreteCorreo interprete = new InterpreteCorreo("buzon", SISTEMA);

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] unir(byte[]... partes) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            salida.writeBytes(parte);
        }
        return salida.toByteArray();
    }

    private static String b(String charset, byte[] datos) {
        return "=?" + charset + "?B?" + Base64.getEncoder().encodeToString(datos) + "?=";
    }

    private static byte[] correo(String cabecerasPrevias, byte[] asunto) {
        return unir(ascii(cabecerasPrevias + "To: <" + SISTEMA + ">\r\nMessage-ID: <prueba@feder.example>\r\nSubject: "),
                asunto, ascii("\r\nMIME-Version: 1.0\r\n\r\nCuerpo\r\n"));
    }

    private SolicitudCorreo interpretar(byte[] asunto) {
        return interprete.interpretar("uid", correo("From: Ana <ana@feder.example>\r\n", asunto));
    }

    static Stream<Arguments> validos() {
        return Stream.of(
                arguments("Consulta en ASCII", ascii("LISPRO[\"miel\"]"), "LISPRO", List.of("miel")),
                arguments("Consulta con tildes y ñ en B UTF-8", ascii(b("UTF-8", "LISPRO[\"Ñandú\"]".getBytes(StandardCharsets.UTF_8))),
                        "LISPRO", List.of("Ñandú")),
                arguments("Consulta con ñ en Q ISO-8859-1", ascii("=?ISO-8859-1?Q?LISPRO[\"pi=F1a\"]?="), "LISPRO", List.of("piña")),
                arguments("Consulta en UTF-8 sin codificar (RFC 6532)", "LISPRO[\"Jabón de cañahua\"]".getBytes(StandardCharsets.UTF_8),
                        "LISPRO", List.of("Jabón de cañahua")),
                arguments("Escritura con comillas escapadas y barra invertida",
                        ascii(b("UTF-8", "INSCAT[\"Jabón \\\"Totaí\\\"\",\"Ruta C:\\\\FEDER\"]".getBytes(StandardCharsets.UTF_8))),
                        "INSCAT", List.of("Jabón \"Totaí\"", "Ruta C:\\FEDER")),
                arguments("Escritura con € y comillas tipográficas en windows-1252",
                        ascii("=?windows-1252?Q?INSCAT[\"=93Mieles=94\",\"Precio_en_=80\"]?="), "INSCAT",
                        List.of("“Mieles”", "Precio en €")),
                arguments("Escritura con signos especiales en B UTF-8",
                        ascii(b("UTF-8", "INSCAT[\"Mieles «Ñandú» & Cía.\",\"Açaí, 100 % orgánico\"]".getBytes(StandardCharsets.UTF_8))),
                        "INSCAT", List.of("Mieles «Ñandú» & Cía.", "Açaí, 100 % orgánico")),
                arguments("Palabras contiguas en líneas plegadas con un carácter partido",
                        ascii("=?UTF-8?Q?INSCAT[\"Caf=C3?=\r\n =?UTF-8?Q?=A9\",\"x\"]?="), "INSCAT", List.of("Café", "x")),
                arguments("Palabra codificada escrita como parámetro se decodifica como dato", ascii("HELP[\"=?UTF-8?Q?=2A?=\"]"),
                        "HELP", List.of("*")),
                arguments("Nombre con una palabra ISO-8859-1 dentro de un asunto UTF-8",
                        ascii(b("UTF-8", "MODUSU[\"José =?ISO-8859-1?Q?Pe=F1a?= Suárez\",\"\",\"\"]".getBytes(StandardCharsets.UTF_8))),
                        "MODUSU", List.of("José Peña Suárez", "", "")),
                arguments("Palabra codificada al inicio de un parámetro", ascii("INSCAT[\"=?UTF-8?Q?T=C3=A9?= verde\",\"x\"]"),
                        "INSCAT", List.of("Té verde", "x")),
                arguments("Texto que solo se parece a una palabra codificada", ascii("HELP[\"=?\"]"), "HELP", List.of("=?")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validos")
    void losAsuntosValidosLleganComoComando(String caso, byte[] asunto, String comando, List<String> parametros) {
        SolicitudCorreo s = interpretar(asunto);
        assertNull(s.errorCodificacion, s.errorCodificacion);
        assertNull(s.errorSintaxis, s.errorSintaxis);
        assertEquals(comando, s.comando);
        assertEquals(parametros, s.parametros);
    }

    static Stream<Arguments> invalidos() {
        return Stream.of(
                arguments("Escritura con 0xFF declarado UTF-8", ascii("=?UTF-8?B?SU5TQ0FUWyJDYWb/IiwieCJd?="), BYTES_UTF8),
                arguments("Escritura con UTF-8 truncado", ascii(b("UTF-8", unir(ascii("INSCAT[\"Caf"), new byte[] {(byte) 0xC3},
                        ascii("\",\"x\"]")))), BYTES_UTF8),
                arguments("Escritura con secuencia UTF-8 incompatible (sobrelarga)",
                        ascii(b("UTF-8", unir(ascii("INSCAT[\"a"), new byte[] {(byte) 0xC0, (byte) 0xAF}, ascii("\",\"x\"]")))), BYTES_UTF8),
                arguments("US-ASCII con un byte de 8 bits", ascii("=?US-ASCII?Q?INSCAT[\"Caf=E9\",\"x\"]?="),
                        "los bytes no son texto válido en el juego de caracteres declarado (US-ASCII)"),
                arguments("Juego de caracteres desconocido", ascii("=?x-inventado?Q?HELP[\"*\"]?="),
                        "juego de caracteres desconocido o no admitido"),
                arguments("Base64 mal formado", ascii("=?UTF-8?B?***?="), "Base64 mal formado"),
                arguments("Q mal formada", ascii("=?UTF-8?Q?HELP=ZZ?="), "codificación Q mal formada"),
                arguments("Palabra vacía que juntaría el comando", ascii("INSCAT =?UTF-8?Q??= [\"a\",\"x\"]"), "codificación Q mal formada"),
                arguments("Una palabra válida y otra rota", ascii("=?UTF-8?Q?INSCAT[\"a\",?= =?UTF-8?B?***?="), "Base64 mal formado"),
                arguments("ISO-2022-JP con un escape incompleto que la palabra siguiente completaría",
                        ascii("=?ISO-2022-JP?Q?INSCAT[\"a=1B?= =?ISO-2022-JP?Q?(Bx\",\"y\"]?="),
                        "los bytes no son texto válido en el juego de caracteres declarado (ISO-2022-JP)"),
                arguments("UTF-32 con unidades sustitutas", ascii("INSCAT[\" =?UTF-32?B?AADYPQAA3gA=?= \",\"x\"]"),
                        "los bytes no son texto válido en el juego de caracteres declarado (UTF-32)"),
                arguments("UTF-8 sin codificar pero dañado",
                        unir("INSCAT[\"Ñandú\",\"".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xFF}, ascii("\"]")), OCHO_BITS),
                arguments("UTF-8 sin codificar y truncado", unir(ascii("INSCAT[\"Caf"), new byte[] {(byte) 0xC3}, ascii("\",\"x\"]")),
                        OCHO_BITS),
                arguments("Latin-1 sin codificar de un cliente antiguo", "LISPRO[\"Jabón\"]".getBytes(StandardCharsets.ISO_8859_1),
                        OCHO_BITS));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidos")
    void losAsuntosMalCodificadosSeRechazanAntesDeLaCapaDeNegocio(String caso, byte[] asunto, String motivo) {
        SolicitudCorreo s = interpretar(asunto);
        assertNull(s.comando, "no debe llegar ningún comando a negocio");
        assertTrue(s.parametros.isEmpty());
        assertNull(s.errorSintaxis);
        assertFalse(s.respuestaAutomatica, "al remitente se le responde con el motivo");
        assertNotNull(s.errorCodificacion);
        assertTrue(s.errorCodificacion.startsWith("No se pudo decodificar el asunto del correo: " + motivo + "."),
                s.errorCodificacion);
        assertTrue(s.errorCodificacion.contains("No se ejecutó ningún comando"));
        assertEquals(InterpreteCorreo.ASUNTO_NO_DECODIFICABLE, s.asunto, "un asunto dudoso no se registra");
    }

    static Stream<Arguments> parametrosMalCodificados() {
        return Stream.of(
                arguments("Q mal formada dentro del parámetro", ascii("INSCAT[\"=?UTF-8?Q?T=ZZ?= verde\",\"x\"]"),
                        "codificación Q mal formada"),
                arguments("Juego de caracteres desconocido dentro del parámetro", ascii("INSCAT[\"=?x-inventado?Q?a?=\",\"x\"]"),
                        "juego de caracteres desconocido o no admitido"),
                arguments("Palabra pegada al texto vecino", ascii("INSCAT[\"Pe=?ISO-8859-1?Q?=F1?=a\",\"x\"]"),
                        "debe ir separado por espacios"),
                arguments("Otra codificación dentro de la palabra (no se decodifica dos veces)",
                        ascii("INSCAT[\"" + b("UTF-8", ascii("=?ISO-8859-1?Q?Pe=F1a?=")) + "\",\"x\"]"),
                        "no puede contener otra codificación dentro"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("parametrosMalCodificados")
    void unParametroMalCodificadoSeRechazaConSuMotivo(String caso, byte[] asunto, String motivo) {
        SolicitudCorreo s = interpretar(asunto);
        assertNull(s.comando, "no debe llegar ningún comando a negocio");
        assertTrue(s.parametros.isEmpty());
        assertNull(s.errorSintaxis);
        assertNotNull(s.errorCodificacion);
        assertTrue(s.errorCodificacion.startsWith("El parámetro 1 del comando INSCAT tiene texto codificado"),
                s.errorCodificacion);
        assertTrue(s.errorCodificacion.contains(motivo), s.errorCodificacion);
        assertTrue(s.errorCodificacion.contains("No se ejecutó ningún comando"));
        assertEquals(InterpreteCorreo.ASUNTO_NO_DECODIFICABLE, s.asunto);
    }

    @Test
    void unConfirmarConParametroMalCodificadoNoDejaElCodigoRegistrado() {
        SolicitudCorreo s = interpretar(ascii("CONFIRMAR[\"=?UTF-8?Q?QWER=ZZ?=\"]"));
        assertNotNull(s.errorCodificacion);
        assertEquals(InterpreteCorreo.ASUNTO_NO_DECODIFICABLE, s.asunto);
        assertFalse(s.errorCodificacion.contains("QWER"), s.errorCodificacion);
        assertNull(s.asuntoOriginal, "el original de un CONFIRMAR lleva el código");
    }

    @Test
    void lasRespuestasAutomaticasMalCodificadasSeDescartanSinContestar() {
        byte[] invalido = ascii("=?UTF-8?B?SU5TQ0FUWyJDYWb/Il0=?=");
        for (String cabeceras : List.of(
                "From: <ana@feder.example>\r\nAuto-Submitted: auto-replied\r\n",
                "From: <ana@feder.example>\r\nPrecedence: bulk\r\n",
                "From: <ana@feder.example>\r\nContent-Type: multipart/report; report-type=delivery-status\r\n",
                "From: <ana@feder.example>\r\nX-Autoreply: yes\r\n",
                "Return-Path: <>\r\nFrom: <mailer-daemon@x.example>\r\n",
                "From: <" + SISTEMA + ">\r\n")) {
            SolicitudCorreo s = interprete.interpretar("uid", correo(cabeceras, invalido));
            assertTrue(s.respuestaAutomatica, "nunca se contesta: " + cabeceras);
            assertNull(s.errorCodificacion);
            assertEquals(InterpreteCorreo.ASUNTO_NO_DECODIFICABLE, s.asunto);
        }
    }

    @Test
    void unRemitenteConNombreMalCodificadoNoBloqueaElComando() {
        SolicitudCorreo s = interprete.interpretar("uid", correo("From: =?UTF-8?B?Qf8=?= <ana@feder.example>\r\n",
                ascii("=?UTF-8?Q?LISPRO[\"Ca=C3=B1a\"]?=")));
        assertEquals("LISPRO", s.comando);
        assertEquals(List.of("Caña"), s.parametros);
        assertEquals("ana@feder.example", s.remitente);
    }

    @Test
    void unConfirmarMalCodificadoNoDejaElCodigoEnElAsuntoRegistrado() {
        for (byte[] asunto : List.of(
                ascii(b("UTF-8", unir(ascii("CONFIRMAR[\"QWER"), new byte[] {(byte) 0xFF}, ascii("TY12\"]")))),
                ascii("=?UTF-8?Q?CONFIRMAR[\"QWER=ZZ\"]?="),
                ascii("=?US-ASCII?Q?CON?= =?UTF-8?Q?FIRMAR[\"QWERTY12\"]=FF?="),
                ascii("=?x-desconocido?B?Q09ORklSTUFSWyJRV0VSVFkxMiJd?="))) {
            SolicitudCorreo s = interpretar(asunto);
            assertNotNull(s.errorCodificacion);
            assertEquals(InterpreteCorreo.ASUNTO_NO_DECODIFICABLE, s.asunto);
            assertFalse(s.asunto.contains("QWER") || s.errorCodificacion.contains("QWER"));
        }
    }

    @Test
    void unCaracterDeReemplazoEscritoEnElAsuntoTambienSeRechaza() {
        // EF BF BD es UTF-8 válido (U+FFFD): no es un error de codificación, pero el texto ya llegó dañado.
        SolicitudCorreo s = interpretar("INSCAT[\"Caf\uFFFD\",\"x\"]".getBytes(StandardCharsets.UTF_8));
        assertNull(s.comando);
        assertNotNull(s.errorSintaxis);
        assertTrue(s.errorSintaxis.contains("U+FFFD"), s.errorSintaxis);
    }

    @Test
    void unControlC1BienCodificadoSeRechazaComoCaracterNoPermitido() {
        SolicitudCorreo s = interpretar(ascii("=?UTF-8?Q?INSCAT[\"a=C2=85b\",\"x\"]?="));
        assertNull(s.comando);
        assertNotNull(s.errorSintaxis);
        assertTrue(s.errorSintaxis.contains("U+0085"), s.errorSintaxis);
    }

    @Test
    void unaCodificacionCorrectaConMalaSintaxisDaElErrorDeSintaxisHabitual() {
        SolicitudCorreo s = interpretar(ascii("=?UTF-8?Q?HELP[LISPRO]?="));
        assertNull(s.errorCodificacion);
        assertNotNull(s.errorSintaxis);
        assertTrue(s.errorSintaxis.contains("Se esperaba un parámetro entre comillas"), s.errorSintaxis);
    }

    @Test
    void conservaElAsuntoTalComoLlegoJuntoAlDecodificado() {
        String crudo = b("US-ASCII", ascii("Ana Perez"));
        SolicitudCorreo s = interpretar(ascii(crudo));
        assertEquals(crudo, s.asuntoOriginal);
        assertEquals("Ana Perez", s.asunto);
    }

    @Test
    void unAsuntoMalCodificadoConservaElOriginalParaMostrarQueLlego() {
        String crudo = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(new byte[] {(byte) 0xC3}) + "?=";
        SolicitudCorreo s = interpretar(ascii(crudo));
        assertEquals(InterpreteCorreo.ASUNTO_NO_DECODIFICABLE, s.asunto);
        assertEquals(crudo, s.asuntoOriginal);
    }

    @Test
    void unConfirmarCodificadoNoConservaElOriginalPorqueLlevaElCodigo() {
        SolicitudCorreo s = interpretar(ascii(b("UTF-8", "CONFIRMAR[\"123456\"]".getBytes(StandardCharsets.UTF_8))));
        assertEquals("CONFIRMAR", s.comando);
        assertNull(s.asuntoOriginal);
    }
}
