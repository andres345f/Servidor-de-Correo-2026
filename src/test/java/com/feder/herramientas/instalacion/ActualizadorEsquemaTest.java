package com.feder.herramientas.instalacion;

import com.feder.datos.ConexionBD;
import com.feder.datos.GestorTransacciones;
import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.pruebas.DestinoPruebas;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Actualización del esquema sobre una base propia del clúster local, que se crea para cada prueba.
 * La instalación anterior se reproduce cargando el esquema y la semilla actuales y quitando los objetos
 * que agrega la migración 001; después se comprueba que la migración los repone sin perder ninguna fila.
 */
class ActualizadorEsquemaTest {

    private static final String SERVIDOR = "jdbc:postgresql://127.0.0.1:55432/";
    private static final String BASE = DestinoPruebas.exigirNombreLocal("feder_mig_anterior");
    private static final String USUARIO = System.getProperty("feder.test.db.user", "feder_admin");
    private static final String MIGRACION_001 = "001_correo_saliente_notificacion.sql";
    private static final List<String> TABLAS_CON_DATOS =
            List.of("usuario", "pago", "pedido", "mensaje_correo", "correo_saliente", "bitacora");

    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final PrintStream salida = new PrintStream(bytes, true, StandardCharsets.UTF_8);
    private final ConexionBD conexion = new ConexionBD(SERVIDOR + BASE, USUARIO, "");

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

    @Test
    @DisplayName("Una base instalada con el 01_schema.sql actual no tiene migraciones pendientes y no se modifica")
    void instalacionNuevaSinPendientes() throws Exception {
        ejecutar(leer(Path.of("db", "01_schema.sql")));
        String antes = columnasDeCorreoSaliente();

        assertEquals(List.of(), ActualizadorEsquema.pendientes(conexion));
        assertTrue(actualizador().ejecutar(null), "sin pendientes el esquema ya está al día");
        assertTrue(actualizador().ejecutar(BASE));
        assertEquals(antes, columnasDeCorreoSaliente());
        assertTrue(texto().contains("Migraciones pendientes: ninguna"), texto());

        // El script, ejecutado directamente, tampoco cambia nada ni falla.
        ejecutar(leer(Path.of("db", "migraciones", MIGRACION_001)));
        assertEquals(antes, columnasDeCorreoSaliente());
    }

    @Test
    @DisplayName("Con un esquema propio del usuario en la base, la comprobación sigue mirando el esquema public de FEDER")
    void esquemaPropioDelUsuarioNoConfunde() throws Exception {
        ejecutar(leer(Path.of("db", "01_schema.sql")));
        // Con el search_path por omisión ("$user", public), current_schema() pasaría a ser este esquema.
        ejecutar("CREATE SCHEMA \"" + USUARIO + "\"");
        assertEquals(List.of(), ActualizadorEsquema.pendientes(conexion), "la migración ya está aplicada en public");
    }

    @Test
    @DisplayName("Una columna agregada a mano sin su clave foránea ni su índice sigue contando como pendiente")
    void columnaSinClaveNiIndiceEsPendiente() throws Exception {
        crearInstalacionAnterior();
        ejecutar("ALTER TABLE correo_saliente ADD COLUMN notificacion_id BIGINT");
        assertEquals(List.of(MIGRACION_001), ActualizadorEsquema.pendientes(conexion));
        assertTrue(actualizador().ejecutar(BASE));
        assertEquals(List.of(), ActualizadorEsquema.pendientes(conexion), "la migración completa la clave y el índice");
    }

    @Test
    @DisplayName("Objetos con los nombres esperados pero de otro tipo o en otra tabla no cuentan como migración aplicada")
    void nombresCorrectosConDefinicionIncorrectaSonPendientes() throws Exception {
        crearInstalacionAnterior();
        ejecutar("ALTER TABLE correo_saliente ADD COLUMN notificacion_id BIGINT");
        ejecutar("ALTER TABLE correo_saliente ADD CONSTRAINT fk_correo_saliente_notificacion CHECK (true)");
        ejecutar("CREATE INDEX ix_correo_saliente_notificacion ON mensaje_correo (id)");
        assertEquals(List.of(MIGRACION_001), ActualizadorEsquema.pendientes(conexion));
    }

    @Test
    @DisplayName("Instalación anterior: sin la confirmación exacta solo informa la migración pendiente")
    void sinConfirmacionNoModifica() throws Exception {
        crearInstalacionAnterior();
        Map<String, Long> antes = contarFilas();

        assertEquals(List.of(MIGRACION_001), ActualizadorEsquema.pendientes(conexion));
        assertFalse(actualizador().ejecutar(null));
        assertFalse(actualizador().ejecutar("db_grupo29sa"));

        assertFalse(existe("SELECT 1 FROM information_schema.columns WHERE table_name = 'correo_saliente'"
                + " AND column_name = 'notificacion_id'"), "la columna no se crea sin confirmación");
        assertEquals(antes, contarFilas());
        assertTrue(texto().contains(MIGRACION_001), texto());
        assertTrue(texto().contains("--confirmar=" + BASE), texto());
    }

    @Test
    @DisplayName("Instalación anterior: con la confirmación exacta agrega columna, clave e índice sin perder filas, y repetirla no cambia nada")
    void migraSinPerderDatos() throws Exception {
        crearInstalacionAnterior();
        Map<String, Long> antes = contarFilas();

        assertTrue(actualizador().ejecutar(BASE));

        assertTrue(existe("SELECT 1 FROM information_schema.columns WHERE table_name = 'correo_saliente'"
                + " AND column_name = 'notificacion_id' AND data_type = 'bigint' AND is_nullable = 'YES'"));
        assertTrue(existe("SELECT 1 FROM pg_constraint WHERE conname = 'fk_correo_saliente_notificacion'"
                + " AND contype = 'f' AND confrelid = 'notificacion'::regclass AND confdeltype = 'r'"));
        assertTrue(existe("SELECT 1 FROM pg_indexes WHERE tablename = 'correo_saliente'"
                + " AND indexname = 'ix_correo_saliente_notificacion'"));
        assertEquals(antes, contarFilas(), "la migración no agrega ni quita filas");
        assertTrue(existe("SELECT 1 FROM correo_saliente WHERE message_id = '<anterior-1@feder.example>'"
                + " AND asunto = 'FEDER - Respuesta anterior' AND notificacion_id IS NULL"),
                "el correo de la versión anterior se conserva tal cual");

        // Segunda ejecución: nada pendiente y nada cambia.
        String columnas = columnasDeCorreoSaliente();
        assertEquals(List.of(), ActualizadorEsquema.pendientes(conexion));
        assertTrue(actualizador().ejecutar(BASE));
        ejecutar(leer(Path.of("db", "migraciones", MIGRACION_001)));
        assertEquals(columnas, columnasDeCorreoSaliente());
        assertEquals(antes, contarFilas());
    }

    @Test
    @DisplayName("Después de migrar, la bandeja de salida guarda un correo con su notificación")
    void trasMigrarSePuedeEncolarConNotificacion() throws Exception {
        crearInstalacionAnterior();
        GestorTransacciones transacciones = new GestorTransacciones(conexion);

        // Con el esquema anterior el repositorio falla: es el defecto que corrige la migración.
        assertThrows(RuntimeException.class, () -> transacciones.enTransaccion(() -> encolar(1L)));

        assertTrue(actualizador().ejecutar(BASE));
        long id = transacciones.enTransaccion(() -> encolar(1L));
        assertTrue(existe("SELECT 1 FROM correo_saliente WHERE id = " + id + " AND notificacion_id = 1"));
        // La clave foránea quedó activa: no admite una notificación inexistente.
        assertThrows(RuntimeException.class, () -> transacciones.enTransaccion(() -> encolar(999_999L)));
    }

    @Test
    @DisplayName("La comprobación de arranque avisa en la base anterior y no en la actualizada")
    void comprobacionDeArranque() throws Exception {
        crearInstalacionAnterior();
        assertEquals(List.of(MIGRACION_001), ActualizadorEsquema.pendientes(conexion));
        assertTrue(actualizador().ejecutar(BASE));
        assertEquals(List.of(), ActualizadorEsquema.pendientes(conexion));
    }

    @Test
    @DisplayName("En una base sin el esquema de FEDER no se aplica nada, ni con confirmación ni ejecutando el script")
    void sinEsquemaBaseNoToca() throws Exception {
        assertFalse(actualizador().ejecutar(BASE));
        assertTrue(texto().contains("instalar-esquema"), texto());
        String sql = leer(Path.of("db", "migraciones", MIGRACION_001));
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement()) {
            SQLException e = assertThrows(SQLException.class, () -> s.execute(sql));
            assertTrue(e.getMessage().contains("no tiene instalado el esquema base de FEDER"), e.getMessage());
        }
        assertFalse(existe("SELECT 1 FROM pg_tables WHERE schemaname = 'public'"), "la base sigue vacía");
    }

    // ------------------------------------------------------------------------------------------------

    private ActualizadorEsquema actualizador() {
        return new ActualizadorEsquema(conexion, Path.of("db", "migraciones"), salida);
    }

    private long encolar(Long notificacionId) {
        return new CorreoSalienteRepositorio().encolar(null, "daniela.flores@feder.example", "FEDER - Aviso de prueba",
                "Texto", null, "<migracion-" + notificacionId + "@feder.example>", null, null, null, null, notificacionId);
    }

    /**
     * Reproduce una base instalada antes de la migración 001: esquema y semilla actuales, sin la columna
     * notificacion_id ni su clave e índice, y con mensajes guardados como los dejaba la versión anterior.
     * Los DROP solo se ejecutan sobre esta base descartable de la prueba.
     */
    private void crearInstalacionAnterior() throws Exception {
        ejecutar(leer(Path.of("db", "01_schema.sql")));
        ejecutar(leer(Path.of("db", "02_seed.sql")));
        ejecutar("ALTER TABLE correo_saliente DROP CONSTRAINT fk_correo_saliente_notificacion");
        ejecutar("DROP INDEX ix_correo_saliente_notificacion");
        ejecutar("ALTER TABLE correo_saliente DROP COLUMN notificacion_id");
        ejecutar("INSERT INTO mensaje_correo (id, buzon, uidl, message_id, remitente, asunto, comando, estado, resultado,"
                + " procesado_en) VALUES (9001, 'sistema', 'uidl-anterior-1', '<entrada-1@feder.example>',"
                + " 'daniela.flores@feder.example', 'HELP[\"*\"]', 'HELP', 'PROCESADO', 'EXITO', now())");
        ejecutar("INSERT INTO correo_saliente (mensaje_correo_id, destinatario, asunto, cuerpo_texto, message_id, estado,"
                + " intentos, enviado_en) VALUES (9001, 'daniela.flores@feder.example', 'FEDER - Respuesta anterior',"
                + " 'Texto', '<anterior-1@feder.example>', 'ENVIADO', 1, now())");
        ejecutar("INSERT INTO correo_saliente (destinatario, asunto, cuerpo_texto, message_id)"
                + " VALUES ('daniela.flores@feder.example', 'FEDER - Aviso pendiente', 'Texto', '<anterior-2@feder.example>')");
    }

    private Map<String, Long> contarFilas() throws SQLException {
        Map<String, Long> filas = new LinkedHashMap<>();
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement()) {
            for (String tabla : TABLAS_CON_DATOS) {
                try (ResultSet r = s.executeQuery("SELECT count(*) FROM " + tabla)) {
                    r.next();
                    filas.put(tabla, r.getLong(1));
                }
            }
        }
        return filas;
    }

    private String columnasDeCorreoSaliente() throws SQLException {
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT string_agg(column_name || ':' || data_type, ',' ORDER BY ordinal_position)"
                     + " || ' fk=' || (SELECT count(*) FROM pg_constraint WHERE conrelid = 'correo_saliente'::regclass)"
                     + " || ' ix=' || (SELECT count(*) FROM pg_indexes WHERE tablename = 'correo_saliente')"
                     + " FROM information_schema.columns WHERE table_name = 'correo_saliente'")) {
            r.next();
            return r.getString(1);
        }
    }

    private boolean existe(String consulta) throws SQLException {
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement();
             ResultSet r = s.executeQuery(consulta)) {
            return r.next();
        }
    }

    private void ejecutar(String sql) throws SQLException {
        try (Connection c = DriverManager.getConnection(SERVIDOR + BASE, USUARIO, ""); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String leer(Path archivo) throws IOException {
        return Files.readString(archivo, StandardCharsets.UTF_8);
    }

    private String texto() {
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
