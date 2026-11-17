package com.feder.datos;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Base de los repositorios JDBC. Centraliza la creación de PreparedStatement, la asignación de
 * parámetros (incluidos los nulos) y el cierre de recursos, para que cada repositorio solo
 * contenga sus sentencias SQL y su mapeo.
 *
 * Regla del proyecto: ningún valor recibido por correo se concatena en el SQL; todo pasa por "?".
 */
public abstract class RepositorioJdbc {

    /** Convierte una fila del ResultSet en un objeto. */
    @FunctionalInterface
    protected interface Mapeador<T> {
        T mapear(ResultSet fila) throws SQLException;
    }

    protected Connection conexion() {
        return GestorTransacciones.conexionActual();
    }

    protected <T> List<T> consultar(String sql, Mapeador<T> mapeador, Object... parametros) {
        try (PreparedStatement sentencia = conexion().prepareStatement(sql)) {
            asignar(sentencia, parametros);
            try (ResultSet filas = sentencia.executeQuery()) {
                List<T> resultado = new ArrayList<>();
                while (filas.next()) {
                    resultado.add(mapeador.mapear(filas));
                }
                return resultado;
            }
        } catch (SQLException e) {
            throw new AccesoDatosException("Error en la consulta", e);
        }
    }

    protected <T> Optional<T> consultarUno(String sql, Mapeador<T> mapeador, Object... parametros) {
        List<T> lista = consultar(sql, mapeador, parametros);
        if (lista.size() > 1) {
            throw new AccesoDatosException("La consulta devolvió más de una fila cuando se esperaba una");
        }
        return lista.stream().findFirst();
    }

    /** Ejecuta INSERT/UPDATE/DELETE y devuelve las filas afectadas. */
    protected int actualizar(String sql, Object... parametros) {
        try (PreparedStatement sentencia = conexion().prepareStatement(sql)) {
            asignar(sentencia, parametros);
            return sentencia.executeUpdate();
        } catch (SQLException e) {
            throw new AccesoDatosException("Error al modificar datos", e);
        }
    }

    /** Ejecuta un INSERT y devuelve el id generado (columna id). */
    protected long insertar(String sql, Object... parametros) {
        try (PreparedStatement sentencia = conexion().prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            asignar(sentencia, parametros);
            sentencia.executeUpdate();
            try (ResultSet claves = sentencia.getGeneratedKeys()) {
                if (!claves.next()) {
                    throw new AccesoDatosException("El INSERT no devolvió el identificador generado");
                }
                return claves.getLong("id");
            }
        } catch (SQLException e) {
            throw new AccesoDatosException("Error al insertar datos", e);
        }
    }

    private static void asignar(PreparedStatement sentencia, Object... parametros) throws SQLException {
        for (int i = 0; i < parametros.length; i++) {
            Object valor = parametros[i];
            int posicion = i + 1;
            if (valor == null) {
                sentencia.setNull(posicion, Types.NULL);
            } else if (valor instanceof Enum<?>) {
                sentencia.setString(posicion, ((Enum<?>) valor).name());
            } else if (valor instanceof LocalDate) {
                sentencia.setDate(posicion, Date.valueOf((LocalDate) valor));
            } else if (valor instanceof OffsetDateTime) {
                sentencia.setObject(posicion, valor);
            } else if (valor instanceof BigDecimal) {
                sentencia.setBigDecimal(posicion, (BigDecimal) valor);
            } else {
                sentencia.setObject(posicion, valor);
            }
        }
    }

    /**
     * PostgreSQL no admite el carácter NUL (U+0000) en columnas de texto. Los datos que provienen
     * de correos externos se limpian antes de guardarse para que un asunto malicioso no impida
     * registrar el mensaje (y bloquee el procesamiento del buzón).
     */
    protected static String sinNulos(String texto) {
        return texto == null || texto.indexOf('\u0000') < 0 ? texto : texto.replace('\u0000', '�');
    }

    // ---- Lectura segura de columnas que pueden ser nulas ----

    protected static Long largoONulo(ResultSet fila, String columna) throws SQLException {
        long valor = fila.getLong(columna);
        return fila.wasNull() ? null : valor;
    }

    protected static Integer enteroONulo(ResultSet fila, String columna) throws SQLException {
        int valor = fila.getInt(columna);
        return fila.wasNull() ? null : valor;
    }

    protected static LocalDate fecha(ResultSet fila, String columna) throws SQLException {
        Date valor = fila.getDate(columna);
        return valor == null ? null : valor.toLocalDate();
    }

    protected static OffsetDateTime fechaHora(ResultSet fila, String columna) throws SQLException {
        return fila.getObject(columna, OffsetDateTime.class);
    }

    protected static <E extends Enum<E>> E enumeracion(ResultSet fila, String columna, Class<E> tipo) throws SQLException {
        String valor = fila.getString(columna);
        return valor == null ? null : Enum.valueOf(tipo, valor);
    }
}
