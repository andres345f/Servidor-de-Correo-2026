package com.feder.negocio.pagos;

import com.feder.negocio.catalogo.Importes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tarea periódica: pregunta al proveedor de pagos por los pagos QR PENDIENTE y confirma los cobrados
 * ({@link ServicioPagos#verificarConPasarela()}). Se ejecuta al inicio de cada ciclo de correo, pero como máximo
 * una vez por intervalo, para no consultar al proveedor cada pocos segundos. Un fallo se registra y se reintenta
 * en la siguiente pasada; nunca detiene el procesamiento del correo.
 */
public final class VerificadorPagosPasarela implements Runnable {

    private static final Logger ACCESO = Logger.getLogger("feder.access");
    private static final Logger ERROR = Logger.getLogger("feder.error");
    private static final Logger CONSOLA = Logger.getLogger("feder.consola");

    private final ServicioPagos pagos;
    private final String pasarela;
    private final Duration intervalo;
    private final Clock reloj;
    private Instant proxima = Instant.EPOCH;

    VerificadorPagosPasarela(ServicioPagos pagos, String pasarela, Duration intervalo, Clock reloj) {
        this.pagos = pagos;
        this.pasarela = pasarela;
        this.intervalo = intervalo;
        this.reloj = reloj;
    }

    @Override
    public void run() {
        Instant ahora = reloj.instant();
        if (ahora.isBefore(proxima)) {
            return;
        }
        proxima = ahora.plus(intervalo);
        try {
            List<ServicioPagos.Verificacion> confirmados = pagos.verificarConPasarela();
            for (ServicioPagos.Verificacion v : confirmados) {
                String factura = v.factura() == null ? "" : " · factura " + v.factura().getNumero();
                String pedido = v.pedidoConfirmado() ? " · pedido CONFIRMADO" : "";
                ACCESO.info(() -> "PAGO_CONFIRMADO_PROVEEDOR clave=" + v.pago().getClaveIdempotencia() + " pedido="
                        + v.pedido().getCodigo() + " referencia=" + v.pago().getReferenciaExterna());
                CONSOLA.info(() -> "[" + pasarela + "] Pago " + v.pago().getClaveIdempotencia() + " del pedido "
                        + v.pedido().getCodigo() + " confirmado (Bs " + Importes.formatear(v.pago().getMonto()) + ")"
                        + pedido + factura + ". Se avisó al cliente por correo.");
            }
        } catch (RuntimeException e) {
            if (com.feder.negocio.comun.FallaConexion.esDeBaseDeDatos(e)) {
                proxima = ahora; // Sin base no se consultó nada: se reintenta en cuanto vuelva.
                throw e;
            }
            ERROR.log(Level.WARNING, "[" + pasarela + "] No se pudieron consultar los pagos QR pendientes; se reintentará en "
                    + intervalo.toSeconds() + " s", e);
        }
    }
}
