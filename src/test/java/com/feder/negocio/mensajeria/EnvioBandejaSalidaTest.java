package com.feder.negocio.mensajeria;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.datos.mensajeria.MensajeCorreoRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.modelo.CanalNotificacion;
import com.feder.modelo.EstadoNotificacion;
import com.feder.modelo.Notificacion;
import com.feder.modelo.TipoNotificacion;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Envío de la bandeja de salida: el intento se cuenta antes de hablar con el servidor SMTP y la notificación
 * del canal CORREO que entrega el envío refleja su resultado (R045). Cada prueba se revierte al terminar.
 */
class EnvioBandejaSalidaTest {

    private static final int MAXIMO_INTENTOS = 24;

    private static ServicioMensajeria servicio(ContextoNegocio contexto) {
        return servicio(contexto, Clock.systemUTC());
    }

    private static ServicioMensajeria servicio(ContextoNegocio contexto, Clock reloj) {
        ServicioConfirmacion confirmaciones = new ServicioConfirmacion(new ConfirmacionRepositorio(), Clock.systemUTC(),
                Duration.ofMinutes(30), 30);
        ComandoDispatcher dispatcher = new ComandoDispatcher(new CatalogoComandos(),
                new ServicioIdentidad(new IdentidadRepositorio()), confirmaciones, contexto.transacciones());
        return new ServicioMensajeria(contexto.transacciones(), new MensajeCorreoRepositorio(),
                new CorreoSalienteRepositorio(), dispatcher, reloj, 30, MAXIMO_INTENTOS);
    }

    /** Deja la bandeja con un único correo pendiente ligado a una notificación CORREO nueva; devuelve ambos ids. */
    private static long[] prepararCorreoConNotificacion() {
        ejecutar("UPDATE correo_saliente SET proximo_intento = now() + interval '1 day' WHERE estado = 'PENDIENTE'");
        Notificacion n = new Notificacion();
        n.setUsuarioId(11L);
        n.setPedidoId(1L);
        n.setTipo(TipoNotificacion.CAMBIO_ESTADO_PEDIDO);
        n.setCanal(CanalNotificacion.CORREO);
        n.setTitulo("Prueba de envío");
        n.setMensaje("Aviso de prueba");
        n.setEstado(EstadoNotificacion.PENDIENTE);
        long notificacion = new NotificacionRepositorio().registrar(n);
        long correo = new CorreoSalienteRepositorio().encolar(null, "daniela.flores@feder.example", "FEDER - Aviso: prueba",
                "texto", null, "<" + UUID.randomUUID() + "@feder.local>", null, null, null, null, notificacion);
        return new long[] {correo, notificacion};
    }

    @Test
    @DisplayName("Un envío aceptado deja el correo ENVIADO con un intento y la notificación ENVIADA con su fecha")
    void envioAceptadoActualizaLaNotificacion() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            AtomicInteger envios = new AtomicInteger();
            assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO,
                    servicio(contexto).enviarSiguientePendiente(correo -> envios.incrementAndGet()));
            assertEquals(1, envios.get());
            assertEquals("ENVIADO", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("1", texto("SELECT intentos::text FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("ENVIADA", texto("SELECT estado FROM notificacion WHERE id = ?", ids[1]));
            assertNotNull(texto("SELECT fecha_envio::text FROM notificacion WHERE id = ?", ids[1]));
        });
    }

    @Test
    @DisplayName("Un fallo transitorio deja la notificación FALLIDA y el reintento posterior la deja ENVIADA")
    void falloTransitorioYReintento() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            ServicioMensajeria mensajeria = servicio(contexto);
            assertEquals(ServicioMensajeria.ResultadoEnvio.FALLO_TRANSITORIO, mensajeria.enviarSiguientePendiente(correo -> {
                throw new EnviadorCorreo.FalloEnvio("servidor no disponible", false, null);
            }));
            assertEquals("PENDIENTE", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("FALLIDA", texto("SELECT estado FROM notificacion WHERE id = ?", ids[1]));
            assertNull(texto("SELECT fecha_envio::text FROM notificacion WHERE id = ?", ids[1]));
            assertEquals("true", texto("SELECT (proximo_intento > now())::text FROM correo_saliente WHERE id = ?", ids[0]));

            ejecutar("UPDATE correo_saliente SET proximo_intento = now() WHERE id = ?", ids[0]);
            assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO, mensajeria.enviarSiguientePendiente(correo -> { }));
            assertEquals("2", texto("SELECT intentos::text FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("ENVIADA", texto("SELECT estado FROM notificacion WHERE id = ?", ids[1]));
        });
    }

    @Test
    @DisplayName("Si el proceso cae después de enviar, el intento ya quedó contado y el correo no se reenvía de inmediato")
    void caidaDespuesDelEnvioDejaElIntentoContado() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            assertThrows(IllegalStateException.class, () -> servicio(contexto).enviarSiguientePendiente(correo -> {
                throw new IllegalStateException("caída simulada después de que el servidor aceptó el mensaje");
            }));
            assertEquals("PENDIENTE", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("1", texto("SELECT intentos::text FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("true", texto("SELECT (proximo_intento > now())::text FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals(ServicioMensajeria.ResultadoEnvio.SIN_PENDIENTES,
                    servicio(contexto).enviarSiguientePendiente(correo -> { }), "no se vuelve a tomar hasta su próximo intento");
        });
    }

    @Test
    @DisplayName("Un correo sin intentos disponibles queda FALLIDO sin volver a enviarse")
    void sinIntentosDisponiblesNoSeReenvia() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            ejecutar("UPDATE correo_saliente SET intentos = ? WHERE id = ?", MAXIMO_INTENTOS, ids[0]);
            AtomicInteger envios = new AtomicInteger();
            assertEquals(ServicioMensajeria.ResultadoEnvio.FALLO_PERMANENTE,
                    servicio(contexto).enviarSiguientePendiente(correo -> envios.incrementAndGet()));
            assertEquals(0, envios.get(), "no se contacta al servidor");
            assertEquals("FALLIDO", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("FALLIDA", texto("SELECT estado FROM notificacion WHERE id = ?", ids[1]));
            assertTrue(texto("SELECT ultimo_error FROM correo_saliente WHERE id = ?", ids[0]).contains("intentos"));
        });
    }

    /** Simula que venció la reserva de un intento que sigue en curso (por ejemplo, un proceso colgado). */
    private static void vencerReserva(long correoId) {
        ejecutar("UPDATE correo_saliente SET proximo_intento = now() WHERE id = ?", correoId);
    }

    @Test
    @DisplayName("El fallo tardío de un intento antiguo no deshace el envío que registró un intento posterior")
    void falloAntiguoNoDeshaceUnEnvioRegistrado() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            ServicioMensajeria mensajeria = servicio(contexto);
            ServicioMensajeria.ResultadoEnvio antiguo = mensajeria.enviarSiguientePendiente(correo -> {
                // Mientras el intento 1 sigue «enviando», su reserva vence y el intento 2 envía con éxito.
                vencerReserva(ids[0]);
                assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO, mensajeria.enviarSiguientePendiente(otro -> { }));
                throw new EnviadorCorreo.FalloEnvio("conexión cortada en el intento antiguo", false, null);
            });
            assertEquals(ServicioMensajeria.ResultadoEnvio.FALLO_TRANSITORIO, antiguo);
            assertEquals("ENVIADO", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]), "el envío registrado se conserva");
            assertNull(texto("SELECT ultimo_error FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("2", texto("SELECT intentos::text FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("ENVIADA", texto("SELECT estado FROM notificacion WHERE id = ?", ids[1]));
        });
    }

    @Test
    @DisplayName("El éxito tardío de un intento antiguo se registra aunque un intento posterior haya fallado")
    void exitoAntiguoSeRegistraTrasUnFalloPosterior() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            ServicioMensajeria mensajeria = servicio(contexto);
            assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO, mensajeria.enviarSiguientePendiente(correo -> {
                vencerReserva(ids[0]);
                assertEquals(ServicioMensajeria.ResultadoEnvio.FALLO_TRANSITORIO, mensajeria.enviarSiguientePendiente(otro -> {
                    throw new EnviadorCorreo.FalloEnvio("servidor no disponible", false, null);
                }));
                // El intento antiguo termina bien: el servidor aceptó el correo.
            }));
            assertEquals("ENVIADO", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]));
            assertNull(texto("SELECT ultimo_error FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("ENVIADA", texto("SELECT estado FROM notificacion WHERE id = ?", ids[1]));
        });
    }

    @Test
    @DisplayName("El fallo tardío de un intento antiguo no pisa la reserva ni el error de un intento más reciente")
    void falloAntiguoNoPisaUnaReservaReciente() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            ServicioMensajeria mensajeria = servicio(contexto);
            String[] proximoTrasIntento2 = new String[1];
            mensajeria.enviarSiguientePendiente(correo -> {
                vencerReserva(ids[0]);
                mensajeria.enviarSiguientePendiente(otro -> {
                    throw new EnviadorCorreo.FalloEnvio("fallo del intento 2", false, null);
                });
                proximoTrasIntento2[0] = texto("SELECT proximo_intento::text FROM correo_saliente WHERE id = ?", ids[0]);
                throw new EnviadorCorreo.FalloEnvio("fallo tardío del intento 1", true, null);
            });
            assertEquals("PENDIENTE", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]),
                    "el fallo permanente del intento antiguo no se aplica");
            assertEquals("fallo del intento 2", texto("SELECT ultimo_error FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals(proximoTrasIntento2[0], texto("SELECT proximo_intento::text FROM correo_saliente WHERE id = ?", ids[0]));
        });
    }

    @Test
    @DisplayName("La reserva dura más que el plazo máximo del envío, para que otro ciclo no lo tome mientras se envía")
    void reservaMasLargaQueElPlazoDeEnvio() {
        assertEquals(3, ServicioMensajeria.minutosDeReserva(1, Duration.ofSeconds(120)));
        assertEquals(12, ServicioMensajeria.minutosDeReserva(1, Duration.ofMinutes(10).plusSeconds(30)));
        assertEquals(60, ServicioMensajeria.minutosDeReserva(8, Duration.ofSeconds(120)), "la espera creciente se mantiene");
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            EnviadorCorreo lento = new EnviadorCorreo() {
                @Override
                public void enviar(CorreoPendiente correo) {
                    assertEquals("true", texto("SELECT (proximo_intento >= now() + interval '10 minutes')::text "
                            + "FROM correo_saliente WHERE id = ?", ids[0]), "reservado por más que el plazo del envío");
                }

                @Override
                public Duration plazoMaximo() {
                    return Duration.ofMinutes(10);
                }
            };
            assertEquals(ServicioMensajeria.ResultadoEnvio.ENVIADO, servicio(contexto).enviarSiguientePendiente(lento));
        });
    }

    @Test
    @DisplayName("Tras reactivar un correo, el fallo tardío de una reserva anterior no pisa la reserva nueva")
    void reactivacionNoConfundeReservas() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            ServicioMensajeria mensajeria = servicio(contexto);
            mensajeria.enviarSiguientePendiente(correo -> {
                // Mientras la primera reserva (intento 1) sigue en curso: vence, el intento 2 falla de forma permanente,
                // el operador reactiva el correo (los intentos vuelven a 0) y una reserva nueva (otra vez intento 1) falla.
                vencerReserva(ids[0]);
                mensajeria.enviarSiguientePendiente(otro -> {
                    throw new EnviadorCorreo.FalloEnvio("rechazo 5xx del intento 2", true, null);
                });
                assertEquals("FALLIDO", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]));
                assertEquals(1, mensajeria.reactivarEnviosFallidos());
                mensajeria.enviarSiguientePendiente(otro -> {
                    throw new EnviadorCorreo.FalloEnvio("fallo de la reserva nueva", false, null);
                });
                throw new EnviadorCorreo.FalloEnvio("fallo tardío de la primera reserva", true, null);
            });
            assertEquals("PENDIENTE", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]),
                    "el fallo permanente de la primera reserva no se aplica a la reserva nueva");
            assertEquals("fallo de la reserva nueva", texto("SELECT ultimo_error FROM correo_saliente WHERE id = ?", ids[0]));
        });
    }

    @Test
    @DisplayName("Si pasó demasiado tiempo entre la reserva y el envío, no se envía (otro ciclo podría tomarlo)")
    void reservaDemasiadoAntiguaNoSeEnvia() {
        // Un reloj que avanza 40 s en cada consulta: entre la reserva y el envío «pasa» más que el margen.
        Clock lento = new Clock() {
            private java.time.Instant ahora = java.time.Instant.now();

            @Override
            public java.time.ZoneId getZone() {
                return java.time.ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zona) {
                return this;
            }

            @Override
            public synchronized java.time.Instant instant() {
                ahora = ahora.plusSeconds(40);
                return ahora;
            }
        };
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            long[] ids = prepararCorreoConNotificacion();
            AtomicInteger envios = new AtomicInteger();
            assertEquals(ServicioMensajeria.ResultadoEnvio.FALLO_TRANSITORIO,
                    servicio(contexto, lento).enviarSiguientePendiente(correo -> envios.incrementAndGet()));
            assertEquals(0, envios.get(), "no se contacta al servidor");
            assertEquals("PENDIENTE", texto("SELECT estado FROM correo_saliente WHERE id = ?", ids[0]));
            assertEquals("1", texto("SELECT intentos::text FROM correo_saliente WHERE id = ?", ids[0]), "el intento quedó contado");
        });
    }

    private static void ejecutar(String sql, Object... parametros) {
        try (PreparedStatement p = preparar(sql, parametros)) {
            p.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String texto(String sql, Object... parametros) {
        try (PreparedStatement p = preparar(sql, parametros); ResultSet r = p.executeQuery()) {
            return r.next() ? r.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedStatement preparar(String sql, Object... parametros) throws SQLException {
        Connection c = GestorTransacciones.conexionActual();
        PreparedStatement p = c.prepareStatement(sql);
        for (int i = 0; i < parametros.length; i++) {
            p.setObject(i + 1, parametros[i]);
        }
        return p;
    }
}
