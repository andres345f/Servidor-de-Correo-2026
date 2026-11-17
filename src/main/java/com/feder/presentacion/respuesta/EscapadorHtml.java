package com.feder.presentacion.respuesta;

/**
 * Escapado de texto para insertarlo en HTML, tanto en contenido como en valores de atributo.
 *
 * Todo dato que venga del usuario o de la base de datos debe pasar por aquí antes de llegar a una plantilla.
 */
public final class EscapadorHtml {

    private EscapadorHtml() {
    }

    public static String escapar(String texto) {
        if (texto == null || texto.isEmpty()) {
            return "";
        }
        StringBuilder seguro = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '&' -> seguro.append("&amp;");
                case '<' -> seguro.append("&lt;");
                case '>' -> seguro.append("&gt;");
                case '"' -> seguro.append("&quot;");
                case '\'' -> seguro.append("&#39;");
                default -> seguro.append(Character.isISOControl(c) && c != '\n' && c != '\t' ? ' ' : c);
            }
        }
        return seguro.toString();
    }
}
