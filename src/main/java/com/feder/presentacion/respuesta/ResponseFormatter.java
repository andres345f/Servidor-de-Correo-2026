package com.feder.presentacion.respuesta;

import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import java.util.Objects;

/**
 * Convierte un {@link ResultadoOperacion} en el asunto y los cuerpos (texto y HTML) del correo de respuesta.
 *
 * El asunto se reduce a una sola línea y se limita en longitud; el constructor de correos
 * rechaza de todas formas cualquier CR o LF que llegara hasta las cabeceras.
 */
public final class ResponseFormatter {

    public static final String PIE = "Respuesta automática del sistema FEDER. No responda a este mensaje.";
    private static final int MAXIMO_TITULO_ASUNTO = 150;

    private final FormateadorTextoPlano texto = new FormateadorTextoPlano();
    private final FormateadorHtml html;

    /** Usa las plantillas de la carpeta "plantillas" del classpath. */
    public ResponseFormatter() {
        this.html = new FormateadorHtml(new MotorPlantillas("plantillas"));
    }

    /**
     * @param resultado       resultado del caso de uso (o del error de validación)
     * @param comandoOriginal asunto recibido; puede ser null si no se pudo leer
     */
    public RespuestaFormateada formatear(ResultadoOperacion resultado, String comandoOriginal) {
        Objects.requireNonNull(resultado, "resultado");
        String titulo = Textos.unaLinea(resultado.titulo());
        String comando = Textos.unaLinea(comandoOriginal);
        // B1: el asunto nombra el comando recibido («FEDER - LISUSU - Éxito: ...») si se puede reconocer.
        String nombre = ComandoDispatcher.nombreEscrito(comando);
        String asunto = "FEDER - " + (nombre == null ? "" : nombre + " - ")
                + (resultado.esExito() ? "Éxito" : "Error") + ": " + limitar(titulo);
        return new RespuestaFormateada(
                asunto,
                texto.formatear(resultado, asunto, comando, PIE),
                html.formatear(resultado, titulo, comando, PIE));
    }

    /**
     * Aviso a un tercero (invitación, decisión sobre una solicitud, pago confirmado...): no responde a un comando
     * de quien lo recibe, así que el asunto dice «Aviso» y el cuerpo no muestra «Comando procesado».
     */
    public RespuestaFormateada formatearAviso(ResultadoOperacion resultado) {
        Objects.requireNonNull(resultado, "resultado");
        String titulo = Textos.unaLinea(resultado.titulo());
        String asunto = "FEDER - Aviso: " + limitar(titulo);
        return new RespuestaFormateada(
                asunto,
                texto.formatear(resultado, asunto, null, PIE),
                html.formatear(resultado, titulo, null, PIE));
    }

    private static String limitar(String titulo) {
        if (Textos.ancho(titulo) <= MAXIMO_TITULO_ASUNTO) {
            return titulo;
        }
        int corte = titulo.offsetByCodePoints(0, MAXIMO_TITULO_ASUNTO - 3);
        return titulo.substring(0, corte) + "...";
    }
}
