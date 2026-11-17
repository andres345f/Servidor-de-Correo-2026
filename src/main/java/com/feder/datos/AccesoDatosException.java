package com.feder.datos;

import java.sql.SQLException;

/**
 * Error técnico de la capa de datos. Conserva el SQLSTATE para que la capa de negocio
 * pueda traducir violaciones de integridad (23505 único, 23503 FK, 23514 CHECK...) a mensajes claros.
 */
public class AccesoDatosException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String sqlState;
    private final String restriccion;
    private final String mensajeServidor;

    public AccesoDatosException(String mensaje, SQLException causa) {
        super(mensaje, causa);
        this.sqlState = causa == null ? null : causa.getSQLState();
        this.restriccion = extraerRestriccion(causa);
        this.mensajeServidor = extraerMensaje(causa);
    }

    public AccesoDatosException(String mensaje) {
        super(mensaje);
        this.sqlState = null;
        this.restriccion = null;
        this.mensajeServidor = null;
    }

    /**
     * Texto principal del error de PostgreSQL (sin detalle ni consulta). Solo se muestra al usuario
     * cuando proviene de una regla propia (SQLSTATE P0001), cuyos mensajes redactamos nosotros.
     */
    public String mensajeServidor() {
        return mensajeServidor;
    }

    public String sqlState() {
        return sqlState;
    }

    /** Nombre de la restricción violada cuando PostgreSQL lo informa (p. ej. uq_categoria_nombre). */
    public String restriccion() {
        return restriccion;
    }

    public boolean esViolacionUnica() {
        return "23505".equals(sqlState);
    }

    /** 23503: clave foránea; 23001: ON DELETE RESTRICT impidió borrar un registro referenciado. */
    public boolean esViolacionClaveForanea() {
        return "23503".equals(sqlState) || "23001".equals(sqlState);
    }

    public boolean esViolacionCheck() {
        return "23514".equals(sqlState);
    }

    public boolean esViolacionNoNulo() {
        return "23502".equals(sqlState);
    }

    /**
     * Fallos técnicos que pueden desaparecer al reintentar: conexión (08), serialización o bloqueo
     * mutuo (40), recursos insuficientes (53), bloqueo no disponible (55P03) y cancelación o
     * apagado del servidor (57). No son errores del usuario y no deben responderse como tales.
     */
    public boolean esTransitoria() {
        if (sqlState == null) {
            // Sin SQLSTATE: decide el tipo de excepción JDBC (transitoria o recuperable) o la falta de causa.
            Throwable causa = getCause();
            return causa == null || causa instanceof java.sql.SQLTransientException
                    || causa instanceof java.sql.SQLRecoverableException;
        }
        return sqlState.startsWith("08") || sqlState.startsWith("40") || sqlState.startsWith("53")
                || sqlState.equals("55P03") || sqlState.startsWith("57");
    }

    /** Errores lanzados a propósito por triggers de negocio (RAISE EXCEPTION ... USING ERRCODE = 'P0001'). */
    public boolean esReglaDeBaseDeDatos() {
        return "P0001".equals(sqlState);
    }

    private static String extraerMensaje(SQLException causa) {
        if (causa instanceof org.postgresql.util.PSQLException) {
            org.postgresql.util.ServerErrorMessage detalle = ((org.postgresql.util.PSQLException) causa).getServerErrorMessage();
            return detalle == null ? null : detalle.getMessage();
        }
        return null;
    }

    private static String extraerRestriccion(SQLException causa) {
        if (causa instanceof org.postgresql.util.PSQLException) {
            org.postgresql.util.ServerErrorMessage detalle = ((org.postgresql.util.PSQLException) causa).getServerErrorMessage();
            return detalle == null ? null : detalle.getConstraint();
        }
        return null;
    }
}
