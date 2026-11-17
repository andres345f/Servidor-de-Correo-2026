package com.feder.herramientas.instalacion;

import com.feder.datos.ConexionBD;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Actualización controlada del esquema de una base que ya tiene instalado FEDER.
 *
 * db/01_schema.sql solo instala sobre una base sin tablas de FEDER; los cambios posteriores del esquema
 * llegan a las bases existentes como migraciones incrementales (db/migraciones), que solo agregan objetos
 * y nunca borran ni reescriben datos.
 *
 * Paso 1, siempre y sin modificar nada: muestra a qué base, con qué usuario y en qué servidor se conectó,
 * y qué migraciones faltan por aplicar.
 * Paso 2, solo si se pide de forma explícita con --confirmar=NOMBRE_EXACTO_DE_LA_BASE: aplica las
 * migraciones pendientes en orden, cada una en su propia transacción, y vuelve a comprobar el resultado.
 */
public final class ActualizadorEsquema {

    /**
     * Una migración: su archivo en db/migraciones y una consulta de solo lectura que devuelve true
     * cuando la base ya la tiene aplicada.
     */
    public record Migracion(String archivo, String descripcion, String consultaAplicada) {
    }

    /**
     * Migraciones en el orden en que deben aplicarse. Las nuevas se agregan al final. Las consultas miran el
     * esquema public, igual que 01_schema.sql y los scripts de migración (que fijan search_path = public): si el
     * usuario de la base tuviera un esquema propio, current_schema() devolvería ese y no el de FEDER.
     */
    static final List<Migracion> MIGRACIONES = List.of(
            new Migracion("001_correo_saliente_notificacion.sql",
                    "columna correo_saliente.notificacion_id con su clave foránea e índice",
                    // Aplicada solo si están los tres objetos: una columna agregada a mano sin su clave o su índice
                    // sigue contando como pendiente (el script es idempotente y completa lo que falte).
                    "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public'"
                            + " AND table_name = 'correo_saliente' AND column_name = 'notificacion_id')"
                            + " AND EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_correo_saliente_notificacion'"
                            + " AND contype = 'f' AND conrelid = to_regclass('public.correo_saliente')"
                            + " AND confrelid = to_regclass('public.notificacion'))"
                            + " AND EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public'"
                            + " AND tablename = 'correo_saliente' AND indexname = 'ix_correo_saliente_notificacion')"));

    private final ConexionBD conexion;
    private final Path carpeta;
    private final PrintStream salida;

    public ActualizadorEsquema(ConexionBD conexion, Path carpeta, PrintStream salida) {
        this.conexion = conexion;
        this.carpeta = carpeta;
        this.salida = salida;
    }

    /**
     * Archivos de las migraciones que la base todavía no tiene aplicadas, en orden. Solo lee el catálogo.
     * El arranque del procesador la usa para negarse a procesar correo con un esquema desactualizado.
     */
    public static List<String> pendientes(ConexionBD conexion) {
        List<String> faltan = new ArrayList<>();
        try (Connection c = conexion.abrir(); Statement s = c.createStatement()) {
            c.setReadOnly(true);
            for (Migracion migracion : MIGRACIONES) {
                try (ResultSet r = s.executeQuery(migracion.consultaAplicada())) {
                    r.next();
                    if (!r.getBoolean(1)) {
                        faltan.add(migracion.archivo());
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo comprobar el esquema de " + conexion.destinoSinCredenciales()
                    + ": " + e.getMessage(), e);
        }
        return faltan;
    }

    /**
     * Muestra el destino y las migraciones pendientes y, si confirmacion coincide con el nombre de la base,
     * las aplica. Devuelve true si la base queda con el esquema al día.
     */
    public boolean ejecutar(String confirmacion) {
        InstaladorEsquema.Destino destino = new InstaladorEsquema(conexion, null, salida).inspeccionar();
        salida.println("Destino: " + conexion.destinoSinCredenciales());
        salida.println("  base actual      : " + destino.base());
        salida.println("  usuario          : " + destino.usuario());
        salida.println("  PostgreSQL       : " + destino.version());
        salida.println("  tablas de FEDER  : " + destino.tablasFeder().size() + " de " + InstaladorEsquema.TABLAS_FEDER.size());
        if (destino.tablasFeder().size() != InstaladorEsquema.TABLAS_FEDER.size()) {
            salida.println("La base no tiene instalado el esquema base de FEDER: no se modificó nada."
                    + " Instálelo primero con 'instalar-esquema'.");
            return false;
        }
        List<String> faltan = pendientes(conexion);
        if (faltan.isEmpty()) {
            salida.println("Migraciones pendientes: ninguna. El esquema está al día.");
            return true;
        }
        salida.println("Migraciones pendientes:");
        for (Migracion migracion : MIGRACIONES) {
            if (faltan.contains(migracion.archivo())) {
                salida.println("  - " + migracion.archivo() + ": " + migracion.descripcion());
            }
        }
        if (confirmacion == null || confirmacion.isBlank()) {
            salida.println("No se modificó nada. Para aplicar las migraciones en esta base, repita el comando con"
                    + " --confirmar=" + destino.base());
            return false;
        }
        if (!confirmacion.equals(destino.base())) {
            salida.println("La confirmación '" + confirmacion + "' no coincide con la base actual '" + destino.base()
                    + "': no se modificó nada.");
            return false;
        }
        for (String archivo : faltan) {
            aplicar(archivo);
            salida.println("Migración aplicada: " + archivo);
        }
        List<String> despues = pendientes(conexion);
        if (!despues.isEmpty()) {
            salida.println("Siguen pendientes: " + String.join(", ", despues));
            return false;
        }
        salida.println("Esquema al día en " + destino.base() + ". No se borró ni se reescribió ningún dato.");
        return true;
    }

    private void aplicar(String archivo) {
        Path script = carpeta.resolve(archivo);
        String sql;
        try {
            sql = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + script + ": " + e.getMessage(), e);
        }
        try (Connection c = conexion.abrir(); Statement s = c.createStatement()) {
            // Cada script controla su propia transacción (BEGIN ... COMMIT): si falla, no deja nada aplicado.
            c.setAutoCommit(true);
            s.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("La migración " + archivo + " se detuvo y no aplicó ningún cambio: "
                    + e.getMessage(), e);
        }
    }
}
