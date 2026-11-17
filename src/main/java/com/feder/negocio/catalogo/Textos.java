package com.feder.negocio.catalogo;

import com.feder.negocio.comun.NegocioException;

/** Normalización de los textos libres recibidos por correo antes de guardarlos. */
public final class Textos {

    private Textos() {
    }

    /** Texto obligatorio: sin espacios sobrantes y sin caracteres de control. */
    public static String obligatorio(String valor, String campo) {
        String limpio = opcional(valor);
        if (limpio == null) {
            throw NegocioException.regla("El dato «" + campo + "» es obligatorio.");
        }
        return limpio;
    }

    /** Número de caracteres como los cuenta VARCHAR(n): puntos de código, no unidades UTF-16. */
    public static int longitud(String valor) {
        return valor.codePointCount(0, valor.length());
    }

    /**
     * Recorta el texto a {@code maximo} caracteres (puntos de código) sin partir un par sustituto y sin dejar
     * espacios sobrantes al final. Si ya cabe, lo devuelve igual.
     */
    public static String recortar(String valor, int maximo) {
        if (longitud(valor) <= maximo) {
            return valor;
        }
        return valor.substring(0, valor.offsetByCodePoints(0, maximo)).stripTrailing();
    }

    /** Texto opcional: devuelve null si llega vacío. */
    public static String opcional(String valor) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.chars().anyMatch(Character::isISOControl)) {
            throw NegocioException.regla("El texto no puede contener caracteres de control.");
        }
        return limpio.isEmpty() ? null : limpio;
    }
}
