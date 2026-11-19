package com.feder.pagofacil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.configuracion.AppConfig;
import com.feder.modelo.MetodoPago;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.pagos.PasarelaPagos;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * La pasarela contra un PagoFácil simulado en 127.0.0.1 que responde como indica el documento de integración
 * (v1.1.0): no se usa la API real ni se mueve dinero.
 */
class PasarelaPagoFacilTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private static final PasarelaPagos.Pagador PAGADOR =
            new PasarelaPagos.Pagador(14, "José Peña", "1234567", "70000000", "jose@feder.example");

    private HttpServer servidor;
    private final AtomicInteger logins = new AtomicInteger();
    private final List<String> cuerpos = new ArrayList<>();
    private final List<String> autorizaciones = new ArrayList<>();
    private volatile String respuestaGenerar;
    private volatile String respuestaConsulta;
    private volatile int rechazarProximoToken;

    @BeforeEach
    void iniciar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/api/services/v2/login", x -> {
            logins.incrementAndGet();
            boolean credenciales = "servicio-prueba".equals(x.getRequestHeaders().getFirst("tcTokenService"))
                    && "secreto-prueba".equals(x.getRequestHeaders().getFirst("tcTokenSecret"));
            responder(x, 200, credenciales
                    ? "{\"error\":0,\"status\":1,\"message\":\"ok\",\"values\":{\"accessToken\":\"tok-" + logins.get()
                    + "\",\"tokenType\":\"bearer\",\"expiresInMinutes\":789.71}}"
                    : "{\"error\":1,\"status\":0,\"message\":\"Credenciales inválidas\",\"values\":null}");
        });
        servidor.createContext("/api/services/v2/generate-qr", x -> protegido(x, () -> respuestaGenerar));
        servidor.createContext("/api/services/v2/query-transaction", x -> protegido(x, () -> respuestaConsulta));
        servidor.start();
        respuestaGenerar = "{\"error\":0,\"status\":2007,\"message\":\"QR Generado Correctamente.\",\"values\":{"
                + "\"transactionId\":\"987654\",\"paymentMethodTransactionId\":\"PAG-PED-1-01\",\"status\":1,"
                + "\"expirationDate\":\"2026-11-18 23:59:59\",\"qrBase64\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}}";
    }

    @AfterEach
    void detener() {
        servidor.stop(0);
    }

    private interface Proveedor {
        String get();
    }

    private void protegido(HttpExchange x, Proveedor respuesta) throws IOException {
        String autorizacion = x.getRequestHeaders().getFirst("Authorization");
        autorizaciones.add(autorizacion);
        cuerpos.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if (rechazarProximoToken > 0) {
            rechazarProximoToken--;
            responder(x, 401, "{\"error\":1,\"status\":401,\"message\":\"Token expirado\",\"values\":null}");
            return;
        }
        responder(x, 200, respuesta.get());
    }

    private static void responder(HttpExchange x, int codigo, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        x.sendResponseHeaders(codigo, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private ConfiguracionPagoFacil config(Integer estadoPagado) {
        return new ConfiguracionPagoFacil("http://127.0.0.1:" + servidor.getAddress().getPort() + "/api/services/v2",
                "servicio-prueba", "secreto-prueba", 4, estadoPagado, null, 60);
    }

    private static PasarelaPagos.SolicitudCobro qr(String clave, String monto) {
        return new PasarelaPagos.SolicitudCobro(clave, "PED-1", MetodoPago.QR, new BigDecimal(monto), PAGADOR);
    }

    @Test
    void generaElQrConLaClaveDelPagoYDevuelveLaImagen() {
        PasarelaPagoFacil pasarela = new PasarelaPagoFacil(config(2));
        PasarelaPagos.InstruccionesCobro r = pasarela.prepararCobro(qr("PAG-PED-1-01", "120.50"));

        assertArrayEquals(PNG, r.imagenQr());
        assertEquals("987654", r.referenciaIntento());
        assertTrue(r.indicaciones().contains("QR adjunto"), r.indicaciones());
        assertTrue(r.indicaciones().contains("2026-11-18 23:59:59"), r.indicaciones());
        String cuerpo = cuerpos.get(0);
        assertTrue(cuerpo.contains("\"paymentNumber\":\"PAG-PED-1-01\""), cuerpo);
        assertTrue(cuerpo.contains("\"amount\":120.50"), cuerpo);
        assertTrue(cuerpo.contains("\"paymentMethod\":4"), cuerpo);
        assertTrue(cuerpo.contains("\"clientName\":\"José Peña\""), cuerpo);
        assertFalse(cuerpo.contains("callbackUrl"), "sin callback configurado no se envía: se confirma consultando");
        assertEquals("Bearer tok-1", autorizaciones.get(0));
    }

    @Test
    void reutilizaElTokenYLoRenuevaSiElProveedorLoRechaza() {
        PasarelaPagoFacil pasarela = new PasarelaPagoFacil(config(2));
        pasarela.prepararCobro(qr("A-1", "10.00"));
        pasarela.prepararCobro(qr("A-2", "10.00"));
        assertEquals(1, logins.get(), "el token vigente se reutiliza");

        rechazarProximoToken = 1;
        pasarela.prepararCobro(qr("A-3", "10.00"));
        assertEquals(2, logins.get(), "ante un 401 se vuelve a autenticar una vez");
        assertEquals("Bearer tok-2", autorizaciones.get(autorizaciones.size() - 1));
    }

    @Test
    void unErrorDelProveedorRechazaElPagoConSuMotivo() {
        respuestaGenerar = "{\"error\":1,\"status\":3001,\"message\":\"El monto supera el máximo por transacción\",\"values\":null}";
        NegocioException e = assertThrows(NegocioException.class,
                () -> new PasarelaPagoFacil(config(2)).prepararCobro(qr("B-1", "99999.00")));
        assertTrue(e.getMessage().contains("El monto supera el máximo por transacción"), e.getMessage());
        assertTrue(e.getMessage().contains("No se registró el pago"), e.getMessage());
    }

    @Test
    void credencialesInvalidasSeInformanSinMostrarLosTokens() {
        ConfiguracionPagoFacil malas = new ConfiguracionPagoFacil(config(2).url(), "otro", "otro-secreto", 4, 2, null, 60);
        NegocioException e = assertThrows(NegocioException.class, () -> new PasarelaPagoFacil(malas).prepararCobro(qr("C-1", "5.00")));
        assertTrue(e.getMessage().contains("Credenciales inválidas"), e.getMessage());
        assertFalse(e.getMessage().contains("otro-secreto"));
        assertFalse(malas.toString().contains("otro-secreto"));
    }

    @Test
    void sinMetodoDePagoConfiguradoNoSeLlamaAlProveedor() {
        ConfiguracionPagoFacil sinMetodo = new ConfiguracionPagoFacil(config(2).url(), "servicio-prueba", "secreto-prueba",
                null, 2, null, 60);
        assertThrows(NegocioException.class, () -> new PasarelaPagoFacil(sinMetodo).prepararCobro(qr("D-1", "5.00")));
        assertEquals(0, logins.get());
    }

    @Test
    void laTarjetaSigueElFlujoLocalSinLlamarAlProveedor() {
        PasarelaPagos.InstruccionesCobro r = new PasarelaPagoFacil(config(2)).prepararCobro(
                new PasarelaPagos.SolicitudCobro("E-1", "PED-1", MetodoPago.TARJETA, new BigDecimal("5.00"), PAGADOR));
        assertNull(r.imagenQr());
        assertEquals(0, logins.get());
    }

    @Test
    void consultaInformaElCobroSoloConElEstadoPagadoYFecha() {
        PasarelaPagoFacil pasarela = new PasarelaPagoFacil(config(2));
        respuestaConsulta = consulta(2, "120.50", "2026-11-18 10:15:00");
        Optional<PasarelaPagos.CobroVerificado> cobro = pasarela.consultarCobro("PAG-PED-1-01");
        assertTrue(cobro.isPresent());
        assertEquals("PAGOFACIL-987654", cobro.get().referencia());
        assertEquals(0, new BigDecimal("120.50").compareTo(cobro.get().monto()));
        assertTrue(cuerpos.get(0).contains("\"companyTransactionId\":\"PAG-PED-1-01\""), cuerpos.get(0));

        respuestaConsulta = consulta(1, "120.50", "");
        assertTrue(pasarela.consultarCobro("PAG-PED-1-01").isEmpty(), "otro estado: todavía no pagado");
        respuestaConsulta = consulta(2, "120.50", null);
        assertTrue(pasarela.consultarCobro("PAG-PED-1-01").isEmpty(), "sin fecha de pago no se da por cobrado");
    }

    @Test
    void sinEstadoPagadoConfiguradoNuncaSeConfirmaNiSeConsulta() {
        respuestaConsulta = consulta(2, "1.00", "2026-11-18 10:15:00");
        assertTrue(new PasarelaPagoFacil(config(null)).consultarCobro("X").isEmpty());
        assertEquals(0, logins.get());
    }

    @Test
    void unaTransaccionDesconocidaNoEstaPagada() {
        respuestaConsulta = "{\"error\":1,\"status\":404,\"message\":\"Transacción no encontrada\",\"values\":null}";
        assertTrue(new PasarelaPagoFacil(config(2)).consultarCobro("PAG-ANTIGUO").isEmpty());
    }

    @Test
    void laConfiguracionSeLeeDeAppConfigYSinTokenSeUsaLaPasarelaLocal() {
        assertNull(ConfiguracionPagoFacil.desde(AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.of())));
        ConfiguracionPagoFacil c = ConfiguracionPagoFacil.desde(AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.of(
                "pagofacil.token.servicio", "s", "pagofacil.token.secreto", "t", "pagofacil.metodo.pago", "4")));
        assertEquals(ConfiguracionPagoFacil.URL_POR_DEFECTO, c.url());
        assertEquals(4, c.metodoPago());
        assertNull(c.estadoPagado());
        assertEquals(60, c.consultaSegundos());
        assertFalse(AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.of("pagofacil.token.secreto", "muy-secreto"))
                .toString().contains("muy-secreto"));
    }

    private static String consulta(int estado, String monto, String fecha) {
        return "{\"error\":0,\"status\":2008,\"message\":\"Consulta realizada.\",\"values\":{\"pagofacilTransactionId\":987654,"
                + "\"companyTransactionId\":\"PAG-PED-1-01\",\"paymentMethodId\":4,\"paymentStatus\":" + estado
                + ",\"amount\":\"" + monto + "\",\"currencyId\":2,\"paymentDate\":" + (fecha == null ? "null" : "\"" + fecha + "\"") + "}}";
    }
}
