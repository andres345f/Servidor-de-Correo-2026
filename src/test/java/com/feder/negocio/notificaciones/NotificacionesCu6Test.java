package com.feder.negocio.notificaciones;

import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.datos.mensajeria.MensajeCorreoRepositorio;
import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.negocio.mensajeria.GeneradorRespuesta;
import com.feder.negocio.mensajeria.ServicioMensajeria;
import com.feder.negocio.mensajeria.SolicitudCorreo;
import com.feder.negocio.pagos.ApoyoCu6;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static com.feder.negocio.pagos.ApoyoCu6.exito;
import static com.feder.negocio.pagos.ApoyoCu6.leer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Notificaciones al cliente (R045, R046): el aviso SISTEMA consultable con LISNOT y, en los eventos importantes,
 * el correo a la dirección registrada del cliente, encolado solo si la operación se confirma.
 */
class NotificacionesCu6Test {

    /** Genera la respuesta como texto y recuerda el último resultado, para leer el código de confirmación. */
    private static final class Generador implements GeneradorRespuesta {
        ResultadoOperacion ultimo;

        @Override
        public Respuesta generar(ResultadoOperacion resultado, String comandoRecibido) {
            if (comandoRecibido != null) {
                ultimo = resultado;
            }
            return new Respuesta("FEDER - " + resultado.titulo(), resultado.mensaje(), null);
        }

        @Override
        public String nuevoMessageId() {
            return "<" + UUID.randomUUID() + "@feder.local>";
        }
    }

    @Test
    @DisplayName("Al verificar un pago por correo, el cliente recibe los avisos por correo y queda constancia en el canal CORREO")
    void avisosPorCorreo() {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ComandoDispatcher dispatcher = new ComandoDispatcher(ApoyoCu45.catalogo(ApoyoCu6.modulos(ctx)),
                    new ServicioIdentidad(new IdentidadRepositorio()),
                    new ServicioConfirmacion(new ConfirmacionRepositorio(), Clock.systemUTC(), Duration.ofMinutes(30), 100),
                    ctx.transacciones());
            ServicioMensajeria mensajeria = new ServicioMensajeria(ctx.transacciones(), new MensajeCorreoRepositorio(),
                    new CorreoSalienteRepositorio(), dispatcher, Clock.systemUTC(), 30, 24);
            Generador generador = new Generador();
            List<String> valores = List.of("pago-ped0011-01", "QR-20261115-000245", "143.00");
            assertTrue(mensajeria.procesar(correo("CONPAG", valores), generador));
            String asunto = detalle(generador.ultimo, "Asunto para confirmar");
            String codigo = asunto.substring(asunto.indexOf("[\"") + 2, asunto.indexOf("\"]"));
            assertTrue(mensajeria.procesar(correo("CONFIRMAR", List.of(codigo)), generador));
            assertTrue(generador.ultimo.esExito(), generador.ultimo.mensaje());
            assertEquals(1, numero("SELECT count(*) FROM correo_saliente WHERE destinatario = 'andres.molina@feder.example' "
                    + "AND asunto = 'FEDER - Pago confirmado'"));
            assertEquals(1, numero("SELECT count(*) FROM correo_saliente WHERE destinatario = 'andres.molina@feder.example' "
                    + "AND asunto = 'FEDER - Pedido confirmado'"));
            assertEquals(2, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 14 AND pedido_id = 11 AND canal = 'CORREO' "
                    + "AND estado = 'PENDIENTE'"));
            assertEquals(2, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 14 AND pedido_id = 11 AND canal = 'SISTEMA' "
                    + "AND tipo IN ('PAGO_CONFIRMADO', 'CAMBIO_ESTADO_PEDIDO') AND fecha_creacion > now() - interval '1 hour'"));
        });
    }

    @Test
    @DisplayName("LISNOT muestra solo las notificaciones propias y LEENOT no marca las ajenas")
    void consultaYLectura() {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        ComandoDispatcher d = ApoyoCu6.despachador(ctx);
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion propias = leer(d, ANDRES, "LISNOT", "*");
            assertEquals(numero("SELECT count(*) FROM notificacion WHERE usuario_id = 14"), propias.tablas().get(0).filas().size());
            assertEquals("No encontrado", ApoyoCu6.escribir(d, ELMER, "LEENOT", "21").titulo());
            assertEquals(0, numero("SELECT count(*) FROM notificacion WHERE id = 21 AND fecha_lectura IS NOT NULL"));
            exito(d, ANDRES, "LEENOT", "21");
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE id = 21 AND fecha_lectura IS NOT NULL"));
            assertEquals(0, leer(d, ANDRES, "LISNOT", "true").tablas().get(0).filas().stream()
                    .filter(f -> f.get(0).equals("21")).count());
            exito(d, DANIELA, "LEENOT", "*");
            assertEquals(0, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 11 AND fecha_lectura IS NULL"));
        });
    }

    private static SolicitudCorreo correo(String comando, List<String> valores) {
        String uidl = "cu6-" + UUID.randomUUID();
        return SolicitudCorreo.conComando("pruebas@local:0", uidl, "<" + uidl + "@feder.example>", CARLA,
                comando + "[...]", comando, valores);
    }
}
