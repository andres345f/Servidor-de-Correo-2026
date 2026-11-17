package com.feder.herramientas.correolocal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;

/**
 * Opciones de los servidores de correo locales. Se configura antes de iniciarlos.
 *
 * Puerto 0 significa "puerto efímero" (lo elige el sistema operativo), útil en pruebas paralelas.
 */
public final class ConfiguracionCorreoLocal {

    private int puertoSmtp;
    private int puertoPop3;
    private final Map<String, String> cuentas = new LinkedHashMap<>();
    private SSLContext contextoTls;
    private boolean pop3TlsImplicito;
    private boolean smtpExigeAutenticacion;
    private boolean pop3AdmiteUidl = true;
    // Por defecto no se registra nada; la demo pasa System.out para ver la actividad.
    private Consumer<String> bitacora = mensaje -> { };

    public ConfiguracionCorreoLocal puertoSmtp(int puerto) {
        this.puertoSmtp = validarPuerto(puerto);
        return this;
    }

    public ConfiguracionCorreoLocal puertoPop3(int puerto) {
        this.puertoPop3 = validarPuerto(puerto);
        return this;
    }

    /** Agrega una cuenta (usuario = dirección del buzón) válida para AUTH SMTP y USER/PASS POP3. */
    public ConfiguracionCorreoLocal cuenta(String direccion, String contrasena) {
        cuentas.put(Objects.requireNonNull(direccion, "direccion").strip().toLowerCase(Locale.ROOT),
                Objects.requireNonNull(contrasena, "contrasena"));
        return this;
    }

    /** Contexto TLS del servidor: habilita STARTTLS en SMTP y STLS (o TLS implícito) en POP3. */
    public ConfiguracionCorreoLocal tls(SSLContext contexto) {
        this.contextoTls = contexto;
        return this;
    }

    public ConfiguracionCorreoLocal pop3TlsImplicito(boolean implicito) {
        this.pop3TlsImplicito = implicito;
        return this;
    }

    public ConfiguracionCorreoLocal smtpExigeAutenticacion(boolean exige) {
        this.smtpExigeAutenticacion = exige;
        return this;
    }

    /** Permite simular un servidor sin UIDL para probar cómo reacciona el cliente. */
    public ConfiguracionCorreoLocal pop3AdmiteUidl(boolean admite) {
        this.pop3AdmiteUidl = admite;
        return this;
    }

    public ConfiguracionCorreoLocal bitacora(Consumer<String> bitacora) {
        this.bitacora = Objects.requireNonNull(bitacora, "bitacora");
        return this;
    }

    public int puertoSmtp() {
        return puertoSmtp;
    }

    public int puertoPop3() {
        return puertoPop3;
    }

    public SSLContext contextoTls() {
        return contextoTls;
    }

    public boolean pop3TlsImplicito() {
        return pop3TlsImplicito;
    }

    public boolean smtpExigeAutenticacion() {
        return smtpExigeAutenticacion;
    }

    public boolean pop3AdmiteUidl() {
        return pop3AdmiteUidl;
    }

    public Consumer<String> bitacora() {
        return bitacora;
    }

    public Set<String> usuarios() {
        return Set.copyOf(cuentas.keySet());
    }

    /** Compara en tiempo constante para no revelar por temporización cuántos caracteres coinciden. */
    boolean credencialesValidas(String usuario, String contrasena) {
        if (usuario == null || contrasena == null) {
            return false;
        }
        String esperada = cuentas.get(usuario.strip().toLowerCase(Locale.ROOT));
        if (esperada == null) {
            return false;
        }
        return MessageDigest.isEqual(esperada.getBytes(StandardCharsets.UTF_8),
                contrasena.getBytes(StandardCharsets.UTF_8));
    }

    private static int validarPuerto(int puerto) {
        if (puerto < 0 || puerto > 65_535) {
            throw new IllegalArgumentException("Puerto fuera de rango: " + puerto);
        }
        return puerto;
    }
}
