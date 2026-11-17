package com.feder.datos;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.function.Supplier;

/**
 * Coordina las transacciones JDBC.
 *
 * La capa de negocio delimita la transacción con {@link #enTransaccion(Supplier)} y los
 * repositorios obtienen la conexión activa con {@link #conexionActual()}. Así los servicios
 * no escriben SQL y todas las sentencias de un caso de uso comparten la misma transacción.
 * El procesamiento es secuencial (un mensaje por vez), por eso basta con un ThreadLocal.
 */
public class GestorTransacciones {

    private static final ThreadLocal<Connection> ACTUAL = new ThreadLocal<>();

    private final ConexionBD conexionBD;

    public GestorTransacciones(ConexionBD conexionBD) {
        this.conexionBD = conexionBD;
    }

    /**
     * Ejecuta el bloque dentro de una transacción. Si el bloque termina normalmente se confirma;
     * ante cualquier excepción se revierte y la excepción se propaga sin modificar.
     * Si ya existe una transacción en curso, el bloque se une a ella.
     */
    public <T> T enTransaccion(Supplier<T> bloque) {
        if (ACTUAL.get() != null) {
            return bloque.get();
        }
        Connection conexion = conexionBD.abrir();
        ACTUAL.set(conexion);
        try {
            conexion.setAutoCommit(false);
            T resultado = bloque.get();
            conexion.commit();
            return resultado;
        } catch (SQLException e) {
            revertir(conexion);
            throw new AccesoDatosException("Error al confirmar la transacción", e);
        } catch (RuntimeException | Error e) {
            revertir(conexion);
            throw e;
        } finally {
            ACTUAL.remove();
            cerrar(conexion);
        }
    }

    public void enTransaccion(Runnable bloque) {
        enTransaccion(() -> {
            bloque.run();
            return null;
        });
    }

    /**
     * Ejecuta el bloque protegido por un SAVEPOINT dentro de la transacción actual.
     * Si falla, solo se deshace lo hecho por el bloque y la transacción sigue utilizable
     * (en PostgreSQL un error sin savepoint deja la transacción abortada).
     */
    public <T> T conPuntoDeGuardado(Supplier<T> bloque) {
        Connection conexion = conexionActual();
        Savepoint punto;
        try {
            punto = conexion.setSavepoint();
        } catch (SQLException e) {
            throw new AccesoDatosException("No se pudo crear el punto de guardado", e);
        }
        try {
            T resultado = bloque.get();
            conexion.releaseSavepoint(punto);
            return resultado;
        } catch (SQLException e) {
            throw new AccesoDatosException("No se pudo liberar el punto de guardado", e);
        } catch (RuntimeException e) {
            try {
                conexion.rollback(punto);
            } catch (SQLException ex) {
                e.addSuppressed(ex);
            }
            throw e;
        }
    }

    /** Conexión de la transacción en curso. Solo la usan los repositorios. */
    public static Connection conexionActual() {
        Connection conexion = ACTUAL.get();
        if (conexion == null) {
            throw new IllegalStateException("No hay una transacción activa: el servicio debe usar enTransaccion(...)");
        }
        return conexion;
    }

    private static void revertir(Connection conexion) {
        try {
            conexion.rollback();
        } catch (SQLException ignorada) {
            // La conexión se cierra a continuación; el error original es el que importa.
        }
    }

    private static void cerrar(Connection conexion) {
        try {
            conexion.close();
        } catch (SQLException ignorada) {
            // Nada más que hacer: la conexión ya no se reutiliza.
        }
    }
}
