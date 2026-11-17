package com.feder.presentacion.procesamiento;

import com.feder.configuracion.AppConfig;
import com.feder.configuracion.ConfiguracionException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.smtp.MecanismoAutenticacion;

import java.time.Duration;
import java.util.Locale;

/**
 * Parámetros del buzón del sistema (POP3 y SMTP) obtenidos de la configuración del entorno.
 * Si no se indica un host específico para POP3 o SMTP se usa mail.host.
 */
public final class ConfiguracionCorreo {

    private final ParametrosConexion pop3;
    private final ParametrosConexion smtp;
    private final MecanismoAutenticacion mecanismoSmtp;
    private final String direccionSistema;
    private final String identificadorBuzon;
    private final int maximoPorCiclo;
    private final boolean eliminarProcesados;
    private final Duration plazoPorEnvio;
    private final Duration plazoPorOperacionPop3;

    /** Longitud de la columna mensaje_correo.buzon. */
    private static final int MAXIMO_IDENTIFICADOR = 120;

    private ConfiguracionCorreo(ParametrosConexion pop3, ParametrosConexion smtp, MecanismoAutenticacion mecanismoSmtp,
                                String direccionSistema, String identificadorBuzon, int maximoPorCiclo,
                                boolean eliminarProcesados, Duration plazoPorEnvio, Duration plazoPorOperacionPop3) {
        this.pop3 = pop3;
        this.smtp = smtp;
        this.mecanismoSmtp = mecanismoSmtp;
        this.direccionSistema = direccionSistema.toLowerCase(Locale.ROOT);
        // Los UIDL solo son únicos dentro de un buzón: el identificador incluye usuario, host y puerto
        // para que dos buzones distintos nunca compartan la misma clave de deduplicación.
        this.identificadorBuzon = (identificadorBuzon != null ? identificadorBuzon
                : pop3.usuario() + "@" + pop3.host() + ":" + pop3.puerto()).toLowerCase(Locale.ROOT);
        if (this.identificadorBuzon.length() > MAXIMO_IDENTIFICADOR) {
            throw new ConfiguracionException("El identificador del buzón supera " + MAXIMO_IDENTIFICADOR
                    + " caracteres; defina uno más corto en mail.buzon.id.");
        }
        this.maximoPorCiclo = maximoPorCiclo;
        this.eliminarProcesados = eliminarProcesados;
        this.plazoPorEnvio = plazoPorEnvio;
        this.plazoPorOperacionPop3 = plazoPorOperacionPop3;
    }

    public static ConfiguracionCorreo desde(AppConfig config) {
        String hostGeneral = config.opcional("mail.host", null);
        String usuario = config.requerido("mail.username");
        String contrasena = config.secreto("mail.password");
        boolean textoPlano = !config.esTecnoweb() && Boolean.parseBoolean(config.opcional("mail.permitir.texto.plano", "false"));
        int timeout = config.entero("mail.timeout.ms", 1000, 300_000);

        ParametrosConexion pop3 = ParametrosConexion
                .hacia(host(config, "mail.pop3.host", hostGeneral), config.entero("mail.pop3.port", 1, 65535))
                .transporte(transporte(config.requerido("mail.pop3.security")))
                .credenciales(usuario, contrasena)
                .timeoutMs(timeout)
                .permitirTextoPlano(textoPlano)
                .construir();
        ParametrosConexion smtp = ParametrosConexion
                .hacia(host(config, "mail.smtp.host", hostGeneral), config.entero("mail.smtp.port", 1, 65535))
                .transporte(transporte(config.requerido("mail.smtp.security")))
                .credenciales(usuario, contrasena)
                .timeoutMs(timeout)
                .permitirTextoPlano(textoPlano)
                .construir();
        return new ConfiguracionCorreo(pop3, smtp, mecanismo(config.requerido("mail.smtp.auth")),
                config.requerido("mail.from"), config.opcional("mail.buzon.id", null),
                config.entero("procesamiento.max.mensajes.por.ciclo", 1, 500),
                Boolean.parseBoolean(config.opcional("mail.pop3.eliminar.procesados", "false")),
                Duration.ofSeconds(config.entero("mail.plazo.envio.segundos", 10, 3600)),
                Duration.ofSeconds(config.entero("mail.plazo.recepcion.segundos", 10, 3600)));
    }

    /** Constructor directo para pruebas automatizadas. */
    public static ConfiguracionCorreo de(ParametrosConexion pop3, ParametrosConexion smtp, MecanismoAutenticacion mecanismo,
                                         String direccionSistema, int maximoPorCiclo, boolean eliminarProcesados) {
        return new ConfiguracionCorreo(pop3, smtp, mecanismo, direccionSistema, null, maximoPorCiclo,
                eliminarProcesados, Duration.ofSeconds(120), Duration.ofSeconds(120));
    }

    /** Copia con otros plazos (pruebas de servidores lentos). */
    public ConfiguracionCorreo conPlazos(Duration envio, Duration operacionPop3) {
        return new ConfiguracionCorreo(pop3, smtp, mecanismoSmtp, direccionSistema, identificadorBuzon, maximoPorCiclo,
                eliminarProcesados, envio, operacionPop3);
    }

    /** Tiempo máximo de cada operación POP3 (conectar, autenticar, UIDL, RETR, DELE, QUIT). */
    public Duration plazoPorOperacionPop3() {
        return plazoPorOperacionPop3;
    }

    /** Tiempo máximo de un envío SMTP completo antes de cerrar la conexión. */
    public Duration plazoPorEnvio() {
        return plazoPorEnvio;
    }

    public ParametrosConexion pop3() {
        return pop3;
    }

    public ParametrosConexion smtp() {
        return smtp;
    }

    public MecanismoAutenticacion mecanismoSmtp() {
        return mecanismoSmtp;
    }

    public String direccionSistema() {
        return direccionSistema;
    }

    /** Identifica el buzón en el registro de mensajes procesados (los UIDL son únicos por buzón). */
    public String identificadorBuzon() {
        return identificadorBuzon;
    }

    public int maximoPorCiclo() {
        return maximoPorCiclo;
    }

    public boolean eliminarProcesados() {
        return eliminarProcesados;
    }

    /** Dominio para EHLO y para los Message-ID: la parte después de @ de la dirección del sistema. */
    public String dominio() {
        int arroba = direccionSistema.indexOf('@');
        return arroba < 0 ? "localhost" : direccionSistema.substring(arroba + 1);
    }

    private static String host(AppConfig config, String clave, String general) {
        String especifico = config.opcional(clave, general);
        if (especifico == null || especifico.isBlank()) {
            throw new ConfiguracionException("Falta la configuración '" + clave + "' (o mail.host).");
        }
        return especifico;
    }

    private static TransporteSeguro transporte(String valor) {
        try {
            return TransporteSeguro.valueOf(valor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfiguracionException("Modo de seguridad de correo inválido: " + valor + " (use NINGUNO, STARTTLS o TLS).");
        }
    }

    private static MecanismoAutenticacion mecanismo(String valor) {
        try {
            return MecanismoAutenticacion.valueOf(valor.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfiguracionException("Mecanismo SMTP inválido: " + valor + " (use NINGUNA, PLAIN o LOGIN).");
        }
    }
}
