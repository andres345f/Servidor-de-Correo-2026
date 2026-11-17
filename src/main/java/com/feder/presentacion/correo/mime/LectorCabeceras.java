package com.feder.presentacion.correo.mime;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Extrae el bloque de cabeceras (hasta la primera línea vacía) y lo despliega en pares nombre-valor.
 *
 * Bytes de 8 bits sin codificar: se decide <b>cabecera por cabecera</b> (antes se decidía para todo el bloque,
 * y un byte extraño en una cabecera cualquiera deformaba un asunto UTF-8 correcto).
 * <ul>
 *   <li>UTF-8 válido: se acepta (RFC 6532).</li>
 *   <li>Cualquier otro byte de 8 bits: el valor se entrega leído como ISO-8859-1 (útil para mostrar un nombre de
 *       remitente de un cliente antiguo) y {@link Cabecera#errorCodificacion()} lo indica. Esos bytes no
 *       permiten saber con certeza qué escribió el usuario (Latin-1, otro juego o UTF-8 truncado se ven
 *       iguales), así que quien ejecuta comandos debe rechazarlo.</li>
 * </ul>
 */
final class LectorCabeceras {

    /**
     * Cabecera ya desplegada: el valor no contiene CRLF.
     *
     * @param errorCodificacion motivo si la cabecera trae bytes de 8 bits que no son UTF-8 (el valor está leído como
     *                          ISO-8859-1 y es solo aproximado), o null
     */
    record Cabecera(String nombre, String valor, String errorCodificacion) {

        Cabecera(String nombre, String valor) {
            this(nombre, valor, null);
        }
    }

    static final String BYTES_NO_UTF8 = "el asunto contiene bytes de 8 bits sin codificar que no son UTF-8 válido";

    private LectorCabeceras() {
    }

    static List<Cabecera> leer(byte[] crudo, int maximoBytes, int maximoCabeceras) throws MensajeInvalidoException {
        int fin = finDeCabeceras(crudo, maximoBytes);
        List<Cabecera> cabeceras = new ArrayList<>();
        String nombre = null;
        ByteArrayOutputStream valor = new ByteArrayOutputStream();
        int inicio = 0;
        while (true) {
            int salto = indiceDe(crudo, (byte) '\n', inicio, fin);
            int finLinea = salto < 0 ? fin : salto;
            // Igual que dividir por "\r?\n" y quitar después un '\r' final más.
            if (salto >= 0 && finLinea > inicio && crudo[finLinea - 1] == '\r') {
                finLinea--;
            }
            if (finLinea > inicio && crudo[finLinea - 1] == '\r') {
                finLinea--;
            }
            if (finLinea > inicio && (crudo[inicio] == ' ' || crudo[inicio] == '\t')) {
                // Línea de continuación (RFC 5322, 2.2.3): desplegar es quitar el CRLF y conservar el espacio.
                if (nombre != null) {
                    valor.write(crudo, inicio, finLinea - inicio);
                }
            } else {
                agregar(cabeceras, nombre, valor, maximoCabeceras);
                nombre = null;
                valor.reset();
                int dosPuntos = indiceDe(crudo, (byte) ':', inicio, finLinea);
                // Líneas sin ':' (p. ej. el "From " de un buzón mbox) no son cabeceras: se descartan.
                if (dosPuntos > inicio) {
                    byte[] bytesNombre = new byte[dosPuntos - inicio];
                    System.arraycopy(crudo, inicio, bytesNombre, 0, bytesNombre.length);
                    String candidato = texto(bytesNombre).stripTrailing();
                    if (esNombreValido(candidato)) {
                        nombre = candidato;
                        valor.write(crudo, dosPuntos + 1, finLinea - dosPuntos - 1);
                    }
                }
            }
            if (salto < 0) {
                break;
            }
            inicio = salto + 1;
        }
        agregar(cabeceras, nombre, valor, maximoCabeceras);
        return cabeceras;
    }

    private static void agregar(List<Cabecera> cabeceras, String nombre, ByteArrayOutputStream valor, int maximo)
            throws MensajeInvalidoException {
        if (nombre == null) {
            return;
        }
        if (cabeceras.size() >= maximo) {
            throw new MensajeInvalidoException("El mensaje tiene más de " + maximo + " cabeceras");
        }
        byte[] bytes = valor.toByteArray();
        String utf8 = utf8Estricto(bytes);
        if (utf8 != null) {
            cabeceras.add(new Cabecera(nombre, utf8.strip(), null));
        } else {
            cabeceras.add(new Cabecera(nombre, new String(bytes, StandardCharsets.ISO_8859_1).strip(), BYTES_NO_UTF8));
        }
    }

    /** UTF-8 si los bytes lo son; si no, ISO-8859-1 (que nunca falla). */
    private static String texto(byte[] bytes) {
        String utf8 = utf8Estricto(bytes);
        return utf8 != null ? utf8 : new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String utf8Estricto(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static int indiceDe(byte[] datos, byte buscado, int desde, int hasta) {
        for (int i = desde; i < hasta; i++) {
            if (datos[i] == buscado) {
                return i;
            }
        }
        return -1;
    }

    /** Posición donde terminan las cabeceras (excluye la línea vacía) o fallo si superan el máximo. */
    private static int finDeCabeceras(byte[] crudo, int maximoBytes) throws MensajeInvalidoException {
        int inicioLinea = 0;
        for (int i = 0; i < crudo.length; i++) {
            if (i >= maximoBytes) {
                throw new MensajeInvalidoException("Las cabeceras del mensaje superan " + maximoBytes + " bytes");
            }
            if (crudo[i] == '\n') {
                int largo = i - inicioLinea;
                boolean vacia = largo == 0 || (largo == 1 && crudo[inicioLinea] == '\r');
                if (vacia) {
                    return inicioLinea;
                }
                inicioLinea = i + 1;
            }
        }
        return crudo.length;
    }

    private static boolean esNombreValido(String nombre) {
        if (nombre.isEmpty()) {
            return false;
        }
        for (int i = 0; i < nombre.length(); i++) {
            char c = nombre.charAt(i);
            if (c < 0x21 || c > 0x7E || c == ':') {
                return false;
            }
        }
        return true;
    }
}
