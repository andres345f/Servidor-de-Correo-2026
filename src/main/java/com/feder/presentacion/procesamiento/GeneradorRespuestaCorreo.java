package com.feder.presentacion.procesamiento;

import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.mensajeria.GeneradorRespuesta;
import com.feder.presentacion.correo.mime.AdjuntoCorreo;
import com.feder.presentacion.correo.mime.EmailResponseBuilder;
import com.feder.presentacion.respuesta.RespuestaFormateada;
import com.feder.presentacion.respuesta.ResponseFormatter;

/** Implementación de presentación del contrato {@link GeneradorRespuesta}: texto plano, HTML y adjunto opcional. */
public class GeneradorRespuestaCorreo implements GeneradorRespuesta {

    private final ResponseFormatter formateador = new ResponseFormatter();
    private final EmailResponseBuilder constructor;
    private final GeneradorAdjuntos adjuntos;

    public GeneradorRespuestaCorreo(EmailResponseBuilder constructor, GeneradorAdjuntos adjuntos) {
        this.constructor = constructor;
        this.adjuntos = adjuntos;
    }

    @Override
    public Respuesta generar(ResultadoOperacion resultado, String comandoRecibido) {
        RespuestaFormateada formato = formateador.formatear(resultado, comandoRecibido == null ? "" : comandoRecibido);
        AdjuntoCorreo adjunto = resultado.esExito() ? adjuntos.generar(resultado) : null;
        if (adjunto == null) {
            return new Respuesta(formato.asunto(), formato.textoPlano(), formato.html());
        }
        return new Respuesta(formato.asunto(), formato.textoPlano(), formato.html(),
                adjunto.nombreArchivo(), adjunto.tipoMime(), adjunto.contenido());
    }

    @Override
    public Respuesta generarAviso(ResultadoOperacion contenido) {
        RespuestaFormateada formato = formateador.formatearAviso(contenido);
        return new Respuesta(formato.asunto(), formato.textoPlano(), formato.html());
    }

    @Override
    public String nuevoMessageId() {
        return constructor.generarMessageId();
    }
}
