package com.feder.presentacion.comandos;

/**
 * Analizador léxico del asunto, implementado como máquina de estados sobre puntos de código Unicode.
 *
 * Entrega los tokens uno a uno a pedido del analizador sintáctico; así los errores se informan
 * en el orden en que aparecen en el texto. Las posiciones se cuentan desde 1, por carácter.
 */
final class LexerComando {

    enum Tipo { NOMBRE, ABRE, CIERRA, COMA, CADENA, OTRO, FIN }

    record Token(Tipo tipo, String valor, int posicion) {

        /** Descripción para los mensajes de error ("se encontró ..."). */
        String describir() {
            return switch (tipo) {
                case FIN -> "el final del texto";
                case CADENA -> "un texto entre comillas";
                default -> "'" + valor + "'";
            };
        }
    }

    private enum Estado { ENTRE_TOKENS, EN_NOMBRE, EN_CADENA, EN_ESCAPE }

    private static final int REEMPLAZO = 0xFFFD;

    private final String texto;
    private int indice;
    private int posicion = 1;

    LexerComando(String texto) {
        this.texto = texto;
    }

    Token siguiente() throws ErrorSintaxisException {
        Estado estado = Estado.ENTRE_TOKENS;
        StringBuilder valor = new StringBuilder();
        int inicio = posicion;
        int posicionEscape = 0;
        while (indice < texto.length()) {
            int cp = texto.codePointAt(indice);
            if (estado == Estado.ENTRE_TOKENS) {
                if (cp == ' ' || cp == '\t') {
                    avanzar(cp);
                    continue;
                }
                inicio = posicion;
                if (esLetraAscii(cp)) {
                    estado = Estado.EN_NOMBRE;
                    valor.appendCodePoint(cp);
                } else if (cp == '"') {
                    estado = Estado.EN_CADENA;
                } else if (cp == REEMPLAZO) {
                    throw textoDanado(posicion);
                } else if (esNoPermitido(cp)) {
                    throw new ErrorSintaxisException("Carácter de control no permitido (" + codigo(cp)
                            + ") en la posición " + posicion, posicion);
                } else {
                    avanzar(cp);
                    return simbolo(cp, inicio);
                }
                avanzar(cp);
            } else if (estado == Estado.EN_NOMBRE) {
                if (!esLetraAscii(cp)) {
                    // El carácter que corta el nombre no se consume: será el inicio del próximo token.
                    return new Token(Tipo.NOMBRE, valor.toString(), inicio);
                }
                valor.appendCodePoint(cp);
                avanzar(cp);
            } else if (estado == Estado.EN_CADENA) {
                if (cp == '"') {
                    avanzar(cp);
                    return new Token(Tipo.CADENA, valor.toString(), inicio);
                }
                if (cp == '\\') {
                    estado = Estado.EN_ESCAPE;
                    posicionEscape = posicion;
                } else if (cp == REEMPLAZO) {
                    throw textoDanado(posicion);
                } else if (esNoPermitido(cp)) {
                    throw new ErrorSintaxisException("Carácter de control no permitido (" + codigo(cp)
                            + ") dentro del parámetro, en la posición " + posicion, posicion);
                } else {
                    valor.appendCodePoint(cp);
                }
                avanzar(cp);
            } else {
                if (cp != '"' && cp != '\\') {
                    String visto = esNoPermitido(cp) ? codigo(cp) : new String(Character.toChars(cp));
                    throw new ErrorSintaxisException("Secuencia de escape no válida '\\" + visto + "' en la posición "
                            + posicionEscape + "; solo se admiten \\\" y \\\\", posicionEscape);
                }
                valor.appendCodePoint(cp);
                estado = Estado.EN_CADENA;
                avanzar(cp);
            }
        }
        if (estado == Estado.EN_NOMBRE) {
            return new Token(Tipo.NOMBRE, valor.toString(), inicio);
        }
        if (estado == Estado.EN_CADENA || estado == Estado.EN_ESCAPE) {
            throw new ErrorSintaxisException("Falta la comilla de cierre del parámetro que empieza en la posición "
                    + inicio, inicio);
        }
        return new Token(Tipo.FIN, "", posicion);
    }

    private void avanzar(int cp) {
        indice += Character.charCount(cp);
        posicion++;
    }

    private static Token simbolo(int cp, int posicion) {
        return switch (cp) {
            case '[' -> new Token(Tipo.ABRE, "[", posicion);
            case ']' -> new Token(Tipo.CIERRA, "]", posicion);
            case ',' -> new Token(Tipo.COMA, ",", posicion);
            default -> new Token(Tipo.OTRO, new String(Character.toChars(cp)), posicion);
        };
    }

    private static boolean esLetraAscii(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z');
    }

    /**
     * U+FFFD es el carácter que pone un programa cuando no pudo leer unos bytes: nunca es un dato legítimo.
     * Si llega, el texto se dañó antes (por ejemplo en otro servidor) y no debe guardarse.
     */
    private static ErrorSintaxisException textoDanado(int posicion) {
        return new ErrorSintaxisException("El asunto contiene el carácter de reemplazo U+FFFD en la posición " + posicion
                + ": el texto llegó dañado por un problema de codificación. Escriba de nuevo el comando", posicion);
    }

    /** Controles C0, DEL, controles C1 (U+0080-U+009F) y sustitutos sueltos (texto UTF-16 corrupto). */
    private static boolean esNoPermitido(int cp) {
        return cp <= 0x1F || (cp >= 0x7F && cp <= 0x9F) || (cp >= 0xD800 && cp <= 0xDFFF);
    }

    private static String codigo(int cp) {
        return String.format("U+%04X", cp);
    }
}
