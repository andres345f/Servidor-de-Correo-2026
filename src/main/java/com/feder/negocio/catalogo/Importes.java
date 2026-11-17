package com.feder.negocio.catalogo;

import com.feder.negocio.comun.NegocioException;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cálculos monetarios del modelo: bolivianos con 2 decimales y redondeo half-up (R018).
 *
 * Los importes recibidos por correo y los que se calculan para guardarlos se comparan aquí con el mayor valor de
 * su columna, antes de llegar a SQL: un valor fuera de rango se rechaza como regla de negocio con el máximo en el
 * mensaje, en lugar de terminar en un error de desbordamiento de la base (SQLSTATE 22003).
 */
public final class Importes {

    public static final int ESCALA = 2;

    /**
     * Mayor valor de NUMERIC(12,2): precios unitarios (producto.precio_venta, lote_produccion.precio_ofrecido,
     * detalle_compra.precio_unitario, detalle_pedido.precio_unitario) y costo de envío del pedido.
     */
    public static final BigDecimal MAXIMO_PRECIO = new BigDecimal("9999999999.99");

    /**
     * Mayor valor de NUMERIC(14,2): montos de pagos y reembolsos, subtotales y totales de pedidos, de compras
     * (compra_feder.total) y de facturas, e importe reembolsable de una devolución.
     */
    public static final BigDecimal MAXIMO_MONTO = new BigDecimal("999999999999.99");

    private Importes() {
    }

    public static BigDecimal redondear(BigDecimal importe) {
        return importe.setScale(ESCALA, RoundingMode.HALF_UP);
    }

    /** round(cantidad x precio, 2), la misma fórmula que el CHECK de detalle_pedido.subtotal. */
    public static BigDecimal subtotal(BigDecimal cantidad, BigDecimal precioUnitario) {
        return redondear(cantidad.multiply(precioUnitario));
    }

    /**
     * Valida un precio unitario recibido por correo: positivo (o cero si se admite), con 2 decimales como máximo y
     * dentro de NUMERIC(12,2).
     */
    public static BigDecimal validarPrecio(BigDecimal precio, boolean admiteCero, String campo) {
        exigirSigno(precio, admiteCero, campo);
        exigirDecimales(precio, campo);
        return exigirMaximo(redondear(precio), MAXIMO_PRECIO, campo);
    }

    /**
     * Valida un monto recibido por correo (por ejemplo, el de un pago): mayor que 0, con 2 decimales como máximo y
     * dentro de NUMERIC(14,2).
     */
    public static BigDecimal validarMonto(BigDecimal monto, String campo) {
        exigirSigno(monto, false, campo);
        exigirDecimales(monto, campo);
        return exigirMaximo(redondear(monto), MAXIMO_MONTO, campo);
    }

    /**
     * Comprueba que un importe calculado (un total o un subtotal que se va a guardar) quepa en NUMERIC(14,2).
     * Devuelve el mismo importe para encadenar la llamada.
     */
    public static BigDecimal exigirMontoAdmitido(BigDecimal importe, String campo) {
        return exigirMaximo(importe, MAXIMO_MONTO, campo);
    }

    public static String formatear(BigDecimal importe) {
        return importe == null ? "" : redondear(importe).toPlainString();
    }

    private static void exigirSigno(BigDecimal importe, boolean admiteCero, String campo) {
        int signo = importe.signum();
        if (signo < 0 || (signo == 0 && !admiteCero)) {
            throw NegocioException.regla("El " + campo + " debe ser "
                    + (admiteCero ? "mayor o igual que 0." : "mayor que 0."));
        }
    }

    private static void exigirDecimales(BigDecimal importe, String campo) {
        if (importe.stripTrailingZeros().scale() > ESCALA) {
            throw NegocioException.regla("El " + campo + " admite como máximo 2 decimales.");
        }
    }

    private static BigDecimal exigirMaximo(BigDecimal importe, BigDecimal maximo, String campo) {
        if (importe.compareTo(maximo) > 0) {
            throw NegocioException.regla("El " + campo + " (Bs " + formatear(importe) + ") supera el máximo admitido: Bs "
                    + maximo.toPlainString() + ".");
        }
        return importe;
    }
}
