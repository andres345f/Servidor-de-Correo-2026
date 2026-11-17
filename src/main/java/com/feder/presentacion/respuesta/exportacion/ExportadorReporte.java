package com.feder.presentacion.respuesta.exportacion;

import com.feder.negocio.comun.ResultadoOperacion;

/**
 * Convierte el resultado de un reporte en el archivo adjunto que se envía por correo.
 * El contenido lo decide la capa de negocio; el exportador solo cambia la forma de presentarlo.
 */
public interface ExportadorReporte {

    byte[] exportar(ResultadoOperacion resultado);

    /** Extensión del archivo sin punto (p. ej. "pdf"). */
    String extension();

    /** Tipo MIME del adjunto. */
    String tipoMime();
}
