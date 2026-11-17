package com.feder.negocio.usuarios;

import com.feder.datos.ConexionBD;
import com.feder.datos.GestorTransacciones;
import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.pruebas.DestinoPruebas;
import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.Properties;

/**
 * Base con solo el esquema (sin semilla), para probar la instalación inicial y la concurrencia con datos
 * confirmados. Cada prueba la deja vacía otra vez: solo las cuatro filas de rol que crea 01_schema.sql.
 *
 * Por seguridad solo se usa una base cuyo nombre termina en «_vacia» y que no tiene catálogo de productos:
 * nunca se vacía una base con la semilla. vaciar() vuelve a comprobarlo, porque JUnit ejecuta @AfterEach aunque
 * el @BeforeEach se haya omitido por una suposición fallida.
 */
final class BaseVacia {

    static final String URL = System.getProperty("feder.test.db.vacia.url",
            "jdbc:postgresql://127.0.0.1:55432/feder_test_vacia");
    private static final String USUARIO = System.getProperty("feder.test.db.user", "feder_admin");

    private BaseVacia() {
    }

    static ContextoNegocio contexto() {
        Assumptions.assumeTrue(nombreAdmitido(), "La base para estas pruebas debe llamarse *_vacia: " + URL);
        try (Connection c = conexionDirecta()) {
            Assumptions.assumeTrue(soloEsquema(c), "La base " + URL + " no es una base solo con el esquema");
        } catch (SQLException e) {
            Assumptions.abort("Base vacía no disponible en " + URL + ": " + e.getMessage());
        }
        Clock reloj = Clock.systemUTC();
        return new ContextoNegocio(new GestorTransacciones(new ConexionBD(URL, USUARIO, "")), reloj,
                new ServicioBitacora(new BitacoraRepositorio()), new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
    }

    static Connection conexionDirecta() throws SQLException {
        if (!nombreAdmitido()) {
            throw new IllegalStateException("Base de pruebas no admitida (solo feder_*_vacia en 127.0.0.1:55432): " + URL);
        }
        Properties p = new Properties();
        p.setProperty("user", USUARIO);
        return DriverManager.getConnection(URL, p);
    }

    /**
     * Deja la base como la dejó 01_schema.sql: sin cuentas, concesiones ni bitácora, con las identidades reiniciadas.
     * Si la base no es la de solo esquema, no borra nada y falla.
     */
    static void vaciar() throws SQLException {
        try (Connection c = conexionDirecta()) {
            vaciar(c, URL);
        }
    }

    /** Vacía la base de la conexión solo si su URL termina en «_vacia» y no tiene catálogo de productos. */
    static void vaciar(Connection c, String url) throws SQLException {
        if (!nombreAdmitido(url)) {
            throw new IllegalStateException("No se vacía " + url + ": no es una base *_vacia solo con el esquema");
        }
        // Además de la URL, el servidor confirma a qué base y dirección quedó conectado.
        DestinoPruebas.exigirConexionLocal(c, DestinoPruebas.base(url));
        if (!soloEsquema(c)) {
            throw new IllegalStateException("No se vacía " + url + ": no es una base *_vacia solo con el esquema");
        }
        try (Statement s = c.createStatement()) {
            // TRUNCATE no dispara el trigger de solo inserción de la bitácora (es por fila); solo se usa en esta base de pruebas.
            s.execute("TRUNCATE usuario, usuario_rol, bitacora, confirmacion_operacion RESTART IDENTITY CASCADE");
        }
    }

    private static boolean nombreAdmitido() {
        return nombreAdmitido(URL);
    }

    /** Solo una base feder_*_vacia del clúster local (127.0.0.1:55432); nunca otro servidor ni otra base. */
    static boolean nombreAdmitido(String url) {
        return DestinoPruebas.esLocal(url) && DestinoPruebas.base(url).endsWith("_vacia");
    }

    private static boolean soloEsquema(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT (SELECT count(*) FROM rol) = 4 AND NOT EXISTS (SELECT 1 FROM producto)")) {
            return r.next() && r.getBoolean(1);
        }
    }
}
