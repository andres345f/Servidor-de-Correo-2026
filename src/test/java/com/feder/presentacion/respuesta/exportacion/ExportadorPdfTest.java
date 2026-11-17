package com.feder.presentacion.respuesta.exportacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.comun.ResultadoOperacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Estructura del PDF 1.4 escrito a mano: cabecera, xref, flujos, codificación WinAnsi y saltos de página. */
class ExportadorPdfTest {

    private static ResultadoOperacion reporte(int filas) {
        ResultadoOperacion r = ResultadoOperacion.exito("Reporte de prueba: Añez Méndez", "¿Cómo? (año 2026) a\\b — →")
                .detalle("Comunidad", "Villa Colmenar, Yapacaní")
                .detalle("Nota larga", "Un detalle muy largo que no cabe en una sola línea del documento y por eso debe "
                        + "continuar en las líneas siguientes con sangría, sin salirse del margen derecho de la página A4.");
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Pedidos", List.of("Código", "Descripción", "Total (Bs)"));
        for (int i = 1; i <= filas; i++) {
            tabla.fila(String.format("PED-2026-%04d", i), "Jabón artesanal de totaí n.º " + i, "18.00");
        }
        return r.tabla(tabla);
    }

    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("Empieza con %PDF-1.4 y termina con %%EOF")
    void cabeceraYFin() {
        String pdf = latin1(new ExportadorPdf().exportar(reporte(3)));
        assertTrue(pdf.startsWith("%PDF-1.4\n"));
        assertTrue(pdf.stripTrailing().endsWith("%%EOF"));
    }

    @Test
    @DisplayName("startxref apunta a la tabla xref y cada entrada apunta a su «n 0 obj»")
    void desplazamientosDeLaXref() {
        String pdf = latin1(new ExportadorPdf().exportar(reporte(200)));
        Matcher inicio = Pattern.compile("startxref\\n(\\d+)\\n%%EOF").matcher(pdf);
        assertTrue(inicio.find());
        int xref = Integer.parseInt(inicio.group(1));
        assertTrue(pdf.startsWith("xref\n0 ", xref));
        String[] lineas = pdf.substring(xref).split("\n");
        int total = Integer.parseInt(lineas[1].split(" ")[1]);
        assertEquals("0000000000 65535 f ", lineas[2]);
        for (int objeto = 1; objeto < total; objeto++) {
            String entrada = lineas[2 + objeto];
            assertEquals(19, entrada.length(), "Cada entrada mide 20 bytes con el salto de línea");
            int desplazamiento = Integer.parseInt(entrada.substring(0, 10));
            assertTrue(pdf.startsWith(objeto + " 0 obj\n", desplazamiento), "Objeto " + objeto);
        }
        assertTrue(pdf.contains("/Size " + total + " /Root 1 0 R"));
    }

    @Test
    @DisplayName("Cada /Length coincide con los bytes del flujo")
    void longitudDeLosFlujos() {
        byte[] bytes = new ExportadorPdf().exportar(reporte(50));
        String pdf = latin1(bytes);
        Matcher flujo = Pattern.compile("/Length (\\d+) >>\\nstream\\n").matcher(pdf);
        int flujos = 0;
        while (flujo.find()) {
            int largo = Integer.parseInt(flujo.group(1));
            assertTrue(pdf.startsWith("\nendstream", flujo.end() + largo));
            flujos++;
        }
        assertTrue(flujos >= 1);
    }

    @Test
    @DisplayName("El texto en español se codifica en WinAnsi y se escapan ( ) y \\")
    void codificacionWinAnsi() {
        byte[] bytes = new ExportadorPdf().exportar(reporte(1));
        String pdf = latin1(bytes);
        assertTrue(pdf.contains("(Reporte de prueba: Añez Méndez) Tj"), "Las letras acentuadas son bytes WinAnsi");
        assertTrue(pdf.contains("Jabón artesanal de totaí n.º 1"));
        // Paréntesis y barra invertida escapados; la raya es 0x97 en WinAnsi y la flecha, que no existe en WinAnsi, pasa a '?'.
        assertTrue(pdf.contains("(¿Cómo? \\(año 2026\\) a\\\\b \u0097 ?) Tj"), "Mensaje codificado y escapado");
        assertTrue(pdf.contains("/BaseFont /Helvetica /Encoding /WinAnsiEncoding"));
        assertTrue(pdf.contains("/BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding"));
        assertTrue(pdf.contains("/MediaBox [0 0 595 842]"), "Tamaño A4");
    }

    @Test
    @DisplayName("Con 200 filas la tabla continúa en varias páginas y repite los encabezados")
    void saltoDePagina() {
        String pdf = latin1(new ExportadorPdf().exportar(reporte(200)));
        Matcher cuenta = Pattern.compile("/Count (\\d+)").matcher(pdf);
        assertTrue(cuenta.find());
        int paginas = Integer.parseInt(cuenta.group(1));
        assertTrue(paginas >= 3, "200 filas no caben en dos páginas A4: " + paginas);
        assertEquals(paginas, contar(pdf, "/Type /Page /Parent"));
        assertEquals(paginas, contar(pdf, "(Descripción) Tj"), "Encabezados repetidos en cada página");
        assertTrue(pdf.contains("(PED-2026-0200) Tj"), "La última fila no se pierde");
        assertTrue(pdf.contains("Página " + paginas + " de " + paginas));
    }

    @Test
    @DisplayName("Ninguna línea de texto se escribe por debajo del margen inferior ni fuera del ancho de la página")
    void textoDentroDeLaPagina() {
        String pdf = latin1(new ExportadorPdf().exportar(reporte(120)));
        Matcher posicion = Pattern.compile("1 0 0 1 (-?[\\d.]+) (-?[\\d.]+) Tm").matcher(pdf);
        while (posicion.find()) {
            double x = Double.parseDouble(posicion.group(1));
            double y = Double.parseDouble(posicion.group(2));
            assertTrue(x >= 40 && x < 555, "x = " + x);
            assertTrue(y >= 28 && y <= 802, "y = " + y);
        }
    }

    @Test
    @DisplayName("En una tabla más ancha que la página se recortan los textos largos y no los códigos cortos")
    void tablaAncha() {
        List<String> columnas = List.of("Lote", "Producto", "Comunidad de origen", "Saldo", "Reservado", "Estado actual",
                "Ubicación", "Ingreso", "Vencimiento de origen", "Aviso", "Observación");
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Lotes", columnas);
        String largo = "Comunidad Totaizal de San Juan (San José de Chiquitos), con una descripción muy extensa";
        tabla.fila("LF-2026-001", "FED-ACE-020", largo, "183", "2", "DISPONIBLE", "Estante A-01", "2026-03-26",
                "2027-03-02", "Próximo a vencer", largo);
        String pdf = latin1(new ExportadorPdf().exportar(ResultadoOperacion.exito("Inventario", "").tabla(tabla)));
        assertTrue(pdf.contains("(LF-2026-001) Tj"));
        assertTrue(pdf.contains("(FED-ACE-020) Tj"));
        assertTrue(pdf.contains("(DISPONIBLE) Tj"));
        assertTrue(pdf.contains("...) Tj"), "El texto largo se recorta con puntos suspensivos");
    }

    private static int contar(String texto, String buscado) {
        int veces = 0;
        for (int i = texto.indexOf(buscado); i >= 0; i = texto.indexOf(buscado, i + 1)) {
            veces++;
        }
        return veces;
    }
}
