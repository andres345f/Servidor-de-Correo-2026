package com.feder.presentacion.correo.mime;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Datos de cabecera de un correo recibido, ya decodificados.
 *
 * @param messageId        Message-ID normalizado ("&lt;id@dominio&gt;") o null si falta o es inválido
 * @param from             dirección del remitente en minúsculas (sin nombre)
 * @param fromNombre       nombre para mostrar del remitente, decodificado, o null
 * @param replyTo          dirección de Reply-To en minúsculas, o null
 * @param returnPath       valor crudo de Return-Path (p. ej. "&lt;&gt;"), o null
 * @param subject          asunto decodificado a Unicode ("" si falta); las palabras RFC 2047 que no pudieron
 *                         decodificarse quedan literales
 * @param autoSubmitted    valor de Auto-Submitted, o null
 * @param precedence       valor de Precedence, o null
 * @param fecha            valor crudo de Date, o null
 * @param contentType      valor crudo de Content-Type, o null
 * @param nombresCabeceras nombres de todas las cabeceras tal como llegaron, en orden
 * @param errorCodificacionAsunto motivo por el que el asunto no pudo decodificarse con certeza (palabra RFC 2047
 *                         inválida o bytes de 8 bits que no son UTF-8), o null si se decodificó por completo.
 *                         Con un motivo, el asunto no debe ejecutarse como comando
 * @param subjectOriginal  valor de Subject tal como llegó, sin decodificar (p. ej. "=?UTF-8?B?...?="), o null si
 *                         falta. Solo sirve para mostrar qué se recibió; nunca se ejecuta
 */
public record CorreoEntrante(
        String messageId,
        String from,
        String fromNombre,
        String replyTo,
        String returnPath,
        String subject,
        String autoSubmitted,
        String precedence,
        String fecha,
        String contentType,
        List<String> nombresCabeceras,
        String errorCodificacionAsunto,
        String subjectOriginal) {

    private static final Set<String> PRECEDENCIAS_MASIVAS = Set.of("bulk", "junk", "list");
    private static final Set<String> BUZONES_DE_SISTEMA = Set.of("mailer-daemon", "postmaster");

    public CorreoEntrante {
        nombresCabeceras = List.copyOf(nombresCabeceras);
    }

    /**
     * Indica si el mensaje es una respuesta automática, un rebote o correo masivo (RFC 3834).
     * Responder a estos mensajes provocaría bucles de correo entre sistemas automáticos.
     */
    public boolean esRespuestaAutomatica() {
        // Si la cabecera aparece repetida no sabemos cuál vale: ante la duda, no respondemos.
        if (nombresCabeceras.stream().filter(n -> n.equalsIgnoreCase("Auto-Submitted")).count() > 1) {
            return true;
        }
        if (autoSubmitted != null) {
            String valor = primerToken(autoSubmitted);
            if (!valor.isEmpty() && !valor.equals("no")) {
                return true;
            }
        }
        if (precedence != null && PRECEDENCIAS_MASIVAS.contains(primerToken(precedence))) {
            return true;
        }
        // Ruta de retorno nula (rebote), admitiendo comentarios (incluso anidados) y espacios: "(bounce) <>".
        if (returnPath != null && sinComentarios(returnPath).replaceAll("\\s", "").equals("<>")) {
            return true;
        }
        if (from != null && from.indexOf('@') > 0
                && BUZONES_DE_SISTEMA.contains(from.substring(0, from.indexOf('@')).toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (contentType != null && primerToken(contentType).startsWith("multipart/report")) {
            return true;
        }
        return nombresCabeceras.stream()
                .anyMatch(n -> n.equalsIgnoreCase("X-Autoreply") || n.equalsIgnoreCase("X-Autorespond"));
    }

    /** Elimina comentarios RFC 5322 entre paréntesis, con anidamiento y caracteres escapados con '\'. */
    static String sinComentarios(String valor) {
        StringBuilder sb = new StringBuilder(valor.length());
        int profundidad = 0;
        for (int i = 0; i < valor.length(); i++) {
            char c = valor.charAt(i);
            if (c == '\\' && profundidad > 0) {
                i++; // el carácter escapado forma parte del comentario
            } else if (c == '(') {
                profundidad++;
            } else if (c == ')' && profundidad > 0) {
                profundidad--;
            } else if (profundidad == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String primerToken(String valor) {
        int fin = valor.indexOf(';');
        return (fin >= 0 ? valor.substring(0, fin) : valor).trim().toLowerCase(Locale.ROOT);
    }
}
