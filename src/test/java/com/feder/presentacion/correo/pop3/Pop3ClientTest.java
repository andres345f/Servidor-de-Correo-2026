package com.feder.presentacion.correo.pop3;

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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Pop3ClientTest {

    private static final String BUZON = "feder@feder.local";
    private static final String CLAVE = "clave-de-prueba";

    private ServidorCorreoLocal servidor;

    @AfterEach
    void detener() {
        if (servidor != null) {
            servidor.detener();
        }
    }

    private void iniciar(ConfiguracionCorreoLocal configuracion) throws IOException {
        servidor = new ServidorCorreoLocal(configuracion.cuenta(BUZON, CLAVE));
        servidor.iniciar();
    }

    private ParametrosConexion.Constructor parametros(String clave) {
        return ParametrosConexion.hacia("127.0.0.1", servidor.puertoPop3())
                .transporte(TransporteSeguro.NINGUNO)
                .credenciales(BUZON, clave)
                .timeoutMs(5000);
    }

    private Pop3Client clienteAutenticado() throws CorreoException {
        Pop3Client cliente = new Pop3Client(parametros(CLAVE).permitirTextoPlano(true).construir());
        cliente.conectar();
        cliente.autenticar();
        return cliente;
    }

    @Test
    void iniciaSesionYConsultaElEstado() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        servidor.buzones().entregar(BUZON, "Subject: a\r\n\r\nuno\r\n".getBytes(StandardCharsets.US_ASCII));
        servidor.buzones().entregar(BUZON, "Subject: b\r\n\r\ndos\r\n".getBytes(StandardCharsets.US_ASCII));
        try (Pop3Client cliente = clienteAutenticado()) {
            assertTrue(cliente.capacidades().containsAll(List.of("USER", "UIDL")));
            EstadoBuzon estado = cliente.stat();
            assertEquals(2, estado.cantidad());
            assertEquals(38, estado.octetos());
            cliente.noop();
            cliente.quit();
        }
    }

    @Test
    void contrasenaIncorrectaDevuelveErr() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        try (Pop3Client cliente = new Pop3Client(parametros("otra-clave").permitirTextoPlano(true).construir())) {
            cliente.conectar();
            CorreoException e = assertThrows(CorreoException.class, cliente::autenticar);
            assertTrue(e.respuestaServidor().startsWith("-ERR"), e.getMessage());
            assertFalse(e.getMessage().contains("otra-clave"), "el mensaje no debe contener la contraseña");
        }
    }

    @Test
    void uidlYRetrRespetanElRellenoDePuntos() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        byte[] original = ("From: ana@x.example\r\nSubject: LISPER[\"*\"]\r\n\r\n.linea con punto\r\n.\r\n..doble\r\nfin\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        String uidl = servidor.buzones().entregar(BUZON, original);
        try (Pop3Client cliente = clienteAutenticado()) {
            Map<Integer, String> mapa = cliente.uidl();
            assertEquals(Map.of(1, uidl), mapa);
            assertArrayEquals(original, cliente.retr(1));
            CorreoException e = assertThrows(CorreoException.class, () -> cliente.retr(7));
            assertTrue(e.respuestaServidor().startsWith("-ERR"));
            cliente.quit();
        }
    }

    @Test
    void deleSeAplicaSoloAlTerminarConQuit() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        servidor.buzones().entregar(BUZON, "Subject: a\r\n\r\nuno\r\n".getBytes(StandardCharsets.US_ASCII));
        servidor.buzones().entregar(BUZON, "Subject: b\r\n\r\ndos\r\n".getBytes(StandardCharsets.US_ASCII));

        try (Pop3Client sinQuit = clienteAutenticado()) {
            sinQuit.dele(1);
        }
        assertEquals(2, servidor.buzones().cantidad(BUZON), "sin QUIT no debe borrarse nada");

        try (Pop3Client conQuit = clienteAutenticado()) {
            conQuit.dele(1);
            assertEquals(1, conQuit.stat().cantidad());
            conQuit.quit();
        }
        assertEquals(1, servidor.buzones().cantidad(BUZON));
        assertTrue(new String(servidor.buzones().mensajes(BUZON).get(0).contenido(), StandardCharsets.US_ASCII)
                .contains("Subject: b"));
    }

    @Test
    void servidorInalcanzableLanzaCorreoException() throws Exception {
        int puertoLibre;
        try (ServerSocket temporal = new ServerSocket(0)) {
            puertoLibre = temporal.getLocalPort();
        }
        Pop3Client cliente = new Pop3Client(ParametrosConexion.hacia("127.0.0.1", puertoLibre)
                .transporte(TransporteSeguro.NINGUNO).timeoutMs(2000).construir());
        assertThrows(CorreoException.class, cliente::conectar);
    }

    @Test
    void rechazaCredencialesEnTextoPlanoSinPermisoExplicito() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        try (Pop3Client cliente = new Pop3Client(parametros(CLAVE).construir())) {
            cliente.conectar();
            CorreoException e = assertThrows(CorreoException.class, cliente::autenticar);
            assertTrue(e.getMessage().contains("sin cifrar"), e.getMessage());
        }
    }

    @Test
    void stlsNoAnunciadoCancelaLaConexionSinVolverATextoPlano() throws Exception {
        iniciar(new ConfiguracionCorreoLocal());
        Pop3Client cliente = new Pop3Client(parametros(CLAVE).transporte(TransporteSeguro.STARTTLS)
                .permitirTextoPlano(true).construir());
        CorreoException e = assertThrows(CorreoException.class, cliente::conectar);
        assertTrue(e.getMessage().contains("STLS"), e.getMessage());
        // La conexión quedó cerrada: autenticar() falla antes de enviar ninguna credencial.
        CorreoException cerrada = assertThrows(CorreoException.class, cliente::autenticar, "la conexión debe quedar cerrada");
        assertTrue(cerrada.getMessage().contains("se cerró"), cerrada.getMessage());
    }

    @Test
    void servidorSinUidlSeInformaComoError() throws Exception {
        iniciar(new ConfiguracionCorreoLocal().pop3AdmiteUidl(false));
        try (Pop3Client cliente = clienteAutenticado()) {
            assertFalse(cliente.capacidades().contains("UIDL"));
            CorreoException e = assertThrows(CorreoException.class, cliente::uidl);
            assertTrue(e.getMessage().contains("no admite UIDL"), e.getMessage());
        }
    }
}
