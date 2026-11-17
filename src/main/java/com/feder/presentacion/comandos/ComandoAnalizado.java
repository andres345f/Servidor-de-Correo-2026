package com.feder.presentacion.comandos;

import java.util.List;
import java.util.Objects;

/**
 * Resultado sintáctico de un asunto: nombre normalizado en mayúsculas y parámetros ya sin comillas ni escapes.
 *
 * @param nombre        nombre del comando en mayúsculas (p. ej. "LISPER")
 * @param parametros    valores de los parámetros en orden
 * @param textoOriginal asunto tal como se recibió
 */
public record ComandoAnalizado(String nombre, List<String> parametros, String textoOriginal) {

    public ComandoAnalizado {
        Objects.requireNonNull(nombre, "nombre");
        parametros = List.copyOf(parametros);
        Objects.requireNonNull(textoOriginal, "textoOriginal");
    }
}
