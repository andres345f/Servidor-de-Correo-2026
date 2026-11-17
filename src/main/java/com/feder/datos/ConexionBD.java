package com.feder.datos;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Fábrica de conexiones JDBC a PostgreSQL.
 *
 * El procesamiento de correos es secuencial, por eso basta con abrir una conexión por
 * operación (no se necesita un pool). La contraseña nunca aparece en los mensajes de error.
 */
public class ConexionBD {

    private final String url;
    private final String usuario;
    private final String contrasena;

    public ConexionBD(String url, String usuario, String contrasena) {
        if (url == null || !url.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("La URL de base de datos debe empezar con jdbc:postgresql://");
        }
        this.url = url;
        this.usuario = usuario;
        this.contrasena = contrasena == null ? "" : contrasena;
    }

    public Connection abrir() {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", usuario);
        if (!contrasena.isEmpty()) {
            propiedades.setProperty("password", contrasena);
        }
        propiedades.setProperty("ApplicationName", "feder-email-driven");
        // Tiempo máximo razonable para no bloquear el ciclo de correo si la BD no responde.
        propiedades.setProperty("connectTimeout", "10");
        propiedades.setProperty("socketTimeout", "60");
        try {
            return DriverManager.getConnection(url, propiedades);
        } catch (SQLException e) {
            throw new AccesoDatosException("No se pudo conectar con PostgreSQL en " + destinoSinCredenciales()
                    + " (" + e.getSQLState() + ")", e);
        }
    }

    /** Host, puerto y base, sin usuario ni contraseña: apto para logs. */
    public String destinoSinCredenciales() {
        return url.replaceFirst("\\?.*$", "");
    }
}
