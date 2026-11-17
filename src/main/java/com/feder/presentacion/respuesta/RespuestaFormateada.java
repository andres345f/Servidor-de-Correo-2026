package com.feder.presentacion.respuesta;

/**
 * Contenido listo para construir el correo de respuesta.
 *
 * @param asunto     asunto en una sola línea ("FEDER - Éxito: ..." o "FEDER - Error: ...")
 * @param textoPlano cuerpo text/plain con tablas alineadas
 * @param html       cuerpo text/html con estilos embebidos
 */
public record RespuestaFormateada(String asunto, String textoPlano, String html) {
}
