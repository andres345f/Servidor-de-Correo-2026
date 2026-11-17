package com.feder.presentacion.correo.smtp;

import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.Secretos;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Diálogo SASL PLAIN y LOGIN (RFC 4954). Los arreglos con secretos se borran al terminar.
 */
final class AutenticadorSmtp {

    /** Envía una línea y devuelve la respuesta del servidor. */
    @FunctionalInterface
    interface Intercambio {
        SmtpRespuesta enviar(byte[] linea) throws IOException, CorreoException;
    }

    private AutenticadorSmtp() {
    }

    static void autenticar(MecanismoAutenticacion mecanismo, String usuario, char[] clave, Intercambio canal)
            throws IOException, CorreoException {
        try {
            switch (mecanismo) {
                case PLAIN -> plain(usuario, clave, canal);
                case LOGIN -> login(usuario, clave, canal);
                default -> throw new IllegalArgumentException("Mecanismo sin autenticación: " + mecanismo);
            }
        } catch (SmtpTransitorioException | SmtpPermanenteException e) {
            throw e; // ya se crearon sin el texto del servidor
        } catch (CorreoException e) {
            // Respuesta mal formada durante AUTH: tampoco se conserva su texto (podría repetir la credencial).
            throw new CorreoException("Respuesta SMTP no válida durante la autenticación", "(texto del servidor omitido)");
        }
    }

    private static void plain(String usuario, char[] clave, Intercambio canal) throws IOException, CorreoException {
        byte[] usuarioBytes = usuario.getBytes(StandardCharsets.UTF_8);
        byte[] claveBytes = Secretos.utf8(clave);
        // Formato RFC 4616: [authzid] NUL authcid NUL contraseña; el authzid vacío significa "el mismo usuario".
        byte[] credencial = new byte[usuarioBytes.length + claveBytes.length + 2];
        System.arraycopy(usuarioBytes, 0, credencial, 1, usuarioBytes.length);
        System.arraycopy(claveBytes, 0, credencial, usuarioBytes.length + 2, claveBytes.length);
        byte[] base64 = Base64.getEncoder().encode(credencial);
        byte[] linea = Secretos.concatenar("AUTH PLAIN ", base64);
        try {
            exigirAceptada(canal.enviar(linea));
        } finally {
            Secretos.borrar(claveBytes, credencial, base64, linea);
        }
    }

    private static void login(String usuario, char[] clave, Intercambio canal) throws IOException, CorreoException {
        exigirContinuacion(canal.enviar("AUTH LOGIN".getBytes(StandardCharsets.US_ASCII)));
        exigirContinuacion(canal.enviar(Base64.getEncoder().encode(usuario.getBytes(StandardCharsets.UTF_8))));
        byte[] claveBytes = Secretos.utf8(clave);
        byte[] base64 = Base64.getEncoder().encode(claveBytes);
        try {
            exigirAceptada(canal.enviar(base64));
        } finally {
            Secretos.borrar(claveBytes, base64);
        }
    }

    private static void exigirContinuacion(SmtpRespuesta respuesta) throws CorreoException {
        if (respuesta.codigo() != 334) {
            throw respuesta.comoExcepcionSinTexto("El servidor SMTP interrumpió AUTH LOGIN");
        }
    }

    private static void exigirAceptada(SmtpRespuesta respuesta) throws CorreoException {
        if (respuesta.codigo() != 235) {
            throw respuesta.comoExcepcionSinTexto("La autenticación SMTP fue rechazada");
        }
    }
}
