package com.feder.herramientas.diagnostico;

import com.feder.configuracion.AppConfig;
import com.feder.datos.ConexionBD;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.pop3.Pop3Client;
import com.feder.presentacion.correo.smtp.MecanismoAutenticacion;
import com.feder.presentacion.correo.smtp.SmtpClient;
import com.feder.presentacion.procesamiento.ConfiguracionCorreo;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * Diagnóstico NO destructivo de la infraestructura configurada (pensado para TecnoWeb):
 *   1. Resolución DNS de los servidores.
 *   2. Puertos TCP de correo habituales (para descubrir qué ofrece realmente el servidor).
 *   3. POP3 y SMTP: saludo y capacidades; si hay contraseñas configuradas, también la autenticación
 *      (sin leer ni borrar mensajes y sin enviar correos).
 *   4. PostgreSQL: conexión y conteo de tablas (solo lectura), si hay contraseña configurada.
 * Nunca muestra contraseñas; sin ellas, omite los pasos que las necesitan y lo indica.
 */
public final class DiagnosticoInfraestructura {

    private static final List<Integer> PUERTOS_CORREO = List.of(110, 995, 143, 993, 25, 465, 587);

    private final AppConfig config;
    private final PrintStream salida;

    public DiagnosticoInfraestructura(AppConfig config, PrintStream salida) {
        this.config = config;
        this.salida = salida;
    }

    /** Devuelve true si todo lo que se pudo comprobar respondió correctamente. */
    public boolean ejecutar() {
        salida.println("Diagnóstico del entorno '" + config.entorno() + "'");
        String hostPop3 = config.opcional("mail.pop3.host", config.opcional("mail.host", ""));
        String hostSmtp = config.opcional("mail.smtp.host", config.opcional("mail.host", ""));
        boolean dns = resolver(hostPop3) & resolver(hostSmtp) & resolver(hostDeBase());
        sondearPuertos(hostPop3);
        boolean conCredenciales = config.tieneSecreto("mail.password");
        boolean pop3;
        boolean smtp;
        if (conCredenciales) {
            ConfiguracionCorreo correo = ConfiguracionCorreo.desde(config);
            pop3 = probarPop3(correo.pop3(), true);
            smtp = probarSmtp(correo.smtp(), correo.mecanismoSmtp(), correo.dominio());
        } else {
            salida.println("[AVISO] MAIL_PASSWORD no configurada: se prueban saludo y capacidades, sin autenticar.");
            pop3 = probarPop3(sinCredenciales(hostPop3, "mail.pop3.port", "mail.pop3.security"), false);
            smtp = probarSmtp(sinCredenciales(hostSmtp, "mail.smtp.port", "mail.smtp.security"),
                    MecanismoAutenticacion.NINGUNA, "diagnostico.local");
        }
        boolean bd = config.tieneSecreto("db.password") || !config.esTecnoweb() ? probarBaseDeDatos() : avisarBdSinClave();
        salida.println();
        salida.println("Resultado: DNS=" + estado(dns) + "  POP3=" + estado(pop3) + "  SMTP=" + estado(smtp)
                + "  PostgreSQL=" + estado(bd));
        return dns && pop3 && smtp && bd;
    }

    private String hostDeBase() {
        String url = config.opcional("db.url", "");
        return url.replaceFirst("^jdbc:postgresql://", "").replaceFirst("[:/].*$", "");
    }

    private ParametrosConexion sinCredenciales(String host, String clavePuerto, String claveSeguridad) {
        return ParametrosConexion.hacia(host, config.entero(clavePuerto, 1, 65535))
                .transporte(TransporteSeguro.valueOf(config.requerido(claveSeguridad).toUpperCase(Locale.ROOT)))
                .timeoutMs(config.entero("mail.timeout.ms", 1000, 300_000))
                .construir();
    }

    private boolean resolver(String host) {
        if (host.isBlank()) {
            return true;
        }
        try {
            InetAddress[] direcciones = InetAddress.getAllByName(host);
            StringBuilder lista = new StringBuilder();
            for (InetAddress d : direcciones) {
                lista.append(d.getHostAddress()).append(' ');
            }
            salida.println("[DNS] " + host + " -> " + lista.toString().trim());
            return true;
        } catch (UnknownHostException e) {
            salida.println("[DNS] " + host + " -> NO RESUELVE (" + e.getMessage() + ")");
            return false;
        }
    }

    private void sondearPuertos(String host) {
        for (int puerto : PUERTOS_CORREO) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, puerto), 4000);
                salida.println("[TCP] " + host + ":" + puerto + " abierto");
            } catch (IOException e) {
                salida.println("[TCP] " + host + ":" + puerto + " sin respuesta (" + e.getClass().getSimpleName() + ")");
            }
        }
    }

    private boolean probarPop3(ParametrosConexion parametros, boolean autenticar) {
        try (Pop3Client pop3 = new Pop3Client(parametros)) {
            pop3.conectar();
            salida.println("[POP3] conectado a " + parametros.host() + ":" + parametros.puerto() + " cifrado=" + pop3.esCifrada());
            salida.println("[POP3] capacidades: " + pop3.capa());
            if (autenticar) {
                pop3.autenticar();
                salida.println("[POP3] autenticación correcta; mensajes en el buzón: " + pop3.stat().cantidad());
            }
            pop3.quit();
            return true;
        } catch (CorreoException e) {
            salida.println("[POP3] ERROR: " + e.getMessage());
            return false;
        }
    }

    private boolean probarSmtp(ParametrosConexion p, MecanismoAutenticacion mecanismo, String dominio) {
        try (SmtpClient smtp = new SmtpClient(p, mecanismo, dominio)) {
            smtp.conectar();
            salida.println("[SMTP] conectado a " + p.host() + ":" + p.puerto() + " cifrado=" + smtp.esCifrada());
            salida.println("[SMTP] extensiones: " + smtp.extensiones().keySet());
            smtp.quit();
            return true;
        } catch (CorreoException e) {
            salida.println("[SMTP] ERROR: " + e.getMessage());
            return false;
        }
    }

    private boolean probarBaseDeDatos() {
        ConexionBD conexion = new ConexionBD(config.requerido("db.url"), config.requerido("db.username"),
                config.secreto("db.password"));
        try (Connection c = conexion.abrir(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")) {
            r.next();
            salida.println("[BD] conectado a " + conexion.destinoSinCredenciales() + "; tablas en public: " + r.getLong(1));
            return true;
        } catch (Exception e) {
            salida.println("[BD] ERROR: " + e.getMessage());
            return false;
        }
    }

    private boolean avisarBdSinClave() {
        salida.println("[AVISO] DB_PASSWORD no configurada: se omite la conexión a PostgreSQL.");
        return false;
    }

    private static String estado(boolean ok) {
        return ok ? "OK" : "FALLA";
    }
}
