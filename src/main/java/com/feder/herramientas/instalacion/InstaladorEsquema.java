package com.feder.herramientas.instalacion;

import com.feder.datos.ConexionBD;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Instalación controlada del esquema en una base existente (pensada para la base institucional).
 *
 * Paso 1, siempre y sin modificar nada: muestra a qué base, con qué usuario y en qué servidor se
 * conectó, cuántas tablas tiene el esquema public y cuáles de ellas son de FEDER.
 * Paso 2, solo si se pide de forma explícita con --confirmar=NOMBRE_EXACTO_DE_LA_BASE: ejecuta
 * db/01_schema.sql. El propio script se niega a continuar si ya existe alguna tabla de FEDER, de modo
 * que nunca borra ni reemplaza datos. Los datos de demostración (02_seed.sql) no se cargan aquí.
 */
public final class InstaladorEsquema {

    static final List<String> TABLAS_FEDER = List.of(
            "bitacora", "categoria", "compra_feder", "comunidad_agricola", "confirmacion_operacion", "correo_saliente",
            "detalle_compra", "detalle_pedido", "devolucion", "entrega", "factura", "historial_pedido", "item_carrito",
            "lote_feder", "lote_produccion", "mensaje_correo", "movimiento_inventario", "notificacion", "pago", "pedido",
            "produccion", "producto", "productor", "reembolso", "reserva_stock", "rol", "usuario", "usuario_rol");

    private final ConexionBD conexion;
    private final Path script;
    private final PrintStream salida;

    public InstaladorEsquema(ConexionBD conexion, Path script, PrintStream salida) {
        this.conexion = conexion;
        this.script = script;
        this.salida = salida;
    }

    /** Estado del destino, obtenido solo con consultas de lectura. */
    public record Destino(String base, String usuario, String version, int tablasPublic, List<String> tablasFeder) {
    }

    public Destino inspeccionar() {
        try (Connection c = conexion.abrir()) {
            c.setReadOnly(true);
            String base;
            String usuario;
            String version;
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT current_database(), current_user, current_setting('server_version')")) {
                r.next();
                base = r.getString(1);
                usuario = r.getString(2);
                version = r.getString(3);
            }
            int tablas;
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname = 'public'")) {
                r.next();
                tablas = r.getInt(1);
            }
            List<String> deFeder = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename = ANY (?) ORDER BY tablename")) {
                p.setArray(1, c.createArrayOf("text", TABLAS_FEDER.toArray()));
                try (ResultSet r = p.executeQuery()) {
                    while (r.next()) {
                        deFeder.add(r.getString(1));
                    }
                }
            }
            return new Destino(base, usuario, version, tablas, deFeder);
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo inspeccionar la base " + conexion.destinoSinCredenciales()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * Muestra el destino y, si confirmacion coincide con el nombre de la base, instala el esquema.
     * Devuelve true si la base quedó con el esquema instalado en esta ejecución.
     */
    public boolean ejecutar(String confirmacion) {
        Destino destino = inspeccionar();
        salida.println("Destino: " + conexion.destinoSinCredenciales());
        salida.println("  base actual      : " + destino.base());
        salida.println("  usuario          : " + destino.usuario());
        salida.println("  PostgreSQL       : " + destino.version());
        salida.println("  tablas en public : " + destino.tablasPublic());
        salida.println("  tablas de FEDER  : " + (destino.tablasFeder().isEmpty() ? "ninguna" : String.join(", ", destino.tablasFeder())));
        if (!destino.tablasFeder().isEmpty()) {
            salida.println("La base ya tiene tablas de FEDER: no se instala nada (este procedimiento nunca borra datos).");
            return false;
        }
        if (confirmacion == null || confirmacion.isBlank()) {
            salida.println("No se modificó nada. Para instalar el esquema en esta base, repita el comando con"
                    + " --confirmar=" + destino.base());
            return false;
        }
        if (!confirmacion.equals(destino.base())) {
            salida.println("La confirmación '" + confirmacion + "' no coincide con la base actual '" + destino.base()
                    + "': no se modificó nada.");
            return false;
        }
        String sql;
        try {
            sql = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + script + ": " + e.getMessage(), e);
        }
        try (Connection c = conexion.abrir(); Statement s = c.createStatement()) {
            // El script controla su propia transacción (BEGIN ... COMMIT) y su guardia de no destrucción.
            c.setAutoCommit(true);
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("La instalación se detuvo y no se aplicó ningún cambio: " + e.getMessage(), e);
        }
        Destino despues = inspeccionar();
        salida.println("Esquema instalado: " + despues.tablasFeder().size() + " tablas de FEDER en " + despues.base() + ".");
        salida.println("Siguiente paso: crear el primer administrador con 'inicializar-admin'.");
        return despues.tablasFeder().size() == TABLAS_FEDER.size();
    }
}
