package com.feder.integracion;

import com.feder.herramientas.clientedemo.ClienteDemo;
import com.feder.presentacion.procesamiento.CicloCorreo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Flujo completo por sockets reales: correo -> POP3 -> parser -> negocio -> PostgreSQL ->
 * bandeja de salida -> SMTP -> buzón del usuario. Cada prueba usa un remitente distinto
 * para que las respuestas de una no interfieran con las de otra.
 */
class FlujoCorreoE2ETest {

    private static final String DESCONOCIDO = "visitante.externo@feder.example";
    private static final String CLIENTE = "elmer.rojas@feder.example";
    private static final String PERSONAL = "almacen.carla.mendez@feder.example";
    private static final String OTRO_CLIENTE = "silvia.anez@feder.example";

    private static EntornoE2E entorno;

    @BeforeAll
    static void iniciar() throws Exception {
        entorno = EntornoE2E.iniciar(List.of(DESCONOCIDO, CLIENTE, PERSONAL, OTRO_CLIENTE));
    }

    @AfterAll
    static void detener() {
        if (entorno != null) {
            entorno.close();
        }
    }

    @Test
    void ayudaPublicaSeRespondeAlRemitente() throws Exception {
        entorno.enviar(DESCONOCIDO, "HELP[\"*\"]");
        CicloCorreo.Resumen resumen = entorno.ciclo();

        assertNull(resumen.errorRecepcion);
        List<ClienteDemo.Respuesta> respuestas = entorno.leer(DESCONOCIDO);
        assertEquals(1, respuestas.size());
        assertTrue(respuestas.get(0).asunto().startsWith("FEDER - HELP - Éxito"), respuestas.get(0).asunto());
        assertTrue(respuestas.get(0).texto().contains("HELP"));
    }

    @Test
    void errorDeSintaxisRespondeConPosicionYFormato() throws Exception {
        entorno.enviar(CLIENTE, "LISPER[\"*\"");
        entorno.ciclo();

        List<ClienteDemo.Respuesta> respuestas = entorno.leer(CLIENTE);
        assertEquals(1, respuestas.size());
        assertEquals("FEDER - LISPER - Error: Sintaxis inválida", respuestas.get(0).asunto());
        assertTrue(respuestas.get(0).texto().contains("COMANDO[\"param1\",\"param2\"]"), respuestas.get(0).texto());
        // LISPER no existe: se muestra la forma general y la ayuda como ejemplo.
        assertTrue(respuestas.get(0).texto().contains("Forma correcta                   : COMANDO[\"dato1\",\"dato2\"]"),
                respuestas.get(0).texto());
        assertTrue(respuestas.get(0).texto().contains("Ejemplo para copiar en el asunto : HELP[\"*\"]"), respuestas.get(0).texto());
    }

    @Test
    @DisplayName("B1 y B2: el asunto nombra el comando; el error muestra arriba lo recibido y debajo la forma correcta")
    void errorMuestraLoRecibidoYLoEsperado() throws Exception {
        entorno.enviar(CLIENTE, "lisusu[\"*\"");
        entorno.enviar(DESCONOCIDO, "HELP[\"a\",\"b\"]");
        entorno.ciclo();

        ClienteDemo.Respuesta sintaxis = entorno.leer(CLIENTE).get(0);
        assertEquals("FEDER - LISUSU - Error: Sintaxis inválida", sintaxis.asunto());
        assertTrue(sintaxis.texto().contains("Usted envió: lisusu[\"*\""), sintaxis.texto());
        assertTrue(sintaxis.texto().contains("Forma correcta                   : LISUSU[\"filtro\"]"), sintaxis.texto());
        assertTrue(sintaxis.texto().contains("Ejemplo para copiar en el asunto : LISUSU[\"*\"]"), sintaxis.texto());

        ClienteDemo.Respuesta dato = entorno.leer(DESCONOCIDO).get(0);
        assertTrue(dato.asunto().startsWith("FEDER - HELP - Error: "), dato.asunto());
        assertTrue(dato.texto().contains("Forma correcta                   : HELP[\"tema\"]"), dato.texto());
    }

    @Test
    void ejemploDelEnunciadoIndicaLaEquivalenciaFeder() throws Exception {
        entorno.enviar(OTRO_CLIENTE, "LISPER[\"*\"]");
        entorno.ciclo();

        List<ClienteDemo.Respuesta> respuestas = entorno.leer(OTRO_CLIENTE);
        assertEquals(1, respuestas.size());
        assertTrue(respuestas.get(0).texto().contains("LISUSU"), respuestas.get(0).texto());
    }

    @Test
    void unMismoCorreoNoSeProcesaDosVeces() throws Exception {
        entorno.enviar(PERSONAL, "HELP[\"HELP\"]");
        CicloCorreo.Resumen primero = entorno.ciclo();
        CicloCorreo.Resumen segundo = entorno.ciclo();

        assertEquals(1, primero.procesados);
        assertEquals(0, segundo.procesados, "el segundo ciclo no debe volver a procesar el mismo UIDL");
        assertEquals(1, entorno.leer(PERSONAL).size());
        assertEquals(1, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE remitente = ? AND asunto = ?",
                PERSONAL, "HELP[\"HELP\"]"));
    }

    @Test
    void respuestasAutomaticasNoSeContestan() throws Exception {
        entorno.entregarCrudo("From: <" + DESCONOCIDO + ">\r\nTo: <" + EntornoE2E.SISTEMA + ">\r\n"
                + "Subject: Fuera de la oficina\r\nAuto-Submitted: auto-replied\r\nMessage-ID: <auto1@feder.example>\r\n\r\nNo estoy.\r\n");
        entorno.ciclo();

        assertEquals(0, entorno.leer(DESCONOCIDO).size());
        assertEquals(1, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE message_id = ? AND estado = 'IGNORADO'",
                "<auto1@feder.example>"));
    }

    @Test
    void asuntoConCaracterNuloNoBloqueaElBuzon() throws Exception {
        // =00 es el carácter NUL: PostgreSQL no lo admite en texto. El mensaje debe responderse con
        // un error de sintaxis y el siguiente correo debe procesarse con normalidad.
        entorno.entregarCrudo("From: <" + DESCONOCIDO + ">\r\nTo: <" + EntornoE2E.SISTEMA + ">\r\n"
                + "Subject: =?UTF-8?Q?HELP=00?=\r\nMessage-ID: <nulo1@feder.example>\r\n\r\nx\r\n");
        entorno.enviar(DESCONOCIDO, "HELP[\"HELP\"]");

        CicloCorreo.Resumen resumen = entorno.ciclo();
        assertEquals(0, resumen.fallidos);
        assertEquals(2, resumen.procesados);
        List<ClienteDemo.Respuesta> respuestas = entorno.leer(DESCONOCIDO);
        assertEquals(2, respuestas.size());
        assertEquals(1, respuestas.stream().filter(r -> r.asunto().startsWith("FEDER - Error")).count());
    }

    @Test
    void mensajeConLineaDemasiadoLargaQuedaEnCuarentenaYNoBloquea() throws Exception {
        String lineaEnorme = "x".repeat(40_000);
        entorno.entregarCrudo("From: <" + PERSONAL + ">\r\nTo: <" + EntornoE2E.SISTEMA + ">\r\n"
                + "Subject: HELP[\"*\"]\r\nMessage-ID: <enorme1@feder.example>\r\n\r\n" + lineaEnorme + "\r\n");
        entorno.enviar(PERSONAL, "HELP[\"HELP\"]");

        CicloCorreo.Resumen primero = entorno.ciclo();
        assertTrue(primero.errorRecepcion != null, "el ciclo se interrumpe al encontrar el mensaje enorme");
        CicloCorreo.Resumen segundo = entorno.ciclo();
        assertNull(segundo.errorRecepcion, "en el ciclo siguiente el mensaje en cuarentena ya no bloquea");
        assertEquals(1, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE asunto = '(mensaje demasiado grande)' AND estado = 'IGNORADO'"));
        assertEquals(1, entorno.leer(PERSONAL).size());
    }

    @Test
    void confirmarMalEscritoTambienSeEnmascara() throws Exception {
        entorno.enviar(OTRO_CLIENTE, "CONFIRMAR[\"ZXCV9876\"");
        entorno.ciclo();

        assertEquals(1, entorno.leer(OTRO_CLIENTE).size());
        assertEquals(0, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE asunto LIKE '%ZXCV9876%'"));
    }

    @Test
    void elCodigoDeConfirmacionNoQuedaEnElRegistroDeMensajes() throws Exception {
        entorno.enviar(OTRO_CLIENTE, "CONFIRMAR[\"ABCD2345\"]");
        entorno.ciclo();

        assertEquals(1, entorno.leer(OTRO_CLIENTE).size());
        assertEquals(0, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE asunto LIKE '%ABCD2345%'"));
        assertEquals(0, entorno.contar("SELECT count(*) FROM correo_saliente WHERE cuerpo_texto LIKE '%ABCD2345%'"));
    }

    @Test
    void fallaTransitoriaSmtpSeReintentaEnElCicloSiguiente() throws Exception {
        entorno.enviar(CLIENTE, "HELP[\"CU1\"]");
        // El servidor rechazará temporalmente (451) el envío de la respuesta del sistema.
        entorno.correo().smtp().fallarSiguientesData(1, 451);

        CicloCorreo.Resumen primero = entorno.ciclo();
        assertEquals(1, primero.fallosEnvio);
        assertEquals(0, entorno.leer(CLIENTE).size(), "la respuesta queda pendiente en la bandeja de salida");
        assertEquals(1, entorno.contar("SELECT count(*) FROM correo_saliente WHERE destinatario = ? AND estado = 'PENDIENTE'", CLIENTE));

        // Adelantamos el próximo intento para no depender de la espera real en la prueba.
        entorno.ejecutar("UPDATE correo_saliente SET proximo_intento = now() WHERE estado = 'PENDIENTE'");
        CicloCorreo.Resumen segundo = entorno.ciclo();
        assertEquals(1, segundo.respuestasEnviadas);
        assertEquals(1, entorno.leer(CLIENTE).size());
    }
}
