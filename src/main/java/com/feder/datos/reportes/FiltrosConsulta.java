package com.feder.datos.reportes;

import java.util.Arrays;

/**
 * Valores de los filtros opcionales de CU8 tal como llegan a las consultas. Un valor nulo significa
 * «sin filtro». Cada filtro aporta sus valores en el orden en que la CTE correspondiente de
 * {@link FragmentosSql} consume sus parámetros.
 */
public final class FiltrosConsulta {

    /** Filtros del reporte de ventas (FragmentosSql.FILTRO_VENTAS). */
    public record Ventas(String producto, String categoria, String tipo, String modalidad, String estado) {
        public static final Ventas NINGUNO = new Ventas(null, null, null, null, null);

        Object[] valores() {
            return new Object[] {producto, categoria, tipo, modalidad, estado};
        }
    }

    /** Filtros de lotes del reporte de inventario (FragmentosSql.FILTRO_LOTES). */
    public record Lotes(String producto, String categoria, String productor, String comunidad, String estado) {
        public static final Lotes NINGUNO = new Lotes(null, null, null, null, null);

        Object[] valores() {
            return new Object[] {producto, categoria, productor, comunidad, estado};
        }
    }

    /** Filtros del reporte de abastecimiento (FragmentosSql.FILTRO_COMPRAS). */
    public record Compras(String producto, String categoria, String productor, String comunidad, String estado) {
        public static final Compras NINGUNO = new Compras(null, null, null, null, null);

        Object[] valores() {
            return new Object[] {producto, categoria, productor, comunidad, estado};
        }
    }

    private FiltrosConsulta() {
    }

    /** Une los parámetros de fecha que preceden a la CTE del filtro con los valores del filtro. */
    static Object[] parametros(Object[] filtro, Object... previos) {
        Object[] todos = Arrays.copyOf(previos, previos.length + filtro.length);
        System.arraycopy(filtro, 0, todos, previos.length, filtro.length);
        return todos;
    }
}
