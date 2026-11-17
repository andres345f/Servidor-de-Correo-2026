package com.feder.presentacion.respuesta.exportacion;

import java.text.Normalizer;

/**
 * Anchos de los caracteres de Helvetica y Helvetica-Bold (métricas AFM estándar, en milésimas del tamaño
 * de la fuente) para alinear columnas y cortar líneas. Las letras acentuadas usan el ancho de su letra base.
 */
final class MetricasHelvetica {

    private static final int PRIMERO = 32;
    private static final int ANCHO_POR_DEFECTO = 556;

    // Caracteres 32 a 126.
    private static final int[] NORMAL = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
        278, 278, 584, 584, 584, 556, 1015,
        667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778, 667, 778, 722, 667, 611, 722,
        667, 944, 667, 667, 611,
        278, 278, 278, 469, 556, 333,
        556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556, 556, 556, 333, 500, 278, 556,
        500, 722, 500, 500, 500,
        334, 260, 334, 584
    };

    private static final int[] NEGRITA = {
        278, 333, 474, 556, 556, 889, 722, 238, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556,
        333, 333, 584, 584, 584, 611, 975,
        722, 722, 722, 722, 667, 611, 778, 722, 278, 556, 722, 611, 833, 722, 778, 667, 778, 722, 667, 611, 722,
        667, 944, 667, 667, 611,
        333, 278, 333, 584, 556, 333,
        556, 611, 556, 611, 556, 333, 611, 611, 278, 278, 556, 278, 889, 611, 611, 611, 611, 389, 556, 333, 611,
        556, 778, 556, 556, 500,
        389, 280, 389, 584
    };

    private MetricasHelvetica() {
    }

    /** Ancho del texto en puntos con la fuente y el tamaño indicados. */
    static double ancho(String texto, boolean negrita, double tamano) {
        int[] tabla = negrita ? NEGRITA : NORMAL;
        long milesimas = 0;
        for (int i = 0; i < texto.length(); i++) {
            milesimas += anchoCaracter(texto.charAt(i), tabla);
        }
        return milesimas * tamano / 1000.0;
    }

    private static int anchoCaracter(char c, int[] tabla) {
        if (c >= PRIMERO && c < PRIMERO + tabla.length) {
            return tabla[c - PRIMERO];
        }
        String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        char primero = base.isEmpty() ? c : base.charAt(0);
        if (primero >= PRIMERO && primero < PRIMERO + tabla.length) {
            return tabla[primero - PRIMERO];
        }
        return ANCHO_POR_DEFECTO;
    }
}
