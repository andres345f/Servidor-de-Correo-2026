package com.feder.pruebas;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DestinoPruebasTest {

    @Test
    @DisplayName("Solo se admite el clúster local 127.0.0.1:55432 con bases feder_* y sin parámetros")
    void soloClusterLocal() {
        assertTrue(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1:55432/feder_test"));
        assertTrue(DestinoPruebas.esLocal("jdbc:postgresql://localhost:55432/feder_test_vacia"));
        assertEquals("feder_test_vacia", DestinoPruebas.base("jdbc:postgresql://127.0.0.1:55432/feder_test_vacia"));

        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo29sa"));
        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1:5432/feder_test"));
        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1:55432/db_grupo29sa"));
        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1.example.com:55432/feder_test"));
        assertFalse(DestinoPruebas.esLocal(null));
        assertThrows(IllegalStateException.class,
                () -> DestinoPruebas.exigirLocal("jdbc:postgresql://db.tecnoweb.org.bo:5432/db_grupo29sa"));
    }

    @Test
    @DisplayName("Los parámetros JDBC que podrían redirigir la conexión se rechazan")
    void parametrosQueRedirigenSeRechazan() {
        // El controlador aplica PGHOST, PGPORT y PGDBNAME después del servidor y la base de la URL.
        assertFalse(DestinoPruebas.esLocal(
                "jdbc:postgresql://127.0.0.1:55432/feder_test_vacia?PGHOST=10.0.0.10&PGPORT=5432&PGDBNAME=produccion"));
        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1:55432/feder_test?service=institucional"));
        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1:55432/feder_test?ssl=false"));
        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1:55432,10.0.0.10:5432/feder_test"));
        assertFalse(DestinoPruebas.esLocal("jdbc:postgresql://127.0.0.1:55432/feder_test#x"));
    }

    @Test
    @DisplayName("El mensaje de un destino rechazado no muestra los parámetros de la URL (podrían ser credenciales)")
    void rechazoSinCredenciales() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> DestinoPruebas.exigirLocal("jdbc:postgresql://127.0.0.1:55432/feder_test?user=u&password=secreta"));
        assertFalse(e.getMessage().contains("secreta"), e.getMessage());
        assertFalse(DestinoPruebas.sinParametros("jdbc:postgresql://u:secreta@10.0.0.1:5432/x").contains("secreta"));
    }

    @Test
    @DisplayName("Los nombres de base que se recrean deben empezar por feder_")
    void nombreDeBaseRecreada() {
        assertEquals("feder_e2e", DestinoPruebas.exigirNombreLocal("feder_e2e"));
        assertThrows(IllegalStateException.class, () -> DestinoPruebas.exigirNombreLocal("db_grupo29sa"));
        assertThrows(IllegalStateException.class, () -> DestinoPruebas.exigirNombreLocal("feder_x; DROP DATABASE y"));
    }

    @Test
    @DisplayName("El servidor confirma la base y la dirección reales de la conexión antes de operar")
    void conexionConfirmadaPorElServidor() throws Exception {
        try (Connection c = BaseDatosPrueba.conexion().abrir()) {
            String real = c.getCatalog();
            assertDoesNotThrow(() -> DestinoPruebas.exigirConexionLocal(c, real));
            assertThrows(IllegalStateException.class, () -> DestinoPruebas.exigirConexionLocal(c, "feder_otra_base"));
        }
    }
}
