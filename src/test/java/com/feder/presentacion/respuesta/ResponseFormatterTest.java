package com.feder.presentacion.respuesta;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.comun.ResultadoOperacion;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResponseFormatterTest {

    private final ResponseFormatter formatter = new ResponseFormatter();

    private static ResultadoOperacion listado() {
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Personas", List.of("CI", "Nombre", "Rol"))
                .fila("9900090", "Ana Lucía Ejemplo", "Estudiante")
                .fila("123", "Ñusta", "Docente");
        return ResultadoOperacion.exito("Personas registradas", "Se encontraron 2 personas.")
                .detalle("Total", 2)
                .tabla(tabla);
    }

    @Test
    void construyeAsuntosDeExitoYError() {
        assertEquals("FEDER - LISPER - Éxito: Personas registradas", formatter.formatear(listado(), "LISPER[\"*\"]").asunto());
        RespuestaFormateada error = formatter.formatear(
                ResultadoOperacion.error("Comando desconocido", "No existe"), "XYZ[\"1\"]");
        assertEquals("FEDER - XYZ - Error: Comando desconocido", error.asunto());
    }

    @Test
    void elAsuntoSoloNombraUnComandoReconocible() {
        ResultadoOperacion ok = ResultadoOperacion.exito("Usuarios", "");
        assertEquals("FEDER - LISUSU - Éxito: Usuarios", formatter.formatear(ok, "  lisusu [\"*\"]").asunto());
        assertEquals("FEDER - Éxito: Usuarios", formatter.formatear(ok, "hola").asunto(), "sin corchete no hay nombre");
        assertEquals("FEDER - Éxito: Usuarios", formatter.formatear(ok, "HÉLP[\"*\"]").asunto(), "solo letras A-Z");
        assertEquals("FEDER - Éxito: Usuarios", formatter.formatear(ok, "ABCDEFGHIJKLM[\"*\"]").asunto(), "más de 12 letras");
        assertEquals("FEDER - Éxito: Usuarios", formatter.formatear(ok, null).asunto());
    }

    @Test
    void enUnErrorLoRecibidoVaArribaYEnUnExitoAbajo() {
        String error = formatter.formatear(ResultadoOperacion.error("Sintaxis inválida", "Falta ]."), "LISUSU[\"*\"").html();
        assertTrue(error.contains("<div class=\"comando recibido\">"), error);
        assertTrue(error.indexOf("Usted envió") < error.indexOf("class=\"mensaje\""), "arriba del mensaje");
        assertFalse(error.contains("Comando procesado"));
        assertTrue(formatter.formatear(ResultadoOperacion.error("x", "y"), "A[\"1\"]").textoPlano().contains("Usted envió: A[\"1\"]"));

        String exito = formatter.formatear(listado(), "LISPER[\"*\"]").html();
        assertTrue(exito.indexOf("Comando procesado") > exito.indexOf("class=\"mensaje\""), "abajo, como antes");
        assertFalse(exito.contains("Usted envió"));
    }

    @Test
    void elAsuntoNuncaContieneSaltosDeLinea() {
        RespuestaFormateada r = formatter.formatear(
                ResultadoOperacion.error("Título\r\nBcc: victima@x.example", ""), "x");
        assertFalse(r.asunto().contains("\r") || r.asunto().contains("\n"));
        assertEquals("FEDER - Error: Título Bcc: victima@x.example", r.asunto());
    }

    @Test
    void escapaTodoElContenidoHtml() {
        ResultadoOperacion peligroso = ResultadoOperacion.error("<script>alert(1)</script>", "Mensaje <b>falso</b> & \"x\"")
                .detalle("<img src=x onerror=alert(1)>", "'valor'")
                .tabla(new ResultadoOperacion.Tabla("<i>t</i>", List.of("<th>")).fila("<script>robar()</script>"));
        String html = formatter.formatear(peligroso, "INSCAT[\"<script>\"]").html();
        assertFalse(html.contains("<script>"), html);
        assertFalse(html.contains("<img"), html);
        assertFalse(html.contains("<b>falso"), html);
        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertTrue(html.contains("Mensaje &lt;b&gt;falso&lt;/b&gt; &amp; &quot;x&quot;"));
        assertTrue(html.contains("&#39;valor&#39;"));
        assertTrue(html.contains("INSCAT[&quot;&lt;script&gt;&quot;]"));
    }

    @Test
    void elHtmlUsaLasPlantillasYEmbebeLosEstilos() {
        String html = formatter.formatear(listado(), "LISPER[\"*\"]").html();
        assertTrue(html.startsWith("<!DOCTYPE html>"));
        assertTrue(html.contains("<style>"));
        assertTrue(html.contains("max-width: 720px"));
        assertFalse(html.contains("{{"), "no deben quedar marcadores sin reemplazar");
        assertFalse(html.contains("<script") || html.contains("http://") || html.contains("https://"));
        assertTrue(html.contains("<th scope=\"col\">Nombre</th>"));
        assertTrue(html.contains("<td>Ana Lucía Ejemplo</td>"));
        assertTrue(html.contains("2 registros"));
        assertTrue(html.contains("LISPER[&quot;*&quot;]"));
        assertTrue(html.contains(ResponseFormatter.PIE));
    }

    @Test
    void elTextoPlanoAlineaLasTablas() {
        String texto = formatter.formatear(listado(), "LISPER[\"*\"]").textoPlano();
        assertTrue(texto.contains("Comando procesado: LISPER[\"*\"]"));
        assertTrue(texto.contains("Se encontraron 2 personas."));
        assertTrue(texto.contains("Total : 2"));
        assertTrue(texto.contains(ResponseFormatter.PIE));
        List<String> filasTabla = Arrays.stream(texto.split("\n"))
                .filter(l -> l.startsWith("|") || l.startsWith("+"))
                .toList();
        assertEquals(6, filasTabla.size(), texto);
        int ancho = filasTabla.get(0).codePointCount(0, filasTabla.get(0).length());
        for (String fila : filasTabla) {
            assertEquals(ancho, fila.codePointCount(0, fila.length()), "fila desalineada: " + fila);
        }
        assertTrue(texto.contains("| 9900090 | Ana Lucía Ejemplo | Estudiante |"));
        assertTrue(texto.contains("| 123     | Ñusta             | Docente    |"));
    }

    @Test
    void indicaTablasVaciasYLimpiaSaltosEnCeldas() {
        ResultadoOperacion r = ResultadoOperacion.exito("Vacío", "")
                .tabla(new ResultadoOperacion.Tabla("Pedidos", List.of("Código")))
                .tabla(new ResultadoOperacion.Tabla("Notas", List.of("Texto")).fila("línea 1\r\nlínea 2"));
        RespuestaFormateada respuesta = formatter.formatear(r, null);
        assertTrue(respuesta.textoPlano().contains("Sin registros."));
        assertTrue(respuesta.textoPlano().contains("| línea 1 línea 2 |"));
        assertTrue(respuesta.html().contains("Sin registros."));
        assertTrue(respuesta.textoPlano().contains("(sin comando)"));
    }
}
