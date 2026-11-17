package com.feder.negocio.usuarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

/**
 * La limpieza de la base solo con esquema nunca vacía una base con la semilla, aunque se le pase por error.
 * Se ejecuta dentro de una transacción que se revierte: si la protección fallara, el TRUNCATE tampoco quedaría.
 */
class BaseVaciaTest {

    @Test
    @DisplayName("vaciar() rechaza una base con catálogo de productos o cuyo nombre no termina en _vacia")
    void noVaciaUnaBaseConSemilla() throws Exception {
        try (Connection c = BaseDatosPrueba.conexion().abrir()) {
            c.setAutoCommit(false);
            try {
                long cuentas = cuentas(c);
                assertThrows(IllegalStateException.class,
                        () -> BaseVacia.vaciar(c, "jdbc:postgresql://127.0.0.1:55432/feder_semilla"));
                assertThrows(IllegalStateException.class,
                        () -> BaseVacia.vaciar(c, "jdbc:postgresql://127.0.0.1:55432/feder_semilla_vacia"));
                // Otro servidor, otro puerto o una base sin el prefijo feder_ se rechazan antes de mirar su contenido.
                assertThrows(IllegalStateException.class,
                        () -> BaseVacia.vaciar(c, "jdbc:postgresql://db.institucional.example:5432/curso_vacia"));
                assertThrows(IllegalStateException.class,
                        () -> BaseVacia.vaciar(c, "jdbc:postgresql://127.0.0.1:5432/feder_test_vacia"));
                assertThrows(IllegalStateException.class,
                        () -> BaseVacia.vaciar(c, "jdbc:postgresql://127.0.0.1:55432/curso_vacia"));
                assertEquals(cuentas, cuentas(c), "No se borró ninguna cuenta");
            } finally {
                c.rollback();
            }
        }
    }

    private static long cuentas(Connection c) throws Exception {
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT count(*) FROM usuario")) {
            r.next();
            return r.getLong(1);
        }
    }
}
