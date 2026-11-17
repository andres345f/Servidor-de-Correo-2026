package com.feder.presentacion.correo.smtp;

import com.feder.presentacion.correo.CanalLineas;
import com.feder.presentacion.correo.CorreoException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Respuesta SMTP completa (RFC 5321, sección 4.2), posiblemente multilínea.
 *
 * Cada línea es "NNN-texto" salvo la última, "NNN texto". Todas deben llevar el mismo código.
 */
public final class SmtpRespuesta {

    private static final int MAXIMO_LINEAS = 200;
    private static final int MAXIMO_LONGITUD_LINEA = 2048;

    private final int codigo;
    private final List<String> textos;
    private final String ultimaLinea;

    private SmtpRespuesta(int codigo, List<String> textos, String ultimaLinea) {
        this.codigo = codigo;
        this.textos = List.copyOf(textos);
        this.ultimaLinea = ultimaLinea;
    }

    /** Lee una respuesta completa validando código, separador y longitud de cada línea. */
    public static SmtpRespuesta leer(CanalLineas canal) throws IOException, CorreoException {
        List<String> textos = new ArrayList<>();
        int codigo = -1;
        while (true) {
            String linea = canal.leerLinea();
            if (linea.length() < 3 || linea.length() > MAXIMO_LONGITUD_LINEA) {
                throw new CorreoException("Línea de respuesta SMTP con longitud no válida", linea);
            }
            int actual = interpretarCodigo(linea);
            if (codigo == -1) {
                codigo = actual;
            } else if (actual != codigo) {
                throw new CorreoException("Respuesta SMTP multilínea con códigos distintos", linea);
            }
            char separador = linea.length() > 3 ? linea.charAt(3) : ' ';
            if (separador != ' ' && separador != '-') {
                throw new CorreoException("Separador no válido después del código SMTP", linea);
            }
            textos.add(linea.length() > 4 ? linea.substring(4) : "");
            if (separador == ' ') {
                return new SmtpRespuesta(codigo, textos, linea);
            }
            if (textos.size() >= MAXIMO_LINEAS) {
                throw new CorreoException("La respuesta SMTP multilínea es demasiado larga", linea);
            }
        }
    }

    private static int interpretarCodigo(String linea) throws CorreoException {
        char c0 = linea.charAt(0);
        char c1 = linea.charAt(1);
        char c2 = linea.charAt(2);
        // RFC 5321: primer dígito 2 a 5 y segundo 0 a 5. Cualquier otra cosa indica un servidor no SMTP.
        if (c0 < '2' || c0 > '5' || c1 < '0' || c1 > '5' || c2 < '0' || c2 > '9') {
            throw new CorreoException("Código de respuesta SMTP no válido", linea);
        }
        return (c0 - '0') * 100 + (c1 - '0') * 10 + (c2 - '0');
    }

    public int codigo() {
        return codigo;
    }

    /** Texto de cada línea sin el código ni el separador. */
    public List<String> textos() {
        return textos;
    }

    /** Última línea tal como la envió el servidor (p. ej. "250 2.0.0 Ok: queued"). */
    public String ultimaLinea() {
        return ultimaLinea;
    }

    /** Convierte la respuesta en la excepción que corresponde a su clase (4xx, 5xx u otra). */
    public CorreoException comoExcepcion(String contexto) {
        return comoExcepcion(contexto, ultimaLinea);
    }

    /**
     * Igual que {@link #comoExcepcion(String)} pero sin el texto del servidor. Se usa en la
     * autenticación: algunos servidores repiten en el error parte de la credencial recibida.
     */
    public CorreoException comoExcepcionSinTexto(String contexto) {
        return comoExcepcion(contexto, "(texto del servidor omitido)");
    }

    private CorreoException comoExcepcion(String contexto, String textoServidor) {
        if (codigo >= 400 && codigo < 500) {
            return new SmtpTransitorioException(contexto + " (rechazo temporal " + codigo + ")", codigo, textoServidor);
        }
        if (codigo >= 500) {
            return new SmtpPermanenteException(contexto + " (rechazo permanente " + codigo + ")", codigo, textoServidor);
        }
        return new CorreoException(contexto + ": respuesta inesperada " + codigo, textoServidor);
    }
}
