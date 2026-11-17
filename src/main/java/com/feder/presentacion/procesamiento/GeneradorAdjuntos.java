package com.feder.presentacion.procesamiento;

import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.presentacion.correo.mime.AdjuntoCorreo;

/**
 * Genera el archivo adjunto que pide un resultado (por ejemplo, un reporte en PDF o Excel).
 * Devuelve null cuando el resultado no solicita ningún archivo.
 */
@FunctionalInterface
public interface GeneradorAdjuntos {

    AdjuntoCorreo generar(ResultadoOperacion resultado);

    /** Implementación para entornos o pruebas sin exportación de archivos. */
    GeneradorAdjuntos NINGUNO = resultado -> null;
}
