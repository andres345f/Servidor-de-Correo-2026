package com.feder.presentacion.respuesta;

import static com.feder.presentacion.respuesta.EscapadorHtml.escapar;

import com.feder.negocio.comun.ResultadoOperacion;
import java.util.List;
import java.util.Map;

/**
 * Cuerpo text/html de la respuesta a partir de las plantillas de src/main/resources/plantillas.
 *
 * Cada valor que proviene del resultado o del comando se escapa aquí; las plantillas solo aportan estructura.
 */
final class FormateadorHtml {

    private final MotorPlantillas plantillas;

    FormateadorHtml(MotorPlantillas plantillas) {
        this.plantillas = plantillas;
    }

    /** @param comando asunto recibido, o null si el correo es un aviso que no responde a ningún comando */
    String formatear(ResultadoOperacion resultado, String titulo, String comando, String pie) {
        StringBuilder tablas = new StringBuilder();
        for (ResultadoOperacion.Tabla tabla : resultado.tablas()) {
            tablas.append(tabla.esEnFichas() ? fichas(tabla) : tabla(tabla));
        }
        StringBuilder recuadros = new StringBuilder();
        for (ResultadoOperacion.Recuadro recuadro : resultado.recuadros()) {
            recuadros.append(recuadro(recuadro));
        }
        String mensaje = Textos.multilinea(resultado.mensaje());
        // B2: en un error, lo que se recibió va arriba («Usted envió»); en un éxito, abajo como «Comando procesado».
        boolean arriba = comando != null && !resultado.esExito();
        String bloque = comando == null ? "" : plantillas.renderizar("comando.html", Map.of(
                "clase", arriba ? "comando recibido" : "comando",
                "etiqueta", arriba ? ETIQUETA_RECIBIDO : ETIQUETA_PROCESADO,
                "comando", escapar(comando.isEmpty() ? "(sin comando)" : comando)));
        return plantillas.renderizar("respuesta.html", Map.ofEntries(
                Map.entry("estilos", estilos()),
                Map.entry("titulo", escapar(titulo)),
                Map.entry("estado", comando == null ? "Aviso" : resultado.esExito() ? "Éxito" : "Error"),
                Map.entry("claseEstado", comando == null ? "aviso" : resultado.esExito() ? "exito" : "error"),
                Map.entry("bloqueRecibido", arriba ? bloque : ""),
                Map.entry("mensaje", mensaje.isEmpty() ? "" : "<p class=\"mensaje\">" + escapar(mensaje).replace("\n", "<br>") + "</p>"),
                Map.entry("recuadros", recuadros.toString()),
                Map.entry("detalles", detalles(resultado.detalles())),
                Map.entry("tablas", tablas.toString()),
                Map.entry("bloqueComando", arriba ? "" : bloque),
                Map.entry("pie", escapar(pie))));
    }

    static final String ETIQUETA_RECIBIDO = "Usted envió";
    static final String ETIQUETA_PROCESADO = "Comando procesado";

    private String estilos() {
        String css = plantillas.cargar("estilos.css");
        // El CSS se inserta dentro de <style>; si contuviera "</" podría cerrar la etiqueta antes de tiempo.
        if (css.contains("</")) {
            throw new IllegalStateException("estilos.css no puede contener la secuencia '</'");
        }
        return css.strip();
    }

    private String detalles(List<String[]> detalles) {
        if (detalles.isEmpty()) {
            return "";
        }
        StringBuilder filas = new StringBuilder();
        for (String[] detalle : detalles) {
            filas.append("<tr><th scope=\"row\">").append(escapar(Textos.unaLinea(detalle[0])))
                    .append("</th><td>").append(valor(detalle[0], detalle[1])).append("</td></tr>\n");
        }
        return plantillas.renderizar("detalles.html", Map.of("filas", filas.toString()));
    }

    /** Un dato cuya etiqueta empieza con «Ejemplo» es un comando para copiar: se muestra destacado como código. */
    private static String valor(String etiqueta, String valor) {
        String texto = escapar(Textos.unaLinea(valor));
        return Textos.unaLinea(etiqueta).startsWith("Ejemplo") && !texto.isEmpty() ? "<code class=\"ej\">" + texto + "</code>" : texto;
    }

    /** "Quién puede usarlo" → "Quién puede usarlo:"; una pregunta ("¿Pide…?") queda sin los dos puntos. */
    static String conDosPuntos(String etiqueta) {
        return etiqueta.endsWith("?") || etiqueta.endsWith(":") ? etiqueta : etiqueta + ":";
    }

    private String recuadro(ResultadoOperacion.Recuadro recuadro) {
        boolean pasos = recuadro.estilo() == ResultadoOperacion.Recuadro.Estilo.PASOS;
        StringBuilder lista = new StringBuilder(pasos ? "<ol>" : "<ul>");
        for (String linea : recuadro.lineas()) {
            lista.append("<li>").append(escapar(Textos.unaLinea(linea))).append("</li>");
        }
        lista.append(pasos ? "</ol>" : "</ul>");
        return plantillas.renderizar("recuadro.html", Map.of(
                "clase", pasos ? "pasos" : "nota",
                "titulo", escapar(Textos.unaLinea(recuadro.titulo())),
                "lista", lista.toString()));
    }

    /**
     * Cada fila como una ficha: 1.ª columna = nombre destacado, 2.ª = título, las demás «columna: valor»
     * (se omiten las vacías). Las clases son cortas a propósito: HELP["*"] tiene más de cien fichas y Gmail
     * recorta los mensajes HTML de más de 102 KB.
     */
    private String fichas(ResultadoOperacion.Tabla tabla) {
        List<String> columnas = tabla.columnas();
        StringBuilder fichas = new StringBuilder();
        for (List<String> fila : tabla.filas()) {
            fichas.append("<div class=\"ficha\"><p class=\"ft\"><span class=\"nc\">").append(escapar(Textos.unaLinea(fila.get(0))))
                    .append("</span>").append(columnas.size() > 1 ? escapar(Textos.unaLinea(fila.get(1))) : "").append("</p>");
            for (int i = 2; i < columnas.size(); i++) {
                if (fila.get(i).isBlank()) {
                    continue;
                }
                String etiqueta = Textos.unaLinea(columnas.get(i));
                // <b> en lugar de <span class=…>: misma apariencia (ver .dt b en estilos.css) y menos bytes por ficha.
                fichas.append("<p class=\"dt\"><b>").append(escapar(conDosPuntos(etiqueta))).append("</b> ")
                        .append(valor(etiqueta, fila.get(i))).append("</p>");
            }
            fichas.append("</div>\n");
        }
        String descripcion = Textos.unaLinea(tabla.descripcion());
        return plantillas.renderizar("fichas.html", Map.of(
                "titulo", escapar(Textos.unaLinea(tabla.titulo())),
                "descripcion", descripcion.isEmpty() ? "" : "<p class=\"intro\">" + escapar(descripcion) + "</p>",
                "fichas", fichas.toString()));
    }

    private String tabla(ResultadoOperacion.Tabla tabla) {
        StringBuilder encabezados = new StringBuilder();
        for (String columna : tabla.columnas()) {
            encabezados.append("<th scope=\"col\">").append(escapar(Textos.unaLinea(columna))).append("</th>");
        }
        StringBuilder filas = new StringBuilder();
        for (List<String> fila : tabla.filas()) {
            filas.append("<tr>");
            for (String celda : fila) {
                filas.append("<td>").append(escapar(Textos.unaLinea(celda))).append("</td>");
            }
            filas.append("</tr>\n");
        }
        if (tabla.filas().isEmpty()) {
            filas.append("<tr><td class=\"vacio\" colspan=\"").append(Math.max(1, tabla.columnas().size()))
                    .append("\">Sin registros.</td></tr>\n");
        }
        int cantidad = tabla.filas().size();
        String titulo = Textos.unaLinea(tabla.titulo());
        return plantillas.renderizar("tabla.html", Map.of(
                "titulo", escapar(titulo.isEmpty() ? "Resultados" : titulo),
                "encabezados", encabezados.toString(),
                "filas", filas.toString(),
                "conteo", cantidad == 1 ? "1 registro" : cantidad + " registros"));
    }
}
