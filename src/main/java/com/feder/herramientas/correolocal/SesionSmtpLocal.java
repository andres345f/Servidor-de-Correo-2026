package com.feder.herramientas.correolocal;

import com.feder.presentacion.correo.CanalLineas;
import com.feder.presentacion.correo.RellenoPuntos;
import com.feder.presentacion.correo.ValidadorDireccion;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Una conexión SMTP con el servidor local: máquina de estados EHLO → [AUTH] → MAIL → RCPT → DATA.
 *
 * Las respuestas de protocolo se escriben en ASCII. Nunca se registran contraseñas.
 */
final class SesionSmtpLocal {

    private static final int MAXIMO_MENSAJE = 10 * 1024 * 1024;
    private static final int MAXIMO_DESTINATARIOS = 100;

    private final ConfiguracionCorreoLocal configuracion;
    private final BuzonesEnMemoria buzones;
    private final ServidorSmtpLocal servidor;
    private final List<String> destinatarios = new ArrayList<>();
    private Socket socket;
    private CanalLineas canal;
    private boolean cifrada;
    private boolean saludado;
    private boolean autenticado;
    private String remitente;

    SesionSmtpLocal(ConfiguracionCorreoLocal configuracion, BuzonesEnMemoria buzones, ServidorSmtpLocal servidor) {
        this.configuracion = configuracion;
        this.buzones = buzones;
        this.servidor = servidor;
    }

    void atender(Socket cliente) throws IOException {
        socket = cliente;
        canal = CanalLineas.sobre(socket);
        try {
            responder("220 localhost ESMTP FEDER local");
            while (true) {
                String linea = canal.leerLinea();
                int espacio = linea.indexOf(' ');
                String verbo = (espacio < 0 ? linea : linea.substring(0, espacio)).toUpperCase(Locale.ROOT);
                String argumento = espacio < 0 ? "" : linea.substring(espacio + 1).strip();
                switch (verbo) {
                    case "EHLO", "HELO" -> saludar(verbo, argumento);
                    case "STARTTLS" -> iniciarTls();
                    case "AUTH" -> autenticar(argumento);
                    case "MAIL" -> mail(argumento);
                    case "RCPT" -> rcpt(argumento);
                    case "DATA" -> data();
                    case "RSET" -> {
                        reiniciar();
                        responder("250 2.0.0 Reset OK");
                    }
                    case "NOOP" -> responder("250 2.0.0 OK");
                    case "QUIT" -> {
                        responder("221 2.0.0 Bye");
                        return;
                    }
                    default -> responder("502 5.5.2 Command not recognized");
                }
            }
        } finally {
            if (socket != cliente) {
                socket.close();
            }
        }
    }

    private void saludar(String verbo, String argumento) throws IOException {
        if (argumento.isEmpty()) {
            responder("501 5.5.4 " + verbo + " requires a domain");
            return;
        }
        reiniciar();
        saludado = true;
        if (verbo.equals("HELO")) {
            responder("250 localhost");
            return;
        }
        List<String> lineas = new ArrayList<>();
        lineas.add("localhost greets " + argumento);
        lineas.add("SIZE " + MAXIMO_MENSAJE);
        lineas.add("8BITMIME");
        if (configuracion.contextoTls() != null && !cifrada) {
            lineas.add("STARTTLS");
        }
        lineas.add("AUTH PLAIN LOGIN");
        lineas.add("ENHANCEDSTATUSCODES");
        for (int i = 0; i < lineas.size(); i++) {
            responder("250" + (i < lineas.size() - 1 ? "-" : " ") + lineas.get(i));
        }
    }

    private void iniciarTls() throws IOException {
        if (configuracion.contextoTls() == null || cifrada) {
            responder("502 5.5.1 STARTTLS not available");
            return;
        }
        responder("220 2.0.0 Ready to start TLS");
        socket = ServidorTcpBase.elevarTls(socket, configuracion.contextoTls());
        canal = CanalLineas.sobre(socket);
        cifrada = true;
        // RFC 3207: el estado previo se descarta y el cliente debe volver a saludar.
        saludado = false;
        autenticado = false;
        reiniciar();
    }

    private void autenticar(String argumento) throws IOException {
        if (!saludado || autenticado) {
            responder("503 5.5.1 Bad sequence of commands");
            return;
        }
        String[] partes = argumento.split(" ", 2);
        String mecanismo = partes[0].toUpperCase(Locale.ROOT);
        String usuario;
        String clave;
        try {
            if (mecanismo.equals("PLAIN")) {
                String inicial = partes.length > 1 ? partes[1] : pedir("334 ");
                String[] campos = decodificar(inicial).split("\0", -1);
                if (campos.length != 3) {
                    responder("501 5.5.2 Malformed PLAIN credentials");
                    return;
                }
                usuario = campos[1];
                clave = campos[2];
            } else if (mecanismo.equals("LOGIN")) {
                usuario = decodificar(pedir("334 VXNlcm5hbWU6"));
                clave = decodificar(pedir("334 UGFzc3dvcmQ6"));
            } else {
                responder("504 5.5.4 Unrecognized authentication type");
                return;
            }
        } catch (IllegalArgumentException e) {
            responder("501 5.5.2 Invalid base64 data");
            return;
        }
        if (configuracion.credencialesValidas(usuario, clave)) {
            autenticado = true;
            bitacora("AUTH " + mecanismo + " aceptado para " + usuario);
            responder("235 2.7.0 Authentication successful");
        } else {
            bitacora("AUTH " + mecanismo + " rechazado para " + usuario);
            responder("535 5.7.8 Authentication credentials invalid");
        }
    }

    private void mail(String argumento) throws IOException {
        if (!saludado) {
            responder("503 5.5.1 Send EHLO first");
            return;
        }
        if (configuracion.smtpExigeAutenticacion() && !autenticado) {
            responder("530 5.7.0 Authentication required");
            return;
        }
        String direccion = extraerRuta(argumento, "FROM:");
        if (direccion == null || (!direccion.isEmpty() && !ValidadorDireccion.esValida(direccion))) {
            responder("501 5.1.7 Bad sender address syntax");
            return;
        }
        reiniciar();
        remitente = direccion;
        responder("250 2.1.0 Sender OK");
    }

    private void rcpt(String argumento) throws IOException {
        if (remitente == null) {
            responder("503 5.5.1 Need MAIL before RCPT");
            return;
        }
        String direccion = extraerRuta(argumento, "TO:");
        if (direccion == null || !ValidadorDireccion.esValida(direccion)) {
            responder("501 5.1.3 Bad recipient address syntax");
            return;
        }
        if (destinatarios.size() >= MAXIMO_DESTINATARIOS) {
            responder("452 4.5.3 Too many recipients");
            return;
        }
        destinatarios.add(direccion.toLowerCase(Locale.ROOT));
        responder("250 2.1.5 Recipient OK");
    }

    private void data() throws IOException {
        if (destinatarios.isEmpty()) {
            responder("503 5.5.1 Need RCPT before DATA");
            return;
        }
        int fallo = servidor.tomarFalloData();
        if (fallo != 0) {
            reiniciar();
            responder(fallo + (fallo < 500 ? " 4.3.0 Simulated temporary failure" : " 5.7.1 Simulated permanent rejection"));
            return;
        }
        responder("354 End data with <CR><LF>.<CR><LF>");
        byte[] contenido = RellenoPuntos.leerBloque(canal, MAXIMO_MENSAJE);
        for (String destinatario : destinatarios) {
            buzones.entregar(destinatario, contenido);
            bitacora("Mensaje de " + contenido.length + " bytes entregado a " + destinatario);
        }
        reiniciar();
        responder("250 2.0.0 Message accepted for delivery");
    }

    /** Extrae la dirección de "FROM:<a@b> PARAMS"; devuelve "" para "<>" y null si no hay sintaxis válida. */
    private static String extraerRuta(String argumento, String prefijo) {
        if (!argumento.toUpperCase(Locale.ROOT).startsWith(prefijo)) {
            return null;
        }
        String resto = argumento.substring(prefijo.length()).strip();
        int cierra = resto.indexOf('>');
        if (!resto.startsWith("<") || cierra < 0) {
            return null;
        }
        return resto.substring(1, cierra);
    }

    private String pedir(String desafio) throws IOException {
        responder(desafio);
        String respuesta = canal.leerLinea();
        if (respuesta.equals("*")) {
            throw new IllegalArgumentException("Autenticación cancelada por el cliente");
        }
        return respuesta;
    }

    private static String decodificar(String base64) {
        return new String(Base64.getDecoder().decode(base64.strip()), StandardCharsets.UTF_8);
    }

    private void reiniciar() {
        remitente = null;
        destinatarios.clear();
    }

    private void responder(String linea) throws IOException {
        canal.escribirLinea(linea);
    }

    private void bitacora(String mensaje) {
        configuracion.bitacora().accept("SMTP local: " + mensaje);
    }
}
