package com.feder.presentacion.correo;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/**
 * Lectura y escritura de líneas terminadas en CRLF sobre un socket.
 *
 * Trabaja con bytes crudos para no depender del juego de caracteres de la plataforma:
 * las respuestas de protocolo se decodifican como ISO-8859-1 (un byte, un carácter)
 * y el contenido de los mensajes se entrega sin transformar.
 */
public final class CanalLineas {

    public static final int MAXIMO_LINEA_POR_DEFECTO = 32 * 1024;
    private static final byte[] CRLF = {'\r', '\n'};

    private final InputStream entrada;
    private final OutputStream salida;
    private final int maximoLinea;
    private final byte[] buffer = new byte[8192];
    private int posicion;
    private int limite;

    public CanalLineas(InputStream entrada, OutputStream salida, int maximoLinea) {
        this.entrada = Objects.requireNonNull(entrada, "entrada");
        this.salida = Objects.requireNonNull(salida, "salida");
        if (maximoLinea < 80) {
            throw new IllegalArgumentException("La longitud máxima de línea es demasiado pequeña");
        }
        this.maximoLinea = maximoLinea;
    }

    public static CanalLineas sobre(Socket socket) throws IOException {
        return new CanalLineas(socket.getInputStream(), new BufferedOutputStream(socket.getOutputStream()),
                MAXIMO_LINEA_POR_DEFECTO);
    }

    /**
     * Lee una línea sin su terminador. Acepta CRLF y, por tolerancia, LF solo.
     *
     * @throws EOFException si el otro extremo cerró la conexión
     * @throws IOException  si la línea supera el máximo permitido
     */
    public byte[] leerLineaBytes() throws IOException {
        return leerLineaBytes(maximoLinea);
    }

    /** Igual que {@link #leerLineaBytes()}, con un máximo menor para respuestas cuyas líneas son cortas por definición. */
    public byte[] leerLineaBytes(int maximo) throws IOException {
        int maximoLinea = Math.min(maximo, this.maximoLinea);
        ByteArrayOutputStream linea = null;
        while (true) {
            if (posicion == limite && !rellenar()) {
                throw new EOFException(linea == null ? "El otro extremo cerró la conexión"
                        : "La conexión se cerró a mitad de una línea");
            }
            int salto = buscarSalto();
            int hasta = salto >= 0 ? salto : limite;
            int acumulado = (linea == null ? 0 : linea.size()) + (hasta - posicion);
            // Si no ponemos un límite, un servidor defectuoso podría agotar la memoria con una sola línea.
            if (acumulado > maximoLinea) {
                throw new LimiteExcedidoException("La línea recibida supera el máximo de " + maximoLinea + " bytes");
            }
            if (linea == null) {
                linea = new ByteArrayOutputStream(Math.max(64, hasta - posicion));
            }
            linea.write(buffer, posicion, hasta - posicion);
            if (salto >= 0) {
                posicion = salto + 1;
                return quitarRetornoFinal(linea.toByteArray());
            }
            posicion = limite;
        }
    }

    /** Lee una línea de protocolo decodificada como ISO-8859-1. */
    public String leerLinea() throws IOException {
        return new String(leerLineaBytes(), StandardCharsets.ISO_8859_1);
    }

    /**
     * Indica si quedan bytes recibidos sin leer. Tras STARTTLS/STLS debe ser false: cualquier dato
     * pendiente llegó sin cifrar y podría haber sido inyectado por un intermediario.
     */
    public boolean hayDatosPendientes() throws IOException {
        return posicion < limite || entrada.available() > 0;
    }

    /** Escribe un comando o respuesta y lo envía de inmediato. Rechaza CR y LF internos. */
    public void escribirLinea(String linea) throws IOException {
        if (linea.indexOf('\r') >= 0 || linea.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Una línea de protocolo no puede contener CR ni LF");
        }
        escribirLinea(linea.getBytes(StandardCharsets.UTF_8));
    }

    /** Variante en bytes, útil para líneas con secretos que el llamador borrará después. */
    public void escribirLinea(byte[] linea) throws IOException {
        for (byte b : linea) {
            if (b == '\r' || b == '\n') {
                throw new IllegalArgumentException("Una línea de protocolo no puede contener CR ni LF");
            }
        }
        salida.write(linea);
        salida.write(CRLF);
        salida.flush();
    }

    /** Escribe bytes sin terminador ni vaciado; se usa para enviar bloques grandes. */
    public void escribirCrudo(byte[] datos, int desde, int cantidad) throws IOException {
        salida.write(datos, desde, cantidad);
    }

    public void vaciar() throws IOException {
        salida.flush();
    }

    private boolean rellenar() throws IOException {
        int leidos;
        do {
            leidos = entrada.read(buffer, 0, buffer.length);
        } while (leidos == 0);
        if (leidos < 0) {
            return false;
        }
        posicion = 0;
        limite = leidos;
        return true;
    }

    private int buscarSalto() {
        for (int i = posicion; i < limite; i++) {
            if (buffer[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static byte[] quitarRetornoFinal(byte[] linea) {
        if (linea.length > 0 && linea[linea.length - 1] == '\r') {
            return Arrays.copyOf(linea, linea.length - 1);
        }
        return linea;
    }
}
