package com.feder.presentacion.respuesta.exportacion;

import com.feder.negocio.comun.ResultadoOperacion;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Libro de Excel en formato XML 2003 (SpreadsheetML), escrito sin bibliotecas externas.
 * La primera hoja lleva el título, el mensaje y los detalles; después, una hoja por tabla.
 * Las celdas cuyo texto es un número decimal se escriben como Number para que se puedan sumar.
 */
public final class ExportadorExcel implements ExportadorReporte {

    private static final int LARGO_MAXIMO_HOJA = 31;
    // Sin ceros a la izquierda: un código como "0012" se conserva como texto.
    private static final Pattern NUMERO = Pattern.compile("-?(0|[1-9]\\d{0,14})(\\.\\d{1,6})?");
    private static final Pattern CARACTERES_PROHIBIDOS_HOJA = Pattern.compile("[\\\\/?*\\[\\]:]");

    @Override
    public byte[] exportar(ResultadoOperacion resultado) {
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<?mso-application progid=\"Excel.Sheet\"?>\n")
                .append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"")
                .append(" xmlns:o=\"urn:schemas-microsoft-com:office:office\"")
                .append(" xmlns:x=\"urn:schemas-microsoft-com:office:excel\"")
                .append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n");
        estilos(xml);
        Set<String> nombresUsados = new HashSet<>();
        hojaResumen(xml, resultado, nombreUnico("Resumen", nombresUsados));
        for (ResultadoOperacion.Tabla tabla : resultado.tablas()) {
            hojaTabla(xml, tabla, nombreUnico(tabla.titulo(), nombresUsados));
        }
        xml.append("</Workbook>\n");
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String extension() {
        return "xls";
    }

    @Override
    public String tipoMime() {
        return "application/vnd.ms-excel";
    }

    private static void estilos(StringBuilder xml) {
        xml.append(" <Styles>\n")
                .append("  <Style ss:ID=\"titulo\"><Font ss:Bold=\"1\" ss:Size=\"14\"/></Style>\n")
                .append("  <Style ss:ID=\"clave\"><Font ss:Bold=\"1\"/></Style>\n")
                .append("  <Style ss:ID=\"encabezado\"><Font ss:Bold=\"1\"/>")
                .append("<Interior ss:Color=\"#D9E1F2\" ss:Pattern=\"Solid\"/></Style>\n")
                .append(" </Styles>\n");
    }

    private static void hojaResumen(StringBuilder xml, ResultadoOperacion resultado, String nombre) {
        abrirHoja(xml, nombre);
        fila(xml, List.of(resultado.titulo()), "titulo");
        if (!resultado.mensaje().isEmpty()) {
            fila(xml, List.of(resultado.mensaje()), null);
        }
        for (String[] detalle : resultado.detalles()) {
            xml.append("   <Row>");
            celda(xml, detalle[0], "clave");
            celda(xml, detalle[1], null);
            xml.append("</Row>\n");
        }
        cerrarHoja(xml);
    }

    private static void hojaTabla(StringBuilder xml, ResultadoOperacion.Tabla tabla, String nombre) {
        abrirHoja(xml, nombre);
        fila(xml, List.of(tabla.titulo()), "titulo");
        fila(xml, tabla.columnas(), "encabezado");
        for (List<String> fila : tabla.filas()) {
            fila(xml, fila, null);
        }
        cerrarHoja(xml);
    }

    private static void abrirHoja(StringBuilder xml, String nombre) {
        xml.append(" <Worksheet ss:Name=\"").append(escapar(nombre)).append("\">\n  <Table>\n");
    }

    private static void cerrarHoja(StringBuilder xml) {
        xml.append("  </Table>\n </Worksheet>\n");
    }

    private static void fila(StringBuilder xml, List<String> valores, String estilo) {
        xml.append("   <Row>");
        for (String valor : valores) {
            celda(xml, valor, estilo);
        }
        xml.append("</Row>\n");
    }

    private static void celda(StringBuilder xml, String valor, String estilo) {
        String texto = valor == null ? "" : valor;
        boolean numero = estilo == null && NUMERO.matcher(texto).matches();
        xml.append("<Cell");
        if (estilo != null) {
            xml.append(" ss:StyleID=\"").append(estilo).append('"');
        }
        xml.append("><Data ss:Type=\"").append(numero ? "Number" : "String").append("\">")
                .append(escapar(texto)).append("</Data></Cell>");
    }

    /** Nombre de hoja válido para Excel: sin caracteres prohibidos, máximo 31 caracteres y sin repetir. */
    static String nombreUnico(String titulo, Set<String> usados) {
        String base = CARACTERES_PROHIBIDOS_HOJA.matcher(titulo == null ? "" : titulo).replaceAll(" ").strip();
        if (base.isEmpty()) {
            base = "Hoja";
        }
        base = recortar(base, LARGO_MAXIMO_HOJA);
        String nombre = base;
        for (int i = 2; !usados.add(nombre.toLowerCase(Locale.ROOT)); i++) {
            String sufijo = " (" + i + ")";
            nombre = recortar(base, LARGO_MAXIMO_HOJA - sufijo.length()).strip() + sufijo;
        }
        return nombre;
    }

    private static String recortar(String texto, int largo) {
        return texto.length() <= largo ? texto : texto.substring(0, largo);
    }

    /** Escapa los caracteres especiales de XML y descarta los caracteres de control que XML 1.0 no admite. */
    static String escapar(String texto) {
        StringBuilder sb = new StringBuilder(texto.length() + 16);
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '&':
                    sb.append("&amp;");
                    break;
                case '<':
                    sb.append("&lt;");
                    break;
                case '>':
                    sb.append("&gt;");
                    break;
                case '"':
                    sb.append("&quot;");
                    break;
                case '\'':
                    sb.append("&apos;");
                    break;
                case '\n':
                    sb.append("&#10;");
                    break;
                default:
                    if ((c >= 0x20 && c != 0xFFFE && c != 0xFFFF) || c == '\t' || c == '\r') {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
