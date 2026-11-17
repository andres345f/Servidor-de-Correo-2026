package com.feder.presentacion.respuesta.exportacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.FormatoReporte;
import com.feder.negocio.comun.ResultadoOperacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;

/** Libro SpreadsheetML 2003: XML bien formado, hojas, escapes y tipos de celda. */
class ExportadorExcelTest {

    private static final String NS = "urn:schemas-microsoft-com:office:spreadsheet";

    private static Document leer(byte[] xml) throws Exception {
        DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
        fabrica.setNamespaceAware(true);
        fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return fabrica.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static ResultadoOperacion reporte() {
        ResultadoOperacion.Tabla ventas = new ResultadoOperacion.Tabla("Ventas: por producto [2026]",
                List.of("Código", "Producto", "Importe (Bs)"));
        ventas.fila("0012", "Miel <500 g> & \"especial\" 'Añez'", "1327.50");
        ventas.fila("PED-2026-0001", "Café\u0001 con control", "-56.00");
        ResultadoOperacion.Tabla repetida = new ResultadoOperacion.Tabla("Ventas: por producto [2026]", List.of("A"));
        repetida.fila("x");
        return ResultadoOperacion.exito("Reporte de ventas", "Mensaje con ñ y acentos: Méndez")
                .detalle("Período", "Del 2026-01-01 al 2026-12-31")
                .tabla(ventas).tabla(repetida);
    }

    @Test
    @DisplayName("El libro se lee con DocumentBuilder y tiene la hoja de resumen más una por tabla")
    void hojas() throws Exception {
        Document libro = leer(new ExportadorExcel().exportar(reporte()));
        assertEquals("Workbook", libro.getDocumentElement().getLocalName());
        NodeList hojas = libro.getElementsByTagNameNS(NS, "Worksheet");
        assertEquals(3, hojas.getLength());
        List<String> nombres = new ArrayList<>();
        for (int i = 0; i < hojas.getLength(); i++) {
            nombres.add(((Element) hojas.item(i)).getAttributeNS(NS, "Name"));
        }
        assertEquals("Resumen", nombres.get(0));
        assertEquals("Ventas  por producto  2026", nombres.get(1), "Sin caracteres prohibidos en el nombre de hoja");
        assertEquals("Ventas  por producto  2026 (2)", nombres.get(2), "Nombres de hoja únicos");
        assertTrue(textos(libro).containsAll(List.of("Reporte de ventas", "Mensaje con ñ y acentos: Méndez", "Período",
                "Del 2026-01-01 al 2026-12-31")));
    }

    @Test
    @DisplayName("Los valores se escapan y conservan; los decimales son Number y los códigos String")
    void escapesYTipos() throws Exception {
        byte[] bytes = new ExportadorExcel().exportar(reporte());
        Document libro = leer(bytes);
        assertTrue(textos(libro).contains("Miel <500 g> & \"especial\" 'Añez'"));
        assertTrue(textos(libro).contains("Café con control"), "Se descartan los caracteres de control");
        assertEquals("Number", tipoDe(libro, "1327.50"));
        assertEquals("Number", tipoDe(libro, "-56.00"));
        assertEquals("String", tipoDe(libro, "0012"), "Un código con ceros a la izquierda sigue siendo texto");
        assertEquals("String", tipoDe(libro, "PED-2026-0001"));
        assertEquals("String", tipoDe(libro, "Importe (Bs)"), "Los encabezados son texto");
    }

    @Test
    @DisplayName("Un título de hoja muy largo se recorta a 31 caracteres")
    void nombreLargo() {
        Set<String> usados = new HashSet<>();
        String nombre = ExportadorExcel.nombreUnico("Saldo pendiente de cobro de pedidos vigentes (información operativa)", usados);
        assertEquals(31, nombre.length());
        String segundo = ExportadorExcel.nombreUnico("Saldo pendiente de cobro de pedidos vigentes (otra)", usados);
        assertTrue(segundo.endsWith(" (2)") && segundo.length() <= 31, segundo);
    }

    @Test
    @DisplayName("La fábrica elige el exportador por formato")
    void fabrica() {
        ExportadorReporte excel = FabricaExportadores.para(FormatoReporte.EXCEL);
        assertInstanceOf(ExportadorExcel.class, excel);
        assertEquals("xls", excel.extension());
        assertEquals("application/vnd.ms-excel", excel.tipoMime());
        ExportadorReporte pdf = FabricaExportadores.para(FormatoReporte.PDF);
        assertInstanceOf(ExportadorPdf.class, pdf);
        assertEquals("pdf", pdf.extension());
        assertEquals("application/pdf", pdf.tipoMime());
        assertThrows(NullPointerException.class, () -> FabricaExportadores.para(null));
    }

    private static List<String> textos(Document libro) {
        NodeList datos = libro.getElementsByTagNameNS(NS, "Data");
        List<String> textos = new ArrayList<>();
        for (int i = 0; i < datos.getLength(); i++) {
            textos.add(datos.item(i).getTextContent());
        }
        return textos;
    }

    private static String tipoDe(Document libro, String texto) {
        NodeList datos = libro.getElementsByTagNameNS(NS, "Data");
        for (int i = 0; i < datos.getLength(); i++) {
            Element dato = (Element) datos.item(i);
            if (dato.getTextContent().equals(texto)) {
                return dato.getAttributeNS(NS, "Type");
            }
        }
        throw new AssertionError("No hay una celda con " + texto);
    }
}
