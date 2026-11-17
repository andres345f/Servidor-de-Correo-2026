package com.feder.negocio.usuarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.datos.mensajeria.MensajeCorreoRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.mensajeria.GeneradorRespuesta;
import com.feder.negocio.mensajeria.ServicioMensajeria;
import com.feder.negocio.mensajeria.SolicitudCorreo;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Procesa comandos como correos reales con ServicioMensajeria, para comprobar en correo_saliente la respuesta
 * al remitente y los avisos a terceros que generó la operación. Usar dentro de enTransaccionRevertida.
 */
public final class BandejaPruebas {

    /** Una fila de correo_saliente: a quién va, el asunto y el cuerpo en texto. */
    public record Saliente(String destinatario, String asunto, String cuerpo) {
    }

    /** Lo que dejó en la bandeja de salida un correo procesado: la respuesta al remitente y los avisos. */
    public record Procesado(Saliente respuesta, List<Saliente> avisos) {

        public List<Saliente> avisosA(String destinatario) {
            return avisos.stream().filter(a -> a.destinatario().equalsIgnoreCase(destinatario)).collect(Collectors.toList());
        }

        public Saliente unicoAvisoA(String destinatario) {
            List<Saliente> encontrados = avisosA(destinatario);
            assertEquals(1, encontrados.size(), () -> "Se esperaba un aviso a " + destinatario + "; avisos: " + avisos);
            return encontrados.get(0);
        }
    }

    /** Formato mínimo: asunto con el título y cuerpo con el mensaje y cada detalle como «clave: valor». */
    private static final GeneradorRespuesta GENERADOR = new GeneradorRespuesta() {
        @Override
        public Respuesta generar(ResultadoOperacion resultado, String comandoRecibido) {
            StringBuilder cuerpo = new StringBuilder(resultado.mensaje());
            for (String[] detalle : resultado.detalles()) {
                cuerpo.append('\n').append(detalle[0]).append(": ").append(detalle[1]);
            }
            return new Respuesta("FEDER - " + resultado.titulo(), cuerpo.toString(), null);
        }

        @Override
        public String nuevoMessageId() {
            return "<" + UUID.randomUUID() + "@feder.local>";
        }
    };

    private static final String BUZON = "pruebas-avisos@local:0";

    private final ComandoDispatcher despachador;
    private final ServicioMensajeria mensajeria;

    public BandejaPruebas(ContextoNegocio ctx) {
        this(ctx, ApoyoPruebas.despachador(ctx));
    }

    public BandejaPruebas(ContextoNegocio ctx, ComandoDispatcher despachador) {
        this.despachador = despachador;
        this.mensajeria = new ServicioMensajeria(ctx.transacciones(), new MensajeCorreoRepositorio(),
                new CorreoSalienteRepositorio(), despachador, Clock.systemUTC(), 1000, 24);
    }

    /**
     * Pide la confirmación de un comando de escritura y la envía con CONFIRMAR como un correo real: los avisos
     * solo se generan al ejecutar la operación, es decir, al procesar el CONFIRMAR.
     */
    public Procesado confirmarPorCorreo(String remitente, String comando, String... valores) {
        ResultadoOperacion pedido = despachador.despachar(comando, List.of(valores), remitente).resultado;
        assertEquals("Confirmación requerida", pedido.titulo(), pedido::mensaje);
        String codigo = ApoyoPruebas.codigo(pedido);
        return procesar(remitente, ComandoDispatcher.COMANDO_CONFIRMAR + "[\"" + codigo + "\"]",
                ComandoDispatcher.COMANDO_CONFIRMAR, List.of(codigo));
    }

    /** Procesa un comando sin confirmación (por ejemplo, un comando de prueba que no modifica datos). */
    public Procesado procesarDirecto(String remitente, String comando, String... valores) {
        return procesar(remitente, comando + "[...]", comando, List.of(valores));
    }

    private Procesado procesar(String remitente, String asunto, String comando, List<String> valores) {
        String uidl = "avisos-" + UUID.randomUUID();
        assertTrue(mensajeria.procesar(SolicitudCorreo.conComando(BUZON, uidl, "<" + uidl + "@feder.example>", remitente,
                asunto, comando, valores), GENERADOR), "El correo debía procesarse");
        List<Saliente> respuestas = new ArrayList<>();
        List<Saliente> avisos = new ArrayList<>();
        leerSalientes(uidl, respuestas, avisos);
        assertEquals(1, respuestas.size(), "Cada correo procesado tiene exactamente una respuesta al remitente");
        return new Procesado(respuestas.get(0), avisos);
    }

    /** La respuesta lleva en_respuesta_a (el Message-ID recibido); los avisos a terceros no. */
    private static void leerSalientes(String uidl, List<Saliente> respuestas, List<Saliente> avisos) {
        String sql = "SELECT cs.destinatario, cs.asunto, cs.cuerpo_texto, cs.en_respuesta_a FROM correo_saliente cs "
                + "JOIN mensaje_correo m ON m.id = cs.mensaje_correo_id WHERE m.buzon = ? AND m.uidl = ? ORDER BY cs.id";
        try (PreparedStatement s = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            s.setString(1, BUZON);
            s.setString(2, uidl);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Saliente saliente = new Saliente(r.getString(1), r.getString(2), r.getString(3));
                    (r.getString(4) == null ? avisos : respuestas).add(saliente);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo leer la bandeja de salida", e);
        }
    }
}
