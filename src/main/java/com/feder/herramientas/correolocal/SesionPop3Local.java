package com.feder.herramientas.correolocal;

import com.feder.presentacion.correo.CanalLineas;
import com.feder.presentacion.correo.RellenoPuntos;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Una conexión POP3 con el servidor local, con los estados AUTHORIZATION, TRANSACTION y UPDATE de RFC 1939.
 *
 * Los borrados (DELE) solo se aplican al recibir QUIT; si la conexión se corta, el buzón queda intacto.
 */
final class SesionPop3Local {

    private final ConfiguracionCorreoLocal configuracion;
    private final BuzonesEnMemoria buzones;
    private Socket socket;
    private CanalLineas canal;
    private boolean cifrada;
    private String usuarioPendiente;
    private String buzon;
    private List<MensajeAlmacenado> mensajes = List.of();
    private boolean[] marcados = new boolean[0];

    SesionPop3Local(ConfiguracionCorreoLocal configuracion, BuzonesEnMemoria buzones) {
        this.configuracion = configuracion;
        this.buzones = buzones;
    }

    void atender(Socket cliente) throws IOException {
        socket = cliente;
        try {
            if (configuracion.pop3TlsImplicito()) {
                socket = ServidorTcpBase.elevarTls(cliente, configuracion.contextoTls());
                cifrada = true;
            }
            canal = CanalLineas.sobre(socket);
            ok("FEDER local POP3 ready");
            while (true) {
                String linea = canal.leerLinea();
                int espacio = linea.indexOf(' ');
                String verbo = (espacio < 0 ? linea : linea.substring(0, espacio)).toUpperCase(Locale.ROOT);
                // El argumento se toma completo: una contraseña puede contener espacios.
                String argumento = espacio < 0 ? "" : linea.substring(espacio + 1);
                if (verbo.equals("QUIT")) {
                    salir();
                    return;
                }
                if (buzon == null) {
                    autorizacion(verbo, argumento);
                } else {
                    transaccion(verbo, argumento.strip());
                }
            }
        } finally {
            if (buzon != null) {
                buzones.liberar(buzon);
            }
            if (socket != cliente) {
                socket.close();
            }
        }
    }

    private void autorizacion(String verbo, String argumento) throws IOException {
        switch (verbo) {
            case "CAPA" -> capa();
            case "STLS" -> iniciarTls();
            case "USER" -> {
                usuarioPendiente = argumento.strip();
                ok("send PASS");
            }
            case "PASS" -> pass(argumento);
            default -> error("command not valid before authentication");
        }
    }

    private void transaccion(String verbo, String argumento) throws IOException {
        switch (verbo) {
            case "CAPA" -> capa();
            case "STAT" -> ok(cantidadVigente() + " " + tamanoVigente());
            case "LIST" -> listar(argumento, false);
            case "UIDL" -> {
                if (configuracion.pop3AdmiteUidl()) {
                    listar(argumento, true);
                } else {
                    error("UIDL not supported");
                }
            }
            case "RETR" -> retr(argumento);
            case "DELE" -> dele(argumento);
            case "RSET" -> {
                marcados = new boolean[mensajes.size()];
                ok("maildrop has " + mensajes.size() + " messages");
            }
            case "NOOP" -> ok("");
            default -> error("unknown command");
        }
    }

    private void capa() throws IOException {
        ok("Capability list follows");
        canal.escribirLinea("USER");
        if (configuracion.pop3AdmiteUidl()) {
            canal.escribirLinea("UIDL");
        }
        if (configuracion.contextoTls() != null && !cifrada && buzon == null) {
            canal.escribirLinea("STLS");
        }
        canal.escribirLinea(".");
    }

    private void iniciarTls() throws IOException {
        if (configuracion.contextoTls() == null || cifrada) {
            error("STLS not available");
            return;
        }
        ok("Begin TLS negotiation");
        socket = ServidorTcpBase.elevarTls(socket, configuracion.contextoTls());
        canal = CanalLineas.sobre(socket);
        cifrada = true;
        usuarioPendiente = null;
    }

    private void pass(String clave) throws IOException {
        if (usuarioPendiente == null) {
            error("send USER first");
            return;
        }
        String usuario = usuarioPendiente;
        usuarioPendiente = null;
        if (!configuracion.credencialesValidas(usuario, clave)) {
            configuracion.bitacora().accept("POP3 local: autenticación rechazada para " + usuario);
            error("[AUTH] invalid credentials");
            return;
        }
        String claveBuzon = usuario.strip().toLowerCase(Locale.ROOT);
        if (!buzones.reservar(claveBuzon)) {
            error("[IN-USE] mailbox already locked");
            return;
        }
        buzon = claveBuzon;
        mensajes = buzones.mensajes(buzon);
        marcados = new boolean[mensajes.size()];
        configuracion.bitacora().accept("POP3 local: sesión abierta para " + usuario + " con " + mensajes.size() + " mensajes");
        ok("maildrop has " + mensajes.size() + " messages");
    }

    private void listar(String argumento, boolean uidl) throws IOException {
        if (!argumento.isEmpty()) {
            int indice = indiceVigente(argumento);
            if (indice >= 0) {
                ok((indice + 1) + " " + (uidl ? mensajes.get(indice).uidl() : mensajes.get(indice).tamano()));
            }
            return;
        }
        ok(cantidadVigente() + " messages");
        for (int i = 0; i < mensajes.size(); i++) {
            if (!marcados[i]) {
                canal.escribirLinea((i + 1) + " " + (uidl ? mensajes.get(i).uidl() : mensajes.get(i).tamano()));
            }
        }
        canal.escribirLinea(".");
    }

    private void retr(String argumento) throws IOException {
        int indice = indiceVigente(argumento);
        if (indice < 0) {
            return;
        }
        MensajeAlmacenado mensaje = mensajes.get(indice);
        ok(mensaje.tamano() + " octets");
        RellenoPuntos.escribirBloque(canal, mensaje.contenido());
    }

    private void dele(String argumento) throws IOException {
        int indice = indiceVigente(argumento);
        if (indice < 0) {
            return;
        }
        marcados[indice] = true;
        ok("message " + (indice + 1) + " deleted");
    }

    /** Estado UPDATE: aplica los borrados marcados y termina la sesión. */
    private void salir() throws IOException {
        if (buzon != null) {
            List<String> borrar = new ArrayList<>();
            for (int i = 0; i < mensajes.size(); i++) {
                if (marcados[i]) {
                    borrar.add(mensajes.get(i).uidl());
                }
            }
            buzones.eliminar(buzon, borrar);
            configuracion.bitacora().accept("POP3 local: " + borrar.size() + " mensajes eliminados de " + buzon);
        }
        ok("bye");
    }

    /** Índice (desde 0) de un mensaje existente y no marcado; si no, responde -ERR y devuelve -1. */
    private int indiceVigente(String argumento) throws IOException {
        int numero;
        try {
            numero = Integer.parseInt(argumento.strip());
        } catch (NumberFormatException e) {
            error("invalid message number");
            return -1;
        }
        if (numero < 1 || numero > mensajes.size() || marcados[numero - 1]) {
            error("no such message");
            return -1;
        }
        return numero - 1;
    }

    private int cantidadVigente() {
        int cantidad = 0;
        for (boolean marcado : marcados) {
            cantidad += marcado ? 0 : 1;
        }
        return cantidad;
    }

    private long tamanoVigente() {
        long total = 0;
        for (int i = 0; i < mensajes.size(); i++) {
            total += marcados[i] ? 0 : mensajes.get(i).tamano();
        }
        return total;
    }

    private void ok(String texto) throws IOException {
        canal.escribirLinea(texto.isEmpty() ? "+OK" : "+OK " + texto);
    }

    private void error(String texto) throws IOException {
        canal.escribirLinea("-ERR " + texto);
    }
}
