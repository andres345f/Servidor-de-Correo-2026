package com.feder.presentacion.correo;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Conversión de contraseñas (char[]) a bytes sin pasar por objetos String inmutables.
 *
 * Quien recibe los arreglos debe borrarlos con {@link #borrar(byte[])} al terminar.
 */
public final class Secretos {

    private Secretos() {
    }

    /** Codifica el secreto en UTF-8 y borra el búfer intermedio que usa el codificador. */
    public static byte[] utf8(char[] secreto) {
        ByteBuffer codificado = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secreto));
        byte[] bytes = new byte[codificado.remaining()];
        codificado.get(bytes);
        if (codificado.hasArray()) {
            Arrays.fill(codificado.array(), (byte) 0);
        }
        return bytes;
    }

    /** Une un prefijo ASCII de protocolo (p. ej. "PASS ") con bytes secretos. */
    public static byte[] concatenar(String prefijo, byte[] secreto) {
        byte[] inicio = prefijo.getBytes(StandardCharsets.US_ASCII);
        byte[] linea = Arrays.copyOf(inicio, inicio.length + secreto.length);
        System.arraycopy(secreto, 0, linea, inicio.length, secreto.length);
        return linea;
    }

    public static void borrar(byte[]... arreglos) {
        for (byte[] arreglo : arreglos) {
            if (arreglo != null) {
                Arrays.fill(arreglo, (byte) 0);
            }
        }
    }
}
