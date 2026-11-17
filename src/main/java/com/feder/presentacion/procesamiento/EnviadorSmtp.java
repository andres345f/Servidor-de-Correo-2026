package com.feder.presentacion.procesamiento;

import com.feder.negocio.mensajeria.EnviadorCorreo;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.mime.AdjuntoCorreo;
import com.feder.presentacion.correo.mime.CorreoSaliente;
import com.feder.presentacion.correo.mime.EmailResponseBuilder;
import com.feder.presentacion.correo.smtp.SmtpClient;
import com.feder.presentacion.correo.smtp.SmtpPermanenteException;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Envía las respuestas por SMTP reutilizando una sola conexión durante el ciclo.
 *
 * Distingue dos tipos de fallo:
 *   - de la SESIÓN (no se pudo conectar, TLS o autenticación fallida, conexión cortada):
 *     el correo queda pendiente y se reintenta; la conexión se descarta.
 *   - del MENSAJE (el servidor rechaza ese correo con 5xx tras aceptar la sesión): fallo permanente.
 * Cada envío tiene un plazo máximo: si se supera, se cierra el socket. Así un servidor que deja de
 * leer no mantiene abierta indefinidamente la transacción (y sus bloqueos) de la bandeja de salida.
 */
public class EnviadorSmtp implements EnviadorCorreo, AutoCloseable {

    private static final Logger ERROR = Logger.getLogger("feder.error");

    private final ConfiguracionCorreo configuracion;
    private final EmailResponseBuilder constructor;
    private final Duration plazoPorEnvio;
    private final ScheduledExecutorService vigilante;
    private SmtpClient conexion;
    /** Conexión que se está usando (o abriendo) en este momento; el vigilante del plazo la cierra si se excede. */
    private volatile SmtpClient enUso;
    /** El plazo del envío en curso ya venció: una conexión creada después se cierra antes de abrirse. */
    private volatile boolean vencido;

    public EnviadorSmtp(ConfiguracionCorreo configuracion, EmailResponseBuilder constructor, Duration plazoPorEnvio) {
        this.configuracion = configuracion;
        this.constructor = constructor;
        this.plazoPorEnvio = plazoPorEnvio;
        this.vigilante = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread hilo = new Thread(tarea, "plazo-envio-smtp");
            hilo.setDaemon(true);
            return hilo;
        });
    }

    /** El vigilante corta el envío al cumplirse este plazo: la bandeja de salida reserva el correo durante más tiempo. */
    @Override
    public Duration plazoMaximo() {
        return plazoPorEnvio;
    }

    @Override
    public void enviar(CorreoPendiente correo) throws FalloEnvio {
        byte[] mensaje = construir(correo);
        // El plazo cubre la apertura de la sesión (EHLO, TLS, AUTH) y el envío completo.
        vencido = false;
        ScheduledFuture<?> plazo = vigilante.schedule(() -> {
            vencido = true;
            cerrarSilenciosamente(enUso);
        }, plazoPorEnvio.toMillis(), TimeUnit.MILLISECONDS);
        try {
            try {
                conectarSiHaceFalta();
            } catch (CorreoException | RuntimeException e) {
                // Fallo de la SESIÓN (conexión, TLS o AUTH, incluido un 535): el correo se reintenta.
                descartarConexion();
                throw new FalloEnvio("No se pudo abrir la sesión SMTP: " + e.getMessage(), false, e);
            }
            // El sobre (MAIL FROM) es el buzón del sistema; el destinatario es la cuenta que pidió la operación.
            conexion.enviar(configuracion.direccionSistema(), correo.destinatario, mensaje);
        } catch (SmtpPermanenteException e) {
            // El servidor rechazó este mensaje en particular; la conexión sigue siendo válida.
            throw new FalloEnvio(e.getMessage(), true, e);
        } catch (CorreoException | RuntimeException e) {
            // Incluye el caso en que el vigilante cerró el socket a mitad de un intercambio.
            descartarConexion();
            throw new FalloEnvio("Fallo de la sesión SMTP: " + e.getMessage(), false, e);
        } finally {
            plazo.cancel(false);
        }
    }

    private byte[] construir(CorreoPendiente correo) throws FalloEnvio {
        try {
            AdjuntoCorreo adjunto = correo.tieneAdjunto()
                    ? new AdjuntoCorreo(correo.adjuntoNombre, correo.adjuntoTipo, correo.adjuntoContenido)
                    : null;
            return constructor.construir(new CorreoSaliente(correo.destinatario, correo.asunto,
                    correo.cuerpoTexto, correo.cuerpoHtml, correo.messageId, correo.enRespuestaA), adjunto);
        } catch (IllegalArgumentException e) {
            // Dirección o cabecera inválida: reintentar no la corregiría.
            throw new FalloEnvio("Datos de correo inválidos: " + e.getMessage(), true, e);
        }
    }

    /** Cualquier fallo al abrir la sesión (incluida la autenticación) es de la sesión: se reintenta. */
    private void conectarSiHaceFalta() throws CorreoException {
        if (conexion != null) {
            return;
        }
        SmtpClient nueva = new SmtpClient(configuracion.smtp(), configuracion.mecanismoSmtp(), configuracion.dominio());
        enUso = nueva;
        if (vencido) {
            // El vigilante actuó antes de que esta conexión existiera: se cierra para que conectar() no la abra.
            cerrarSilenciosamente(nueva);
        }
        nueva.conectar();
        conexion = nueva;
    }

    private void descartarConexion() {
        cerrarSilenciosamente(enUso);
        cerrarSilenciosamente(conexion);
        conexion = null;
        enUso = null;
    }

    private static void cerrarSilenciosamente(SmtpClient cliente) {
        if (cliente == null) {
            return;
        }
        try {
            cliente.close();
        } catch (CorreoException | RuntimeException e) {
            ERROR.fine(() -> "No se pudo cerrar la conexión SMTP: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (conexion != null) {
            // QUIT también tiene plazo: un servidor que no responde no debe retener el cierre del ciclo.
            SmtpClient saliente = conexion;
            ScheduledFuture<?> plazo = vigilante.schedule(() -> cerrarSilenciosamente(saliente),
                    plazoPorEnvio.toMillis(), TimeUnit.MILLISECONDS);
            try {
                saliente.quit();
            } catch (CorreoException | RuntimeException e) {
                ERROR.fine(() -> "QUIT SMTP no confirmado: " + e.getMessage());
            } finally {
                plazo.cancel(false);
            }
        }
        descartarConexion();
        vigilante.shutdownNow();
    }
}
