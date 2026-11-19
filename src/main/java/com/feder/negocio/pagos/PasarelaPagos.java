package com.feder.negocio.pagos;

import com.feder.modelo.MetodoPago;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Punto de integración con un proveedor de pagos por QR o tarjeta (PAGO «Confirmación verificada y única», R048).
 *
 * El negocio solo pide preparar el intento con su clave de idempotencia, que identifica el intento ante FEDER
 * y ante la pasarela. Con la pasarela local, la confirmación o el rechazo los registra el personal tras verificar
 * el resultado; con un proveedor real ({@link #conectadaAProveedor()}), el sistema consulta el resultado
 * verificado con {@link #consultarCobro(String)} y confirma el pago solo. El efectivo no pasa por ninguna
 * pasarela (R049).
 */
public interface PasarelaPagos {

    /** Quién paga: el cliente del pedido (el proveedor pide sus datos para emitir el QR). */
    record Pagador(long usuarioId, String nombre, String documento, String telefono, String correo) {
    }

    /** Datos del intento que se presenta a la pasarela. */
    record SolicitudCobro(String claveIdempotencia, String codigoPedido, MetodoPago metodo, BigDecimal monto, Pagador pagador) {
        public SolicitudCobro(String claveIdempotencia, String codigoPedido, MetodoPago metodo, BigDecimal monto) {
            this(claveIdempotencia, codigoPedido, metodo, monto, null);
        }
    }

    /**
     * Lo que la pasarela devuelve al preparar el intento: cómo debe pagar el cliente y, si el proveedor lo
     * emite, la imagen PNG del código QR (se adjunta a la respuesta por correo).
     */
    record InstruccionesCobro(String referenciaIntento, String indicaciones, byte[] imagenQr) {
        public InstruccionesCobro(String referenciaIntento, String indicaciones) {
            this(referenciaIntento, indicaciones, null);
        }
    }

    /** Cobro verificado por el proveedor: su referencia de la transacción y el importe efectivamente cobrado. */
    record CobroVerificado(String referencia, BigDecimal monto) {
    }

    /** Nombre para las respuestas y la bitácora. */
    String nombre();

    /** true solo si un proveedor real comunica y verifica el resultado de los cobros. */
    boolean conectadaAProveedor();

    /** Prepara el intento de cobro; reintentarlo con la misma clave no crea otro intento. */
    InstruccionesCobro prepararCobro(SolicitudCobro solicitud);

    /**
     * Consulta al proveedor si el intento con esta clave ya se cobró. Vacío si todavía no se pagó, si el proveedor
     * no lo conoce o si la pasarela no está conectada a un proveedor (la local nunca informa cobros).
     */
    default Optional<CobroVerificado> consultarCobro(String claveIdempotencia) {
        return Optional.empty();
    }
}
