package com.feder.negocio.indicadores;

import com.feder.datos.GestorTransacciones;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * SQL directo para las pruebas: calcula los valores esperados de forma independiente del código bajo prueba
 * y prepara escenarios dentro de la transacción que la prueba revierte al terminar.
 */
public final class SqlPrueba {

    private SqlPrueba() {
    }

    /** Primer valor numérico de la consulta, redondeado half-up a 2 decimales. */
    public static BigDecimal importe(String sql, Object... parametros) {
        try (PreparedStatement sentencia = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            asignar(sentencia, parametros);
            try (ResultSet fila = sentencia.executeQuery()) {
                if (!fila.next()) {
                    throw new IllegalStateException("La consulta no devolvió filas: " + sql);
                }
                BigDecimal valor = fila.getBigDecimal(1);
                return (valor == null ? BigDecimal.ZERO : valor).setScale(2, RoundingMode.HALF_UP);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falló la consulta de prueba: " + e.getMessage(), e);
        }
    }

    public static long entero(String sql, Object... parametros) {
        return importe(sql, parametros).longValueExact();
    }

    public static void ejecutar(String sql, Object... parametros) {
        try (PreparedStatement sentencia = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            asignar(sentencia, parametros);
            sentencia.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Falló la preparación del escenario: " + e.getMessage(), e);
        }
    }

    private static void asignar(PreparedStatement sentencia, Object... parametros) throws SQLException {
        for (int i = 0; i < parametros.length; i++) {
            sentencia.setObject(i + 1, parametros[i]);
        }
    }
}
