package com.feder.presentacion.respuesta;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Carga plantillas UTF-8 desde el classpath y sustituye marcadores {{nombre}}.
 *
 * La sustitución se hace en una sola pasada: si un valor contiene "{{x}}", no se vuelve a expandir.
 * Los valores se insertan tal cual, así que quien llama debe entregarlos ya escapados.
 */
final class MotorPlantillas {

    private static final Pattern MARCADOR = Pattern.compile("\\{\\{([A-Za-z]+)\\}\\}");

    private final String carpeta;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    MotorPlantillas(String carpeta) {
        this.carpeta = carpeta.endsWith("/") ? carpeta : carpeta + "/";
    }

    /** Devuelve el contenido del recurso; falla si no existe, porque es un error de empaquetado. */
    String cargar(String nombre) {
        return cache.computeIfAbsent(nombre, this::leerRecurso);
    }

    String renderizar(String nombre, Map<String, String> valores) {
        Matcher m = MARCADOR.matcher(cargar(nombre));
        StringBuilder resultado = new StringBuilder();
        while (m.find()) {
            String valor = valores.get(m.group(1));
            if (valor == null) {
                throw new IllegalStateException("La plantilla " + nombre + " usa {{" + m.group(1) + "}} sin valor asignado");
            }
            m.appendReplacement(resultado, Matcher.quoteReplacement(valor));
        }
        m.appendTail(resultado);
        return resultado.toString();
    }

    private String leerRecurso(String nombre) {
        String ruta = carpeta + nombre;
        ClassLoader cargador = MotorPlantillas.class.getClassLoader();
        try (InputStream entrada = cargador.getResourceAsStream(ruta)) {
            if (entrada == null) {
                throw new IllegalStateException("No se encontró la plantilla en el classpath: " + ruta);
            }
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer la plantilla " + ruta, e);
        }
    }
}
