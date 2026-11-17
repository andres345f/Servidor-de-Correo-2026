package com.feder.presentacion.correo.smtp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.presentacion.correo.CanalLineas;
import com.feder.presentacion.correo.CorreoException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Validación del formato de respuesta SMTP con entradas que un servidor real no siempre produciría. */
class SmtpRespuestaTest {

    private static SmtpRespuesta leer(String crudo) throws IOException, CorreoException {
        CanalLineas canal = new CanalLineas(new ByteArrayInputStream(crudo.getBytes(StandardCharsets.ISO_8859_1)),
                new ByteArrayOutputStream(), 1024);
        return SmtpRespuesta.leer(canal);
    }

    @Test
    void leeRespuestaMultilinea() throws Exception {
        SmtpRespuesta r = leer("250-mail.x.example hola\r\n250-SIZE 1000\r\n250 AUTH PLAIN\r\n");
        assertEquals(250, r.codigo());
        assertEquals(List.of("mail.x.example hola", "SIZE 1000", "AUTH PLAIN"), r.textos());
        assertEquals("250 AUTH PLAIN", r.ultimaLinea());
        assertEquals(220, leer("220\r\n").codigo());
    }

    @Test
    void rechazaRespuestasMalFormadas() {
        assertThrows(CorreoException.class, () -> leer("25O ok\r\n"));
        assertThrows(CorreoException.class, () -> leer("650 ok\r\n"));
        assertThrows(CorreoException.class, () -> leer("25\r\n"));
        assertThrows(CorreoException.class, () -> leer("250-a\r\n251 b\r\n"));
        assertThrows(CorreoException.class, () -> leer("250_ok\r\n"));
    }

    @Test
    void clasificaLosCodigosDeError() throws Exception {
        assertTrue(leer("421 ocupado\r\n").comoExcepcion("x") instanceof SmtpTransitorioException);
        assertTrue(leer("550 no existe\r\n").comoExcepcion("x") instanceof SmtpPermanenteException);
    }
}
