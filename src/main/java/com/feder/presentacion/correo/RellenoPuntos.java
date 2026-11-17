package com.feder.presentacion.correo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Bloques multilínea terminados en "." (POP3 RETR, SMTP DATA).
 *
 * Al enviar se duplica el punto inicial de cada línea ("dot-stuffing") y al recibir se quita,
 * para que una línea del mensaje que sea solo "." no se confunda con el fin del bloque.
 */
public final class RellenoPuntos {

    private static final byte[] CRLF = {'\r', '\n'};
    private static final byte[] PUNTO = {'.'};
    private static final byte[] TERMINADOR = {'.', '\r', '\n'};

    private RellenoPuntos() {
    }

    /**
     * Lee líneas hasta encontrar "." sola, quitando el punto de relleno.
     * Cada línea se devuelve terminada en CRLF.
     */
    public static byte[] leerBloque(CanalLineas canal, int maximoBytes) throws IOException {
        ByteArrayOutputStream contenido = new ByteArrayOutputStream(4096);
        while (true) {
            byte[] linea = canal.leerLineaBytes();
            if (linea.length == 1 && linea[0] == '.') {
                return contenido.toByteArray();
            }
            int inicio = linea.length > 0 && linea[0] == '.' ? 1 : 0;
            if (contenido.size() + linea.length + 2 > maximoBytes) {
                throw new LimiteExcedidoException("El mensaje supera el máximo de " + maximoBytes + " bytes");
            }
            contenido.write(linea, inicio, linea.length - inicio);
            contenido.write(CRLF);
        }
    }

    /**
     * Envía el contenido normalizando los saltos de línea a CRLF, aplica el relleno de puntos
     * y termina con ".". CR o LF sueltos se tratan como saltos: SMTP no admite saltos desnudos.
     */
    public static void escribirBloque(CanalLineas canal, byte[] contenido) throws IOException {
        int inicioLinea = 0;
        int i = 0;
        while (i < contenido.length) {
            byte b = contenido[i];
            if (b == '\r' || b == '\n') {
                escribirLinea(canal, contenido, inicioLinea, i);
                if (b == '\r' && i + 1 < contenido.length && contenido[i + 1] == '\n') {
                    i++;
                }
                i++;
                inicioLinea = i;
            } else {
                i++;
            }
        }
        if (inicioLinea < contenido.length) {
            escribirLinea(canal, contenido, inicioLinea, contenido.length);
        }
        canal.escribirCrudo(TERMINADOR, 0, TERMINADOR.length);
        canal.vaciar();
    }

    private static void escribirLinea(CanalLineas canal, byte[] datos, int desde, int hasta) throws IOException {
        if (hasta > desde && datos[desde] == '.') {
            canal.escribirCrudo(PUNTO, 0, 1);
        }
        canal.escribirCrudo(datos, desde, hasta - desde);
        canal.escribirCrudo(CRLF, 0, 2);
    }
}
