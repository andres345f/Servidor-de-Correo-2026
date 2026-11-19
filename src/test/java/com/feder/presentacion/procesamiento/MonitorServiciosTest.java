package com.feder.presentacion.procesamiento;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.presentacion.correo.CorreoException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Secuencia en pantalla de una caída: bloque inicial, líneas de reintento, cambio de motivo y recuperación. */
class MonitorServiciosTest {

    private static final DiagnosticoRed.Resultado SERVIDOR_SIN_PUERTO = new DiagnosticoRed.Resultado(true, "139.99.68.169",
            true, 5432, DiagnosticoRed.EstadoPuerto.SIN_RESPUESTA, true);

    private final List<String> pantalla = new ArrayList<>();
    private final AtomicInteger diagnosticos = new AtomicInteger();
    private Instant ahora = Instant.parse("2026-11-19T01:55:53Z");
    private MonitorServicios monitor;

    @BeforeEach
    void crear() {
        Clock reloj = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zona) {
                return this;
            }

            @Override
            public Instant instant() {
                return ahora;
            }
        };
        monitor = new MonitorServicios(MonitorServicios.destinoBaseDatos("db.tecnoweb.org.bo", 5432),
                MonitorServicios.destinoCorreo("mail.tecnoweb.org.bo", 110), (host, puerto) -> {
                    diagnosticos.incrementAndGet();
                    return SERVIDOR_SIN_PUERTO;
                }, reloj, pantalla::add, "OK", "X");
    }

    private static RuntimeException bdTiempoAgotado() {
        return new RuntimeException("No se pudo conectar con PostgreSQL (08001)",
                new SQLException("El intento de conexión falló.", "08001", new SocketTimeoutException("Connect timed out")));
    }

    private static CorreoException correoTiempoAgotado() {
        return new CorreoException("Tiempo de espera agotado al conectar con mail.tecnoweb.org.bo:110",
                new SocketTimeoutException("connect timed out"));
    }

    @Test
    void caidaReintentosYRecuperacion() {
        monitor.registrar(bdTiempoAgotado(), null, false, 1, Duration.ofSeconds(10));
        String bloque = pantalla.get(0);
        assertTrue(bloque.startsWith("[BD] X Sin conexión con db.tecnoweb.org.bo:5432"), bloque);
        assertTrue(bloque.contains("Motivo:      el servidor no respondió a tiempo"), bloque);
        assertTrue(bloque.contains("Diagnóstico: DNS OK · servidor 139.99.68.169 responde OK · puerto 5432 X (sin respuesta)"), bloque);
        assertTrue(bloque.contains("tu IP bloqueada"), bloque);
        assertTrue(bloque.contains("En pausa:"), bloque);
        assertEquals("Reintento 1 en 10 s.", pantalla.get(1));

        pantalla.clear();
        ahora = ahora.plusSeconds(12);
        monitor.registrar(bdTiempoAgotado(), null, false, 2, Duration.ofSeconds(20));
        assertEquals(List.of("[BD] X Sigue sin conexión (hace 12 s): tiempo de espera agotado. Reintento 2 en 20 s."), pantalla);
        assertEquals(1, diagnosticos.get(), "el diagnóstico de red se hace una sola vez por caída");

        pantalla.clear();
        ahora = ahora.plusSeconds(680);
        monitor.registrar(null, null, false, 0, Duration.ofSeconds(5));
        assertEquals(List.of("[BD] OK Conexión recuperada tras 11 min 32 s sin servicio."), pantalla);
    }

    @Test
    void cuandoTambienCaeElCorreoSeAvisaQueEsTodoElServidor() {
        monitor.registrar(bdTiempoAgotado(), null, false, 1, Duration.ofSeconds(10));
        pantalla.clear();
        ahora = ahora.plusSeconds(200);
        monitor.registrar(bdTiempoAgotado(), correoTiempoAgotado(), false, 2, Duration.ofSeconds(20));
        assertTrue(pantalla.get(0).startsWith("[POP3] X Sin conexión con mail.tecnoweb.org.bo:110"), pantalla.get(0));
        assertTrue(pantalla.get(1).startsWith("Ahora fallan la base Y el correo"), pantalla.get(1));
        assertEquals("Reintento 2 en 20 s.", pantalla.get(2));

        pantalla.clear();
        ahora = ahora.plusSeconds(40);
        monitor.registrar(bdTiempoAgotado(), correoTiempoAgotado(), false, 3, Duration.ofSeconds(40));
        assertEquals(List.of("[BD + POP3] X Siguen sin conexión (hace 4 min 0 s): tiempo de espera agotado. Reintento 3 en 40 s."),
                pantalla);
    }

    @Test
    void unCambioDeMotivoSeMuestraCompletoYSinDiagnosticoDeRed() {
        monitor.registrar(bdTiempoAgotado(), null, false, 1, Duration.ofSeconds(10));
        pantalla.clear();
        monitor.registrar(new RuntimeException("x", new SQLException("password authentication failed", "28P01")),
                null, false, 2, Duration.ofSeconds(20));
        String bloque = pantalla.get(0);
        assertTrue(bloque.contains("Cambió el motivo"), bloque);
        assertTrue(bloque.contains("revisa db.password"), bloque);
        assertFalse(bloque.contains("Diagnóstico:"), "una contraseña rechazada no necesita diagnóstico de red");
        assertEquals(1, diagnosticos.get());
    }

    @Test
    void sinFallasNoSeMuestraNada() {
        monitor.registrar(null, null, false, 0, Duration.ofSeconds(5));
        assertTrue(pantalla.isEmpty());
    }

    @Test
    void duraciones() {
        assertEquals("45 s", MonitorServicios.duracion(Duration.ofSeconds(45)));
        assertEquals("1 min 16 s", MonitorServicios.duracion(Duration.ofSeconds(76)));
        assertEquals("2 h 5 min", MonitorServicios.duracion(Duration.ofMinutes(125)));
    }
}
