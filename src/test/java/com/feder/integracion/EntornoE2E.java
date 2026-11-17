package com.feder.integracion;

import com.feder.Aplicacion;
import com.feder.configuracion.AppConfig;
import com.feder.herramientas.clientedemo.ClienteDemo;
import com.feder.herramientas.correolocal.ConfiguracionCorreoLocal;
import com.feder.herramientas.correolocal.ServidorCorreoLocal;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.smtp.MecanismoAutenticacion;
import com.feder.presentacion.correo.smtp.SmtpClient;
import com.feder.presentacion.procesamiento.CicloCorreo;
import com.feder.pruebas.DestinoPruebas;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

/**
 * Entorno completo para las pruebas de extremo a extremo:
 *   - base PostgreSQL propia (feder_e2e) recreada con db/01_schema.sql y db/02_seed.sql;
 *   - servidor de correo local con SMTP y POP3 reales sobre TCP (puertos efímeros);
 *   - la aplicación construida con la misma raíz de composición que en producción.
 * Los usuarios de la semilla tienen buzón local con la clave de demostración.
 */
final class EntornoE2E implements AutoCloseable {

    static final String SISTEMA = "grupo29sa@feder.local";
    static final String CLAVE = "clave-demo";
    /** Base propia de estas pruebas (se recrea); con otro nombre, dos copias del proyecto pueden probarse a la vez. */
    private static final String BASE = DestinoPruebas.exigirNombreLocal(System.getProperty("feder.test.e2e.base", "feder_e2e"));
    private static final String SERVIDOR = "jdbc:postgresql://127.0.0.1:55432/";
    private static final String USUARIO_BD = "feder_admin";

    private final ServidorCorreoLocal correo;
    private final Aplicacion aplicacion;
    private final ClienteDemo cliente;

    private EntornoE2E(ServidorCorreoLocal correo, Aplicacion aplicacion) {
        this.correo = correo;
        this.aplicacion = aplicacion;
        this.cliente = new ClienteDemo("127.0.0.1", correo.puertoSmtp(), correo.puertoPop3());
    }

    static EntornoE2E iniciar(List<String> buzonesUsuarios) throws IOException {
        recrearBase();
        ConfiguracionCorreoLocal configuracion = new ConfiguracionCorreoLocal().puertoSmtp(0).puertoPop3(0)
                .cuenta(SISTEMA, CLAVE).smtpExigeAutenticacion(true).bitacora(linea -> { });
        buzonesUsuarios.forEach(b -> configuracion.cuenta(b, CLAVE));
        ServidorCorreoLocal servidor = new ServidorCorreoLocal(configuracion);
        servidor.iniciar();
        AppConfig config = AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.ofEntries(
                Map.entry("db.url", SERVIDOR + BASE),
                Map.entry("db.username", USUARIO_BD),
                Map.entry("db.password", ""),
                Map.entry("mail.username", SISTEMA),
                Map.entry("mail.password", CLAVE),
                Map.entry("mail.from", SISTEMA),
                Map.entry("mail.host", "127.0.0.1"),
                Map.entry("mail.pop3.port", String.valueOf(servidor.puertoPop3())),
                Map.entry("mail.pop3.security", "NINGUNO"),
                Map.entry("mail.smtp.port", String.valueOf(servidor.puertoSmtp())),
                Map.entry("mail.smtp.security", "NINGUNO"),
                Map.entry("mail.smtp.auth", "PLAIN"),
                Map.entry("mail.permitir.texto.plano", "true")));
        return new EntornoE2E(servidor, new Aplicacion(config));
    }

    void enviar(String de, String asunto) throws CorreoException {
        cliente.enviar(de, CLAVE, SISTEMA, asunto);
    }

    /**
     * Envía por SMTP real (cliente de sockets del proyecto, AUTH PLAIN) los bytes exactos de un mensaje:
     * sirve para probar cabeceras codificadas o de 8 bits tal como las mandaría otro cliente de correo.
     */
    void enviarCrudo(String de, byte[] mensaje) throws CorreoException {
        ParametrosConexion parametros = ParametrosConexion.hacia("127.0.0.1", correo.puertoSmtp())
                .transporte(TransporteSeguro.NINGUNO)
                .credenciales(de, CLAVE)
                .permitirTextoPlano(true)
                .construir();
        try (SmtpClient smtp = new SmtpClient(parametros, MecanismoAutenticacion.PLAIN, "pruebas.local")) {
            smtp.conectar();
            smtp.enviar(de, SISTEMA, mensaje);
            smtp.quit();
        }
    }

    /** Entrega un mensaje con cabeceras arbitrarias directamente en el buzón del sistema. */
    void entregarCrudo(String mensaje) {
        correo.buzones().entregar(SISTEMA, mensaje.getBytes(StandardCharsets.UTF_8));
    }

    CicloCorreo.Resumen ciclo() {
        return aplicacion.nuevoCiclo().ejecutar();
    }

    List<ClienteDemo.Respuesta> leer(String de) throws CorreoException {
        return cliente.leer(de, CLAVE);
    }

    ServidorCorreoLocal correo() {
        return correo;
    }

    Aplicacion aplicacion() {
        return aplicacion;
    }

    /** Consulta escalar sobre la base de la prueba (verificación independiente de la aplicación). */
    long contar(String sql, Object... parametros) {
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO_BD, "");
             java.sql.PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                s.setObject(i + 1, parametros[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Consulta de verificación fallida: " + e.getMessage(), e);
        }
    }

    /** Sentencia de preparación sobre la base de la prueba (solo para simular situaciones). */
    void ejecutar(String sql) {
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO_BD, "");
             Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Sentencia de preparación fallida: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        correo.detener();
    }

    private static void recrearBase() {
        try (Connection c = DestinoPruebas.abrirMantenimientoLocal(USUARIO_BD);
             Statement s = c.createStatement()) {
            // El nombre de la base es una constante de la prueba, no un dato externo.
            s.execute("DROP DATABASE IF EXISTS " + BASE + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + BASE + " ENCODING 'UTF8' TEMPLATE template0");
        } catch (SQLException e) {
            Assumptions.abort("PostgreSQL local no disponible (scripts/pg-local.ps1 init): " + e.getMessage());
        }
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE + "?preferQueryMode=simple", USUARIO_BD, "");
             Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("db", "01_schema.sql"), StandardCharsets.UTF_8));
            s.execute(Files.readString(Path.of("db", "02_seed.sql"), StandardCharsets.UTF_8));
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("No se pudo cargar el esquema o la semilla: " + e.getMessage(), e);
        }
    }
}
