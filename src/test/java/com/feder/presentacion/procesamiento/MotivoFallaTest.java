package com.feder.presentacion.procesamiento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.comun.FallaConexion;
import com.feder.presentacion.correo.CorreoException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/** La causa técnica de un fallo de conexión se traduce a un motivo que se entiende. */
class MotivoFallaTest {

    /** Como lo entrega PgJDBC: SQLException 08001 con la causa de red debajo, envuelta por la capa de datos. */
    private static RuntimeException falloPostgres(Throwable causaRed) {
        SQLException sql = new SQLException("El intento de conexión falló.", "08001", causaRed);
        return new RuntimeException("No se pudo conectar con PostgreSQL en jdbc:postgresql://db.ejemplo:5432/x (08001)", sql);
    }

    @Test
    void tiempoAgotadoDePostgres() {
        MotivoFalla m = MotivoFalla.de(falloPostgres(new SocketTimeoutException("Connect timed out")));
        assertEquals(MotivoFalla.Tipo.TIEMPO_AGOTADO, m.tipo());
        assertTrue(m.detalle().contains("no respondió a tiempo"), m.detalle());
    }

    @Test
    void puertoCerrado() {
        MotivoFalla m = MotivoFalla.de(falloPostgres(new ConnectException("Connection refused: no further information")));
        assertEquals(MotivoFalla.Tipo.RECHAZADA, m.tipo());
        assertTrue(m.detalle().contains("puerto está cerrado"), m.detalle());
    }

    @Test
    void conexionDeWindowsQueTerminaPorTiempo() {
        assertEquals(MotivoFalla.Tipo.TIEMPO_AGOTADO,
                MotivoFalla.de(falloPostgres(new ConnectException("Connection timed out: connect"))).tipo());
    }

    @Test
    void sinDns() {
        assertEquals(MotivoFalla.Tipo.NOMBRE, MotivoFalla.de(falloPostgres(new UnknownHostException("db.ejemplo"))).tipo());
    }

    @Test
    void contrasenaYBaseInexistenteSegunSqlState() {
        MotivoFalla clave = MotivoFalla.de(new RuntimeException("x",
                new SQLException("la autentificación password falló", "28P01")));
        assertEquals(MotivoFalla.Tipo.CREDENCIALES, clave.tipo());
        assertTrue(clave.corto().contains("28P01"));
        assertFalse(clave.tipo().esDeRed(), "una contraseña rechazada no se diagnostica como problema de red");
        assertEquals(MotivoFalla.Tipo.BASE_INEXISTENTE,
                MotivoFalla.de(new RuntimeException("x", new SQLException("no existe", "3D000"))).tipo());
        assertEquals(MotivoFalla.Tipo.SATURADO,
                MotivoFalla.de(new RuntimeException("x", new SQLException("too many", "53300"))).tipo());
    }

    @Test
    void erroresDelCorreo() {
        assertEquals(MotivoFalla.Tipo.TIEMPO_AGOTADO, MotivoFalla.de(new CorreoException(
                "Tiempo de espera agotado al conectar con mail.ejemplo:110", new SocketTimeoutException("connect timed out"))).tipo());
        assertEquals(MotivoFalla.Tipo.CREDENCIALES,
                MotivoFalla.de(new CorreoException("El servidor POP3 rechazó la autenticación", "-ERR [AUTH]")).tipo());
    }

    @Test
    void reconoceLasFallasDeConexionConLaBase() {
        assertTrue(FallaConexion.esDeBaseDeDatos(falloPostgres(new SocketTimeoutException("x"))));
        assertTrue(FallaConexion.esDeBaseDeDatos(new RuntimeException(new SQLException("clave", "28P01"))));
        assertFalse(FallaConexion.esDeBaseDeDatos(new RuntimeException(new SQLException("duplicado", "23505"))),
                "un registro duplicado no es una caída de la base");
        assertFalse(FallaConexion.esDeBaseDeDatos(new IllegalStateException("otro error")));
    }
}
