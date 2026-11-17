package com.feder.negocio.mensajeria;

import com.feder.negocio.comun.ResultadoOperacion;

/**
 * Contrato que implementa la capa de presentación para dar formato a una respuesta.
 * La capa de negocio solo sabe QUÉ responder; la presentación decide CÓMO (texto, HTML y,
 * en los reportes, el archivo adjunto). Se invoca dentro de la transacción para que la
 * respuesta se guarde junto con la operación.
 */
public interface GeneradorRespuesta {

    /** Contenido listo para la bandeja de salida. El adjunto es opcional (todo nulo si no hay). */
    final class Respuesta {
        public final String asunto;
        public final String cuerpoTexto;
        public final String cuerpoHtml;
        public final String adjuntoNombre;
        public final String adjuntoTipo;
        public final byte[] adjuntoContenido;

        public Respuesta(String asunto, String cuerpoTexto, String cuerpoHtml) {
            this(asunto, cuerpoTexto, cuerpoHtml, null, null, null);
        }

        public Respuesta(String asunto, String cuerpoTexto, String cuerpoHtml,
                         String adjuntoNombre, String adjuntoTipo, byte[] adjuntoContenido) {
            this.asunto = asunto;
            this.cuerpoTexto = cuerpoTexto;
            this.cuerpoHtml = cuerpoHtml;
            this.adjuntoNombre = adjuntoNombre;
            this.adjuntoTipo = adjuntoTipo;
            this.adjuntoContenido = adjuntoContenido;
        }

        public boolean tieneAdjunto() {
            return adjuntoContenido != null;
        }
    }

    Respuesta generar(ResultadoOperacion resultado, String comandoRecibido);

    /** Aviso a un tercero: no responde a ningún comando de quien lo recibe. */
    default Respuesta generarAviso(ResultadoOperacion contenido) {
        return generar(contenido, null);
    }

    /** Identificador único para el encabezado Message-ID de la respuesta. */
    String nuevoMessageId();
}
