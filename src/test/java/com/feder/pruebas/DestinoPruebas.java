package com.feder.pruebas;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confinamiento de las pruebas al clúster local del proyecto.
 *
 * Varias pruebas confirman datos y luego los borran, vacían una base de solo esquema o recrean la base de
 * extremo a extremo. Todo eso solo se permite contra 127.0.0.1:55432 (scripts/pg-local.ps1) y bases cuyo
 * nombre empieza por feder_. La protección tiene dos partes:
 *   1. La URL debe ser exactamente jdbc:postgresql://127.0.0.1:55432/feder_... (o localhost), SIN parámetros:
 *      el controlador JDBC acepta parámetros como ?PGHOST=...&PGDBNAME=... que reemplazan el servidor y la base
 *      escritos antes en la URL, así que cualquier «?» se rechaza.
 *   2. Antes de una operación destructiva se pregunta al propio servidor a qué base y a qué dirección quedó
 *      conectado (current_database, inet_server_addr, inet_server_port); si no coincide, no se ejecuta nada.
 */
public final class DestinoPruebas {

    public static final int PUERTO_LOCAL = 55432;
    private static final Pattern URL_LOCAL =
            Pattern.compile("^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):55432/(feder_[a-z0-9_]+)$");
    private static final Pattern NOMBRE_BASE = Pattern.compile("^feder_[a-z0-9_]+$");
    private static final Set<String> DIRECCIONES_LOCALES = Set.of("127.0.0.1", "::1");

    private DestinoPruebas() {
    }

    /** True si la URL apunta, sin parámetros, a una base feder_* del clúster local de pruebas. */
    public static boolean esLocal(String url) {
        return url != null && URL_LOCAL.matcher(url).matches();
    }

    /** Devuelve la URL si es local; si no, falla antes de abrir ninguna conexión. */
    public static String exigirLocal(String url) {
        if (!esLocal(url)) {
            throw new IllegalStateException("Las pruebas solo se ejecutan contra el clúster local 127.0.0.1:55432 y bases"
                    + " feder_*, con una URL sin parámetros; destino rechazado: " + sinParametros(url));
        }
        return url;
    }

    /** La URL sin parámetros ni fragmento, para mostrarla en un error sin revelar posibles credenciales. */
    static String sinParametros(String url) {
        return url == null ? "(sin URL)" : url.replaceFirst("[?#].*$", "?…").replaceFirst("//[^/@]*@", "//…@");
    }

    /**
     * Conexión a la base de mantenimiento «postgres» del clúster local, verificada con el propio servidor. Se usa
     * antes de crear o eliminar bases de prueba (CREATE/DROP DATABASE).
     */
    public static Connection abrirMantenimientoLocal(String usuario) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + PUERTO_LOCAL + "/postgres", usuario, "");
        try {
            exigirConexionLocal(c, "postgres");
            return c;
        } catch (RuntimeException | SQLException e) {
            c.close();
            throw e;
        }
    }

    /** Nombre de la base de una URL local ya validada. */
    public static String base(String url) {
        Matcher m = URL_LOCAL.matcher(exigirLocal(url));
        m.matches();
        return m.group(1);
    }

    /** Valida un nombre de base que las pruebas van a recrear (DROP/CREATE DATABASE). */
    public static String exigirNombreLocal(String nombre) {
        if (nombre == null || !NOMBRE_BASE.matcher(nombre).matches()) {
            throw new IllegalStateException("Nombre de base de pruebas no admitido (debe empezar por feder_): " + nombre);
        }
        return nombre;
    }

    /**
     * Comprueba, preguntando al servidor, que la conexión abierta es a la base esperada del clúster local
     * (dirección de loopback y puerto 55432). Se llama antes de borrar o vaciar datos.
     */
    public static void exigirConexionLocal(Connection conexion, String baseEsperada) throws SQLException {
        try (Statement s = conexion.createStatement();
             ResultSet r = s.executeQuery("SELECT current_database(), host(inet_server_addr()), inet_server_port()")) {
            r.next();
            String base = r.getString(1);
            String direccion = r.getString(2);
            int puerto = r.getInt(3);
            if (!baseEsperada.equals(base) || !DIRECCIONES_LOCALES.contains(direccion) || puerto != PUERTO_LOCAL) {
                throw new IllegalStateException("La conexión de pruebas no es la esperada (base " + base + " en " + direccion
                        + ":" + puerto + "; se esperaba " + baseEsperada + " en 127.0.0.1:" + PUERTO_LOCAL
                        + "): no se ejecuta ninguna operación.");
            }
        }
    }
}
