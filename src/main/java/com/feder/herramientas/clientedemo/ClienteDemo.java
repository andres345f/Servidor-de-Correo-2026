package com.feder.herramientas.clientedemo;

import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.mime.CodificadorCabeceras;
import com.feder.presentacion.correo.mime.CorreoEntrante;
import com.feder.presentacion.correo.mime.EmailMessageParser;
import com.feder.presentacion.correo.mime.MensajeInvalidoException;
import com.feder.presentacion.correo.pop3.Pop3Client;
import com.feder.presentacion.correo.smtp.MecanismoAutenticacion;
import com.feder.presentacion.correo.smtp.SmtpClient;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cliente de correo de demostración para el entorno local: hace de "usuario" que envía un
 * comando al buzón del sistema por SMTP y lee las respuestas de su propio buzón por POP3.
 * Usa los mismos clientes de sockets del proyecto contra el servidor de correo local.
 *
 * Uso:
 *   enviar --de=usuario@feder.example --clave=CLAVE --para=grupo29sa@feder.local "ASUNTO"
 *   leer   --de=usuario@feder.example --clave=CLAVE
 * Opcionales: --host=127.0.0.1 --smtp=2525 --pop3=1110
 */
public final class ClienteDemo {

    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);

    private final String host;
    private final int puertoSmtp;
    private final int puertoPop3;

    public ClienteDemo(String host, int puertoSmtp, int puertoPop3) {
        this.host = host;
        this.puertoSmtp = puertoSmtp;
        this.puertoPop3 = puertoPop3;
        // Herramienta de demostración sin TLS: solo puede usarse contra el servidor local del propio equipo,
        // nunca contra un servidor remoto donde la contraseña y las respuestas viajarían en claro.
        if (!host.equals("127.0.0.1") && !host.equals("localhost") && !host.equals("::1")) {
            throw new IllegalArgumentException("El cliente de demostración solo se conecta a 127.0.0.1 (servidor local).");
        }
    }

    /** Respuesta leída del buzón del usuario. */
    public record Respuesta(String asunto, String texto) {
    }

    /** Envía un correo cuyo asunto es el comando. Devuelve el Message-ID usado. */
    public String enviar(String de, String clave, String para, String asunto) throws CorreoException {
        String messageId = "<" + UUID.randomUUID().toString().replace("-", "") + "@" + de.substring(de.indexOf('@') + 1) + ">";
        String mensaje = "Date: " + FECHA.format(ZonedDateTime.now()) + "\r\n"
                + "From: <" + de + ">\r\n"
                + "To: <" + para + ">\r\n"
                + "Subject: " + CodificadorCabeceras.textoNoEstructurado(asunto, "Subject") + "\r\n"
                + "Message-ID: " + messageId + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/plain; charset=UTF-8\r\n"
                + "\r\n"
                + "Comando enviado desde el cliente de demostración.\r\n";
        try (SmtpClient smtp = new SmtpClient(parametros(puertoSmtp, de, clave), MecanismoAutenticacion.PLAIN, "cliente.local")) {
            smtp.conectar();
            smtp.enviar(de, para, mensaje.getBytes(StandardCharsets.US_ASCII));
            smtp.quit();
        }
        return messageId;
    }

    /** Lee y elimina del buzón del usuario todas las respuestas recibidas. */
    public List<Respuesta> leer(String de, String clave) throws CorreoException {
        List<Respuesta> respuestas = new ArrayList<>();
        try (Pop3Client pop3 = new Pop3Client(parametros(puertoPop3, de, clave))) {
            pop3.conectar();
            pop3.autenticar();
            for (Map.Entry<Integer, String> entrada : pop3.uidl().entrySet()) {
                byte[] crudo = pop3.retr(entrada.getKey());
                respuestas.add(new Respuesta(asunto(crudo), CuerpoTexto.extraer(crudo)));
                pop3.dele(entrada.getKey());
            }
            pop3.quit();
        }
        return respuestas;
    }

    private ParametrosConexion parametros(int puerto, String usuario, String clave) {
        // Solo para el servidor local de demostración (127.0.0.1): no hay TLS.
        return ParametrosConexion.hacia(host, puerto)
                .transporte(TransporteSeguro.NINGUNO)
                .credenciales(usuario, clave)
                .permitirTextoPlano(true)
                .construir();
    }

    private static String asunto(byte[] crudo) {
        try {
            CorreoEntrante correo = new EmailMessageParser().analizar(crudo);
            return correo.subject();
        } catch (MensajeInvalidoException e) {
            return "(mensaje ilegible: " + e.getMessage() + ")";
        }
    }

    public static void main(String[] args) throws CorreoException {
        Map<String, String> opciones = Argumentos.leer(args);
        String accion = opciones.getOrDefault("_accion", "");
        ClienteDemo cliente = new ClienteDemo(opciones.getOrDefault("host", "127.0.0.1"),
                Integer.parseInt(opciones.getOrDefault("smtp", "2525")),
                Integer.parseInt(opciones.getOrDefault("pop3", "1110")));
        String de = Argumentos.requerido(opciones, "de");
        String clave = Argumentos.requerido(opciones, "clave");
        if (accion.equals("enviar")) {
            String para = opciones.getOrDefault("para", "grupo29sa@feder.local");
            // El asunto puede venir en la variable FEDER_ASUNTO: así ninguna consola altera sus comillas.
            String delEntorno = System.getenv("FEDER_ASUNTO");
            String asunto = delEntorno != null && !delEntorno.isBlank() ? delEntorno : Argumentos.requerido(opciones, "_texto");
            cliente.enviar(de, clave, para, asunto);
            System.out.println("Enviado a " + para + ": " + asunto);
        } else if (accion.equals("leer")) {
            List<Respuesta> respuestas = cliente.leer(de, clave);
            if (respuestas.isEmpty()) {
                System.out.println("No hay respuestas nuevas en " + de + ".");
            }
            for (Respuesta r : respuestas) {
                System.out.println("==================================================================");
                System.out.println("Asunto: " + r.asunto());
                System.out.println("------------------------------------------------------------------");
                System.out.println(r.texto());
            }
        } else {
            System.err.println("Uso: enviar --de=... --clave=... \"ASUNTO\" | leer --de=... --clave=...");
            System.exit(2);
        }
    }
}
