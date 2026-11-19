package com.feder.pagofacil;

import com.feder.modelo.MetodoPago;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.pagos.PasarelaPagos;
import com.feder.negocio.pagos.PasarelaPagosLocal;

import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Pasarela conectada a PagoFácil (QR). Al registrar un pago QR genera el código con /generate-qr usando la clave
 * del pago como paymentNumber, y el sistema confirma el cobro consultando /query-transaction con esa misma clave.
 * Los pagos con TARJETA siguen el flujo de la pasarela local (los verifica el personal): PagoFácil QR no los cubre.
 */
public final class PasarelaPagoFacil implements PasarelaPagos {

    private static final Logger ERROR = Logger.getLogger("feder.error");

    private final ConfiguracionPagoFacil config;
    private final ClientePagoFacil cliente;
    private final PasarelaPagosLocal tarjeta = new PasarelaPagosLocal();

    public PasarelaPagoFacil(ConfiguracionPagoFacil config) {
        this(config, new ClientePagoFacil(config));
    }

    PasarelaPagoFacil(ConfiguracionPagoFacil config, ClientePagoFacil cliente) {
        this.config = config;
        this.cliente = cliente;
    }

    @Override
    public String nombre() {
        return "PagoFácil (QR)";
    }

    @Override
    public boolean conectadaAProveedor() {
        return true;
    }

    @Override
    public InstruccionesCobro prepararCobro(SolicitudCobro s) {
        if (s.metodo() != MetodoPago.QR) {
            return tarjeta.prepararCobro(s);
        }
        if (config.metodoPago() == null) {
            throw NegocioException.regla("El pago por QR no está disponible: falta configurar pagofacil.metodo.pago. "
                    + "No se registró el pago.");
        }
        PasarelaPagos.Pagador p = s.pagador();
        if (p == null) {
            throw new IllegalArgumentException("PagoFácil necesita los datos del cliente del pedido");
        }
        ClientePagoFacil.QrGenerado qr;
        try {
            qr = cliente.generarQr(config.metodoPago(), new ClientePagoFacil.Cliente(p.nombre(), p.documento(), p.telefono(),
                    p.correo(), String.valueOf(p.usuarioId())), s.claveIdempotencia(), s.monto(), "Pedido " + s.codigoPedido());
        } catch (PagoFacilException e) {
            ERROR.warning(() -> "PagoFácil /generate-qr falló para el pago " + s.claveIdempotencia() + ": " + e.getMessage());
            throw NegocioException.regla("No se pudo generar el QR de PagoFácil (" + e.getMessage() + "). No se registró el "
                    + "pago: vuelva a intentarlo en unos minutos.");
        }
        byte[] imagen;
        try {
            imagen = Base64.getMimeDecoder().decode(qr.qrBase64().replaceFirst("^data:image/[a-z]+;base64,", ""));
        } catch (IllegalArgumentException e) {
            throw NegocioException.regla("PagoFácil devolvió un QR que no se pudo leer. No se registró el pago: vuelva a intentarlo.");
        }
        String vence = qr.vence() == null || qr.vence().isBlank() ? "" : " El código vence el " + qr.vence() + ".";
        return new InstruccionesCobro(qr.transaccionPagoFacil(), "Escanee el código QR adjunto con la app de su banco para pagar "
                + "Bs " + Importes.formatear(s.monto()) + " del pedido " + s.codigoPedido() + "." + vence + " FEDER confirma el pago "
                + "automáticamente en pocos minutos y le avisará por correo; no hace falta enviar comprobante.", imagen);
    }

    /**
     * Pagado solo si PagoFácil informa el estado configurado como pagado (pagofacil.estado.pagado) y una fecha de
     * pago. Sin ese valor configurado, nunca se confirma nada automáticamente.
     */
    @Override
    public Optional<CobroVerificado> consultarCobro(String claveIdempotencia) {
        if (config.estadoPagado() == null) {
            return Optional.empty();
        }
        ClientePagoFacil.EstadoTransaccion estado;
        try {
            estado = cliente.consultar(claveIdempotencia);
        } catch (PagoFacilException e) {
            if (e.getMessage().startsWith("PagoFácil rechazó /query-transaction")) {
                // El proveedor no conoce la transacción (por ejemplo, un pago QR anterior a PagoFácil): no está pagada.
                return Optional.empty();
            }
            throw e;
        }
        boolean pagado = config.estadoPagado().equals(estado.estado()) && estado.monto() != null
                && estado.fechaPago() != null && !estado.fechaPago().isBlank();
        if (!pagado) {
            return Optional.empty();
        }
        String referencia = estado.transaccionPagoFacil() == null ? claveIdempotencia : estado.transaccionPagoFacil();
        return Optional.of(new CobroVerificado("PAGOFACIL-" + referencia, estado.monto()));
    }
}
