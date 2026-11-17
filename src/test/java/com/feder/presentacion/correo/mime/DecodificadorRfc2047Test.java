package com.feder.presentacion.correo.mime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Decodificación estricta de RFC 2047: nunca se inventan caracteres y cada fallo informa su motivo. */
class DecodificadorRfc2047Test {

    private static final String BYTES_UTF8 = "los bytes no son texto válido en el juego de caracteres declarado (UTF-8)";

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] bytes(int... valores) {
        byte[] r = new byte[valores.length];
        for (int i = 0; i < valores.length; i++) {
            r[i] = (byte) valores[i];
        }
        return r;
    }

    private static byte[] unir(byte[]... partes) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            salida.writeBytes(parte);
        }
        return salida.toByteArray();
    }

    private static String palabraB(String charset, byte[] datos) {
        return "=?" + charset + "?B?" + Base64.getEncoder().encodeToString(datos) + "?=";
    }

    private static String bytesInvalidos(String charset) {
        return "los bytes no son texto válido en el juego de caracteres declarado (" + charset + ")";
    }

    /** Motivo para un juego desconocido o no admitido: el nombre declarado no se repite en la respuesta. */
    private static String desconocido(String nombre) {
        return "juego de caracteres desconocido o no admitido";
    }

    static Stream<Arguments> validas() {
        return Stream.of(
                arguments("ASCII sin codificar", "HELP[\"*\"]", "HELP[\"*\"]"),
                arguments("B en UTF-8", palabraB("UTF-8", utf8("INSCAT[\"Jabón\",\"Ñandú\"]")), "INSCAT[\"Jabón\",\"Ñandú\"]"),
                arguments("B con letras minúsculas", "=?utf-8?b?" + Base64.getEncoder().encodeToString(utf8("LISPRO[\"café\"]")) + "?=",
                        "LISPRO[\"café\"]"),
                arguments("B sin relleno y alias utf8", "=?utf8?B?SEVMUFsiTElTUFJPIl0?=", "HELP[\"LISPRO\"]"),
                arguments("Etiqueta de idioma de RFC 2231", "=?UTF-8*es?Q?HELP[\"LISPRO\"]?=", "HELP[\"LISPRO\"]"),
                arguments("B en US-ASCII", palabraB("US-ASCII", ascii("HELP[\"*\"]")), "HELP[\"*\"]"),
                arguments("B en ISO-8859-1", palabraB("ISO-8859-1", "Jabón de mañana".getBytes(StandardCharsets.ISO_8859_1)),
                        "Jabón de mañana"),
                arguments("B en ISO-8859-15 con euro", palabraB("ISO-8859-15", unir(ascii("Precio "), bytes(0xA4))), "Precio €"),
                arguments("B en UTF-16 con BOM", palabraB("UTF-16", unir(bytes(0xFE, 0xFF), "Ñandú".getBytes(StandardCharsets.UTF_16BE))),
                        "Ñandú"),
                arguments("B en UTF-16BE", palabraB("UTF-16BE", "Señal".getBytes(StandardCharsets.UTF_16BE)), "Señal"),
                arguments("B en UTF-16LE", palabraB("UTF-16LE", "Señal".getBytes(StandardCharsets.UTF_16LE)), "Señal"),
                arguments("B en UTF-32", palabraB("UTF-32", "Año".getBytes(Charset.forName("UTF-32"))), "Año"),
                arguments("B en UTF-32 con un carácter fuera del plano básico", palabraB("UTF-32", bytes(0, 1, 0xF6, 0)), "😀"),
                arguments("B en Shift_JIS", palabraB("Shift_JIS", "あい".getBytes(Charset.forName("Shift_JIS"))), "あい"),
                arguments("B en ISO-2022-JP que termina en ASCII", palabraB("ISO-2022-JP", "日本語".getBytes(Charset.forName("ISO-2022-JP"))),
                        "日本語"),
                arguments("Q en ISO-2022-JP con barra y tilde en ASCII", "=?ISO-2022-JP?Q?a=5C~?=", "a" + (char) 0x5C + "~"),
                arguments("B en windows-874 (tailandés)", palabraB("windows-874", "สวัสดี".getBytes(Charset.forName("windows-874"))),
                        "สวัสดี"),
                arguments("Q en UTF-8 con '_' como espacio", "=?UTF-8?Q?Miel_de_ca=C3=B1a?=", "Miel de caña"),
                arguments("Q con hexadecimal en minúsculas", "=?UTF-8?Q?caf=c3=a9?=", "café"),
                arguments("Q en ISO-8859-1", "=?ISO-8859-1?Q?Jab=F3n?=", "Jabón"),
                arguments("Q con otra etiqueta de ISO-8859-1 (l1)", "=?l1?Q?Jab=F3n?=", "Jabón"),
                arguments("Q en windows-1252 con euro y comillas", "=?windows-1252?Q?=93Precio=94_=80?=", "“Precio” €"),
                arguments("ISO-8859-1 se lee como windows-1252 (WHATWG)", "=?ISO-8859-1?Q?=80_=93x=94?=", "€ “x”"),
                arguments("Q con comillas y corchetes en un asunto", "=?UTF-8?Q?INSCAT[\"Caf=C3=A9\",\"x\"]?=", "INSCAT[\"Café\",\"x\"]"),
                arguments("Palabras contiguas: se quita el espacio intermedio", "=?UTF-8?Q?Hola?= =?UTF-8?Q?_mundo?=", "Hola mundo"),
                arguments("Contiguas en ISO-8859-1: cada una por separado", "=?ISO-8859-1?Q?Jab=F3n?= =?ISO-8859-1?Q?_de_pi=F1a?=",
                        "Jabón de piña"),
                arguments("Contiguas con distinto juego de caracteres", "=?ISO-8859-1?Q?Jab=F3n?= =?UTF-8?Q?_y_caf=C3=A9?=",
                        "Jabón y café"),
                arguments("Carácter UTF-8 partido entre palabras plegadas", "=?UTF-8?Q?a=C3?=\r\n =?UTF-8?Q?=B1b?=", "añb"),
                arguments("Carácter UTF-8 partido entre una palabra B y otra Q",
                        palabraB("UTF-8", unir(ascii("INSCAT[\"Caf"), bytes(0xC3))) + " =?UTF-8?Q?=A9\",\"x\"]?=",
                        "INSCAT[\"Café\",\"x\"]"),
                arguments("UTF-16 contiguo: cada palabra con su BOM",
                        palabraB("UTF-16", unir(bytes(0xFE, 0xFF), "Ño".getBytes(StandardCharsets.UTF_16BE))) + " "
                                + palabraB("UTF-16", unir(bytes(0xFF, 0xFE), "ño".getBytes(StandardCharsets.UTF_16LE))),
                        "Ñoño"),
                arguments("Palabra entre texto literal conserva los espacios del texto", "Re: =?UTF-8?Q?caf=C3=A9?= listo",
                        "Re: café listo"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validas")
    void decodificaLasCodificacionesValidas(String caso, String entrada, String esperado) {
        DecodificadorRfc2047.Resultado r = DecodificadorRfc2047.analizar(entrada);
        assertEquals(esperado, r.texto());
        assertNull(r.error(), r.error());
        assertTrue(r.valido());
        assertEquals(esperado, DecodificadorRfc2047.decodificar(entrada));
        assertNull(DecodificadorRfc2047.primerError(entrada));
    }

    static Stream<Arguments> invalidas() {
        return Stream.of(
                arguments("UTF-8 con el byte 0xFF", "=?UTF-8?B?SU5TQ0FUWyJDYWb/Il0=?=", BYTES_UTF8),
                arguments("UTF-8 truncado en medio", palabraB("UTF-8", unir(ascii("INSCAT[\"Caf"), bytes(0xC3), ascii("\"]"))),
                        BYTES_UTF8),
                arguments("UTF-8 truncado al final", palabraB("UTF-8", unir(ascii("Caf"), bytes(0xE2, 0x82))), BYTES_UTF8),
                arguments("UTF-8 con byte de continuación suelto", palabraB("UTF-8", unir(ascii("Caf"), bytes(0x80))), BYTES_UTF8),
                arguments("UTF-8 sobrelargo (C0 AF)", palabraB("UTF-8", unir(ascii("a"), bytes(0xC0, 0xAF))), BYTES_UTF8),
                arguments("UTF-8 con un sustituto codificado (ED A0 80)", palabraB("UTF-8", bytes(0x61, 0xED, 0xA0, 0x80)), BYTES_UTF8),
                arguments("UTF-8 fuera de Unicode (F4 90 80 80)", palabraB("UTF-8", bytes(0x61, 0xF4, 0x90, 0x80, 0x80)), BYTES_UTF8),
                arguments("UTF-8 con el carácter de reemplazo U+FFFD", "=?UTF-8?Q?Caf=EF=BF=BD?=", BYTES_UTF8),
                arguments("Carácter partido que no se completa", "=?UTF-8?Q?a=C3?= =?UTF-8?Q?=28b?=", BYTES_UTF8),
                arguments("US-ASCII con un byte de 8 bits", "=?US-ASCII?Q?Caf=E9?=", bytesInvalidos("US-ASCII")),
                arguments("UTF-16 con número impar de bytes", palabraB("UTF-16BE", unir("HELP".getBytes(StandardCharsets.UTF_16BE), bytes(0x00))),
                        bytesInvalidos("UTF-16BE")),
                arguments("UTF-16 con un sustituto sin pareja",
                        palabraB("UTF-16BE", unir("a".getBytes(StandardCharsets.UTF_16BE), bytes(0xD8, 0x00), "b".getBytes(StandardCharsets.UTF_16BE))),
                        bytesInvalidos("UTF-16BE")),
                arguments("UTF-32 con número de bytes que no es múltiplo de 4", palabraB("UTF-32", bytes(0, 0, 0, 0x41, 0)),
                        bytesInvalidos("UTF-32")),
                arguments("UTF-32 con unidades sustitutas en una sola palabra", "=?UTF-32?B?AADYPQAA3gA=?=", bytesInvalidos("UTF-32")),
                arguments("UTF-32 con un sustituto en cada palabra contigua", "=?UTF-32?B?AADYPQ==?= =?UTF-32?B?AADeAA==?=",
                        bytesInvalidos("UTF-32")),
                arguments("Shift_JIS con un carácter partido entre dos palabras",
                        palabraB("Shift_JIS", bytes(0x82)) + " " + palabraB("Shift_JIS", bytes(0xA0)), bytesInvalidos("SHIFT_JIS")),
                arguments("ISO-2022-JP: una palabra con escape incompleto no se repara con la siguiente",
                        "=?ISO-2022-JP?Q?INSCAT[\"a=1B?= =?ISO-2022-JP?Q?(Bx\",\"y\"]?=", bytesInvalidos("ISO-2022-JP")),
                arguments("ISO-2022-JP: una palabra que termina fuera del modo ASCII (RFC 1468)",
                        "=?ISO-2022-JP?B?SU5TQ0FUWyIbJEI=?= =?ISO-2022-JP?B?Rnw4bBsoQiJd?=", bytesInvalidos("ISO-2022-JP")),
                arguments("ISO-2022-JP: escape a JIS X 0208 al final de la palabra", "INSCAT[\"x =?ISO-2022-JP?B?GyRC?= \"]",
                        bytesInvalidos("ISO-2022-JP")),
                arguments("ISO-2022-JP: una palabra que termina en JIS-Roman (la tilde de la siguiente cambiaría)",
                        "=?ISO-2022-JP?Q?INSCAT[\"=1B(J?= =?ISO-2022-JP?Q?~\",\"x\"]=1B(B?=", bytesInvalidos("ISO-2022-JP")),
                arguments("ISO-2022-JP-2: una palabra que termina en JIS-Roman",
                        "=?ISO-2022-JP-2?Q?INSCAT[\"=1B(J?= =?ISO-2022-JP-2?Q?~\",\"x\"]?=", bytesInvalidos("ISO-2022-JP-2")),
                arguments("ISO-2022-JP: una palabra que termina en katakana (JIS X 0201)", "=?ISO-2022-JP?Q?a=1B(I?=",
                        bytesInvalidos("ISO-2022-JP")),
                arguments("ISO-2022-KR (con estado) no se admite", "=?ISO-2022-KR?B?DolC7Fk=?=", desconocido("ISO-2022-KR")),
                arguments("ISO-2022-CN (con estado; el JDK supone la designación) no se admite", "=?ISO-2022-CN?B?DlZQDw==?=",
                        desconocido("ISO-2022-CN")),
                arguments("windows-1252 con un byte no definido (0x81)", "=?windows-1252?Q?a=81b?=", bytesInvalidos("WINDOWS-1252")),
                arguments("Otra etiqueta de ISO-8859-1 con un byte no definido", "=?l1?Q?a=81b?=", bytesInvalidos("L1")),
                arguments("Juego de caracteres desconocido", "=?x-inventado?Q?HELP[\"*\"]?=", desconocido("x-inventado")),
                arguments("Juego desconocido con etiqueta de idioma", "=?x-otro*es?Q?a?=", desconocido("x-otro")),
                arguments("Juego propio de Java que adivina (autodetección)", "=?x-JISAutoDetect?B?zek=?=", desconocido("x-JISAutoDetect")),
                arguments("Alias de un juego con marca de orden de bytes", "=?UnicodeLittle?B?//5BAA==?=", desconocido("UnicodeLittle")),
                arguments("CESU-8 (admite sustitutos sueltos)", "=?CESU-8?B?7aC9?=", desconocido("CESU-8")),
                arguments("Base64 con caracteres inválidos", "=?UTF-8?B?***?=", "Base64 mal formado"),
                arguments("Base64 con longitud imposible", "=?UTF-8?B?SEVMU?=", "Base64 mal formado"),
                arguments("Base64 con relleno en medio", "=?UTF-8?B?QQ==QQ==?=", "Base64 mal formado"),
                arguments("Base64 vacío", "INSCAT =?UTF-8?B??= [\"a\",\"x\"]", "Base64 mal formado"),
                arguments("Q con hexadecimal inválido", "=?UTF-8?Q?HELP=ZZ?=", "codificación Q mal formada"),
                arguments("Q con un escape cortado", "=?UTF-8?Q?HELP=2?=", "codificación Q mal formada"),
                arguments("Q con dígitos que no son ASCII", "=?UTF-8?Q?a=\uFF11\uFF11?=", "codificación Q mal formada"),
                arguments("Q con un carácter no ASCII sin codificar", "=?UTF-8?Q?café?=", "codificación Q mal formada"),
                arguments("Q vacío", "INSCAT =?UTF-8?Q??= [\"a\",\"x\"]", "codificación Q mal formada"),
                arguments("Palabra válida seguida de otra rota", "=?UTF-8?Q?HELP?= =?UTF-8?B?***?=", "Base64 mal formado"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidas")
    void rechazaLasCodificacionesInvalidasConSuMotivo(String caso, String entrada, String motivo) {
        DecodificadorRfc2047.Resultado r = DecodificadorRfc2047.analizar(entrada);
        assertEquals(motivo, r.error());
        assertFalse(r.valido());
        assertFalse(r.texto().contains("\uFFFD") && !entrada.contains("=EF=BF=BD"), "nunca se inventa U+FFFD: " + r.texto());
        assertEquals(motivo, DecodificadorRfc2047.primerError(entrada));
    }

    static Stream<Arguments> literalesSinError() {
        return Stream.of(
                arguments("Palabra escrita como dato entre comillas", "HELP[\"=?UTF-8?Q?=2A?=\"]"),
                arguments("Palabra pegada al texto anterior", "INSCAT[\"x\"]=?UTF-8?Q?=C3=A9?="),
                arguments("Palabra pegada con juego desconocido", "a=?x-inventado?Q?b?="),
                arguments("Palabra rota pegada al texto", "x=?UTF-8?B?***?=y"),
                arguments("Texto que solo se parece a una palabra", "HELP[\"=?\"]"),
                arguments("Signos sueltos", "=? ?= =?a?"),
                arguments("Espacio dentro de la palabra: no es una palabra codificada", "=?UTF-8?Q?a b?="),
                arguments("Codificación distinta de B y Q", "=?UTF-8?X?abc?="));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("literalesSinError")
    void loQueSoloSeParecaAUnaPalabraQuedaLiteralSinError(String caso, String entrada) {
        DecodificadorRfc2047.Resultado r = DecodificadorRfc2047.analizar(entrada);
        assertEquals(entrada, r.texto());
        assertNull(r.error());
    }

    @Test
    void unaPalabraQueFallaQuedaExactamenteComoLlego() {
        String entrada = "=?UTF-8?B?SU5TQ0FUWyJDYWb/Il0=?=";
        assertEquals(entrada, DecodificadorRfc2047.analizar(entrada).texto());
        assertFalse(DecodificadorRfc2047.decodificar(entrada).contains("\uFFFD"));
    }

    @Test
    void unGrupoQueFallaQuedaLiteralConSusEspaciosInternos() {
        String entrada = "Re: =?UTF-8?Q?a=C3?=  =?UTF-8?Q?=28b?= fin";
        DecodificadorRfc2047.Resultado r = DecodificadorRfc2047.analizar(entrada);
        assertEquals(entrada, r.texto());
        assertEquals(BYTES_UTF8, r.error());
    }

    @Test
    void seInformaElPrimerFalloYLoQueSigueQuedaLiteral() {
        assertEquals(desconocido("x-a"), DecodificadorRfc2047.primerError("=?x-a?Q?a?= =?UTF-8?B?***?="));
        assertEquals(BYTES_UTF8, DecodificadorRfc2047.primerError("=?UTF-8?Q?=FF?= =?UTF-8?Q?=ZZ?="));
        // Después del primer fallo ya no se decodifica nada más, tanto si falla al leer la palabra...
        assertEquals("=?UTF-8?B?***?= =?UTF-8?Q?caf=C3=A9?=", DecodificadorRfc2047.decodificar("=?UTF-8?B?***?= =?UTF-8?Q?caf=C3=A9?="));
        // ...como si falla al convertir sus bytes, que se descubre al llegar la palabra siguiente.
        DecodificadorRfc2047.Resultado diferido = DecodificadorRfc2047.analizar("=?US-ASCII?Q?=FF?= =?UTF-8?Q?caf=C3=A9?=");
        assertEquals("=?US-ASCII?Q?=FF?= =?UTF-8?Q?caf=C3=A9?=", diferido.texto());
        assertEquals(bytesInvalidos("US-ASCII"), diferido.error());
        assertEquals("=?US-ASCII?Q?=FF?= texto =?UTF-8?Q?a?= =?UTF-8?Q?b?=",
                DecodificadorRfc2047.decodificar("=?US-ASCII?Q?=FF?= texto =?UTF-8?Q?a?= =?UTF-8?Q?b?="));
    }

    @Test
    void juntoATextoLiteralSeConservaElEspacio() {
        // Una palabra que falla queda literal y el espacio que la separa de la vecina no se elimina.
        assertEquals("a =?US-ASCII?Q?=FF?=", DecodificadorRfc2047.analizar("=?UTF-8?Q?a?= =?US-ASCII?Q?=FF?=").texto());
        // Con el mismo juego (UTF-8) las dos palabras forman un grupo: si falla, el grupo entero queda literal.
        assertEquals("=?UTF-8?Q?a?= =?UTF-8?Q?=FF?=", DecodificadorRfc2047.analizar("=?UTF-8?Q?a?= =?UTF-8?Q?=FF?=").texto());
    }

    @Test
    void elNombreDeclaradoNoSeRepiteEnElMotivo() {
        // Lo que el remitente escribe como juego de caracteres (podría ser cualquier dato) no vuelve en la respuesta.
        assertEquals("juego de caracteres desconocido o no admitido", DecodificadorRfc2047.primerError("=?AB12CD34?Q?a?="));
        assertEquals("juego de caracteres desconocido o no admitido", DecodificadorRfc2047.primerError("=?" + "x".repeat(60) + "?Q?a?="));
    }

    @Test
    void muchasPalabrasConJuegosDesconocidosSeAnalizanRapido() {
        String enorme = "=?klingon?Q?a?= ".repeat(15_000);
        assertTimeout(Duration.ofSeconds(2), () -> assertEquals(desconocido("klingon"), DecodificadorRfc2047.primerError(enorme)));
    }

    @Test
    void textoNuloOVacio() {
        assertNull(DecodificadorRfc2047.analizar(null).texto());
        assertNull(DecodificadorRfc2047.analizar(null).error());
        assertEquals("", DecodificadorRfc2047.analizar("").texto());
    }
}
