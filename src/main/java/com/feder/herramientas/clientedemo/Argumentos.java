package com.feder.herramientas.clientedemo;

import java.util.HashMap;
import java.util.Map;

/** Lectura simple de argumentos de línea de comandos: acción, opciones --clave=valor y un texto libre. */
public final class Argumentos {

    private Argumentos() {
    }

    public static Map<String, String> leer(String[] args) {
        Map<String, String> opciones = new HashMap<>();
        for (String argumento : args) {
            if (argumento.startsWith("--") && argumento.contains("=")) {
                int igual = argumento.indexOf('=');
                opciones.put(argumento.substring(2, igual), argumento.substring(igual + 1));
            } else if (!opciones.containsKey("_accion")) {
                opciones.put("_accion", argumento);
            } else {
                opciones.put("_texto", argumento);
            }
        }
        return opciones;
    }

    public static String requerido(Map<String, String> opciones, String clave) {
        String valor = opciones.get(clave);
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException("Falta el argumento " + (clave.startsWith("_") ? "de texto" : "--" + clave + "=..."));
        }
        return valor;
    }
}
