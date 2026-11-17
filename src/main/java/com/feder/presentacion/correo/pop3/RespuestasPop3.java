package com.feder.presentacion.correo.pop3;

import com.feder.presentacion.correo.CanalLineas;
import com.feder.presentacion.correo.CorreoException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Interpretación de las líneas de estado y de los listados multilínea de POP3. */
final class RespuestasPop3 {

    private static final int MAXIMO_LINEAS_LISTADO = 100_000;
    /** Una línea de UIDL o CAPA es corta (RFC 1939: identificador de hasta 70 caracteres). */
    static final int MAXIMO_LINEA_LISTADO = 512;
    /** Presupuesto total de un listado UIDL (alcanza para decenas de miles de mensajes). */
    static final int MAXIMO_BYTES_UIDL = 4 * 1024 * 1024;
    /** Presupuesto total de la respuesta a CAPA. */
    static final int MAXIMO_BYTES_CAPA = 64 * 1024;

    private RespuestasPop3() {
    }

    /** Devuelve el texto que sigue a "+OK"; lanza la excepción si es "-ERR" u otra cosa. */
    static String interpretarEstado(String linea, String descripcion) throws CorreoException {
        if (linea.startsWith("+OK") && (linea.length() == 3 || linea.charAt(3) == ' ')) {
            return linea.substring(3).trim();
        }
        if (linea.startsWith("-ERR")) {
            throw new CorreoException("El servidor POP3 respondió -ERR a " + descripcion, linea);
        }
        throw new CorreoException("Respuesta POP3 no reconocida a " + descripcion, linea);
    }

    /**
     * Lee las líneas de un listado hasta el "." final, quitando el punto de relleno. El tamaño se controla
     * mientras se lee (por línea y en total): una respuesta enorme o mal formada termina con un error de
     * protocolo recuperable en lugar de agotar la memoria del proceso.
     */
    static List<String> leerListado(CanalLineas canal, int maximoBytesTotal) throws IOException, CorreoException {
        List<String> lineas = new ArrayList<>();
        long acumulado = 0;
        while (true) {
            byte[] linea = canal.leerLineaBytes(MAXIMO_LINEA_LISTADO);
            if (linea.length == 1 && linea[0] == '.') {
                return lineas;
            }
            acumulado += linea.length + 2;
            if (lineas.size() >= MAXIMO_LINEAS_LISTADO || acumulado > maximoBytesTotal) {
                throw new CorreoException("La respuesta multilínea del servidor POP3 es demasiado larga");
            }
            int inicio = linea.length > 0 && linea[0] == '.' ? 1 : 0;
            lineas.add(new String(linea, inicio, linea.length - inicio, StandardCharsets.ISO_8859_1));
        }
    }

    static int numeroPositivo(String texto, String linea) throws CorreoException {
        int numero;
        try {
            numero = Integer.parseInt(texto);
        } catch (NumberFormatException e) {
            throw new CorreoException("Número de mensaje no válido en la respuesta POP3", linea, e);
        }
        if (numero < 1) {
            throw new CorreoException("Número de mensaje no válido en la respuesta POP3", linea);
        }
        return numero;
    }
}
