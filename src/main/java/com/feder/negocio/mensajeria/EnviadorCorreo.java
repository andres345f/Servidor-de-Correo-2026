package com.feder.negocio.mensajeria;

import java.time.Duration;

/**
 * Contrato que implementa la capa de presentación para enviar una respuesta por SMTP.
 * La capa de negocio decide qué enviar y registra el resultado; la presentación habla el protocolo.
 */
public interface EnviadorCorreo {

    /** Respuesta almacenada en la bandeja de salida, lista para enviarse. */
    final class CorreoPendiente {
        public final String destinatario;
        public final String asunto;
        public final String cuerpoTexto;
        public final String cuerpoHtml;
        public final String messageId;
        public final String enRespuestaA;
        public final String adjuntoNombre;
        public final String adjuntoTipo;
        public final byte[] adjuntoContenido;

        public CorreoPendiente(String destinatario, String asunto, String cuerpoTexto, String cuerpoHtml,
                               String messageId, String enRespuestaA,
                               String adjuntoNombre, String adjuntoTipo, byte[] adjuntoContenido) {
            this.destinatario = destinatario;
            this.asunto = asunto;
            this.cuerpoTexto = cuerpoTexto;
            this.cuerpoHtml = cuerpoHtml;
            this.messageId = messageId;
            this.enRespuestaA = enRespuestaA;
            this.adjuntoNombre = adjuntoNombre;
            this.adjuntoTipo = adjuntoTipo;
            this.adjuntoContenido = adjuntoContenido;
        }

        public boolean tieneAdjunto() {
            return adjuntoContenido != null;
        }
    }

    /** Error de envío. Permanente = el servidor rechazó el mensaje (5xx): no tiene sentido reintentar. */
    final class FalloEnvio extends Exception {
        private static final long serialVersionUID = 1L;
        private final boolean permanente;

        public FalloEnvio(String mensaje, boolean permanente, Throwable causa) {
            super(mensaje, causa);
            this.permanente = permanente;
        }

        public boolean esPermanente() {
            return permanente;
        }
    }

    void enviar(CorreoPendiente correo) throws FalloEnvio;

    /**
     * Tiempo máximo que puede durar un envío antes de que el enviador lo corte. La bandeja de salida reserva
     * cada correo durante más tiempo que este plazo, para que ningún otro ciclo lo tome mientras se envía.
     */
    default Duration plazoMaximo() {
        return Duration.ofMinutes(2);
    }
}
