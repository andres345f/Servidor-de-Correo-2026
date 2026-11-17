package com.feder.presentacion.correo.mime;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Archivo adjunto de una respuesta (por ejemplo, un reporte PDF o Excel de CU8).
 * El nombre se limita a caracteres ASCII seguros para que no pueda inyectar cabeceras.
 */
public record AdjuntoCorreo(String nombreArchivo, String tipoMime, byte[] contenido) {

    private static final Pattern NOMBRE_SEGURO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern TIPO_MIME = Pattern.compile("[a-z]+/[a-z0-9.+-]+");

    public AdjuntoCorreo {
        Objects.requireNonNull(contenido, "contenido");
        if (nombreArchivo == null || !NOMBRE_SEGURO.matcher(nombreArchivo).matches()) {
            throw new IllegalArgumentException("Nombre de adjunto no permitido");
        }
        if (tipoMime == null || !TIPO_MIME.matcher(tipoMime).matches()) {
            throw new IllegalArgumentException("Tipo MIME de adjunto no permitido");
        }
    }
}
