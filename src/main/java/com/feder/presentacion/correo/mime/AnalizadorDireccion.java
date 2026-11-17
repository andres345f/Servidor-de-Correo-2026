package com.feder.presentacion.correo.mime;

import com.feder.presentacion.correo.ValidadorDireccion;
import java.util.Locale;

/**
 * Extrae la dirección y el nombre de una cabecera de buzón único ("Nombre &lt;a@b&gt;" o "a@b").
 *
 * Respeta comillas y comentarios entre paréntesis. Devuelve null si hay varias direcciones,
 * sintaxis de grupo o una dirección no válida: ante la duda no se adivina el remitente.
 */
final class AnalizadorDireccion {

    record Direccion(String direccion, String nombre) {
    }

    private AnalizadorDireccion() {
    }

    static Direccion analizar(String valor) {
        if (valor == null) {
            return null;
        }
        String texto = valor.strip();
        int abre = -1;
        int cierra = -1;
        boolean enComillas = false;
        int comentario = 0;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (enComillas || comentario > 0) {
                if (c == '\\') {
                    i++;
                } else if (enComillas && c == '"') {
                    enComillas = false;
                } else if (!enComillas && c == '(') {
                    comentario++;
                } else if (!enComillas && c == ')') {
                    comentario--;
                }
                continue;
            }
            boolean dentroDeAngulos = abre >= 0 && cierra < 0;
            if (c == '"') {
                enComillas = true;
            } else if (c == '(' && !dentroDeAngulos) {
                comentario = 1;
            } else if (c == '<') {
                if (abre >= 0) {
                    return null;
                }
                abre = i;
            } else if (c == '>') {
                if (!dentroDeAngulos) {
                    return null;
                }
                cierra = i;
            } else if ((c == ',' || c == ';' || c == ':') && !dentroDeAngulos) {
                // Coma: varias direcciones. Dos puntos o punto y coma: sintaxis de grupo.
                return null;
            }
        }
        if (enComillas || comentario > 0 || (abre >= 0 && cierra < 0)) {
            return null;
        }
        String direccion;
        String nombre = null;
        if (abre >= 0) {
            if (!quitarComentarios(texto.substring(cierra + 1)).isBlank()) {
                return null;
            }
            direccion = texto.substring(abre + 1, cierra).strip();
            nombre = limpiarNombre(texto.substring(0, abre));
        } else {
            direccion = quitarComentarios(texto).strip();
        }
        if (!ValidadorDireccion.esValida(direccion)) {
            return null;
        }
        // Se conserva la dirección tal como la escribió el remitente: las comparaciones del sistema no
        // distinguen mayúsculas, pero la respuesta debe ir exactamente al buzón de origen.
        return new Direccion(direccion, nombre);
    }

    /** Quita comillas y escapes, decodifica RFC 2047 y reemplaza caracteres de control. */
    private static String limpiarNombre(String crudo) {
        String sinComentarios = quitarComentarios(crudo);
        StringBuilder nombre = new StringBuilder(sinComentarios.length());
        for (int i = 0; i < sinComentarios.length(); i++) {
            char c = sinComentarios.charAt(i);
            if (c == '\\' && i + 1 < sinComentarios.length()) {
                nombre.append(sinComentarios.charAt(++i));
            } else if (c != '"') {
                nombre.append(c);
            }
        }
        String decodificado = DecodificadorRfc2047.decodificar(nombre.toString());
        String limpio = decodificado.replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static String quitarComentarios(String texto) {
        StringBuilder sin = new StringBuilder(texto.length());
        int nivel = 0;
        boolean enComillas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '\\' && i + 1 < texto.length()) {
                if (nivel == 0) {
                    sin.append(c).append(texto.charAt(i + 1));
                }
                i++;
            } else if (enComillas) {
                enComillas = c != '"';
                sin.append(c);
            } else if (c == '(') {
                nivel++;
            } else if (c == ')' && nivel > 0) {
                nivel--;
            } else if (nivel == 0) {
                enComillas = c == '"';
                sin.append(c);
            }
        }
        return sin.toString();
    }
}
