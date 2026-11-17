package com.feder.presentacion.correo.mime;

import com.feder.presentacion.correo.mime.LectorCabeceras.Cabecera;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Interpreta las cabeceras de un mensaje RFC 5322 recibido por POP3.
 *
 * Solo se analizan las cabeceras: el sistema recibe los comandos en el asunto y no necesita el cuerpo.
 * Los mensajes con Subject o From duplicados se rechazan, porque distintos programas podrían
 * leer valores diferentes y el remitente usado para autorizar no sería confiable.
 */
public final class EmailMessageParser {

    public static final int MAXIMO_BYTES_CABECERAS = 256 * 1024;
    public static final int MAXIMO_CABECERAS = 1000;
    private static final Pattern MESSAGE_ID = Pattern.compile("<[\\x21-\\x3B\\x3D\\x3F-\\x7E]{1,250}>");

    public CorreoEntrante analizar(byte[] crudo) throws MensajeInvalidoException {
        Objects.requireNonNull(crudo, "crudo");
        List<Cabecera> cabeceras = LectorCabeceras.leer(crudo, MAXIMO_BYTES_CABECERAS, MAXIMO_CABECERAS);
        exigirUnica(cabeceras, "Subject");
        exigirUnica(cabeceras, "From");

        String fromCrudo = primera(cabeceras, "From");
        if (fromCrudo == null) {
            throw new MensajeInvalidoException("El mensaje no tiene cabecera From");
        }
        AnalizadorDireccion.Direccion remitente = AnalizadorDireccion.analizar(fromCrudo);
        if (remitente == null) {
            throw new MensajeInvalidoException("La cabecera From no contiene una única dirección válida");
        }
        AnalizadorDireccion.Direccion responderA = AnalizadorDireccion.analizar(primera(cabeceras, "Reply-To"));

        // El asunto se entrega fiel, sin quitar caracteres de control: el analizador de comandos
        // los rechaza indicando la posición, lo que resulta más útil para el usuario.
        String asunto = "";
        String errorAsunto = null;
        Cabecera subject = primeraCabecera(cabeceras, "Subject");
        if (subject != null) {
            DecodificadorRfc2047.Resultado decodificado = DecodificadorRfc2047.analizar(subject.valor());
            asunto = decodificado.texto();
            // Primero los bytes de 8 bits que no son UTF-8 (afectan a todo el valor) y luego las palabras RFC 2047.
            errorAsunto = subject.errorCodificacion() != null ? subject.errorCodificacion() : decodificado.error();
        }

        List<String> nombres = new ArrayList<>(cabeceras.size());
        for (Cabecera cabecera : cabeceras) {
            nombres.add(cabecera.nombre());
        }
        return new CorreoEntrante(
                normalizarMessageId(primera(cabeceras, "Message-ID")),
                remitente.direccion(),
                remitente.nombre(),
                responderA == null ? null : responderA.direccion(),
                primera(cabeceras, "Return-Path"),
                asunto,
                primera(cabeceras, "Auto-Submitted"),
                primera(cabeceras, "Precedence"),
                primera(cabeceras, "Date"),
                primera(cabeceras, "Content-Type"),
                nombres,
                errorAsunto,
                subject == null ? null : subject.valor());
    }

    private static void exigirUnica(List<Cabecera> cabeceras, String nombre) throws MensajeInvalidoException {
        long cantidad = cabeceras.stream().filter(c -> c.nombre().equalsIgnoreCase(nombre)).count();
        if (cantidad > 1) {
            throw new MensajeInvalidoException("El mensaje tiene la cabecera " + nombre + " repetida " + cantidad
                    + " veces y se descarta por seguridad");
        }
    }

    private static String primera(List<Cabecera> cabeceras, String nombre) {
        Cabecera cabecera = primeraCabecera(cabeceras, nombre);
        return cabecera == null ? null : cabecera.valor();
    }

    private static Cabecera primeraCabecera(List<Cabecera> cabeceras, String nombre) {
        for (Cabecera cabecera : cabeceras) {
            if (cabecera.nombre().equalsIgnoreCase(nombre)) {
                return cabecera;
            }
        }
        return null;
    }

    // Un Message-ID con espacios o caracteres raros no se reutiliza en In-Reply-To: se descarta.
    private static String normalizarMessageId(String valor) {
        if (valor == null) {
            return null;
        }
        String recortado = valor.strip();
        return MESSAGE_ID.matcher(recortado).matches() ? recortado : null;
    }
}
