package com.feder.negocio.pagos;

import com.feder.modelo.MetodoPago;
import com.feder.negocio.catalogo.Importes;

/**
 * Pasarela local de demostración. NO está conectada a ningún proveedor de pagos real: no cobra, no consulta
 * bancos ni emisores de tarjetas y nunca informa que un cobro fue aprobado.
 *
 * Solo devuelve como referencia del intento su propia clave de idempotencia y las indicaciones para que el
 * cliente pague por el canal habitual de FEDER y entregue su comprobante. El personal verifica ese cobro y lo
 * confirma o rechaza manualmente con CONPAG o RECPAG, con la referencia de la transacción externa.
 */
public final class PasarelaPagosLocal implements PasarelaPagos {

    @Override
    public String nombre() {
        return "Pasarela local (sin proveedor real)";
    }

    @Override
    public boolean conectadaAProveedor() {
        return false;
    }

    @Override
    public InstruccionesCobro prepararCobro(SolicitudCobro s) {
        if (s.metodo() == MetodoPago.EFECTIVO) {
            throw new IllegalArgumentException("El efectivo no se presenta a ninguna pasarela");
        }
        String canal = s.metodo() == MetodoPago.QR ? "el código QR de FEDER" : "el terminal de tarjetas de FEDER";
        return new InstruccionesCobro(s.claveIdempotencia(), "Pague Bs " + Importes.formatear(s.monto()) + " del pedido "
                + s.codigoPedido() + " con " + canal + " indicando la referencia " + s.claveIdempotencia()
                + ". Este sistema no está conectado a un proveedor de pagos: el personal de FEDER verificará su comprobante "
                + "y confirmará el pago (o lo rechazará, con el motivo, si no hubo cobro); hasta entonces el pago queda "
                + "PENDIENTE y no vence por el paso del tiempo.");
    }
}
