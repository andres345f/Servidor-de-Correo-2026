package com.feder.presentacion.correo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.feder.herramientas.correolocal.CertificadosPrueba;
import com.feder.herramientas.correolocal.ConfiguracionCorreoLocal;
import com.feder.herramientas.correolocal.ServidorCorreoLocal;
import com.feder.presentacion.correo.pop3.Pop3Client;
import com.feder.presentacion.correo.smtp.MecanismoAutenticacion;
import com.feder.presentacion.correo.smtp.SmtpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * POP3 (STLS y TLS implícito) y SMTP (STARTTLS) cifrados de extremo a extremo con un certificado de prueba.
 * Se omite si el JDK no trae keytool.
 */
class TlsExtremoAExtremoTest {

    private static final String CUENTA = "feder@feder.local";
    private static final String CLAVE = "clave-tls";

    private static CertificadosPrueba valido;
    private static CertificadosPrueba otroHost;
    private ServidorCorreoLocal servidor;

    @BeforeAll
    static void generarCertificados() throws Exception {
        assumeTrue(Files.isExecutable(CertificadosPrueba.keytool()), "keytool no está disponible en este JDK");
        valido = CertificadosPrueba.generar("servidor-local", "ip:127.0.0.1,dns:localhost");
        otroHost = CertificadosPrueba.generar("servidor-otro-host", "dns:otro-host.invalid");
    }

    @AfterEach
    void detener() {
        if (servidor != null) {
            servidor.detener();
        }
    }

    private void iniciar(CertificadosPrueba certificado, boolean pop3Implicito) throws Exception {
        servidor = new ServidorCorreoLocal(new ConfiguracionCorreoLocal()
                .cuenta(CUENTA, CLAVE)
                .tls(certificado.contextoServidor())
                .pop3TlsImplicito(pop3Implicito)
                .smtpExigeAutenticacion(true));
        servidor.iniciar();
    }

    private static ParametrosConexion.Constructor parametros(int puerto, TransporteSeguro transporte,
                                                             SSLSocketFactory fabrica) {
        // Sin permitirTextoPlano: las credenciales solo pueden viajar si TLS quedó establecido.
        return ParametrosConexion.hacia("127.0.0.1", puerto).transporte(transporte).credenciales(CUENTA, CLAVE)
                .timeoutMs(5000).fabricaSsl(fabrica);
    }

    private static SSLSocketFactory confiaEn(CertificadosPrueba certificado) throws Exception {
        return certificado.contextoClienteQueConfiaSoloEnEste().getSocketFactory();
    }

    @Test
    void pop3ConStlsCifraAntesDeAutenticar() throws Exception {
        iniciar(valido, false);
        byte[] mensaje = "Subject: LISPER[\"*\"]\r\n\r\n.punto\r\n".getBytes(StandardCharsets.US_ASCII);
        servidor.buzones().entregar(CUENTA, mensaje);
        try (Pop3Client cliente = new Pop3Client(parametros(servidor.puertoPop3(), TransporteSeguro.STARTTLS,
                confiaEn(valido)).construir())) {
            cliente.conectar();
            assertTrue(cliente.esCifrada());
            assertFalse(cliente.capacidades().contains("STLS"), "tras STLS el servidor ya no debe anunciarlo");
            cliente.autenticar();
            assertEquals(1, cliente.uidl().size());
            assertArrayEquals(mensaje, cliente.retr(1));
            cliente.quit();
        }
    }

    @Test
    void pop3ConTlsImplicito() throws Exception {
        iniciar(valido, true);
        try (Pop3Client cliente = new Pop3Client(parametros(servidor.puertoPop3(), TransporteSeguro.TLS,
                confiaEn(valido)).construir())) {
            cliente.conectar();
            cliente.autenticar();
            assertEquals(0, cliente.stat().cantidad());
            cliente.quit();
        }
    }

    @Test
    void smtpConStarttlsYAuthPlain() throws Exception {
        iniciar(valido, false);
        byte[] mensaje = "Subject: cifrado\r\n\r\nhola\r\n".getBytes(StandardCharsets.US_ASCII);
        try (SmtpClient cliente = new SmtpClient(parametros(servidor.puertoSmtp(), TransporteSeguro.STARTTLS,
                confiaEn(valido)).construir(), MecanismoAutenticacion.PLAIN, "localhost")) {
            cliente.conectar();
            assertTrue(cliente.esCifrada());
            assertFalse(cliente.extensiones().containsKey("STARTTLS"));
            cliente.enviar(CUENTA, "ana@universidad.example", mensaje);
            cliente.quit();
        }
        assertArrayEquals(mensaje, servidor.buzones().mensajes("ana@universidad.example").get(0).contenido());
    }

    @Test
    void certificadoNoConfiableCancelaSinCaerATextoPlano() throws Exception {
        iniciar(valido, false);
        // La fábrica por defecto usa los certificados raíz del JDK, que no incluyen el autofirmado.
        Pop3Client pop3 = new Pop3Client(parametros(servidor.puertoPop3(), TransporteSeguro.STARTTLS, null).construir());
        CorreoException e = assertThrows(CorreoException.class, pop3::conectar);
        assertTrue(cadenaDeCausas(e).contains("PKIX"), cadenaDeCausas(e));

        SmtpClient smtp = new SmtpClient(parametros(servidor.puertoSmtp(), TransporteSeguro.STARTTLS, null).construir(),
                MecanismoAutenticacion.PLAIN, "localhost");
        assertThrows(CorreoException.class, smtp::conectar);
        assertEquals(0, servidor.buzones().cantidad("ana@universidad.example"));
    }

    @Test
    void certificadoDeOtroHostSeRechazaAunqueSeaConfiable() throws Exception {
        iniciar(otroHost, true);
        Pop3Client cliente = new Pop3Client(parametros(servidor.puertoPop3(), TransporteSeguro.TLS,
                confiaEn(otroHost)).construir());
        CorreoException e = assertThrows(CorreoException.class, cliente::conectar);
        // El certificado es confiable pero su nombre no coincide: debe fallar la verificación del host.
        assertTrue(cadenaDeCausas(e).toLowerCase(Locale.ROOT).contains("subject alternative"), cadenaDeCausas(e));
    }

    private static String cadenaDeCausas(Throwable error) {
        StringBuilder texto = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            texto.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
        }
        return texto.toString();
    }
}
