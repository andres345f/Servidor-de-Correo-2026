package com.feder.presentacion.respuesta;

import com.feder.negocio.comun.ResultadoOperacion;
import java.util.ArrayList;
import java.util.List;

/**
 * Cuerpo text/plain de la respuesta: encabezado, mensaje, detalles y tablas alineadas con bordes ASCII.
 *
 * Las celdas se convierten a una sola línea para que un salto de línea en un dato no rompa la tabla.
 */
final class FormateadorTextoPlano {

    String formatear(ResultadoOperacion resultado, String encabezado, String comando, String pie) {
        StringBuilder t = new StringBuilder(1024);
        t.append(encabezado).append('\n');
        t.append("=".repeat(Math.min(72, Textos.ancho(encabezado)))).append('\n');
        if (comando == null) {
            // Aviso a un tercero: no responde a ningún comando suyo.
            t.append('\n');
        } else {
            // B2: en un error se lee «Usted envió»; en ambos casos va arriba (el texto plano no tiene recuadros).
            t.append(resultado.esExito() ? FormateadorHtml.ETIQUETA_PROCESADO : FormateadorHtml.ETIQUETA_RECIBIDO)
                    .append(": ").append(comando.isEmpty() ? "(sin comando)" : comando).append("\n\n");
        }

        String mensaje = Textos.multilinea(resultado.mensaje());
        if (!mensaje.isEmpty()) {
            t.append(mensaje).append("\n\n");
        }
        for (ResultadoOperacion.Recuadro recuadro : resultado.recuadros()) {
            agregarRecuadro(t, recuadro);
        }
        agregarDetalles(t, resultado.detalles());
        for (ResultadoOperacion.Tabla tabla : resultado.tablas()) {
            if (tabla.esEnFichas()) {
                agregarFichas(t, tabla);
            } else {
                agregarTabla(t, tabla);
            }
        }
        t.append("--\n").append(pie).append('\n');
        return t.toString();
    }

    private static void agregarDetalles(StringBuilder t, List<String[]> detalles) {
        if (detalles.isEmpty()) {
            return;
        }
        int anchoClave = 0;
        for (String[] detalle : detalles) {
            anchoClave = Math.max(anchoClave, Textos.ancho(Textos.unaLinea(detalle[0])));
        }
        t.append("Detalles:\n");
        for (String[] detalle : detalles) {
            t.append("  ").append(Textos.rellenar(Textos.unaLinea(detalle[0]), anchoClave))
                    .append(" : ").append(Textos.unaLinea(detalle[1])).append('\n');
        }
        t.append('\n');
    }

    private static void agregarRecuadro(StringBuilder t, ResultadoOperacion.Recuadro recuadro) {
        t.append(Textos.unaLinea(recuadro.titulo())).append('\n');
        boolean pasos = recuadro.estilo() == ResultadoOperacion.Recuadro.Estilo.PASOS;
        int n = 1;
        for (String linea : recuadro.lineas()) {
            t.append("  ").append(pasos ? (n++) + ". " : "- ").append(Textos.unaLinea(linea)).append('\n');
        }
        t.append('\n');
    }

    /** Una ficha por fila: «NOMBRE - título» y debajo «columna: valor» (se omiten los valores vacíos). */
    private static void agregarFichas(StringBuilder t, ResultadoOperacion.Tabla tabla) {
        String titulo = Textos.unaLinea(tabla.titulo());
        t.append(titulo).append('\n').append("=".repeat(Math.min(72, Math.max(3, Textos.ancho(titulo))))).append('\n');
        String descripcion = Textos.unaLinea(tabla.descripcion());
        if (!descripcion.isEmpty()) {
            t.append(descripcion).append('\n');
        }
        t.append('\n');
        List<String> columnas = tabla.columnas();
        for (List<String> fila : tabla.filas()) {
            t.append(Textos.unaLinea(fila.get(0)));
            if (columnas.size() > 1 && !fila.get(1).isBlank()) {
                t.append(" - ").append(Textos.unaLinea(fila.get(1)));
            }
            t.append('\n');
            for (int i = 2; i < columnas.size(); i++) {
                if (!fila.get(i).isBlank()) {
                    t.append("    ").append(FormateadorHtml.conDosPuntos(Textos.unaLinea(columnas.get(i)))).append(' ')
                            .append(Textos.unaLinea(fila.get(i))).append('\n');
                }
            }
            t.append('\n');
        }
    }

    private static void agregarTabla(StringBuilder t, ResultadoOperacion.Tabla tabla) {
        List<String> columnas = new ArrayList<>();
        for (String columna : tabla.columnas()) {
            columnas.add(Textos.unaLinea(columna));
        }
        List<List<String>> filas = new ArrayList<>();
        for (List<String> fila : tabla.filas()) {
            List<String> limpia = new ArrayList<>(fila.size());
            for (String celda : fila) {
                limpia.add(Textos.unaLinea(celda));
            }
            filas.add(limpia);
        }
        int[] anchos = new int[columnas.size()];
        for (int i = 0; i < anchos.length; i++) {
            anchos[i] = Textos.ancho(columnas.get(i));
            for (List<String> fila : filas) {
                anchos[i] = Math.max(anchos[i], Textos.ancho(fila.get(i)));
            }
        }
        String titulo = Textos.unaLinea(tabla.titulo());
        if (!titulo.isEmpty()) {
            t.append(titulo).append('\n');
        }
        String borde = borde(anchos);
        t.append(borde).append(fila(columnas, anchos)).append(borde);
        if (filas.isEmpty()) {
            t.append("Sin registros.\n\n");
            return;
        }
        for (List<String> fila : filas) {
            t.append(fila(fila, anchos));
        }
        t.append(borde);
        t.append(filas.size() == 1 ? "(1 registro)" : "(" + filas.size() + " registros)").append("\n\n");
    }

    private static String borde(int[] anchos) {
        StringBuilder b = new StringBuilder("+");
        for (int ancho : anchos) {
            b.append("-".repeat(ancho + 2)).append('+');
        }
        return b.append('\n').toString();
    }

    private static String fila(List<String> celdas, int[] anchos) {
        StringBuilder f = new StringBuilder("|");
        for (int i = 0; i < anchos.length; i++) {
            f.append(' ').append(Textos.rellenar(celdas.get(i), anchos[i])).append(" |");
        }
        return f.append('\n').toString();
    }
}
