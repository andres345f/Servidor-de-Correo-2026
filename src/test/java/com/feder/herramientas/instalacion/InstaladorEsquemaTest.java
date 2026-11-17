package com.feder.herramientas.instalacion;

import com.feder.datos.ConexionBD;
import com.feder.pruebas.DestinoPruebas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Instalación controlada del esquema sobre una base propia del clúster local, que se crea vacía para la prueba.
 * Comprueba que sin la confirmación exacta no se modifica nada y que una segunda instalación no borra datos.
 */
class InstaladorEsquemaTest {

    private static final String SERVIDOR = "jdbc:postgresql://127.0.0.1:55432/";
    private static final String BASE = DestinoPruebas.exigirNombreLocal("feder_test_instalador");
    private static final String USUARIO = System.getProperty("feder.test.db.user", "feder_admin");

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream salida = new PrintStream(bytes, true, StandardCharsets.UTF_8);

    /** Solo se limpia lo que la preparación llegó a crear (sin PostgreSQL local, la prueba se omite). */
    private boolean baseCreada;

    @BeforeEach
    void crearBaseVacia() {
        try (Connection c = DestinoPruebas.abrirMantenimientoLocal(USUARIO);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + BASE + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + BASE + " ENCODING 'UTF8' TEMPLATE template0");
            baseCreada = true;
        } catch (SQLException e) {
            Assumptions.abort("PostgreSQL local no disponible: " + e.getMessage());
        }
    }

    @AfterEach
    void eliminarBase() throws SQLException {
        if (!baseCreada) {
            return;
        }
        try (Connection c = DestinoPruebas.abrirMantenimientoLocal(USUARIO);
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + BASE + " WITH (FORCE)");
        }
    }

    private InstaladorEsquema instalador() {
        return new InstaladorEsquema(new ConexionBD(SERVIDOR + BASE, USUARIO, ""), Path.of("db", "01_schema.sql"), salida);
    }

    @Test
    @DisplayName("Sin confirmación, o con otro nombre de base, solo informa y no crea nada")
    void sinConfirmacionNoModifica() {
        assertFalse(instalador().ejecutar(null));
        assertFalse(instalador().ejecutar("db_grupo29sa"));
        assertEquals(0, instalador().inspeccionar().tablasPublic());
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("--confirmar=" + BASE));
    }

    @Test
    @DisplayName("Con la confirmación exacta instala las 28 tablas; una segunda instalación se niega sin borrar datos")
    void instalaUnaSolaVez() throws SQLException {
        assertTrue(instalador().ejecutar(BASE));
        assertEquals(InstaladorEsquema.TABLAS_FEDER.size(), instalador().inspeccionar().tablasFeder().size());
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement()) {
            s.execute("INSERT INTO categoria (nombre, descripcion) VALUES ('Testigo', 'Fila que no debe perderse')");
        }
        assertFalse(instalador().ejecutar(BASE), "con tablas de FEDER no se vuelve a instalar");
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement();
             var r = s.executeQuery("SELECT count(*) FROM categoria WHERE nombre = 'Testigo'")) {
            r.next();
            assertEquals(1, r.getInt(1), "la fila testigo sigue en la base");
        }
    }

    @Test
    @DisplayName("El script se detiene por sí mismo si ya existen tablas de FEDER, aunque se ejecute sin el instalador")
    void elScriptTieneSuPropioGuardia() throws Exception {
        assertTrue(instalador().ejecutar(BASE));
        String sql = java.nio.file.Files.readString(Path.of("db", "01_schema.sql"), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.execute(sql));
            assertTrue(e.getMessage().contains("ya contiene objetos de FEDER"), e.getMessage());
        }
    }
}
