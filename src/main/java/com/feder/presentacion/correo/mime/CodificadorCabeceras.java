package com.feder.presentacion.correo.mime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Codificación de valores de cabecera de salida: RFC 2047 para texto no ASCII y control de inyección.
 */
public final class CodificadorCabeceras {

    // 39 bytes generan 52 caracteres Base64; con "=?UTF-8?B?" y "?=" la palabra ocupa 64,
    // así cada línea plegada queda por debajo de los 78 caracteres recomendados por RFC 5322.
    private static final int BYTES_POR_PALABRA = 39;
    private static final int MAXIMO_LINEA = 998;

    private CodificadorCabeceras() {
    }

    /** Rechaza CR y LF: permitirían a un usuario añadir cabeceras propias (inyección de cabeceras). */
    static String exigirSinSaltos(String valor, String campo) {
        if (valor != null && (valor.indexOf('\r') >= 0 || valor.indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("La cabecera " + campo + " no puede contener CR ni LF");
        }
        return valor;
    }

    /** Valor de un campo no estructurado (Subject): tal cual si es ASCII imprimible, si no en encoded-words. */
    public static String textoNoEstructurado(String texto, String campo) {
        exigirSinSaltos(texto, campo);
        if (esAsciiImprimible(texto) && !texto.contains("=?") && campo.length() + 2 + texto.length() <= MAXIMO_LINEA) {
            return texto;
        }
        return String.join("\r\n ", palabrasCodificadas(texto));
    }

    /** Buzón para From: "Nombre" &lt;dirección&gt;, con el nombre entre comillas o codificado. */
    static String buzon(String nombre, String direccion) {
        if (nombre == null || nombre.isBlank()) {
            return "<" + direccion + ">";
        }
        exigirSinSaltos(nombre, "From");
        String frase = esAsciiImprimible(nombre)
                ? "\"" + nombre.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : String.join(" ", palabrasCodificadas(nombre));
        return frase + " <" + direccion + ">";
    }

    private static List<String> palabrasCodificadas(String texto) {
        List<String> palabras = new ArrayList<>();
        StringBuilder trozo = new StringBuilder();
        int bytesTrozo = 0;
        for (int i = 0; i < texto.length(); ) {
            int cp = texto.codePointAt(i);
            String caracter = new String(Character.toChars(cp));
            int bytes = caracter.getBytes(StandardCharsets.UTF_8).length;
            // Se corta entre caracteres completos: un carácter UTF-8 nunca queda partido entre dos palabras.
            if (bytesTrozo + bytes > BYTES_POR_PALABRA && trozo.length() > 0) {
                palabras.add(codificar(trozo.toString()));
                trozo.setLength(0);
                bytesTrozo = 0;
            }
            trozo.append(caracter);
            bytesTrozo += bytes;
            i += Character.charCount(cp);
        }
        if (trozo.length() > 0 || palabras.isEmpty()) {
            palabras.add(codificar(trozo.toString()));
        }
        return palabras;
    }

    private static String codificar(String trozo) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(trozo.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static boolean esAsciiImprimible(String texto) {
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }
}
