package com.feder.pagofacil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    @SuppressWarnings("unchecked")
    void leeLaRespuestaEstandarDePagoFacil() {
        Map<String, Object> r = (Map<String, Object>) Json.leer("""
                {"error": 0, "status": 2006, "message": "Lista recuperada.",
                 "values": [{"paymentMethodId": 4, "paymentMethodName": "QR Simple", "maxAmountPerDay": 10000.00,
                             "activo": true, "nota": null}]}""");
        assertEquals(new BigDecimal("0"), r.get("error"));
        List<Object> valores = (List<Object>) r.get("values");
        Map<String, Object> metodo = (Map<String, Object>) valores.get(0);
        assertEquals(new BigDecimal("4"), metodo.get("paymentMethodId"));
        assertEquals("QR Simple", metodo.get("paymentMethodName"));
        assertEquals(0, new BigDecimal("10000").compareTo((BigDecimal) metodo.get("maxAmountPerDay")));
        assertEquals(Boolean.TRUE, metodo.get("activo"));
        assertNull(metodo.get("nota"));
    }

    @Test
    void decodificaEscapesYUnicode() {
        assertEquals("Peña \"QR\"\n/ok", Json.leer("\"Pe\\u00f1a \\\"QR\\\"\\n\\/ok\""));
    }

    @Test
    void escribeTextosEscapados() {
        String original = "José \"Peña\"\\ \n\t\u0001";
        assertEquals(original, Json.leer(Json.texto(original)));
        assertEquals("null", Json.texto(null));
    }

    @Test
    void rechazaJsonMalFormado() {
        assertThrows(IllegalArgumentException.class, () -> Json.leer("{\"a\": 1"));
        assertThrows(IllegalArgumentException.class, () -> Json.leer("{\"a\" 1}"));
        assertThrows(IllegalArgumentException.class, () -> Json.leer("[1, 2] x"));
        assertThrows(IllegalArgumentException.class, () -> Json.leer("<html>Error</html>"));
        assertThrows(IllegalArgumentException.class, () -> Json.leer(""));
    }
}
