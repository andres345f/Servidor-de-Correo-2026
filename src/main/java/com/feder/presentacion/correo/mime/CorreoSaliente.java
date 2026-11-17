package com.feder.presentacion.correo.mime;

/**
 * Datos de un correo de respuesta antes de convertirlo a bytes RFC 5322.
 *
 * @param destinatario dirección del destinatario
 * @param asunto       asunto en Unicode (sin CR ni LF)
 * @param cuerpoTexto  cuerpo text/plain
 * @param cuerpoHtml   cuerpo text/html, o null para enviar solo texto plano
 * @param messageId    Message-ID a usar, o null para que se genere uno
 * @param inReplyTo    Message-ID del correo que se responde, o null
 */
public record CorreoSaliente(
        String destinatario,
        String asunto,
        String cuerpoTexto,
        String cuerpoHtml,
        String messageId,
        String inReplyTo) {
}
