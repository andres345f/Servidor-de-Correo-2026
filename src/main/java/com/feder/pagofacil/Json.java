package com.feder.pagofacil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lector y escritor JSON mínimo para las respuestas de PagoFácil (RFC 8259), sin dependencias externas.
 * Objetos → Map (en orden), arreglos → List, números → BigDecimal, true/false → Boolean, null → null.
 */
final class Json {

    private final String texto;
    private int pos;

    private Json(String texto) {
        this.texto = texto;
    }

    /** Interpreta un documento JSON completo. */
    static Object leer(String texto) {
        if (texto == null) {
            throw new IllegalArgumentException("Respuesta JSON vacía");
        }
        Json lector = new Json(texto);
        Object valor = lector.valor();
        lector.espacios();
        if (lector.pos != texto.length()) {
            throw lector.error("contenido sobrante después del JSON");
        }
        return valor;
    }

    /** Cadena JSON entre comillas, con los caracteres especiales escapados. */
    static String texto(String valor) {
        if (valor == null) {
            return "null";
        }
        StringBuilder s = new StringBuilder("\"");
        for (char c : valor.toCharArray()) {
            switch (c) {
                case '"' -> s.append("\\\"");
                case '\\' -> s.append("\\\\");
                case '\n' -> s.append("\\n");
                case '\r' -> s.append("\\r");
                case '\t' -> s.append("\\t");
                default -> {
                    if (c < 0x20) {
                        s.append(String.format("\\u%04x", (int) c));
                    } else {
                        s.append(c);
                    }
                }
            }
        }
        return s.append('"').toString();
    }

    private Object valor() {
        espacios();
        if (pos >= texto.length()) {
            throw error("se esperaba un valor");
        }
        char c = texto.charAt(pos);
        return switch (c) {
            case '{' -> objeto();
            case '[' -> arreglo();
            case '"' -> cadena();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> numero();
        };
    }

    private Map<String, Object> objeto() {
        Map<String, Object> mapa = new LinkedHashMap<>();
        pos++;
        espacios();
        if (consumir('}')) {
            return mapa;
        }
        do {
            espacios();
            if (pos >= texto.length() || texto.charAt(pos) != '"') {
                throw error("se esperaba el nombre de un campo");
            }
            String clave = cadena();
            espacios();
            if (!consumir(':')) {
                throw error("se esperaba ':'");
            }
            mapa.put(clave, valor());
            espacios();
        } while (consumir(','));
        if (!consumir('}')) {
            throw error("se esperaba '}'");
        }
        return mapa;
    }

    private List<Object> arreglo() {
        List<Object> lista = new ArrayList<>();
        pos++;
        espacios();
        if (consumir(']')) {
            return lista;
        }
        do {
            lista.add(valor());
            espacios();
        } while (consumir(','));
        if (!consumir(']')) {
            throw error("se esperaba ']'");
        }
        return lista;
    }

    private String cadena() {
        pos++;
        StringBuilder s = new StringBuilder();
        while (pos < texto.length()) {
            char c = texto.charAt(pos++);
            if (c == '"') {
                return s.toString();
            }
            if (c != '\\') {
                s.append(c);
                continue;
            }
            if (pos >= texto.length()) {
                break;
            }
            char e = texto.charAt(pos++);
            switch (e) {
                case '"', '\\', '/' -> s.append(e);
                case 'b' -> s.append('\b');
                case 'f' -> s.append('\f');
                case 'n' -> s.append('\n');
                case 'r' -> s.append('\r');
                case 't' -> s.append('\t');
                case 'u' -> {
                    if (pos + 4 > texto.length()) {
                        throw error("escape \\u incompleto");
                    }
                    try {
                        s.append((char) Integer.parseInt(texto.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("escape \\u inválido");
                    }
                    pos += 4;
                }
                default -> throw error("escape inválido \\" + e);
            }
        }
        throw error("cadena sin cerrar");
    }

    private BigDecimal numero() {
        int inicio = pos;
        while (pos < texto.length() && "+-0123456789.eE".indexOf(texto.charAt(pos)) >= 0) {
            pos++;
        }
        if (inicio == pos) {
            throw error("carácter inesperado '" + texto.charAt(pos) + "'");
        }
        try {
            return new BigDecimal(texto.substring(inicio, pos));
        } catch (NumberFormatException ex) {
            throw error("número inválido");
        }
    }

    private Object literal(String palabra, Object valor) {
        if (!texto.startsWith(palabra, pos)) {
            throw error("se esperaba " + palabra);
        }
        pos += palabra.length();
        return valor;
    }

    private boolean consumir(char c) {
        if (pos < texto.length() && texto.charAt(pos) == c) {
            pos++;
            return true;
        }
        return false;
    }

    private void espacios() {
        while (pos < texto.length() && Character.isWhitespace(texto.charAt(pos))) {
            pos++;
        }
    }

    private IllegalArgumentException error(String motivo) {
        return new IllegalArgumentException("JSON mal formado en la posición " + pos + ": " + motivo);
    }
}
