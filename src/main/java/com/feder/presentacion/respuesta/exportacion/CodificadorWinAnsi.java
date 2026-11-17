package com.feder.presentacion.respuesta.exportacion;

import java.io.ByteArrayOutputStream;
import java.util.Map;

/**
 * Codifica texto en WinAnsiEncoding (Windows-1252), la codificación de las fuentes estándar del PDF.
 * Así las vocales acentuadas, la ñ y los signos de apertura del español se ven correctamente.
 * Un carácter que WinAnsi no puede representar se reemplaza por '?'.
 */
final class CodificadorWinAnsi {

    private static final byte REEMPLAZO = '?';

    // Caracteres del rango 0x80-0x9F de Windows-1252, que no coinciden con Latin-1.
    private static final Map<Integer, Integer> ESPECIALES = Map.ofEntries(
            Map.entry(0x20AC, 0x80), Map.entry(0x201A, 0x82), Map.entry(0x0192, 0x83), Map.entry(0x201E, 0x84),
            Map.entry(0x2026, 0x85), Map.entry(0x2020, 0x86), Map.entry(0x2021, 0x87), Map.entry(0x02C6, 0x88),
            Map.entry(0x2030, 0x89), Map.entry(0x0160, 0x8A), Map.entry(0x2039, 0x8B), Map.entry(0x0152, 0x8C),
            Map.entry(0x017D, 0x8E), Map.entry(0x2018, 0x91), Map.entry(0x2019, 0x92), Map.entry(0x201C, 0x93),
            Map.entry(0x201D, 0x94), Map.entry(0x2022, 0x95), Map.entry(0x2013, 0x96), Map.entry(0x2014, 0x97),
            Map.entry(0x02DC, 0x98), Map.entry(0x2122, 0x99), Map.entry(0x0161, 0x9A), Map.entry(0x203A, 0x9B),
            Map.entry(0x0153, 0x9C), Map.entry(0x017E, 0x9E), Map.entry(0x0178, 0x9F));

    private CodificadorWinAnsi() {
    }

    /** Byte WinAnsi del punto de código, o '?' si no existe. Tabuladores y saltos de línea pasan a espacio. */
    static int codificar(int puntoCodigo) {
        if (puntoCodigo == '\t' || puntoCodigo == '\n' || puntoCodigo == '\r') {
            return ' ';
        }
        if ((puntoCodigo >= 0x20 && puntoCodigo <= 0x7E) || (puntoCodigo >= 0xA0 && puntoCodigo <= 0xFF)) {
            return puntoCodigo;
        }
        return ESPECIALES.getOrDefault(puntoCodigo, (int) REEMPLAZO);
    }

    static byte[] codificar(String texto) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(texto.length());
        texto.codePoints().forEach(cp -> bytes.write(codificar(cp)));
        return bytes.toByteArray();
    }

    /**
     * Cadena literal de PDF: (texto) con los caracteres ( ) y \ escapados. Los bytes altos de WinAnsi se
     * escriben tal cual, lo que la sintaxis de cadenas literales permite.
     */
    static byte[] cadenaPdf(String texto) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream(texto.length() + 8);
        salida.write('(');
        for (byte b : codificar(texto)) {
            if (b == '(' || b == ')' || b == '\\') {
                salida.write('\\');
            }
            salida.write(b);
        }
        salida.write(')');
        return salida.toByteArray();
    }
}
