package com.feder.herramientas.clientedemo;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Extrae la parte text/plain de una respuesta del sistema para mostrarla en la consola.
 * Solo contempla el formato que genera FEDER (partes en Base64 UTF-8 o texto sin codificar);
 * no es un analizador MIME general.
 */
final class CuerpoTexto {

    private CuerpoTexto() {
    }

    static String extraer(byte[] crudo) {
        String mensaje = new String(crudo, StandardCharsets.ISO_8859_1);
        String minusculas = mensaje.toLowerCase(Locale.ROOT);
        int tipo = minusculas.indexOf("content-type: text/plain");
        if (tipo < 0) {
            return "(la respuesta no tiene parte de texto)";
        }
        int inicioCuerpo = mensaje.indexOf("\r\n\r\n", tipo);
        if (inicioCuerpo < 0) {
            return "";
        }
        String cabeceras = minusculas.substring(tipo, inicioCuerpo);
        int fin = mensaje.indexOf("\r\n--", inicioCuerpo + 4);
        String cuerpo = mensaje.substring(inicioCuerpo + 4, fin < 0 ? mensaje.length() : fin);
        if (cabeceras.contains("content-transfer-encoding: base64")) {
            byte[] bytes = Base64.getMimeDecoder().decode(cuerpo.trim());
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(cuerpo.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }
}
