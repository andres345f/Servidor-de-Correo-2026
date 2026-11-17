package com.feder.presentacion.respuesta;

/** Utilidades de texto compartidas por los formateadores. */
final class Textos {

    private Textos() {
    }

    /** Convierte cualquier valor en una sola línea: los controles (CR, LF, TAB...) pasan a espacio. */
    static String unaLinea(String valor) {
        if (valor == null) {
            return "";
        }
        StringBuilder limpio = new StringBuilder(valor.length());
        boolean espacioPrevio = false;
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            boolean esEspacio = Character.isISOControl(c) || c == ' ' || c == ' ' || c == ' ';
            if (esEspacio) {
                if (!espacioPrevio) {
                    limpio.append(' ');
                }
            } else {
                limpio.append(c);
            }
            espacioPrevio = esEspacio;
        }
        return limpio.toString().strip();
    }

    /** Normaliza saltos de línea a '\n' y quita los demás caracteres de control salvo el tabulador. */
    static String multilinea(String valor) {
        if (valor == null) {
            return "";
        }
        String normalizado = valor.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder limpio = new StringBuilder(normalizado.length());
        for (int i = 0; i < normalizado.length(); i++) {
            char c = normalizado.charAt(i);
            limpio.append(c == '\n' || c == '\t' || !Character.isISOControl(c) ? c : ' ');
        }
        return limpio.toString().strip();
    }

    /** Ancho visible aproximado: cuenta puntos de código, no unidades UTF-16. */
    static int ancho(String texto) {
        return texto.codePointCount(0, texto.length());
    }

    static String rellenar(String texto, int ancho) {
        return texto + " ".repeat(Math.max(0, ancho - ancho(texto)));
    }
}
