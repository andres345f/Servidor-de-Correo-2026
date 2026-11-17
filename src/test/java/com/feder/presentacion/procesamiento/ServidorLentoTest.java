package com.feder.presentacion.procesamiento;

import com.feder.negocio.mensajeria.EnviadorCorreo;
import com.feder.presentacion.correo.mime.EmailResponseBuilder;
import com.feder.presentacion.correo.smtp.MecanismoAutenticacion;
import com.feder.presentacion.correo.ConectorSocket;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.pop3.Pop3Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Servidores POP3 defectuosos simulados con un socket propio: uno que responde un byte cada pocos milisegundos
 * (nunca agota el tiempo de espera del socket) y otros que envían listados enormes.
 */
class ServidorLentoTest {

    private ServerSocket servidor;
    private Thread hilo;

    @AfterEach
    void cerrar() throws IOException {
        if (servidor != null) {
            servidor.close();
        }
        if (hilo != null) {
            hilo.interrupt();
        }
    }

    private int iniciar(BiConsumer<InputStream, OutputStream> guion) throws IOException {
        servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        hilo = new Thread(() -> {
            try (Socket s = servidor.accept()) {
                guion.accept(s.getInputStream(), s.getOutputStream());
            } catch (IOException e) {
                // El cliente cerró la conexión: fin del guion.
            }
        });
        hilo.setDaemon(true);
        hilo.start();
        return servidor.getLocalPort();
    }

    private static ParametrosConexion parametros(int puerto) {
        return ParametrosConexion.hacia("127.0.0.1", puerto).transporte(TransporteSeguro.NINGUNO)
                .credenciales("feder@feder.local", "clave").permitirTextoPlano(true).timeoutMs(2000).construir();
    }

    private static void escribir(OutputStream salida, String texto) {
        try {
            salida.write(texto.getBytes(StandardCharsets.US_ASCII));
            salida.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void leerLinea(InputStream entrada) {
        try {
            int c;
            while ((c = entrada.read()) != -1 && c != '\n') {
                // descartar
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Un servidor que responde byte a byte sin terminar nunca la línea se corta al vencer el plazo")
    void plazoAbsolutoCortaUnServidorLento() throws Exception {
        int puerto = iniciar((entrada, salida) -> {
            try {
                for (int i = 0; i < 10_000; i++) {
                    escribir(salida, "+");
                    Thread.sleep(200); // menos que el tiempo de espera del socket (2 s): nunca lo agota
                }
            } catch (InterruptedException | IllegalStateException e) {
                Thread.currentThread().interrupt();
            }
        });
        long inicio = System.nanoTime();
        try (VigilantePlazo plazo = new VigilantePlazo("plazo-prueba", Duration.ofMillis(1500));
             Pop3Client cliente = new Pop3Client(parametros(puerto))) {
            assertThrows(CorreoException.class, () -> plazo.ejecutar(cliente::conectar, () -> {
                try {
                    cliente.close();
                } catch (CorreoException e) {
                    // ya cerrado
                }
            }));
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(ms < 6000, "la operación debía terminar al vencer el plazo; duró " + ms + " ms");
    }

    /** Servidor que acepta la conexión TCP y nunca responde: el saludo TLS queda esperando. */
    private int iniciarMudo() throws IOException {
        return iniciar((entrada, salida) -> {
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static ParametrosConexion conTls(int puerto) {
        // Tiempo de espera del socket largo a propósito: solo el plazo absoluto debe cortar la espera.
        return ParametrosConexion.hacia("127.0.0.1", puerto).transporte(TransporteSeguro.TLS)
                .credenciales("feder@feder.local", "clave").timeoutMs(60_000).construir();
    }

    @Test
    @DisplayName("POP3 con TLS: un servidor que nunca contesta el saludo TLS se corta al vencer el plazo")
    void handshakeTlsPop3SinRespuesta() throws Exception {
        int puerto = iniciarMudo();
        long inicio = System.nanoTime();
        try (VigilantePlazo plazo = new VigilantePlazo("plazo-prueba", Duration.ofMillis(1500));
             Pop3Client cliente = new Pop3Client(conTls(puerto))) {
            assertThrows(CorreoException.class, () -> plazo.ejecutar(cliente::conectar, () -> {
                try {
                    cliente.close();
                } catch (CorreoException e) {
                    // ya cerrado
                }
            }));
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(ms < 8000, "el saludo TLS bloqueado debía cortarse con el plazo; duró " + ms + " ms");
    }

    @Test
    @DisplayName("SMTP con TLS: un servidor mudo se corta con el plazo del envío y el fallo se reintenta")
    void handshakeTlsSmtpSinRespuesta() throws Exception {
        int puerto = iniciarMudo();
        ParametrosConexion smtp = conTls(puerto);
        ConfiguracionCorreo configuracion = ConfiguracionCorreo.de(smtp, smtp, MecanismoAutenticacion.PLAIN,
                "feder@feder.local", 20, false);
        long inicio = System.nanoTime();
        try (EnviadorSmtp enviador = new EnviadorSmtp(configuracion, new EmailResponseBuilder("feder@feder.local", "Sistema FEDER"),
                Duration.ofMillis(1500))) {
            EnviadorCorreo.FalloEnvio fallo = assertThrows(EnviadorCorreo.FalloEnvio.class, () -> enviador.enviar(
                    new EnviadorCorreo.CorreoPendiente("cliente@feder.example", "FEDER - Aviso: prueba", "texto", null,
                            "<tls-mudo@feder.local>", null, null, null, null)));
            assertFalse(fallo.esPermanente(), "un servidor que no responde es un fallo de sesión que se reintenta");
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(ms < 8000, "el envío debía cortarse con el plazo; duró " + ms + " ms");
    }

    @Test
    @DisplayName("Un servidor que cierra la conexión en pleno saludo TLS produce un error de correo inmediato")
    void handshakeTlsInterrumpido() throws Exception {
        int puerto = iniciar((entrada, salida) -> escribir(salida, "no es TLS\r\n"));
        long inicio = System.nanoTime();
        try (Pop3Client cliente = new Pop3Client(conTls(puerto))) {
            assertThrows(CorreoException.class, cliente::conectar);
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(ms < 5000, "duró " + ms + " ms");
    }

    @Test
    @DisplayName("Un nombre de servidor que no existe falla con un error de correo dentro del tiempo de espera")
    void hostInexistenteFallaDentroDelPlazo() {
        ParametrosConexion inexistente = ParametrosConexion.hacia("feder-prueba.invalid", 110).transporte(TransporteSeguro.NINGUNO)
                .credenciales("feder@feder.local", "clave").timeoutMs(3000).construir();
        long inicio = System.nanoTime();
        try (Pop3Client cliente = new Pop3Client(inexistente)) {
            CorreoException e = assertThrows(CorreoException.class, cliente::conectar);
            assertTrue(e.getMessage().contains("feder-prueba.invalid"), e.getMessage());
        } catch (CorreoException e) {
            throw new AssertionError(e);
        }
        long ms = (System.nanoTime() - inicio) / 1_000_000;
        assertTrue(ms < 6000, "la resolución debía terminar dentro del tiempo de espera; duró " + ms + " ms");
    }

    @Test
    @DisplayName("Si el plazo vence antes de empezar a conectar, la conexión ya no se abre")
    void cierreAntesDeConectarCancelaLaConexion() throws Exception {
        servidor = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        servidor.setSoTimeout(1500);
        Pop3Client cliente = new Pop3Client(parametros(servidor.getLocalPort()));
        cliente.close(); // el vigilante actuó antes de que existiera el socket
        assertThrows(CorreoException.class, cliente::conectar);
        assertThrows(java.net.SocketTimeoutException.class, servidor::accept, "no debía llegar ninguna conexión");
    }

    @Test
    @DisplayName("Una operación después de que el vigilante cerró la conexión es un error de correo, no un fallo interno")
    void operacionTrasElCierreEsErrorDeCorreo() throws Exception {
        int puerto = iniciar((entrada, salida) -> {
            escribir(salida, "+OK listo\r\n");
            leerLinea(entrada);
            escribir(salida, "-ERR sin CAPA\r\n");
            leerLinea(entrada);
        });
        Pop3Client cliente = new Pop3Client(parametros(puerto));
        cliente.conectar();
        cliente.close();
        assertThrows(CorreoException.class, cliente::uidl);
    }

    @Test
    @DisplayName("Una línea de CAPA demasiado larga es un error de protocolo, no un consumo de memoria ilimitado")
    void lineaDeListadoDemasiadoLarga() throws Exception {
        int puerto = iniciar((entrada, salida) -> {
            escribir(salida, "+OK listo\r\n");
            leerLinea(entrada);
            escribir(salida, "+OK\r\n" + "X".repeat(4000) + "\r\n.\r\n");
        });
        try (Pop3Client cliente = new Pop3Client(parametros(puerto))) {
            assertThrows(CorreoException.class, cliente::conectar);
        }
    }

    @Test
    @DisplayName("Un listado CAPA que supera el presupuesto total se rechaza mientras se lee")
    void listadoQueSuperaElPresupuesto() throws Exception {
        int puerto = iniciar((entrada, salida) -> {
            escribir(salida, "+OK listo\r\n");
            leerLinea(entrada);
            StringBuilder listado = new StringBuilder("+OK\r\n");
            for (int i = 0; i < 400; i++) {
                listado.append("CAPACIDAD-").append("Y".repeat(300)).append("\r\n");
            }
            escribir(salida, listado.append(".\r\n").toString());
        });
        try (Pop3Client cliente = new Pop3Client(parametros(puerto))) {
            assertThrows(CorreoException.class, cliente::conectar);
        }
    }

    @Test
    @DisplayName("esLoopback distingue una conexión a 127.0.0.1 de un socket sin conectar")
    void deteccionDeLoopback() throws Exception {
        int puerto = iniciar((entrada, salida) -> leerLinea(entrada));
        try (Socket local = new Socket(InetAddress.getLoopbackAddress(), puerto); Socket suelto = new Socket()) {
            assertTrue(ConectorSocket.esLoopback(local));
            assertFalse(ConectorSocket.esLoopback(suelto));
            assertFalse(ConectorSocket.esLoopback(null));
        }
    }
}
