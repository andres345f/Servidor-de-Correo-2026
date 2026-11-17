package com.feder.herramientas.correolocal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.pop3.Pop3Client;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServidorCorreoLocalTest {

    private static final String CUENTA = "feder@feder.local";

    private static Pop3Client cliente(int puerto) throws CorreoException {
        Pop3Client cliente = new Pop3Client(ParametrosConexion.hacia("127.0.0.1", puerto)
                .transporte(TransporteSeguro.NINGUNO).credenciales(CUENTA, "clave").permitirTextoPlano(true)
                .timeoutMs(3000).construir());
        cliente.conectar();
        cliente.autenticar();
        return cliente;
    }

    @Test
    void detenerCierraLasConexionesAbiertasYLiberaLosPuertos() throws Exception {
        List<String> bitacora = new ArrayList<>();
        ServidorCorreoLocal servidor = new ServidorCorreoLocal(new ConfiguracionCorreoLocal()
                .cuenta(CUENTA, "clave").bitacora(m -> {
                    synchronized (bitacora) {
                        bitacora.add(m);
                    }
                }));
        servidor.iniciar();
        int puerto = servidor.puertoPop3();
        try (Socket abierto = new Socket("127.0.0.1", servidor.puertoSmtp())) {
            InputStream entrada = abierto.getInputStream();
            byte[] saludo = new byte[3];
            assertEquals(3, entrada.readNBytes(saludo, 0, 3));
            assertEquals("220", new String(saludo, StandardCharsets.US_ASCII));
            servidor.detener();
            // Tras detener, la conexión abierta debe terminar: se lee el resto del saludo y luego el fin de flujo.
            // Si el servidor no la cerrara, la lectura agotaría el tiempo de espera y la prueba fallaría.
            abierto.setSoTimeout(3000);
            try {
                String resto = new String(entrada.readAllBytes(), StandardCharsets.US_ASCII);
                assertTrue(resto.endsWith("\r\n"), resto);
            } catch (SocketTimeoutException e) {
                throw new AssertionError("El servidor detenido no cerró la conexión abierta", e);
            } catch (IOException e) {
                // Un reinicio de conexión (RST) también demuestra que el servidor la cerró.
                assertTrue(abierto.isConnected());
            }
        }
        assertThrows(CorreoException.class, () -> cliente(puerto));
        synchronized (bitacora) {
            assertTrue(bitacora.stream().anyMatch(m -> m.contains("detenido")), bitacora.toString());
            assertFalse(bitacora.stream().anyMatch(m -> m.contains("clave")), "la bitácora no debe mostrar contraseñas");
        }
    }

    @Test
    void unBuzonAdmiteUnaSolaSesionPop3ALaVez() throws Exception {
        try (ServidorCorreoLocal servidor = new ServidorCorreoLocal(new ConfiguracionCorreoLocal().cuenta(CUENTA, "clave"))) {
            servidor.iniciar();
            try (Pop3Client primero = cliente(servidor.puertoPop3())) {
                CorreoException e = assertThrows(CorreoException.class, () -> cliente(servidor.puertoPop3()));
                assertTrue(e.respuestaServidor().contains("IN-USE"), e.getMessage());
                primero.quit();
            }
            try (Pop3Client segundo = cliente(servidor.puertoPop3())) {
                segundo.quit();
            }
        }
    }
}
