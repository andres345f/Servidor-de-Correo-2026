package com.feder.presentacion.procesamiento;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * Diagnóstico rápido de red cuando un servidor deja de responder: ¿se resuelve el nombre?, ¿el servidor responde?,
 * ¿el puerto acepta conexiones?, ¿hay internet? Con eso se distingue "sin internet", "servidor caído",
 * "servicio detenido" y "IP bloqueada". Tarda como máximo unos 10 segundos y solo se ejecuta al empezar una caída.
 */
final class DiagnosticoRed {

    private static final int ESPERA_MS = 3000;
    /** Dirección fija (sin DNS) para comprobar si hay salida a internet. */
    private static final String REFERENCIA_INTERNET = "1.1.1.1";

    enum EstadoPuerto { ABIERTO, RECHAZADO, SIN_RESPUESTA }

    record Resultado(boolean dns, String ip, boolean servidorResponde, int numeroPuerto, EstadoPuerto puerto, boolean internet) {

        String resumen(String ok, String falla) {
            StringBuilder s = new StringBuilder("DNS ").append(dns ? ok : falla);
            if (dns) {
                s.append(" · servidor ").append(ip).append(" responde ").append(servidorResponde ? ok : falla)
                        .append(" · puerto ").append(numeroPuerto).append(' ')
                        .append(puerto == EstadoPuerto.ABIERTO ? ok : falla)
                        .append(puerto == EstadoPuerto.RECHAZADO ? " (rechazado)" : puerto == EstadoPuerto.SIN_RESPUESTA ? " (sin respuesta)" : "");
            }
            return s.append(" · internet ").append(internet ? ok : falla).toString();
        }

        /** Quién tiene el problema, en una frase. */
        String conclusion(String servicio) {
            if (!internet) {
                return "tu computadora no tiene conexión a internet.";
            }
            if (!dns) {
                return "hay internet, pero el nombre del servidor no se resuelve: fallan los servidores DNS del dominio"
                        + "\n             (problema del proveedor del servidor) o el DNS de tu red.";
            }
            return switch (puerto) {
                case ABIERTO -> "el puerto responde ahora: fue un corte momentáneo o el servidor está saturado.";
                case RECHAZADO -> "el servidor está encendido, pero " + servicio + " está detenido (puerto cerrado).";
                case SIN_RESPUESTA -> servidorResponde
                        ? "el servidor está encendido pero no acepta conexiones a " + servicio
                        + "\n             (servicio detenido en el servidor o tu IP bloqueada)."
                        : "el servidor no responde: está apagado o es inaccesible desde tu red.";
            };
        }
    }

    private DiagnosticoRed() {
    }

    static Resultado ejecutar(String host, int puerto) {
        boolean internet = conecta(REFERENCIA_INTERNET, 443) == EstadoPuerto.ABIERTO;
        InetAddress direccion;
        try {
            direccion = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            return new Resultado(false, null, false, puerto, EstadoPuerto.SIN_RESPUESTA, internet);
        }
        boolean responde;
        try {
            responde = direccion.isReachable(ESPERA_MS);
        } catch (IOException e) {
            responde = false;
        }
        EstadoPuerto estado = conecta(direccion.getHostAddress(), puerto);
        // Si el puerto acepta o rechaza conexiones, el servidor está encendido aunque no conteste al ping.
        return new Resultado(true, direccion.getHostAddress(), responde || estado != EstadoPuerto.SIN_RESPUESTA, puerto,
                estado, internet);
    }

    private static EstadoPuerto conecta(String host, int puerto) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, puerto), ESPERA_MS);
            return EstadoPuerto.ABIERTO;
        } catch (ConnectException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
            return m.contains("timed out") ? EstadoPuerto.SIN_RESPUESTA : EstadoPuerto.RECHAZADO;
        } catch (IOException e) {
            return EstadoPuerto.SIN_RESPUESTA;
        }
    }
}
