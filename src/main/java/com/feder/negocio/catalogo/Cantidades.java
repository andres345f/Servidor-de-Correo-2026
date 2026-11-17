package com.feder.negocio.catalogo;

import com.feder.modelo.UnidadMedida;
import com.feder.negocio.comun.NegocioException;

import java.math.BigDecimal;

/**
 * Reglas de las cantidades físicas (NUMERIC(12,3)): positivas, hasta 3 decimales y enteras
 * cuando el producto se vende por UNIDAD (PRODUCTO «Cantidades enteras por pieza»).
 * La base lo vuelve a comprobar con sus disparadores; aquí se valida antes para dar un mensaje claro.
 */
public final class Cantidades {

    /** Mayor valor de NUMERIC(12,3), el tipo de todas las cantidades físicas del modelo. */
    public static final BigDecimal MAXIMO = new BigDecimal("999999999.999");

    private Cantidades() {
    }

    public static BigDecimal validarPositiva(BigDecimal cantidad, UnidadMedida unidad, String campo) {
        if (cantidad == null || cantidad.signum() <= 0) {
            throw NegocioException.regla("La " + campo + " debe ser mayor que 0.");
        }
        return validarNoNegativa(cantidad, unidad, campo);
    }

    public static BigDecimal validarNoNegativa(BigDecimal cantidad, UnidadMedida unidad, String campo) {
        if (cantidad == null || cantidad.signum() < 0) {
            throw NegocioException.regla("La " + campo + " no puede ser negativa.");
        }
        exigirMaximo(cantidad, campo);
        if (cantidad.stripTrailingZeros().scale() > 3) {
            throw NegocioException.regla("La " + campo + " admite como máximo 3 decimales.");
        }
        if (unidad == UnidadMedida.UNIDAD && !esEntera(cantidad)) {
            throw NegocioException.regla("La " + campo + " debe ser un número entero porque el producto se vende por UNIDAD.");
        }
        return cantidad;
    }

    /**
     * Comprueba que la cantidad quepa en NUMERIC(12,3) antes de llegar a SQL. Las reglas de cantidades de CU3
     * (ReglasCantidades) comparan con el mismo {@link #MAXIMO} y responden con sus propios mensajes.
     */
    public static BigDecimal exigirMaximo(BigDecimal cantidad, String campo) {
        if (cantidad.compareTo(MAXIMO) > 0) {
            throw NegocioException.regla("La " + campo + " (" + formatear(cantidad) + ") supera el máximo admitido: "
                    + MAXIMO.toPlainString() + ".");
        }
        return cantidad;
    }

    public static boolean esEntera(BigDecimal cantidad) {
        return cantidad.signum() == 0 || cantidad.stripTrailingZeros().scale() <= 0;
    }

    /** Presentación sin ceros sobrantes: 2.500 se muestra como 2.5 y 3.000 como 3. */
    public static String formatear(BigDecimal cantidad) {
        if (cantidad == null) {
            return "";
        }
        return cantidad.signum() == 0 ? "0" : cantidad.stripTrailingZeros().toPlainString();
    }

    public static boolean mayor(BigDecimal a, BigDecimal b) {
        return a.compareTo(b) > 0;
    }
}
