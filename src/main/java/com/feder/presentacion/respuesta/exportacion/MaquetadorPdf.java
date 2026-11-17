package com.feder.presentacion.respuesta.exportacion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Distribuye texto en páginas A4: lleva la posición vertical, corta las líneas largas por palabras y abre
 * una página nueva cuando la siguiente línea ya no cabe sobre el margen inferior.
 */
final class MaquetadorPdf {

    static final double MARGEN = 40;
    static final double ANCHO_UTIL = DocumentoPdf.ANCHO_A4 - 2 * MARGEN;
    private static final double TOPE = DocumentoPdf.ALTO_A4 - MARGEN;
    private static final double LIMITE_INFERIOR = 50;
    private static final double INTERLINEADO = 1.3;
    private static final double TOLERANCIA = 0.01;

    private final List<ByteArrayOutputStream> paginas = new ArrayList<>();
    private ByteArrayOutputStream actual;
    private double y;

    MaquetadorPdf() {
        nuevaPagina();
    }

    void nuevaPagina() {
        actual = new ByteArrayOutputStream(4096);
        paginas.add(actual);
        y = TOPE;
    }

    /** Reserva el alto de una línea; si no cabe en la página, pasa a la siguiente. Devuelve la línea base. */
    double reservarLinea(double tamano) {
        double alto = tamano * INTERLINEADO;
        if (y - alto < LIMITE_INFERIOR) {
            nuevaPagina();
        }
        y -= alto;
        return y;
    }

    /** Indica si caben n líneas más en la página actual. */
    boolean caben(int lineas, double tamano) {
        return y - lineas * tamano * INTERLINEADO >= LIMITE_INFERIOR;
    }

    void espacio(double puntos) {
        y -= puntos;
    }

    /** Párrafo con corte automático de líneas a todo el ancho útil. */
    void parrafo(String texto, boolean negrita, double tamano) {
        for (String linea : cortar(texto, negrita, tamano, ANCHO_UTIL)) {
            texto(MARGEN, reservarLinea(tamano), linea, negrita, tamano);
        }
    }

    /** Escribe texto en una posición absoluta de la página actual. */
    void texto(double x, double base, String texto, boolean negrita, double tamano) {
        ByteArrayOutputStream flujo = actual;
        ascii(flujo, String.format(Locale.ROOT, "BT /%s %.2f Tf 1 0 0 1 %.2f %.2f Tm ", negrita ? "F2" : "F1", tamano, x, base));
        flujo.writeBytes(CodificadorWinAnsi.cadenaPdf(texto));
        ascii(flujo, " Tj ET\n");
    }

    /** Añade el pie «Página i de n» a todas las páginas y devuelve sus flujos de contenido. */
    List<byte[]> terminar(String pie) {
        List<byte[]> contenidos = new ArrayList<>(paginas.size());
        for (int i = 0; i < paginas.size(); i++) {
            actual = paginas.get(i);
            String texto = pie + " - Página " + (i + 1) + " de " + paginas.size();
            texto(MARGEN, 28, texto, false, 7.5);
            contenidos.add(actual.toByteArray());
        }
        return contenidos;
    }

    /** Corta el texto por palabras para que cada línea mida como máximo el ancho indicado. */
    static List<String> cortar(String texto, boolean negrita, double tamano, double ancho) {
        List<String> lineas = new ArrayList<>();
        for (String bloque : texto.split("\\R", -1)) {
            StringBuilder linea = new StringBuilder();
            for (String palabra : bloque.strip().split("\\s+")) {
                String propuesta = linea.length() == 0 ? palabra : linea + " " + palabra;
                if (MetricasHelvetica.ancho(propuesta, negrita, tamano) <= ancho + TOLERANCIA || linea.length() == 0) {
                    linea.setLength(0);
                    linea.append(recortar(propuesta, negrita, tamano, ancho));
                } else {
                    lineas.add(linea.toString());
                    linea.setLength(0);
                    linea.append(recortar(palabra, negrita, tamano, ancho));
                }
            }
            lineas.add(linea.toString());
        }
        return lineas;
    }

    /** Recorta el texto con «...» si supera el ancho; se usa en celdas y en palabras más largas que la línea. */
    static String recortar(String texto, boolean negrita, double tamano, double ancho) {
        // La tolerancia evita recortar un texto que mide exactamente el ancho por errores de redondeo.
        double limite = ancho + TOLERANCIA;
        if (MetricasHelvetica.ancho(texto, negrita, tamano) <= limite) {
            return texto;
        }
        String puntos = "...";
        int fin = texto.length();
        while (fin > 0 && MetricasHelvetica.ancho(texto.substring(0, fin) + puntos, negrita, tamano) > limite) {
            fin--;
        }
        return texto.substring(0, fin) + puntos;
    }

    private static void ascii(ByteArrayOutputStream flujo, String texto) {
        flujo.writeBytes(texto.getBytes(StandardCharsets.US_ASCII));
    }
}
