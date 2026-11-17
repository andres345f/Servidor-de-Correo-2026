package com.feder.negocio.comun;

import java.util.List;

/**
 * Regla común de los listados: una respuesta muestra como máximo {@link #TOPE} filas, para que un
 * LIS…["*"] no genere un correo enorme. Se piden {@link #A_PEDIR} filas (una de más): si llega la
 * fila extra, hay más registros que los mostrados y la respuesta lo avisa.
 */
public final class Listados {

    public static final int TOPE = 100;
    public static final int A_PEDIR = TOPE + 1;

    private Listados() {
    }

    /** Las filas a mostrar: como máximo {@link #TOPE}. */
    public static <T> List<T> recortar(List<T> obtenidas) {
        return obtenidas.size() > TOPE ? obtenidas.subList(0, TOPE) : obtenidas;
    }

    /** Aviso para agregar al mensaje de la respuesta ("" si se muestran todas las filas). */
    public static String aviso(List<?> obtenidas) {
        return obtenidas.size() > TOPE
                ? " Se muestran los primeros " + TOPE + " registros; hay más: use un filtro para acotar."
                : "";
    }
}
