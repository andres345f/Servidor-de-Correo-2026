package com.feder.presentacion.respuesta.exportacion;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Escribe un PDF 1.4 mínimo y válido a partir de los flujos de contenido de cada página: catálogo, árbol de
 * páginas, las fuentes estándar Helvetica y Helvetica-Bold con WinAnsiEncoding, la tabla xref con el
 * desplazamiento exacto en bytes de cada objeto y el trailer.
 */
final class DocumentoPdf {

    static final double ANCHO_A4 = 595;
    static final double ALTO_A4 = 842;

    private static final int CATALOGO = 1;
    private static final int PAGINAS = 2;
    private static final int FUENTE_NORMAL = 3;
    private static final int FUENTE_NEGRITA = 4;
    private static final int INFORMACION = 5;
    private static final int PRIMERA_PAGINA = 6;

    private final ByteArrayOutputStream salida = new ByteArrayOutputStream(8192);
    private final List<Integer> desplazamientos = new ArrayList<>();

    private DocumentoPdf() {
    }

    static byte[] construir(String titulo, List<byte[]> contenidos) {
        return new DocumentoPdf().escribir(titulo, contenidos);
    }

    private byte[] escribir(String titulo, List<byte[]> contenidos) {
        ascii("%PDF-1.4\n");
        // Comentario con bytes altos: indica a los programas que el archivo es binario.
        salida.write(new byte[] {'%', (byte) 0xE2, (byte) 0xE3, (byte) 0xCF, (byte) 0xD3, '\n'}, 0, 6);
        objeto(CATALOGO, "<< /Type /Catalog /Pages " + PAGINAS + " 0 R >>");
        objeto(PAGINAS, arbolDePaginas(contenidos.size()));
        objeto(FUENTE_NORMAL, fuente("Helvetica"));
        objeto(FUENTE_NEGRITA, fuente("Helvetica-Bold"));
        iniciarObjeto(INFORMACION);
        ascii("<< /Title ");
        salida.writeBytes(CodificadorWinAnsi.cadenaPdf(titulo));
        ascii(" /Producer (FEDER) >>\nendobj\n");
        for (int i = 0; i < contenidos.size(); i++) {
            int pagina = PRIMERA_PAGINA + 2 * i;
            objeto(pagina, "<< /Type /Page /Parent " + PAGINAS + " 0 R /MediaBox [0 0 595 842] "
                    + "/Resources << /Font << /F1 " + FUENTE_NORMAL + " 0 R /F2 " + FUENTE_NEGRITA + " 0 R >> >> "
                    + "/Contents " + (pagina + 1) + " 0 R >>");
            flujo(pagina + 1, contenidos.get(i));
        }
        escribirXrefYTrailer();
        return salida.toByteArray();
    }

    private static String arbolDePaginas(int cantidad) {
        StringBuilder hijos = new StringBuilder();
        for (int i = 0; i < cantidad; i++) {
            hijos.append(i == 0 ? "" : " ").append(PRIMERA_PAGINA + 2 * i).append(" 0 R");
        }
        return "<< /Type /Pages /Kids [" + hijos + "] /Count " + cantidad + " >>";
    }

    private static String fuente(String nombre) {
        return "<< /Type /Font /Subtype /Type1 /BaseFont /" + nombre + " /Encoding /WinAnsiEncoding >>";
    }

    private void objeto(int numero, String diccionario) {
        iniciarObjeto(numero);
        ascii(diccionario + "\nendobj\n");
    }

    private void flujo(int numero, byte[] contenido) {
        iniciarObjeto(numero);
        ascii("<< /Length " + contenido.length + " >>\nstream\n");
        salida.writeBytes(contenido);
        ascii("\nendstream\nendobj\n");
    }

    /** Registra el desplazamiento del objeto antes de escribir su cabecera; los objetos se escriben en orden. */
    private void iniciarObjeto(int numero) {
        if (numero != desplazamientos.size() + 1) {
            throw new IllegalStateException("Los objetos del PDF deben escribirse en orden: se esperaba " + (desplazamientos.size() + 1));
        }
        desplazamientos.add(salida.size());
        ascii(numero + " 0 obj\n");
    }

    private void escribirXrefYTrailer() {
        int inicioXref = salida.size();
        int total = desplazamientos.size() + 1;
        StringBuilder xref = new StringBuilder("xref\n0 ").append(total).append('\n');
        // Cada entrada mide exactamente 20 bytes, incluido el fin de línea de dos caracteres.
        xref.append("0000000000 65535 f \n");
        for (int desplazamiento : desplazamientos) {
            xref.append(String.format("%010d 00000 n \n", desplazamiento));
        }
        xref.append("trailer\n<< /Size ").append(total).append(" /Root ").append(CATALOGO).append(" 0 R /Info ")
                .append(INFORMACION).append(" 0 R >>\nstartxref\n").append(inicioXref).append("\n%%EOF\n");
        ascii(xref.toString());
    }

    private void ascii(String texto) {
        salida.writeBytes(texto.getBytes(StandardCharsets.US_ASCII));
    }
}
