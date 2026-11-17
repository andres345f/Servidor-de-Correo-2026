package com.feder.negocio.produccion;

import com.feder.modelo.UnidadMedida;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.comun.NegocioException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cantidades e importes de CU3. Si el producto se vende por UNIDAD toda cantidad es entera (R067);
 * la base también lo exige, pero aquí se responde con un mensaje claro antes de escribir.
 */
final class ReglasCantidades {

    private ReglasCantidades() {
    }

    /** Cantidad estrictamente positiva, entera si la unidad es UNIDAD y dentro de NUMERIC(12,3). */
    static BigDecimal positiva(BigDecimal valor, UnidadMedida unidad, String campo) {
        if (valor.signum() <= 0) {
            throw invalido(campo + " debe ser mayor que cero.");
        }
        return enteraSiUnidad(valor, unidad, campo);
    }

    /** Cantidad mayor o igual que cero, entera si la unidad es UNIDAD y dentro de NUMERIC(12,3). */
    static BigDecimal noNegativa(BigDecimal valor, UnidadMedida unidad, String campo) {
        if (valor.signum() < 0) {
            throw invalido(campo + " no puede ser negativa.");
        }
        return enteraSiUnidad(valor, unidad, campo);
    }

    /** Importe en bolivianos con 2 decimales, redondeo half-up, dentro de NUMERIC(12,2) (lote_produccion.precio_ofrecido). */
    static BigDecimal precio(BigDecimal valor) {
        if (valor == null) {
            return null;
        }
        if (valor.signum() < 0) {
            throw invalido("El precio ofrecido no puede ser negativo.");
        }
        BigDecimal redondeado = valor.setScale(2, RoundingMode.HALF_UP);
        if (redondeado.compareTo(Importes.MAXIMO_PRECIO) > 0) {
            throw invalido("El precio ofrecido supera el máximo admitido: Bs " + Importes.MAXIMO_PRECIO.toPlainString() + ".");
        }
        return redondeado;
    }

    private static BigDecimal enteraSiUnidad(BigDecimal valor, UnidadMedida unidad, String campo) {
        if (unidad == UnidadMedida.UNIDAD && valor.stripTrailingZeros().scale() > 0) {
            throw invalido(campo + " debe ser entera porque el producto se vende por UNIDAD.");
        }
        if (valor.compareTo(Cantidades.MAXIMO) > 0) {
            throw invalido(campo + " supera el máximo admitido: " + Cantidades.MAXIMO.toPlainString() + ".");
        }
        return valor;
    }

    private static NegocioException invalido(String mensaje) {
        return new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO, mensaje);
    }
}
