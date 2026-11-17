package com.feder.negocio.mensajeria;

import java.util.List;

/**
 * Datos de un correo recibido, ya interpretados por la capa de presentación.
 * Si el asunto no tiene una sintaxis válida, {@code errorSintaxis} contiene la explicación
 * y {@code comando} es nulo. Si el asunto no pudo decodificarse con certeza (codificación MIME inválida),
 * {@code errorCodificacion} contiene la explicación y {@code comando} también es nulo: nunca se ejecuta.
 */
public final class SolicitudCorreo {

    public final String buzon;
    public final String uidl;
    public final String messageId;
    public final String remitente;
    public final String asunto;
    public final String comando;
    public final List<String> parametros;
    public final String errorSintaxis;
    public final String errorCodificacion;
    public final boolean respuestaAutomatica;
    /** Asunto tal como llegó, sin decodificar (p. ej. "=?UTF-8?B?...?="), o null si no se conoce. Solo para mostrar. */
    public final String asuntoOriginal;

    private SolicitudCorreo(String buzon, String uidl, String messageId, String remitente, String asunto,
                            String comando, List<String> parametros, String errorSintaxis, String errorCodificacion,
                            boolean respuestaAutomatica) {
        this(buzon, uidl, messageId, remitente, asunto, comando, parametros, errorSintaxis, errorCodificacion,
                respuestaAutomatica, null);
    }

    private SolicitudCorreo(String buzon, String uidl, String messageId, String remitente, String asunto,
                            String comando, List<String> parametros, String errorSintaxis, String errorCodificacion,
                            boolean respuestaAutomatica, String asuntoOriginal) {
        this.buzon = buzon;
        this.uidl = uidl;
        this.messageId = messageId;
        this.remitente = remitente;
        this.asunto = asunto;
        this.comando = comando;
        this.parametros = parametros == null ? List.of() : List.copyOf(parametros);
        this.errorSintaxis = errorSintaxis;
        this.errorCodificacion = errorCodificacion;
        this.respuestaAutomatica = respuestaAutomatica;
        this.asuntoOriginal = asuntoOriginal;
    }

    /** Copia de esta solicitud que además recuerda el asunto tal como llegó en el correo. */
    public SolicitudCorreo conAsuntoOriginal(String original) {
        return new SolicitudCorreo(buzon, uidl, messageId, remitente, asunto, comando, parametros, errorSintaxis,
                errorCodificacion, respuestaAutomatica, original);
    }

    public static SolicitudCorreo conComando(String buzon, String uidl, String messageId, String remitente,
                                             String asunto, String comando, List<String> parametros) {
        return new SolicitudCorreo(buzon, uidl, messageId, remitente, asunto, comando, parametros, null, null, false);
    }

    public static SolicitudCorreo conErrorSintaxis(String buzon, String uidl, String messageId, String remitente,
                                                   String asunto, String error) {
        return new SolicitudCorreo(buzon, uidl, messageId, remitente, asunto, null, null, error, null, false);
    }

    /** Asunto que no pudo decodificarse con certeza: se responde con el motivo y no se ejecuta ningún comando. */
    public static SolicitudCorreo conErrorCodificacion(String buzon, String uidl, String messageId, String remitente,
                                                       String asunto, String error) {
        return new SolicitudCorreo(buzon, uidl, messageId, remitente, asunto, null, null, null, error, false);
    }

    /** Respuestas automáticas, rebotes o avisos de entrega: se registran pero nunca se contestan. */
    public static SolicitudCorreo automatica(String buzon, String uidl, String messageId, String remitente, String asunto) {
        return new SolicitudCorreo(buzon, uidl, messageId, remitente, asunto, null, null, null, null, true);
    }
}
