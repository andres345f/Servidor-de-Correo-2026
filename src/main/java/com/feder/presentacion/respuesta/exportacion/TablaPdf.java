package com.feder.presentacion.respuesta.exportacion;

import com.feder.negocio.comun.ResultadoOperacion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dibuja una tabla como filas de texto alineadas en columnas. El ancho de cada columna sale del dato más largo
 * y de la palabra más larga de su encabezado, que puede ocupar varias líneas. Si la tabla no cabe, se reduce la
 * letra hasta un mínimo legible y después se recortan las celdas con «...». En cada página nueva se repiten
 * los encabezados.
 */
final class TablaPdf {

    private static final double TAMANO_TITULO = 10.5;
    private static final double TAMANO_NORMAL = 8;
    private static final double TAMANO_MINIMO = 5.5;
    private static final double SEPARACION = 6;

    private final ResultadoOperacion.Tabla tabla;
    private double tamano = TAMANO_NORMAL;
    private double[] anchos;

    private TablaPdf(ResultadoOperacion.Tabla tabla) {
        this.tabla = tabla;
    }

    static void dibujar(MaquetadorPdf maquetador, ResultadoOperacion.Tabla tabla) {
        new TablaPdf(tabla).dibujarEn(maquetador);
    }

    private void dibujarEn(MaquetadorPdf m) {
        calcularAnchos();
        List<List<String>> encabezados = encabezadosEnLineas();
        int lineasEncabezado = encabezados.stream().mapToInt(List::size).max().orElse(1);
        // El título no queda solo al pie de una página: exigimos espacio para él, los encabezados y una fila.
        if (!m.caben(lineasEncabezado + 3, TAMANO_NORMAL + 2)) {
            m.nuevaPagina();
        }
        m.espacio(4);
        m.parrafo(tabla.titulo(), true, TAMANO_TITULO);
        dibujarEncabezados(m, encabezados, lineasEncabezado);
        if (tabla.filas().isEmpty()) {
            m.texto(MaquetadorPdf.MARGEN, m.reservarLinea(tamano), "Sin registros.", false, tamano);
        }
        for (List<String> fila : tabla.filas()) {
            if (!m.caben(1, tamano)) {
                m.nuevaPagina();
                dibujarEncabezados(m, encabezados, lineasEncabezado);
            }
            fila(m, m.reservarLinea(tamano), fila);
        }
        m.espacio(6);
    }

    private List<List<String>> encabezadosEnLineas() {
        List<List<String>> lineas = new ArrayList<>();
        for (int i = 0; i < anchos.length; i++) {
            lineas.add(MaquetadorPdf.cortar(tabla.columnas().get(i), true, tamano, anchos[i] - SEPARACION));
        }
        return lineas;
    }

    private void dibujarEncabezados(MaquetadorPdf m, List<List<String>> encabezados, int lineas) {
        for (int linea = 0; linea < lineas; linea++) {
            double base = m.reservarLinea(tamano);
            double x = MaquetadorPdf.MARGEN;
            for (int i = 0; i < anchos.length; i++) {
                List<String> columna = encabezados.get(i);
                if (linea < columna.size()) {
                    escribirCelda(m, x, base, columna.get(linea), anchos[i], true);
                }
                x += anchos[i];
            }
        }
    }

    private void fila(MaquetadorPdf m, double base, List<String> valores) {
        double x = MaquetadorPdf.MARGEN;
        for (int i = 0; i < valores.size(); i++) {
            escribirCelda(m, x, base, valores.get(i), anchos[i], false);
            x += anchos[i];
        }
    }

    private void escribirCelda(MaquetadorPdf m, double x, double base, String valor, double ancho, boolean negrita) {
        String texto = MaquetadorPdf.recortar(valor, negrita, tamano, ancho - SEPARACION);
        if (!texto.isEmpty()) {
            m.texto(x, base, texto, negrita, tamano);
        }
    }

    private void calcularAnchos() {
        double total = anchosNaturales(TAMANO_NORMAL);
        if (total > MaquetadorPdf.ANCHO_UTIL) {
            tamano = Math.max(TAMANO_MINIMO, TAMANO_NORMAL * MaquetadorPdf.ANCHO_UTIL / total);
            total = anchosNaturales(tamano);
        }
        if (total > MaquetadorPdf.ANCHO_UTIL) {
            limitarColumnasAnchas();
        }
    }

    /**
     * Fija un ancho máximo común solo para las columnas más anchas, de modo que la tabla ocupe el ancho útil:
     * los códigos y números (columnas cortas) se conservan completos y se recortan los textos largos.
     */
    private void limitarColumnasAnchas() {
        double[] ordenados = anchos.clone();
        Arrays.sort(ordenados);
        double restante = MaquetadorPdf.ANCHO_UTIL;
        double tope = ordenados[ordenados.length - 1];
        for (int i = 0; i < ordenados.length; i++) {
            double reparto = restante / (ordenados.length - i);
            if (ordenados[i] >= reparto) {
                tope = reparto;
                break;
            }
            restante -= ordenados[i];
        }
        for (int i = 0; i < anchos.length; i++) {
            anchos[i] = Math.min(anchos[i], tope);
        }
    }

    /** Ancho de cada columna: el dato más largo o la palabra más larga del encabezado, más la separación. */
    private double anchosNaturales(double tamanoLetra) {
        int columnas = tabla.columnas().size();
        anchos = new double[columnas];
        for (int i = 0; i < columnas; i++) {
            for (String palabra : tabla.columnas().get(i).split("\\s+")) {
                anchos[i] = Math.max(anchos[i], MetricasHelvetica.ancho(palabra, true, tamanoLetra));
            }
        }
        for (List<String> fila : tabla.filas()) {
            for (int i = 0; i < columnas; i++) {
                anchos[i] = Math.max(anchos[i], MetricasHelvetica.ancho(fila.get(i), false, tamanoLetra));
            }
        }
        double total = 0;
        for (int i = 0; i < columnas; i++) {
            anchos[i] += SEPARACION;
            total += anchos[i];
        }
        return total;
    }
}
