package com.feder.presentacion.respuesta.exportacion;

import com.feder.negocio.comun.ResultadoOperacion;

import java.util.List;

/**
 * Documento PDF 1.4 escrito a mano, sin bibliotecas: título, mensaje, detalles y tablas como filas de texto
 * alineadas, en páginas A4 con saltos de página automáticos y la numeración al pie.
 */
public final class ExportadorPdf implements ExportadorReporte {

    private static final double TAMANO_TITULO = 15;
    private static final double TAMANO_TEXTO = 9;
    private static final double SANGRIA_DETALLE = 12;

    @Override
    public byte[] exportar(ResultadoOperacion resultado) {
        MaquetadorPdf maquetador = new MaquetadorPdf();
        maquetador.parrafo(resultado.titulo(), true, TAMANO_TITULO);
        maquetador.espacio(4);
        if (!resultado.mensaje().isEmpty()) {
            maquetador.parrafo(resultado.mensaje(), false, TAMANO_TEXTO);
            maquetador.espacio(4);
        }
        for (String[] detalle : resultado.detalles()) {
            detalle(maquetador, detalle[0], detalle[1]);
        }
        for (ResultadoOperacion.Tabla tabla : resultado.tablas()) {
            TablaPdf.dibujar(maquetador, tabla);
        }
        return DocumentoPdf.construir(resultado.titulo(), maquetador.terminar("FEDER - " + resultado.titulo()));
    }

    @Override
    public String extension() {
        return "pdf";
    }

    @Override
    public String tipoMime() {
        return "application/pdf";
    }

    /** «Clave: valor» con la clave en negrita; si el valor no cabe, continúa en líneas con sangría. */
    private static void detalle(MaquetadorPdf m, String clave, String valor) {
        String etiqueta = clave + ": ";
        double anchoEtiqueta = MetricasHelvetica.ancho(etiqueta, true, TAMANO_TEXTO);
        double base = m.reservarLinea(TAMANO_TEXTO);
        m.texto(MaquetadorPdf.MARGEN, base, etiqueta, true, TAMANO_TEXTO);
        double restante = MaquetadorPdf.ANCHO_UTIL - anchoEtiqueta;
        List<String> lineas = MaquetadorPdf.cortar(valor, false, TAMANO_TEXTO, restante);
        if (lineas.size() > 1) {
            lineas = MaquetadorPdf.cortar(valor, false, TAMANO_TEXTO, MaquetadorPdf.ANCHO_UTIL - SANGRIA_DETALLE);
            for (String linea : lineas) {
                m.texto(MaquetadorPdf.MARGEN + SANGRIA_DETALLE, m.reservarLinea(TAMANO_TEXTO), linea, false, TAMANO_TEXTO);
            }
            return;
        }
        m.texto(MaquetadorPdf.MARGEN + anchoEtiqueta, base, lineas.get(0), false, TAMANO_TEXTO);
    }
}
