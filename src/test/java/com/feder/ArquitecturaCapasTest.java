package com.feder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las dependencias entre capas solo van hacia abajo: presentación → negocio → datos → modelo.
 * Se revisan las importaciones y los nombres completos en el código fuente de src/main/java.
 */
class ArquitecturaCapasTest {

    private static final Path FUENTES = Path.of("src", "main", "java", "com", "feder");

    private static List<String> referenciasProhibidas(String capa, String... prohibidas) throws IOException {
        List<String> encontradas = new ArrayList<>();
        try (Stream<Path> archivos = Files.walk(FUENTES.resolve(capa))) {
            for (Path archivo : archivos.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lineas = Files.readAllLines(archivo, StandardCharsets.UTF_8);
                for (int i = 0; i < lineas.size(); i++) {
                    for (String paquete : prohibidas) {
                        if (lineas.get(i).contains("com.feder." + paquete + ".")) {
                            encontradas.add(FUENTES.relativize(archivo) + ":" + (i + 1) + " usa com.feder." + paquete);
                        }
                    }
                }
            }
        }
        return encontradas;
    }

    @Test
    @DisplayName("La capa de negocio no depende de la presentación (correo, MIME, HTML)")
    void negocioNoDependeDePresentacion() throws IOException {
        List<String> r = referenciasProhibidas("negocio", "presentacion", "herramientas");
        assertTrue(r.isEmpty(), String.join("\n", r));
    }

    @Test
    @DisplayName("La capa de datos no depende de negocio ni de presentación")
    void datosNoDependenDeCapasSuperiores() throws IOException {
        List<String> r = referenciasProhibidas("datos", "negocio", "presentacion", "herramientas");
        assertTrue(r.isEmpty(), String.join("\n", r));
    }

    @Test
    @DisplayName("El modelo no depende de ninguna capa")
    void modeloIndependiente() throws IOException {
        List<String> r = referenciasProhibidas("modelo", "negocio", "presentacion", "datos", "herramientas");
        assertTrue(r.isEmpty(), String.join("\n", r));
    }
}
