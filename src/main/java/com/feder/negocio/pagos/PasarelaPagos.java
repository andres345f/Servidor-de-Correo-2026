package com.feder.negocio.pagos;

import com.feder.modelo.MetodoPago;

import java.math.BigDecimal;

/**
 * Punto de integración con un proveedor de pagos por QR o tarjeta (PAGO «Confirmación verificada y única», R048).
 *
 * El negocio solo pide preparar el intento con su clave de idempotencia, que identifica el intento ante FEDER
 * y ante la pasarela. La confirmación o el rechazo del cobro nunca se deducen de esta interfaz: los registra
 * el personal tras verificar el resultado (o, con un proveedor real, su respuesta verificada). El efectivo no
 * pasa por ninguna pasarela (R049).
 */
public interface PasarelaPagos {

    /** Datos del intento que se presenta a la pasarela. */
    record SolicitudCobro(String claveIdempotencia, String codigoPedido, MetodoPago metodo, BigDecimal monto) {
    }

    /** Lo que la pasarela devuelve al preparar el intento: cómo debe pagar el cliente. */
    record InstruccionesCobro(String referenciaIntento, String indicaciones) {
    }

    /** Nombre para las respuestas y la bitácora. */
    String nombre();

    /** true solo si un proveedor real comunica y verifica el resultado de los cobros. */
    boolean conectadaAProveedor();

    /** Prepara el intento de cobro; reintentarlo con la misma clave no crea otro intento. */
    InstruccionesCobro prepararCobro(SolicitudCobro solicitud);
}
