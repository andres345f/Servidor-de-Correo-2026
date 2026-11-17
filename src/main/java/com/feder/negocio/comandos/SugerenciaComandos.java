package com.feder.negocio.comandos;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sugiere comandos parecidos cuando el usuario escribe uno inexistente.
 * También explica la equivalencia de los ejemplos del enunciado (que operan sobre "persona")
 * con los comandos de FEDER, donde la persona es la cuenta de USUARIO.
 */
final class SugerenciaComandos {

    private static final Map<String, String> EQUIVALENCIAS_ENUNCIADO = Map.of(
            "LISPER", "En FEDER las personas son cuentas de usuario: use LISUSU[\"*\"].",
            "INSPER", "En FEDER las personas se registran como usuarios: use REGCLI (registro de cliente) o INVUSU (incorporación de personal).");

    private SugerenciaComandos() {
    }

    static String sugerir(String escrito, CatalogoComandos catalogo) {
        String nombre = escrito == null ? "" : escrito.toUpperCase(Locale.ROOT);
        if (EQUIVALENCIAS_ENUNCIADO.containsKey(nombre)) {
            return EQUIVALENCIAS_ENUNCIADO.get(nombre);
        }
        String parecidos = catalogo.todos().stream()
                .map(ComandoSpec::nombre)
                .filter(c -> distancia(c, nombre) <= 2)
                .sorted(Comparator.comparingInt(c -> distancia(c, nombre)))
                .limit(3)
                .collect(Collectors.joining(", "));
        return parecidos.isEmpty() ? "" : "¿Quiso decir: " + parecidos + "?";
    }

    /** Distancia de edición de Levenshtein (cantidad mínima de cambios entre dos palabras). */
    static int distancia(String a, String b) {
        int[] previa = new int[b.length() + 1];
        int[] actual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previa[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            actual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                actual[j] = Math.min(Math.min(actual[j - 1] + 1, previa[j] + 1), previa[j - 1] + costo);
            }
            int[] temporal = previa;
            previa = actual;
            actual = temporal;
        }
        return previa[b.length()];
    }
}
