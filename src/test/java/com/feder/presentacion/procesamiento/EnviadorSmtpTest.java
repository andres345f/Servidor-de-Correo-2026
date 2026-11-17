package com.feder.presentacion.procesamiento;

import com.feder.herramientas.correolocal.ConfiguracionCorreoLocal;
import com.feder.herramientas.correolocal.ServidorCorreoLocal;
import com.feder.negocio.mensajeria.EnviadorCorreo;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.mime.EmailResponseBuilder;
import com.feder.presentacion.correo.smtp.MecanismoAutenticacion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Distinción entre fallos de la sesión SMTP (se reintenta) y rechazos de un mensaje concreto. */
class EnviadorSmtpTest {

    private static final String SISTEMA = "grupo29sa@feder.local";
    private static final String DESTINO = "elmer.rojas@feder.example";

    private ServidorCorreoLocal servidor;

    @BeforeEach
    void iniciar() throws Exception {
        servidor = new ServidorCorreoLocal(new ConfiguracionCorreoLocal().puertoSmtp(0).puertoPop3(0)
                .cuenta(SISTEMA, "correcta").cuenta(DESTINO, "x").smtpExigeAutenticacion(true).bitacora(l -> { }));
        servidor.iniciar();
    }

    @AfterEach
    void detener() {
        servidor.detener();
    }

    private EnviadorSmtp enviador(String clave) {
        ParametrosConexion smtp = ParametrosConexion.hacia("127.0.0.1", servidor.puertoSmtp())
                .transporte(TransporteSeguro.NINGUNO).credenciales(SISTEMA, clave).permitirTextoPlano(true).construir();
        ConfiguracionCorreo configuracion = ConfiguracionCorreo.de(smtp, smtp, MecanismoAutenticacion.PLAIN, SISTEMA, 20, false);
        return new EnviadorSmtp(configuracion, new EmailResponseBuilder(SISTEMA, "Sistema FEDER"),
                configuracion.plazoPorEnvio());
    }

    private static EnviadorCorreo.CorreoPendiente correo() {
        return new EnviadorCorreo.CorreoPendiente(DESTINO, "FEDER - Éxito: prueba", "texto", null,
                "<prueba1@feder.local>", null, "reporte.pdf", "application/pdf", "%PDF-1.4".getBytes());
    }

    @Test
    void autenticacionRechazadaEsUnFalloDeSesionQueSeReintenta() {
        try (EnviadorSmtp enviador = enviador("equivocada")) {
            EnviadorCorreo.FalloEnvio fallo = assertThrows(EnviadorCorreo.FalloEnvio.class, () -> enviador.enviar(correo()));
            assertFalse(fallo.esPermanente(), "un 535 de autenticación no debe descartar la respuesta");
        }
        assertEquals(0, servidor.buzones().cantidad(DESTINO));
    }

    @Test
    void rechazoPermanenteDeUnMensajeEsDefinitivo() {
        servidor.smtp().fallarSiguientesData(1, 554);
        try (EnviadorSmtp enviador = enviador("correcta")) {
            EnviadorCorreo.FalloEnvio fallo = assertThrows(EnviadorCorreo.FalloEnvio.class, () -> enviador.enviar(correo()));
            assertTrue(fallo.esPermanente());
        }
    }

    @Test
    void envioConAdjuntoLlegaAlBuzon() throws Exception {
        try (EnviadorSmtp enviador = enviador("correcta")) {
            enviador.enviar(correo());
        }
        assertEquals(1, servidor.buzones().cantidad(DESTINO));
        String crudo = new String(servidor.buzones().mensajes(DESTINO).get(0).contenido(), java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(crudo.contains("multipart/mixed"));
        assertTrue(crudo.contains("filename=\"reporte.pdf\""));
    }
}
