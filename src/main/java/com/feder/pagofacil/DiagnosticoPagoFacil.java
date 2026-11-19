package com.feder.pagofacil;

import com.feder.configuracion.AppConfig;

import java.io.PrintStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Diagnóstico de PagoFácil que NO genera QR ni crea transacciones: comprueba las credenciales (/login), lista los
 * métodos QR habilitados (/list-enabled-services) para elegir pagofacil.metodo.pago y muestra la bibliografía de
 * paymentStatus para saber qué valor significa "pagado" (pagofacil.estado.pagado). Nunca muestra los tokens.
 */
public final class DiagnosticoPagoFacil {

    private DiagnosticoPagoFacil() {
    }

    /** @return true si las credenciales funcionan y la configuración está completa. */
    public static boolean ejecutar(AppConfig appConfig, PrintStream salida) {
        ConfiguracionPagoFacil config = ConfiguracionPagoFacil.desde(appConfig);
        if (config == null) {
            salida.println("[PagoFácil] No configurado: defina pagofacil.token.servicio y pagofacil.token.secreto en "
                    + "config/application-" + appConfig.entorno() + ".properties.");
            return false;
        }
        salida.println("[PagoFácil] " + config.url());
        ClientePagoFacil cliente = new ClientePagoFacil(config);
        try {
            BigDecimal minutos = cliente.autenticar();
            salida.println("[PagoFácil] /login correcto: token válido por " + (minutos == null ? "?" : minutos.toPlainString())
                    + " minutos.");
        } catch (PagoFacilException e) {
            salida.println("[PagoFácil] ERROR en /login: " + e.getMessage());
            salida.println("            Revise pagofacil.token.servicio y pagofacil.token.secreto.");
            return false;
        }
        boolean metodoValido = false;
        try {
            List<Map<String, Object>> metodos = cliente.metodosHabilitados();
            salida.println("[PagoFácil] Métodos QR habilitados (" + metodos.size() + "):");
            for (Map<String, Object> m : metodos) {
                Object id = m.get("paymentMethodId");
                salida.println("            paymentMethodId=" + id + "  " + m.get("paymentMethodName") + "  moneda="
                        + m.get("currencyName") + "  máx. por transacción=" + m.get("maxAmountPerTransaction")
                        + "  máx. por día=" + m.get("maxAmountPerDay"));
                if (config.metodoPago() != null && id instanceof BigDecimal n && n.intValue() == config.metodoPago()) {
                    metodoValido = true;
                }
            }
        } catch (PagoFacilException e) {
            salida.println("[PagoFácil] ERROR en /list-enabled-services: " + e.getMessage());
        }
        try {
            salida.println("[PagoFácil] Bibliografía de paymentStatus (para elegir pagofacil.estado.pagado):");
            salida.println("            " + cliente.bibliografia("paymentStatus"));
        } catch (PagoFacilException e) {
            salida.println("[PagoFácil] No se pudo consultar /bibliography/paymentStatus: " + e.getMessage());
        }
        salida.println();
        salida.println("pagofacil.metodo.pago   = " + (config.metodoPago() == null ? "(sin configurar: los pagos QR se rechazan)"
                : config.metodoPago() + (metodoValido ? "  (habilitado)" : "  (¡no aparece entre los habilitados!)")));
        salida.println("pagofacil.estado.pagado = " + (config.estadoPagado() == null
                ? "(sin configurar: la confirmación automática está desactivada)" : config.estadoPagado()));
        salida.println("Consulta de pagos pendientes cada " + config.consultaSegundos() + " s.");
        return metodoValido && config.estadoPagado() != null;
    }
}
