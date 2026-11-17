package com.feder.presentacion.correo.smtp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.herramientas.correolocal.ConfiguracionCorreoLocal;
import com.feder.herramientas.correolocal.ServidorCorreoLocal;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SmtpClientTest {

    private static final String CUENTA = "feder@feder.local";
    private static final String CLAVE = "clave-smtp";
    private static final String DESTINO = "ana@universidad.example";

    private ServidorCorreoLocal servidor;

    @AfterEach
    void detener() {
        if (servidor != null) {
            servidor.detener();
        }
    }

    private void iniciar(ConfiguracionCorreoLocal configuracion) throws IOException {
        servidor = new ServidorCorreoLocal(configuracion.cuenta(CUENTA, CLAVE));
        servidor.iniciar();
    }

    private ParametrosConexion.Constructor parametros(String clave) {
        return ParametrosConexion.hacia("127.0.0.1", servidor.puertoSmtp())
                .transporte(TransporteSeguro.NINGUNO)
                .credenciales(CUENTA, clave)
                .timeoutMs(5000);
    }

    private SmtpClient cliente(MecanismoAutenticacion mecanismo) throws CorreoException {
        SmtpClient cliente = new SmtpClient(parametros(CLAVE).permitirTextoPlano(true).construir(), mecanismo, "localhost");
        cliente.conectar();
        return cliente;
    }

    @Test
    void enviaYElServidorRecibeLosBytesExactos() throws Exception {
        iniciar(new ConfiguracionCorreoLocal().smtpExigeAutenticacion(true));
        byte[] mensaje = ("From: <feder@feder.local>\r\nTo: <ana@universidad.example>\r\nSubject: prueba\r\n\r\n"
                + "Hola\r\n.\r\n.linea que empieza con punto\r\n..dos puntos\r\nfin\r\n").getBytes(StandardCharsets.US_ASCII);
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.PLAIN)) {
            String aceptacion = cliente.enviar(CUENTA, DESTINO, mensaje);
            assertTrue(aceptacion.startsWith("250"), aceptacion);
            cliente.quit();
        }
        assertEquals(1, servidor.buzones().cantidad(DESTINO));
        assertArrayEquals(mensaje, servidor.buzones().mensajes(DESTINO).get(0).contenido());
    }

    @Test
    void normalizaSaltosDeLineaSueltosACrlf() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.NINGUNA)) {
            cliente.enviar("", List.of(DESTINO, "otro@x.example"), "Subject: x\n\nuno\n.\ndos".getBytes(StandardCharsets.US_ASCII));
        }
        byte[] esperado = "Subject: x\r\n\r\nuno\r\n.\r\ndos\r\n".getBytes(StandardCharsets.US_ASCII);
        assertArrayEquals(esperado, servidor.buzones().mensajes(DESTINO).get(0).contenido());
        assertArrayEquals(esperado, servidor.buzones().mensajes("otro@x.example").get(0).contenido());
    }

    @Test
    void interpretaElEhloMultilinea() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.NINGUNA)) {
            assertEquals("PLAIN LOGIN", cliente.extensiones().get("AUTH"));
            assertTrue(cliente.extensiones().keySet().containsAll(List.of("SIZE", "8BITMIME", "ENHANCEDSTATUSCODES")));
            assertFalse(cliente.extensiones().containsKey("STARTTLS"), "sin contexto TLS no se anuncia STARTTLS");
        }
    }

    @Test
    void rechazo451EsTransitorioYLaConexionSigueUtilizable() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        servidor.smtp().fallarSiguientesData(1, 451);
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.NINGUNA)) {
            SmtpTransitorioException e = assertThrows(SmtpTransitorioException.class,
                    () -> cliente.enviar(CUENTA, DESTINO, "Subject: x\r\n\r\ny\r\n".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(451, e.codigo());
            cliente.enviar(CUENTA, DESTINO, "Subject: x\r\n\r\ny\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals(1, servidor.buzones().cantidad(DESTINO));
    }

    @Test
    void rechazo554EsPermanente() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        servidor.smtp().fallarSiguientesData(1, 554);
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.NINGUNA)) {
            SmtpPermanenteException e = assertThrows(SmtpPermanenteException.class,
                    () -> cliente.enviar(CUENTA, DESTINO, "Subject: x\r\n\r\ny\r\n".getBytes(StandardCharsets.US_ASCII)));
            assertEquals(554, e.codigo());
        }
        assertEquals(0, servidor.buzones().cantidad(DESTINO));
    }

    @Test
    void autenticacionFallidaEsPermanenteYNoMuestraLaClave() throws Exception {
        iniciar(new ConfiguracionCorreoLocal().smtpExigeAutenticacion(true));
        for (MecanismoAutenticacion mecanismo : List.of(MecanismoAutenticacion.PLAIN, MecanismoAutenticacion.LOGIN)) {
            SmtpClient cliente = new SmtpClient(parametros("incorrecta").permitirTextoPlano(true).construir(),
                    mecanismo, "localhost");
            SmtpPermanenteException e = assertThrows(SmtpPermanenteException.class, cliente::conectar);
            assertEquals(535, e.codigo());
            assertFalse(e.getMessage().contains("incorrecta"));
        }
    }

    @Test
    void authLoginCorrectoPermiteEnviar() throws Exception {
        iniciar(new ConfiguracionCorreoLocal().smtpExigeAutenticacion(true));
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.LOGIN)) {
            cliente.enviar(CUENTA, DESTINO, "Subject: x\r\n\r\ny\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        assertEquals(1, servidor.buzones().cantidad(DESTINO));
    }

    @Test
    void sinAutenticarElServidorExigenteRechazaMail() throws Exception {
        iniciar(new ConfiguracionCorreoLocal().smtpExigeAutenticacion(true));
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.NINGUNA)) {
            SmtpPermanenteException e = assertThrows(SmtpPermanenteException.class,
                    () -> cliente.enviar(CUENTA, DESTINO, new byte[0]));
            assertEquals(530, e.codigo());
        }
    }

    @Test
    void rechazaAutenticarseEnTextoPlanoSinPermiso() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        SmtpClient cliente = new SmtpClient(parametros(CLAVE).construir(), MecanismoAutenticacion.PLAIN, "localhost");
        CorreoException e = assertThrows(CorreoException.class, cliente::conectar);
        assertTrue(e.getMessage().contains("sin cifrar"), e.getMessage());
    }

    @Test
    void rechazaInyeccionEnDirecciones() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        byte[] mensaje = "Subject: x\r\n\r\ny\r\n".getBytes(StandardCharsets.US_ASCII);
        try (SmtpClient cliente = cliente(MecanismoAutenticacion.NINGUNA)) {
            assertThrows(IllegalArgumentException.class,
                    () -> cliente.enviar(CUENTA, "ana@x.example>\r\nRCPT TO:<victima@x.example", mensaje));
            assertThrows(IllegalArgumentException.class, () -> cliente.enviar("a@x.example\nDATA", DESTINO, mensaje));
            assertThrows(IllegalArgumentException.class, () -> cliente.enviar(CUENTA, "<ana@x.example>", mensaje));
            assertThrows(IllegalArgumentException.class, () -> cliente.enviar(CUENTA, "ana @x.example", mensaje));
            cliente.enviar(CUENTA, DESTINO, mensaje);
        }
        assertEquals(1, servidor.buzones().cantidad(DESTINO));
        assertEquals(0, servidor.buzones().cantidad("victima@x.example"));
    }

    @Test
    void starttlsNoAnunciadoCancelaLaConexion() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        SmtpClient cliente = new SmtpClient(parametros(CLAVE).transporte(TransporteSeguro.STARTTLS)
                .permitirTextoPlano(true).construir(), MecanismoAutenticacion.PLAIN, "localhost");
        CorreoException e = assertThrows(CorreoException.class, cliente::conectar);
        assertTrue(e.getMessage().contains("STARTTLS"), e.getMessage());
    }
}
