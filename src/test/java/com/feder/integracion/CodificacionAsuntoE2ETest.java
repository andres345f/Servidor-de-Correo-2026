package com.feder.integracion;

import com.feder.herramientas.clientedemo.ClienteDemo;
import com.feder.presentacion.procesamiento.CicloCorreo;
import com.feder.presentacion.procesamiento.InterpreteCorreo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asuntos con codificación MIME de extremo a extremo, enviando por SMTP real los bytes exactos de las cabeceras:
 * correo -> POP3 -> decodificación -> parser -> negocio -> PostgreSQL (base local feder_e2e) -> SMTP -> buzón.
 * Lo válido se procesa normalmente; lo mal codificado se responde con el motivo y no modifica datos de negocio.
 */
class CodificacionAsuntoE2ETest {

    private static final String CONSULTAS = "visitante.consultas@feder.example";
    private static final String CARLA = "almacen.carla.mendez@feder.example";
    private static final String JORGE = "ventas.jorge.suarez@feder.example";
    private static final String ELMER = "elmer.rojas@feder.example";
    private static final String SILVIA = "silvia.anez@feder.example";
    private static final String MARIA = "maria.quispe@feder.example";
    private static final String PATRICIA = "patricia.vargas@feder.example";
    private static final Pattern CODIGO = Pattern.compile("CONFIRMAR\\[\"([A-Z0-9]+)\"\\]");

    private static EntornoE2E entorno;

    @BeforeAll
    static void iniciar() throws Exception {
        entorno = EntornoE2E.iniciar(List.of(CONSULTAS, CARLA, JORGE, ELMER, SILVIA, MARIA, PATRICIA));
    }

    @AfterAll
    static void detener() {
        if (entorno != null) {
            entorno.close();
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] unir(byte[]... partes) {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        for (byte[] parte : partes) {
            salida.writeBytes(parte);
        }
        return salida.toByteArray();
    }

    private static String b(byte[] datos) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(datos) + "?=";
    }

    /** Mensaje completo con los bytes exactos del asunto y, opcionalmente, otras cabeceras crudas. */
    private static byte[] mensaje(String de, String messageId, byte[] asunto, byte[]... otrasCabeceras) {
        ByteArrayOutputStream m = new ByteArrayOutputStream();
        m.writeBytes(ascii("Date: " + DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now()) + "\r\n"
                + "From: <" + de + ">\r\nTo: <" + EntornoE2E.SISTEMA + ">\r\nMessage-ID: " + messageId + "\r\n"));
        for (byte[] cabecera : otrasCabeceras) {
            m.writeBytes(cabecera);
            m.writeBytes(ascii("\r\n"));
        }
        m.writeBytes(ascii("Subject: "));
        m.writeBytes(asunto);
        m.writeBytes(ascii("\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nPrueba\r\n"));
        return m.toByteArray();
    }

    private static String nuevoId() {
        return "<" + UUID.randomUUID().toString().replace("-", "") + "@feder.example>";
    }

    private static void enviar(String de, byte[] asunto, byte[]... otrasCabeceras) throws Exception {
        entorno.enviarCrudo(de, mensaje(de, nuevoId(), asunto, otrasCabeceras));
    }

    /** Filas de las tablas de negocio que un comando rechazado nunca debe tocar. */
    private static Map<String, Long> instantaneaDeNegocio() {
        Map<String, Long> filas = new LinkedHashMap<>();
        for (String tabla : List.of("categoria", "producto", "usuario", "usuario_rol", "confirmacion_operacion", "bitacora",
                "pedido", "pago", "movimiento_inventario")) {
            filas.put(tabla, entorno.contar("SELECT count(*) FROM " + tabla));
        }
        return filas;
    }

    @Test
    void consultasCodificadasConTildesYEnieSeRespondenConExito() throws Exception {
        enviar(CONSULTAS, ascii(b(utf8("LISPRO[\"Ñandú\"]"))));
        enviar(CONSULTAS, ascii("=?ISO-8859-1?Q?LISPRO[\"pi=F1a\"]?="));
        enviar(CONSULTAS, ascii("=?windows-1252?Q?LISPRO[\"caf=E9\"]?="));
        enviar(CONSULTAS, utf8("LISPRO[\"Jabón\"]"));

        CicloCorreo.Resumen resumen = entorno.ciclo();
        assertEquals(4, resumen.procesados);
        assertEquals(0, resumen.fallidos);
        List<ClienteDemo.Respuesta> respuestas = entorno.leer(CONSULTAS);
        assertEquals(4, respuestas.size());
        respuestas.forEach(r -> assertTrue(r.asunto().startsWith("FEDER - LISPRO - Éxito"), r.asunto()));
        for (String comando : List.of("LISPRO[\"Ñandú\"]", "LISPRO[\"piña\"]", "LISPRO[\"café\"]", "LISPRO[\"Jabón\"]")) {
            assertTrue(respuestas.stream().anyMatch(r -> r.texto().contains(comando)), "falta la respuesta a " + comando);
        }
    }

    @Test
    void escrituraValidaConCaracteresEspecialesSeConfirmaYSeGuardaExacta() throws Exception {
        String nombre = "Mieles «Ñandú» & Cía.";
        String descripcion = "Precio en € y “comillas”, 100 % orgánico";
        long antes = entorno.contar("SELECT count(*) FROM categoria");

        enviar(CARLA, ascii(b(utf8("INSCAT[\"" + nombre + "\",\"" + descripcion + "\"]"))));
        entorno.ciclo();
        List<ClienteDemo.Respuesta> pedido = entorno.leer(CARLA);
        assertEquals(1, pedido.size());
        assertTrue(pedido.get(0).asunto().contains("Confirmación requerida"), pedido.get(0).asunto());
        assertTrue(pedido.get(0).texto().contains(nombre), "el correo de confirmación muestra el dato exacto");
        Matcher codigo = CODIGO.matcher(pedido.get(0).texto());
        assertTrue(codigo.find(), pedido.get(0).texto());

        entorno.enviar(CARLA, "CONFIRMAR[\"" + codigo.group(1) + "\"]");
        entorno.ciclo();
        List<ClienteDemo.Respuesta> confirmado = entorno.leer(CARLA);
        assertEquals(1, confirmado.size());
        assertTrue(confirmado.get(0).asunto().contains("Categoría registrada"), confirmado.get(0).asunto());
        assertEquals(antes + 1, entorno.contar("SELECT count(*) FROM categoria"));
        assertEquals(1, entorno.contar("SELECT count(*) FROM categoria WHERE nombre = ? AND descripcion = ?", nombre, descripcion));
    }

    @Test
    void escriturasMalCodificadasSeRechazanSinModificarDatosDeNegocio() throws Exception {
        Map<String, Long> antes = instantaneaDeNegocio();
        List<byte[]> asuntos = List.of(
                ascii("=?UTF-8?B?SU5TQ0FUWyJDYWb/IiwieCJd?="),
                ascii(b(unir(ascii("INSCAT[\"Caf"), new byte[] {(byte) 0xC3}, ascii("\",\"x\"]")))),
                ascii("=?US-ASCII?Q?INSCAT[\"Caf=E9\",\"x\"]?="),
                ascii("=?x-inventado?Q?INSCAT[\"Caf\",\"x\"]?="),
                ascii("=?UTF-8?Q?INSCAT[\"Caf=ZZ\",\"x\"]?="),
                ascii("=?UTF-8?B?***?="),
                ascii("INSCAT =?UTF-8?Q??= [\"Mieles\",\"x\"]"),
                ascii("=?ISO-2022-JP?Q?INSCAT[\"a=1B?= =?ISO-2022-JP?Q?(Bx\",\"y\"]?="),
                unir(utf8("INSCAT[\"Ñandú\",\""), new byte[] {(byte) 0xFF}, ascii("\"]")),
                "INSCAT[\"Jabón\",\"x\"]".getBytes(StandardCharsets.ISO_8859_1));
        for (byte[] asunto : asuntos) {
            enviar(JORGE, asunto);
        }

        CicloCorreo.Resumen resumen = entorno.ciclo();
        assertEquals(asuntos.size(), resumen.procesados);
        assertEquals(0, resumen.fallidos);
        List<ClienteDemo.Respuesta> respuestas = entorno.leer(JORGE);
        assertEquals(asuntos.size(), respuestas.size());
        for (ClienteDemo.Respuesta r : respuestas) {
            assertTrue(r.asunto().startsWith("FEDER - Error: Asunto con codificación no válida"), r.asunto());
            assertTrue(r.texto().contains("No se ejecutó ningún comando"), r.texto());
        }
        for (String motivo : List.of("(UTF-8)", "(US-ASCII)", "(ISO-2022-JP)", "juego de caracteres desconocido o no admitido",
                "codificación Q mal formada", "Base64 mal formado", "bytes de 8 bits sin codificar")) {
            assertTrue(respuestas.stream().anyMatch(r -> r.texto().contains(motivo)), "falta el motivo " + motivo);
        }

        // Ningún dato de negocio cambió: ni categorías, ni confirmaciones pendientes, ni bitácora.
        assertEquals(antes, instantaneaDeNegocio());
        // Lo único que se registra es el mensaje recibido y su respuesta, sin texto inventado.
        assertEquals(asuntos.size(), entorno.contar("SELECT count(*) FROM mensaje_correo WHERE remitente = ? "
                + "AND estado = 'PROCESADO' AND resultado = 'ERROR' AND comando IS NULL", JORGE));
        assertEquals(asuntos.size(), entorno.contar("SELECT count(*) FROM mensaje_correo WHERE remitente = ? AND asunto = ?",
                JORGE, InterpreteCorreo.ASUNTO_NO_DECODIFICABLE));
        assertEquals(0, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE strpos(asunto, ?) > 0", "\uFFFD"));
        assertEquals(0, entorno.contar("SELECT count(*) FROM correo_saliente WHERE strpos(cuerpo_texto, ?) > 0 "
                + "OR strpos(asunto, ?) > 0", "\uFFFD", "\uFFFD"));
    }

    @Test
    void unaActualizacionMalCodificadaNoCambiaElProducto() throws Exception {
        String sql = "SELECT (precio_venta * 100)::bigint FROM producto WHERE codigo = 'FED-ACE-020'";
        long precioAntes = entorno.contar(sql);
        Map<String, Long> antes = instantaneaDeNegocio();

        // PREPRO con el motivo dañado (0xFF) y con una palabra en un juego de caracteres no admitido.
        enviar(PATRICIA, ascii(b(unir(ascii("PREPRO[\"FED-ACE-020\",\"99.00\",\"Ajuste "), new byte[] {(byte) 0xFF}, ascii("\"]")))));
        enviar(PATRICIA, ascii("PREPRO[\"FED-ACE-020\",\"99.00\",\" =?x-JISAutoDetect?B?zek=?= \"]"));
        CicloCorreo.Resumen resumen = entorno.ciclo();
        assertEquals(2, resumen.procesados);

        List<ClienteDemo.Respuesta> respuestas = entorno.leer(PATRICIA);
        assertEquals(2, respuestas.size());
        respuestas.forEach(r -> assertTrue(r.asunto().startsWith("FEDER - Error: Asunto con codificación no válida"), r.asunto()));
        assertEquals(precioAntes, entorno.contar(sql), "el precio no cambia");
        assertEquals(antes, instantaneaDeNegocio());
    }

    @Test
    void unConfirmarMalCodificadoNoConsumeLaOperacionPendiente() throws Exception {
        String nombre = "Infusiones de muña";
        String pendientes = "SELECT count(*) FROM confirmacion_operacion WHERE comando = 'INSCAT' AND usada_en IS NULL "
                + "AND intentos_fallidos = 0 AND parametros::text LIKE '%muña%'";
        enviar(CARLA, ascii(b(utf8("INSCAT[\"" + nombre + "\",\"Hierbas andinas\"]"))));
        entorno.ciclo();
        Matcher codigo = CODIGO.matcher(entorno.leer(CARLA).get(0).texto());
        assertTrue(codigo.find());
        assertEquals(1, entorno.contar(pendientes));

        // El mismo CONFIRMAR, pero con un byte inválido: se rechaza sin tocar la operación pendiente.
        enviar(CARLA, ascii(b(unir(ascii("CONFIRMAR[\"" + codigo.group(1) + "\"]"), new byte[] {(byte) 0xFF}))));
        entorno.ciclo();
        List<ClienteDemo.Respuesta> rechazo = entorno.leer(CARLA);
        assertEquals(1, rechazo.size());
        assertTrue(rechazo.get(0).asunto().startsWith("FEDER - Error: Asunto con codificación no válida"), rechazo.get(0).asunto());
        assertEquals(1, entorno.contar(pendientes), "la operación sigue pendiente, sin intentos fallidos");
        assertEquals(0, entorno.contar("SELECT count(*) FROM categoria WHERE nombre = ?", nombre));
        assertEquals(0, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE strpos(asunto, ?) > 0", codigo.group(1)));

        // El CONFIRMAR correcto sigue funcionando.
        entorno.enviar(CARLA, "CONFIRMAR[\"" + codigo.group(1) + "\"]");
        entorno.ciclo();
        assertTrue(entorno.leer(CARLA).get(0).asunto().contains("Categoría registrada"));
        assertEquals(1, entorno.contar("SELECT count(*) FROM categoria WHERE nombre = ?", nombre));
    }

    @Test
    void unAsuntoUtf8SinCodificarNoSeDeformaPorOtraCabeceraLatin1() throws Exception {
        String messageId = nuevoId();
        entorno.enviarCrudo(ELMER, mensaje(ELMER, messageId, utf8("LISPRO[\"Ñandú\"]"),
                unir(ascii("X-Nota: "), "Año".getBytes(StandardCharsets.ISO_8859_1))));

        entorno.ciclo();
        List<ClienteDemo.Respuesta> respuestas = entorno.leer(ELMER);
        assertEquals(1, respuestas.size());
        assertTrue(respuestas.get(0).asunto().startsWith("FEDER - LISPRO - Éxito"), respuestas.get(0).asunto());
        assertEquals(1, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE message_id = ? AND asunto = ?",
                messageId, "LISPRO[\"Ñandú\"]"));
    }

    @Test
    void unaRespuestaAutomaticaMalCodificadaNoSeContesta() throws Exception {
        String messageId = nuevoId();
        entorno.enviarCrudo(SILVIA, mensaje(SILVIA, messageId, ascii("=?UTF-8?B?SU5TQ0FUWyJDYWb/IiwieCJd?="),
                ascii("Auto-Submitted: auto-replied")));

        entorno.ciclo();
        assertEquals(0, entorno.leer(SILVIA).size(), "nunca se contesta a una respuesta automática: evitaría bucles");
        assertEquals(1, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE message_id = ? AND estado = 'IGNORADO'", messageId));
    }

    @Test
    void unConfirmarMalCodificadoNoDejaElCodigoEnElRegistro() throws Exception {
        enviar(MARIA, ascii(b(unir(ascii("CONFIRMAR[\"QWER"), new byte[] {(byte) 0xFF}, ascii("TY12\"]")))));

        entorno.ciclo();
        List<ClienteDemo.Respuesta> respuestas = entorno.leer(MARIA);
        assertEquals(1, respuestas.size());
        assertTrue(respuestas.get(0).asunto().startsWith("FEDER - Error: Asunto con codificación no válida"));
        assertEquals(0, entorno.contar("SELECT count(*) FROM mensaje_correo WHERE asunto LIKE '%QWER%' OR asunto LIKE '%Q09ORklSTUFS%'"));
        assertEquals(0, entorno.contar("SELECT count(*) FROM correo_saliente WHERE cuerpo_texto LIKE '%QWER%'"));
    }
}
