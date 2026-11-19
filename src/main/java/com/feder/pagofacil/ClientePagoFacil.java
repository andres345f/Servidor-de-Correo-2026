package com.feder.pagofacil;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Cliente HTTP de la API QR de PagoFácil v2 (documento "Especificaciones Integración por API", v1.1.0):
 * /login, /list-enabled-services, /generate-qr, /query-transaction y /bibliography.
 *
 * Todas las respuestas tienen la forma {"error": 0|1, "status": n, "message": "...", "values": ...}; un error
 * distinto de 0 se informa con {@link PagoFacilException} y el mensaje del proveedor. El token de acceso se
 * reutiliza hasta un minuto antes de que venza y se renueva solo; nunca se escribe en registros ni mensajes.
 */
public class ClientePagoFacil {

    private static final Duration TIEMPO_CONEXION = Duration.ofSeconds(10);
    private static final Duration TIEMPO_RESPUESTA = Duration.ofSeconds(30);

    private final ConfiguracionPagoFacil config;
    private final HttpClient http;
    private final Clock reloj;
    private String token;
    private Instant tokenVence = Instant.EPOCH;

    public ClientePagoFacil(ConfiguracionPagoFacil config) {
        this(config, Clock.systemUTC());
    }

    ClientePagoFacil(ConfiguracionPagoFacil config, Clock reloj) {
        this.config = config;
        this.reloj = reloj;
        this.http = HttpClient.newBuilder().connectTimeout(TIEMPO_CONEXION).build();
    }

    /** QR generado: identificador de PagoFácil, imagen PNG en Base64 y vencimiento informado por el proveedor. */
    public record QrGenerado(String transaccionPagoFacil, String qrBase64, String vence) {
    }

    /** Estado de una transacción según /query-transaction. */
    public record EstadoTransaccion(String transaccionPagoFacil, Integer estado, BigDecimal monto, String fechaPago) {
    }

    /** Datos de la persona que paga, tal como los pide /generate-qr. */
    public record Cliente(String nombre, String documento, String telefono, String correo, String codigo) {
    }

    /** POST /login: devuelve los minutos de vigencia del token obtenido. */
    public synchronized BigDecimal autenticar() {
        HttpRequest.Builder solicitud = base("/login")
                .header("tcTokenService", config.tokenServicio())
                .header("tcTokenSecret", config.tokenSecreto())
                .POST(HttpRequest.BodyPublishers.noBody());
        Map<String, Object> valores = objeto(enviar(solicitud, "/login").get("values"), "/login");
        String nuevo = texto(valores.get("accessToken"));
        if (nuevo == null || nuevo.isBlank()) {
            throw new PagoFacilException("PagoFácil no devolvió el token de acceso en /login.");
        }
        BigDecimal minutos = numero(valores.get("expiresInMinutes"));
        long segundos = minutos == null ? 600 : Math.max(60, minutos.multiply(BigDecimal.valueOf(60)).longValue() - 60);
        token = nuevo;
        tokenVence = reloj.instant().plusSeconds(segundos);
        return minutos;
    }

    /** POST /list-enabled-services: métodos QR habilitados del comercio (paymentMethodId, nombre, límites). */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> metodosHabilitados() {
        Object valores = conToken("/list-enabled-services", null).get("values");
        if (!(valores instanceof List<?> lista)) {
            throw new PagoFacilException("Respuesta inesperada de /list-enabled-services.");
        }
        return (List<Map<String, Object>>) lista;
    }

    /** GET /bibliography/{parametro}: documentación del parámetro según el propio proveedor. */
    public Object bibliografia(String parametro) {
        HttpRequest.Builder solicitud = base("/bibliography/" + parametro).GET();
        return enviarConToken(solicitud, "/bibliography").get("values");
    }

    /** POST /generate-qr para un pago de un solo ítem (el pedido completo o su anticipo). */
    public QrGenerado generarQr(int metodoPago, Cliente cliente, String numeroPago, BigDecimal monto, String detalle) {
        String importe = monto.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
        StringBuilder cuerpo = new StringBuilder("{")
                .append("\"paymentMethod\":").append(metodoPago)
                .append(",\"clientName\":").append(Json.texto(cliente.nombre()))
                .append(",\"documentType\":1")
                .append(",\"documentId\":").append(Json.texto(cliente.documento()))
                .append(",\"phoneNumber\":").append(Json.texto(cliente.telefono()))
                .append(",\"email\":").append(Json.texto(cliente.correo()))
                .append(",\"paymentNumber\":").append(Json.texto(numeroPago))
                .append(",\"amount\":").append(importe)
                .append(",\"currency\":2")
                .append(",\"clientCode\":").append(Json.texto(cliente.codigo()));
        if (config.callbackUrl() != null && !config.callbackUrl().isBlank()) {
            cuerpo.append(",\"callbackUrl\":").append(Json.texto(config.callbackUrl()));
        }
        cuerpo.append(",\"orderDetail\":[{\"serial\":1,\"product\":").append(Json.texto(detalle))
                .append(",\"quantity\":1,\"price\":").append(importe)
                .append(",\"discount\":0,\"total\":").append(importe).append("}]}");
        Map<String, Object> valores = objeto(conToken("/generate-qr", cuerpo.toString()).get("values"), "/generate-qr");
        String qr = texto(valores.get("qrBase64"));
        String transaccion = texto(valores.get("transactionId"));
        if (qr == null || qr.isBlank() || transaccion == null) {
            throw new PagoFacilException("PagoFácil no devolvió el QR o el identificador de la transacción.");
        }
        return new QrGenerado(transaccion, qr, texto(valores.get("expirationDate")));
    }

    /** POST /query-transaction por el identificador del comercio (la clave del pago en FEDER). */
    public EstadoTransaccion consultar(String transaccionComercio) {
        String cuerpo = "{\"companyTransactionId\":" + Json.texto(transaccionComercio) + "}";
        Map<String, Object> valores = objeto(conToken("/query-transaction", cuerpo).get("values"), "/query-transaction");
        BigDecimal estado = numero(valores.get("paymentStatus"));
        return new EstadoTransaccion(texto(valores.get("pagofacilTransactionId")),
                estado == null ? null : estado.intValueExact(), numero(valores.get("amount")), texto(valores.get("paymentDate")));
    }

    // ------------------------------------------------------------------ HTTP

    private Map<String, Object> conToken(String ruta, String json) {
        HttpRequest.Builder solicitud = base(ruta).header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        return enviarConToken(solicitud, ruta);
    }

    /** Usa el token vigente; si el proveedor lo rechaza (401), lo renueva y repite la solicitud una vez. */
    private synchronized Map<String, Object> enviarConToken(HttpRequest.Builder solicitud, String ruta) {
        if (token == null || !reloj.instant().isBefore(tokenVence)) {
            autenticar();
        }
        try {
            return enviar(solicitud.copy().header("Authorization", "Bearer " + token), ruta);
        } catch (TokenRechazado e) {
            autenticar();
            return enviar(solicitud.copy().header("Authorization", "Bearer " + token), ruta);
        }
    }

    private HttpRequest.Builder base(String ruta) {
        return HttpRequest.newBuilder(URI.create(config.url() + ruta)).timeout(TIEMPO_RESPUESTA)
                .header("Accept", "application/json").header("Response-Language", "es");
    }

    private Map<String, Object> enviar(HttpRequest.Builder solicitud, String ruta) {
        HttpResponse<String> respuesta;
        try {
            respuesta = http.send(solicitud.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new PagoFacilException("No se pudo comunicar con PagoFácil (" + ruta + "): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PagoFacilException("Se interrumpió la comunicación con PagoFácil (" + ruta + ").", e);
        }
        if (respuesta.statusCode() == 401 && !"/login".equals(ruta)) {
            throw new TokenRechazado();
        }
        Map<String, Object> cuerpo;
        try {
            cuerpo = objeto(Json.leer(respuesta.body()), ruta);
        } catch (IllegalArgumentException | PagoFacilException e) {
            throw new PagoFacilException("PagoFácil respondió HTTP " + respuesta.statusCode() + " sin un JSON válido en " + ruta + ".");
        }
        BigDecimal error = numero(cuerpo.get("error"));
        if (respuesta.statusCode() >= 400 || error == null || error.signum() != 0) {
            String mensaje = texto(cuerpo.get("message"));
            throw new PagoFacilException("PagoFácil rechazó " + ruta + " (HTTP " + respuesta.statusCode() + "): "
                    + (mensaje == null ? "sin mensaje" : mensaje));
        }
        return cuerpo;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objeto(Object valor, String ruta) {
        if (valor instanceof Map<?, ?> mapa) {
            return (Map<String, Object>) mapa;
        }
        throw new PagoFacilException("Respuesta inesperada de PagoFácil en " + ruta + ".");
    }

    private static String texto(Object valor) {
        if (valor == null) {
            return null;
        }
        return valor instanceof BigDecimal n ? n.toPlainString() : String.valueOf(valor);
    }

    private static BigDecimal numero(Object valor) {
        if (valor instanceof BigDecimal n) {
            return n;
        }
        if (valor instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Señal interna: el token venció o fue revocado antes de lo informado. */
    private static final class TokenRechazado extends RuntimeException {
        TokenRechazado() {
            super(null, null, false, false);
        }
    }
}
