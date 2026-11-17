package com.feder.presentacion.correo.mime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodificación de "encoded-words" de RFC 2047 (=?charset?B|Q?texto?=).
 *
 * Política: nunca se adivinan caracteres.
 * <ul>
 *   <li>Una palabra solo se decodifica si está separada del texto vecino por espacios (RFC 2047 §5). Pegada a
 *       otro texto (p. ej. dentro de comillas: HELP["=?UTF-8?Q?=2A?="]) se deja literal y no es un error.</li>
 *   <li>Los espacios entre palabras codificadas contiguas se eliminan (§6.2).</li>
 *   <li>Cada palabra se decodifica por separado (§5: cada una contiene caracteres completos). Única tolerancia:
 *       las palabras UTF-8 contiguas se unen a nivel de bytes, porque algunos clientes parten un carácter
 *       UTF-8 entre dos palabras; en UTF-8 la unión no puede cambiar el significado de lo demás.</li>
 *   <li>Los bytes se convierten a texto con un {@code CharsetDecoder} en modo REPORT, y el resultado debe ser
 *       texto Unicode bien formado (sin sustitutos sueltos ni el carácter de reemplazo U+FFFD). Cualquier
 *       secuencia inválida para el juego declarado es un error y nunca se sustituye.</li>
 *   <li>Solo se admiten juegos de caracteres estándar: los propios de Java ({@code x-...}: autodetección,
 *       variantes con marca de orden de bytes) y CESU-8 se tratan como desconocidos.</li>
 *   <li>Una palabra que no puede decodificarse (juego desconocido, Base64 o Q mal formados o vacíos, bytes
 *       inválidos) queda literal, y {@link Resultado#error()} informa el motivo del primer fallo. Desde ese
 *       punto el resto del texto queda literal: quien ejecuta comandos debe rechazarlo, y quien solo muestra
 *       un nombre puede usar el texto tal cual ({@link #decodificar}).</li>
 * </ul>
 * Tolerancias de compatibilidad, todas sin ambigüedad: relleno '=' ausente en Base64, hexadecimal en
 * minúsculas en Q, sufijo de idioma de RFC 2231 (UTF-8*es), alias utf8, y cualquier etiqueta de ISO-8859-1
 * (latin1, l1, cp819...) se lee como windows-1252, igual que los navegadores (WHATWG): los clientes de Windows
 * etiquetan así las comillas tipográficas y el símbolo €. Los cinco bytes que windows-1252 no define (0x81,
 * 0x8D, 0x8F, 0x90, 0x9D) son controles y se informan como error.
 */
public final class DecodificadorRfc2047 {

    /**
     * Resultado de analizar un texto.
     *
     * @param texto texto con las palabras decodificadas; las que fallaron (y todo lo que sigue al primer fallo)
     *              quedan literales
     * @param error motivo del primer fallo, o null si todas las palabras se decodificaron
     */
    public record Resultado(String texto, String error) {

        public boolean valido() {
            return error == null;
        }
    }

    private static final Pattern PALABRA = Pattern.compile("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=");
    private static final Pattern SOLO_ESPACIOS = Pattern.compile("[ \\t\\r\\n]*");
    private static final Charset WINDOWS_1252 = cargarWindows1252();
    private static final int MAXIMO_NOMBRE_VISIBLE = 40;

    private DecodificadorRfc2047() {
    }

    /** Texto decodificado; las palabras que no pueden decodificarse quedan literales (uso tolerante). */
    public static String decodificar(String texto) {
        return analizar(texto).texto();
    }

    /** Motivo del primer fallo de decodificación, o null si el texto se decodificó por completo. */
    public static String primerError(String texto) {
        return analizar(texto).error();
    }

    public static Resultado analizar(String texto) {
        if (texto == null || !texto.contains("=?")) {
            return new Resultado(texto, null);
        }
        Analisis analisis = new Analisis(texto);
        int ultimoFin = 0;
        Matcher m = PALABRA.matcher(texto);
        while (m.find()) {
            String intermedio = texto.substring(ultimoFin, m.start());
            ultimoFin = m.end();
            // RFC 2047 §5: una palabra codificada debe estar separada del texto vecino por espacios.
            // Si está pegada se deja literal, así el comando que llega al analizador es el que escribió el usuario.
            boolean delimitada = (m.start() == 0 || esEspacio(texto.charAt(m.start() - 1)))
                    && (m.end() == texto.length() || esEspacio(texto.charAt(m.end())));
            // Tras el primer fallo el texto ya no es confiable: el resto queda literal (y el coste, acotado).
            if (!delimitada || analisis.hayError()) {
                analisis.cerrarGrupo();
                analisis.literal(intermedio);
                analisis.literal(m.group());
                continue;
            }
            Charset charset = charset(m.group(1));
            byte[] bytes = charset == null ? null : decodificarBytes(m.group(2), m.group(3));
            boolean contigua = analisis.hayGrupo() && SOLO_ESPACIOS.matcher(intermedio).matches();
            if (bytes == null) {
                analisis.cerrarGrupo();
                analisis.literal(intermedio);
                analisis.literal(m.group());
                // El nombre declarado no se repite en el motivo: el asunto rechazado no debe reaparecer en la respuesta.
                analisis.registrar(charset == null ? "juego de caracteres desconocido o no admitido"
                        : m.group(2).equalsIgnoreCase("B") ? "Base64 mal formado" : "codificación Q mal formada");
            } else if (contigua && analisis.admiteUnion(charset)) {
                analisis.ampliarGrupo(bytes, m.end());
            } else {
                boolean anteriorDecodificada = analisis.cerrarGrupo();
                if (analisis.hayError()) {
                    // El grupo anterior acaba de fallar al cerrarse: desde aquí todo queda literal.
                    analisis.literal(intermedio);
                    analisis.literal(m.group());
                    continue;
                }
                // Entre dos palabras decodificadas el espacio se elimina (§6.2); junto a texto literal se conserva.
                // Si este grupo termina sin poder decodificarse, al cerrarlo el espacio se conserva.
                analisis.iniciarGrupo(charset, visible(nombreDeclarado(m.group(1))).toUpperCase(Locale.ROOT),
                        bytes, m.start(), m.end(), intermedio, contigua && anteriorDecodificada);
            }
        }
        analisis.cerrarGrupo();
        analisis.literal(texto.substring(ultimoFin));
        return analisis.resultado();
    }

    /** Estado de un análisis: texto producido, primer error y el grupo de palabras pendiente. */
    private static final class Analisis {
        private final String fuente;
        private final StringBuilder texto;
        private final ByteArrayOutputStream bytesGrupo = new ByteArrayOutputStream();
        private String error;
        private Charset charsetGrupo;
        private String nombreGrupo;
        private int inicioGrupo;
        private int finGrupo;
        private String separador = "";
        private boolean separadorEliminable;

        Analisis(String fuente) {
            this.fuente = fuente;
            this.texto = new StringBuilder(fuente.length());
        }

        boolean hayGrupo() {
            return charsetGrupo != null;
        }

        boolean hayError() {
            return error != null;
        }

        /** Solo las palabras UTF-8 contiguas se unen (carácter partido entre dos palabras); las demás, nunca. */
        boolean admiteUnion(Charset charset) {
            return charset.equals(StandardCharsets.UTF_8) && charset.equals(charsetGrupo);
        }

        /**
         * @param separador           texto entre la palabra anterior y esta
         * @param separadorEliminable el separador son solo espacios tras una palabra decodificada: se quita si
         *                            este grupo también se decodifica (§6.2)
         */
        void iniciarGrupo(Charset charset, String nombre, byte[] bytes, int inicio, int fin,
                          String separador, boolean separadorEliminable) {
            charsetGrupo = charset;
            nombreGrupo = nombre;
            inicioGrupo = inicio;
            finGrupo = fin;
            this.separador = separador;
            this.separadorEliminable = separadorEliminable;
            bytesGrupo.reset();
            bytesGrupo.writeBytes(bytes);
        }

        void ampliarGrupo(byte[] bytes, int fin) {
            bytesGrupo.writeBytes(bytes);
            finGrupo = fin;
        }

        /** Decodifica el grupo pendiente. Devuelve true si se convirtió en texto y false si quedó literal o no había. */
        boolean cerrarGrupo() {
            if (charsetGrupo == null) {
                return false;
            }
            String decodificado = decodificarEstricto(charsetGrupo, bytesGrupo.toByteArray());
            if (decodificado != null) {
                if (!separadorEliminable) {
                    literal(separador);
                }
                texto.append(decodificado);
            } else {
                // Nunca se adivina: el grupo completo (con sus espacios internos) queda como lo escribió el cliente.
                literal(separador);
                texto.append(fuente, inicioGrupo, finGrupo);
                registrar("los bytes no son texto válido en el juego de caracteres declarado (" + nombreGrupo + ")");
            }
            charsetGrupo = null;
            bytesGrupo.reset();
            return decodificado != null;
        }

        void literal(String parte) {
            texto.append(parte);
        }

        void registrar(String motivo) {
            if (error == null) {
                error = motivo;
            }
        }

        Resultado resultado() {
            return new Resultado(texto.toString(), error);
        }
    }

    /**
     * Texto de los bytes en el juego indicado, o null si no son texto válido. Además del modo REPORT se exige
     * Unicode bien formado: algunos decodificadores del JDK (UTF-32, ISO-2022-KR) entregan sustitutos sueltos o
     * U+FFFD sin informar error.
     */
    private static String decodificarEstricto(Charset charset, byte[] bytes) {
        if (!unidadesUtf32Validas(charset, bytes)) {
            return null;
        }
        String texto = decodificarReport(charset, bytes);
        if (texto == null || !terminaEnAscii(charset, bytes, texto)) {
            return null;
        }
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '\uFFFD') {
                return null;
            }
            if (Character.isHighSurrogate(c) && i + 1 < texto.length() && Character.isLowSurrogate(texto.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return null;
            }
        }
        return texto;
    }

    private static String decodificarReport(Charset charset, byte[] bytes) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * ISO-2022-JP tiene estado: RFC 1468 (y RFC 2047 §5) exige que cada palabra termine en ASCII. Si terminara en
     * otro modo, la palabra siguiente, que se decodifica desde ASCII, se leería distinto de lo que escribió el
     * usuario. Se comprueba agregando los bytes de "A", la barra invertida y "~": solo en modo ASCII se decodifican
     * igual. En JIS-Roman la barra y la tilde son ¥ y ‾; en JIS X 0208 queda un byte suelto; en katakana, otros
     * caracteres.
     */
    private static boolean terminaEnAscii(Charset charset, byte[] bytes, String texto) {
        if (!charset.name().toUpperCase(Locale.ROOT).startsWith("ISO-2022-JP")) {
            return true;
        }
        byte[] prueba = {0x41, 0x5C, 0x7E};
        byte[] conPrueba = new byte[bytes.length + prueba.length];
        System.arraycopy(bytes, 0, conPrueba, 0, bytes.length);
        System.arraycopy(prueba, 0, conPrueba, bytes.length, prueba.length);
        String esperado = texto + new String(prueba, StandardCharsets.US_ASCII);
        return esperado.equals(decodificarReport(charset, conPrueba));
    }

    /**
     * El decodificador UTF-32 del JDK acepta unidades en el rango de los sustitutos (D800-DFFF), que en UTF-32 son
     * inválidas (Unicode D90): dos de ellas seguidas se convertirían en un carácter que nadie escribió.
     */
    private static boolean unidadesUtf32Validas(Charset charset, byte[] bytes) {
        String nombre = charset.name().toUpperCase(Locale.ROOT);
        if (!nombre.startsWith("UTF-32")) {
            return true;
        }
        boolean bigEndian = !nombre.endsWith("LE");
        int inicio = 0;
        if (nombre.equals("UTF-32") && bytes.length >= 4) {
            if (bytes[0] == 0 && bytes[1] == 0 && (bytes[2] & 0xFF) == 0xFE && (bytes[3] & 0xFF) == 0xFF) {
                inicio = 4;
            } else if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE && bytes[2] == 0 && bytes[3] == 0) {
                bigEndian = false;
                inicio = 4;
            }
        }
        if ((bytes.length - inicio) % 4 != 0) {
            return false;
        }
        for (int i = inicio; i < bytes.length; i += 4) {
            long unidad = bigEndian
                    ? ((bytes[i] & 0xFFL) << 24) | ((bytes[i + 1] & 0xFF) << 16) | ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF)
                    : ((bytes[i + 3] & 0xFFL) << 24) | ((bytes[i + 2] & 0xFF) << 16) | ((bytes[i + 1] & 0xFF) << 8) | (bytes[i] & 0xFF);
            if ((unidad >= 0xD800 && unidad <= 0xDFFF) || unidad > 0x10FFFF) {
                return false;
            }
        }
        return true;
    }

    private static boolean esEspacio(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    /** Bytes de la palabra, o null si su contenido está vacío (RFC 2047 §2 exige al menos un carácter) o mal formado. */
    private static byte[] decodificarBytes(String codificacion, String datos) {
        if (datos.isEmpty()) {
            return null;
        }
        return codificacion.equalsIgnoreCase("B") ? decodificarB(datos) : decodificarQ(datos);
    }

    private static byte[] decodificarB(String datos) {
        String completo = datos;
        // Algunos clientes omiten el relleno '='; lo restituimos para que el decodificador estricto lo acepte.
        // Una longitud imposible (resto 1 al dividir por 4) sigue siendo inválida.
        while (completo.length() % 4 != 0) {
            completo += "=";
        }
        try {
            return Base64.getDecoder().decode(completo);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] decodificarQ(String datos) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(datos.length());
        for (int i = 0; i < datos.length(); i++) {
            char c = datos.charAt(i);
            if (c == '_') {
                bytes.write(' ');
            } else if (c == '=') {
                if (i + 2 >= datos.length()) {
                    return null;
                }
                int alto = hexadecimal(datos.charAt(i + 1));
                int bajo = hexadecimal(datos.charAt(i + 2));
                if (alto < 0 || bajo < 0) {
                    return null;
                }
                bytes.write(alto * 16 + bajo);
                i += 2;
            } else if (c > 0x20 && c < 0x7F) {
                bytes.write(c);
            } else {
                return null;
            }
        }
        return bytes.toByteArray();
    }

    /** Dígito hexadecimal ASCII (se aceptan minúsculas por tolerancia); cualquier otro carácter es inválido. */
    private static int hexadecimal(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        return -1;
    }

    /** Nombre del juego sin el sufijo de idioma de RFC 2231 (p. ej. "UTF-8*es" → "UTF-8"). */
    private static String nombreDeclarado(String declarado) {
        return declarado.split("\\*", 2)[0].trim();
    }

    /** El nombre declarado se repite en la respuesta al usuario: solo ASCII visible y de longitud acotada. */
    private static String visible(String nombre) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nombre.length() && sb.length() < MAXIMO_NOMBRE_VISIBLE; i++) {
            char c = nombre.charAt(i);
            sb.append(c > 0x20 && c < 0x7F ? c : '?');
        }
        return nombre.length() > MAXIMO_NOMBRE_VISIBLE ? sb + "…" : sb.toString();
    }

    /** Devuelve el juego de caracteres declarado, o null si es desconocido o no se admite (la palabra queda literal). */
    private static Charset charset(String declarado) {
        // RFC 2231 permite añadir el idioma tras '*' (p. ej. UTF-8*es); el idioma no afecta la decodificación.
        String nombre = nombreDeclarado(declarado).toLowerCase(Locale.ROOT);
        Charset charset;
        switch (nombre) {
            case "utf-8", "utf8":
                return StandardCharsets.UTF_8;
            case "us-ascii":
                return StandardCharsets.US_ASCII;
            case "windows-1252", "cp1252":
                return WINDOWS_1252;
            default:
                try {
                    charset = Charset.forName(nombre);
                } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
                    return null;
                }
        }
        if (charset.equals(StandardCharsets.ISO_8859_1)) {
            // Cualquier etiqueta de ISO-8859-1 (iso-8859-1, latin1, l1, cp819...): los clientes de Windows etiquetan así
            // texto que en realidad es windows-1252 (comillas tipográficas, €), que coincide en los caracteres imprimibles.
            return WINDOWS_1252;
        }
        String canonico = charset.name();
        if (canonico.equalsIgnoreCase("x-windows-874")) {
            // windows-874 (tailandés) es una etiqueta estándar (IANA, WHATWG); Java solo le da un nombre propio.
            return charset;
        }
        // Los juegos propios de Java (x-JISAutoDetect adivina; x-UTF-16LE-BOM y similares añaden marcas), CESU-8
        // (admite sustitutos sueltos) e ISO-2022-KR/CN (con estado; el JDK supone designaciones que faltan) no se admiten.
        if (canonico.regionMatches(true, 0, "x-", 0, 2) || canonico.equalsIgnoreCase("CESU-8")
                || canonico.toUpperCase(Locale.ROOT).startsWith("ISO-2022-KR")
                || canonico.toUpperCase(Locale.ROOT).startsWith("ISO-2022-CN")) {
            return null;
        }
        return charset;
    }

    private static Charset cargarWindows1252() {
        try {
            return Charset.forName("windows-1252");
        } catch (UnsupportedCharsetException e) {
            // Todo JDK completo trae windows-1252; si faltara, ISO-8859-1 es la mejor aproximación.
            return StandardCharsets.ISO_8859_1;
        }
    }
}
