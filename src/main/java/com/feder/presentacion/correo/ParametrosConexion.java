package com.feder.presentacion.correo;

import java.util.Objects;
import javax.net.ssl.SSLSocketFactory;

/**
 * Datos para abrir una sesión con un servidor POP3 o SMTP.
 *
 * La contraseña se guarda como char[] y nunca aparece en toString() ni en mensajes de error.
 * Por defecto se exige TLS implícito: quien quiera texto plano debe pedirlo de forma explícita.
 */
public final class ParametrosConexion {

    public static final int TIMEOUT_POR_DEFECTO_MS = 20_000;

    private final String host;
    private final int puerto;
    private final TransporteSeguro transporte;
    private final String usuario;
    private final char[] contrasena;
    private final int timeoutMs;
    private final SSLSocketFactory fabricaSsl;
    private final boolean permitirTextoPlano;

    private ParametrosConexion(Constructor c) {
        this.host = c.host;
        this.puerto = c.puerto;
        this.transporte = c.transporte;
        this.usuario = c.usuario;
        this.contrasena = c.contrasena == null ? null : c.contrasena.clone();
        this.timeoutMs = c.timeoutMs;
        this.fabricaSsl = c.fabricaSsl;
        this.permitirTextoPlano = c.permitirTextoPlano;
    }

    /** Inicia la construcción de los parámetros para el servidor indicado. */
    public static Constructor hacia(String host, int puerto) {
        return new Constructor(host, puerto);
    }

    public String host() {
        return host;
    }

    public int puerto() {
        return puerto;
    }

    public TransporteSeguro transporte() {
        return transporte;
    }

    public String usuario() {
        return usuario;
    }

    public boolean tieneCredenciales() {
        return usuario != null && contrasena != null;
    }

    /** Devuelve una copia de la contraseña; quien la use debe borrarla con Arrays.fill al terminar. */
    public char[] contrasena() {
        return contrasena == null ? null : contrasena.clone();
    }

    public int timeoutMs() {
        return timeoutMs;
    }

    /** Fábrica TLS alternativa (pruebas con un certificado propio); null usa la del JDK. */
    public SSLSocketFactory fabricaSsl() {
        return fabricaSsl;
    }

    /** Solo el entorno local debe activarlo: permite enviar credenciales sin cifrar. */
    public boolean permitirTextoPlano() {
        return permitirTextoPlano;
    }

    @Override
    public String toString() {
        return "ParametrosConexion[host=" + host + ", puerto=" + puerto + ", transporte=" + transporte
                + ", usuario=" + (usuario == null ? "-" : usuario)
                + ", contrasena=" + (contrasena == null ? "-" : "****")
                + ", timeoutMs=" + timeoutMs + ", permitirTextoPlano=" + permitirTextoPlano + "]";
    }

    /** Constructor fluido de {@link ParametrosConexion}. */
    public static final class Constructor {
        private final String host;
        private final int puerto;
        private TransporteSeguro transporte = TransporteSeguro.TLS;
        private String usuario;
        private char[] contrasena;
        private int timeoutMs = TIMEOUT_POR_DEFECTO_MS;
        private SSLSocketFactory fabricaSsl;
        private boolean permitirTextoPlano;

        private Constructor(String host, int puerto) {
            if (host == null || host.isBlank() || host.indexOf('\r') >= 0 || host.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("El host del servidor de correo no es válido");
            }
            if (puerto < 1 || puerto > 65_535) {
                throw new IllegalArgumentException("El puerto debe estar entre 1 y 65535 (recibido: " + puerto + ")");
            }
            this.host = host.trim();
            this.puerto = puerto;
        }

        public Constructor transporte(TransporteSeguro transporte) {
            this.transporte = Objects.requireNonNull(transporte, "transporte");
            return this;
        }

        public Constructor credenciales(String usuario, char[] contrasena) {
            this.usuario = Objects.requireNonNull(usuario, "usuario");
            this.contrasena = Objects.requireNonNull(contrasena, "contrasena").clone();
            return this;
        }

        public Constructor credenciales(String usuario, String contrasena) {
            return credenciales(usuario, Objects.requireNonNull(contrasena, "contrasena").toCharArray());
        }

        public Constructor timeoutMs(int timeoutMs) {
            if (timeoutMs <= 0) {
                throw new IllegalArgumentException("El tiempo de espera debe ser positivo");
            }
            this.timeoutMs = timeoutMs;
            return this;
        }

        public Constructor fabricaSsl(SSLSocketFactory fabricaSsl) {
            this.fabricaSsl = fabricaSsl;
            return this;
        }

        public Constructor permitirTextoPlano(boolean permitir) {
            this.permitirTextoPlano = permitir;
            return this;
        }

        public ParametrosConexion construir() {
            return new ParametrosConexion(this);
        }
    }
}
