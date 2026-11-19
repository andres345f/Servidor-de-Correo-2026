package com.feder.pagofacil;

import com.feder.configuracion.AppConfig;
import com.feder.configuracion.ConfiguracionException;

/**
 * Datos para usar la API QR de PagoFácil (pagofacil.* en config/application-&lt;entorno&gt;.properties).
 *
 * @param metodoPago   paymentMethodId que se usa al generar el QR (lo muestra diagnostico-pagofacil).
 * @param estadoPagado valor de paymentStatus que significa "pagado"; null desactiva la confirmación automática.
 * @param callbackUrl  opcional: este sistema confirma consultando /query-transaction, no necesita callback.
 */
public record ConfiguracionPagoFacil(String url, String tokenServicio, String tokenSecreto, Integer metodoPago,
                                     Integer estadoPagado, String callbackUrl, int consultaSegundos) {

    public static final String URL_POR_DEFECTO = "https://masterqr.pagofacil.com.bo/api/services/v2";

    @Override
    public String toString() {
        // Los tokens nunca se muestran.
        return "ConfiguracionPagoFacil[url=" + url + ", metodoPago=" + metodoPago + ", estadoPagado=" + estadoPagado + "]";
    }

    /** null si PagoFácil no está configurado (no hay token de servicio): se usa la pasarela local. */
    public static ConfiguracionPagoFacil desde(AppConfig config) {
        if (!config.tieneSecreto("pagofacil.token.servicio")) {
            return null;
        }
        if (!config.tieneSecreto("pagofacil.token.secreto")) {
            throw new ConfiguracionException("Falta pagofacil.token.secreto (se configuró pagofacil.token.servicio).");
        }
        return new ConfiguracionPagoFacil(
                config.opcional("pagofacil.url", URL_POR_DEFECTO).replaceAll("/+$", ""),
                config.secreto("pagofacil.token.servicio"),
                config.secreto("pagofacil.token.secreto"),
                enteroOpcional(config, "pagofacil.metodo.pago"),
                enteroOpcional(config, "pagofacil.estado.pagado"),
                config.opcional("pagofacil.callback.url", null),
                config.entero("pagofacil.consulta.segundos", 10, 3600));
    }

    private static Integer enteroOpcional(AppConfig config, String clave) {
        String valor = config.opcional(clave, null);
        if (valor == null || valor.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(valor.trim());
        } catch (NumberFormatException e) {
            throw new ConfiguracionException("La clave " + clave + " debe ser un número entero (valor recibido: " + valor + ").");
        }
    }
}
