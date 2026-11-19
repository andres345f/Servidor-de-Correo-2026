package com.feder.negocio.mensajeria;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.GestorTransacciones;
import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.datos.mensajeria.MensajeCorreoRepositorio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comun.ResultadoOperacion;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orquesta, en la capa de negocio, el tratamiento de un correo recibido.
 *
 * Todo ocurre en UNA transacción: reclamar el mensaje (buzón + UIDL), ejecutar el comando,
 * guardar la respuesta en la bandeja de salida y cerrar el registro. Si PostgreSQL falla, no
 * queda nada a medias y el mensaje se volverá a intentar en el ciclo siguiente.
 *
 * Garantía real: cada correo recibido aplica su operación como máximo una vez mientras exista
 * su registro. Si el usuario reenvía el mismo comando en otro correo, es una petición nueva
 * (por eso las escrituras exigen un código de confirmación de un solo uso).
 */
public class ServicioMensajeria {

    private static final Logger ACCESO = Logger.getLogger("feder.access");
    private static final Logger ERROR = Logger.getLogger("feder.error");
    /** Salida legible por pantalla (ver ConfiguradorLogs). */
    private static final Logger CONSOLA = Logger.getLogger("feder.consola");

    private final GestorTransacciones transacciones;
    private final MensajeCorreoRepositorio mensajes;
    private final CorreoSalienteRepositorio salientes;
    private final ComandoDispatcher dispatcher;
    private final Clock reloj;
    private final int limitePorHora;
    private final int maximoIntentosEnvio;

    public ServicioMensajeria(GestorTransacciones transacciones, MensajeCorreoRepositorio mensajes,
                              CorreoSalienteRepositorio salientes, ComandoDispatcher dispatcher, Clock reloj,
                              int limitePorHora, int maximoIntentosEnvio) {
        this.transacciones = transacciones;
        this.mensajes = mensajes;
        this.salientes = salientes;
        this.dispatcher = dispatcher;
        this.reloj = reloj;
        this.limitePorHora = limitePorHora;
        this.maximoIntentosEnvio = maximoIntentosEnvio;
    }

    /** Agrega el punto final a una frase que no termina en signo de puntuación. */
    static String conPuntoFinal(String frase) {
        String texto = frase == null ? "" : frase.strip();
        return texto.isEmpty() || ".?!".indexOf(texto.charAt(texto.length() - 1)) >= 0 ? texto : texto + ".";
    }

    /** UIDL ya registrados de un buzón: la presentación los usa para no descargar de nuevo esos mensajes. */
    public Set<String> uidlsRegistrados(String buzon) {
        return transacciones.enTransaccion(() -> mensajes.uidlsRegistrados(buzon));
    }

    /** Procesa un correo. Devuelve false si ya había sido procesado (duplicado). */
    public boolean procesar(SolicitudCorreo solicitud, GeneradorRespuesta generador) {
        long inicio = System.nanoTime();
        // El código de un CONFIRMAR es un secreto de un solo uso: no lo guardamos en el registro
        // de mensajes ni lo repetimos en la respuesta.
        // También se enmascara si el CONFIRMAR viene mal escrito (sin comando reconocido).
        boolean esConfirmar = ComandoDispatcher.COMANDO_CONFIRMAR.equalsIgnoreCase(solicitud.comando)
                || (solicitud.asunto != null && solicitud.asunto.strip().toUpperCase(java.util.Locale.ROOT)
                        .startsWith(ComandoDispatcher.COMANDO_CONFIRMAR));
        String asuntoRegistrable = esConfirmar ? ComandoDispatcher.COMANDO_CONFIRMAR + "[\"********\"]" : solicitud.asunto;
        return transacciones.enTransaccion(() -> {
            // Serializa las decisiones de cuota de un mismo remitente (dos procesos no cuentan a la vez).
            mensajes.bloquearRemitente(solicitud.remitente);
            Optional<Long> reclamado = mensajes.reclamar(solicitud.buzon, solicitud.uidl, solicitud.messageId,
                    solicitud.remitente, asuntoRegistrable);
            if (reclamado.isEmpty()) {
                ACCESO.info(() -> "DUPLICADO uidl=" + solicitud.uidl + " remitente=" + solicitud.remitente);
                CONSOLA.info(() -> "Correo repetido de " + solicitud.remitente + ": ya se había procesado, no se responde de nuevo.");
                return false;
            }
            long mensajeId = reclamado.get();
            if (solicitud.respuestaAutomatica) {
                // Nunca contestamos rebotes ni respuestas automáticas: evitaría bucles de correo.
                mensajes.finalizar(mensajeId, "IGNORADO", null, null, "Respuesta automática o aviso de entrega");
                ACCESO.info(() -> "IGNORADO automatico uidl=" + solicitud.uidl);
                CONSOLA.info(() -> "Correo automático de " + solicitud.remitente + " ignorado (rebote o aviso de entrega).");
                return true;
            }
            int recibidos = mensajes.contarDesde(solicitud.remitente, OffsetDateTime.now(reloj).minusHours(1));
            if (recibidos > limitePorHora * 2) {
                // Muy por encima del límite: registramos y no respondemos, para no amplificar abusos.
                mensajes.finalizar(mensajeId, "IGNORADO", null, null, "Límite de solicitudes ampliamente superado");
                return true;
            }
            Resolucion resolucion;
            List<AvisosPendientes.Aviso> avisos;
            AvisosPendientes.iniciar();
            try {
                resolucion = resolver(solicitud, recibidos > limitePorHora);
            } finally {
                avisos = AvisosPendientes.terminar();
            }
            GeneradorRespuesta.Respuesta respuesta = generador.generar(resolucion.resultado, asuntoRegistrable);
            salientes.encolar(mensajeId, resolucion.destinatario, respuesta.asunto, respuesta.cuerpoTexto,
                    respuesta.cuerpoHtml, generador.nuevoMessageId(), solicitud.messageId,
                    respuesta.adjuntoNombre, respuesta.adjuntoTipo, respuesta.adjuntoContenido, null);
            // Avisos a terceros generados por la operación (invitaciones, decisiones...): misma transacción.
            for (AvisosPendientes.Aviso aviso : avisos) {
                GeneradorRespuesta.Respuesta formato = generador.generarAviso(aviso.contenido);
                salientes.encolar(mensajeId, aviso.destinatario, formato.asunto, formato.cuerpoTexto, formato.cuerpoHtml,
                        generador.nuevoMessageId(), null, null, null, null, aviso.notificacionId);
            }
            String tipo = resolucion.esConfirmacion ? "CONFIRMACION" : (resolucion.resultado.esExito() ? "EXITO" : "ERROR");
            mensajes.finalizar(mensajeId, "PROCESADO", tipo, resolucion.comando, resolucion.resultado.titulo());
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            // Asunto recibido (con el código de CONFIRMAR ya enmascarado) y, si falló, el motivo exacto.
            String asunto = enUnaLinea(asuntoRegistrable);
            // Asunto tal como llegó (p. ej. "=?UTF-8?B?...?="), solo si es distinto del decodificado.
            // Nunca el de un CONFIRMAR: aunque venga codificado, lleva dentro el código secreto.
            String crudo = esConfirmar ? "" : enUnaLinea(solicitud.asuntoOriginal);
            String asuntoOriginal = crudo.isEmpty() || crudo.equals(asunto) ? null : crudo;
            String motivo = "ERROR".equals(tipo)
                    ? enUnaLinea(resolucion.resultado.titulo() + ": " + resolucion.resultado.mensaje()) : null;
            ACCESO.info(() -> String.format("PROCESADO uidl=%s remitente=%s comando=%s resultado=%s duracion_ms=%d asunto=\"%s\"%s%s",
                    solicitud.uidl, solicitud.remitente, resolucion.comando, tipo, ms, asunto,
                    asuntoOriginal == null ? "" : " asunto_original=\"" + asuntoOriginal + "\"",
                    motivo == null ? "" : " motivo=\"" + motivo + "\""));
            CONSOLA.info(() -> bloqueRecibido(solicitud.remitente, asunto, asuntoOriginal, resolucion, tipo, motivo));
            return true;
        });
    }

    /**
     * Bloque legible para la pantalla: quién escribió, qué asunto llegó y cómo terminó.
     * Si el asunto llegó codificado, "Asunto" lo muestra tal como vino y "Descripción" lo que dice.
     */
    private static String bloqueRecibido(String remitente, String asunto, String asuntoOriginal, Resolucion resolucion,
                                         String tipo, String motivo) {
        String legible = asunto.isEmpty() ? "(vacío)" : asunto;
        StringBuilder bloque = new StringBuilder("CORREO RECIBIDO")
                .append("\nDe:           ").append(remitente);
        if (asuntoOriginal != null) {
            bloque.append("\nAsunto:       ").append(asuntoOriginal)
                    .append("\nDescripción:  ").append(legible);
        } else {
            bloque.append("\nAsunto:       ").append(legible);
        }
        bloque.append("\nComando:      ").append(resolucion.comando == null ? "(no reconocido)" : resolucion.comando)
                .append("\nResultado:    ");
        switch (tipo) {
            case "EXITO":
                bloque.append("ÉXITO - ").append(resolucion.resultado.titulo());
                break;
            case "CONFIRMACION":
                bloque.append("ESPERA CONFIRMACIÓN - ").append(resolucion.resultado.titulo());
                break;
            default:
                bloque.append("ERROR").append("\nMotivo:       ").append(motivo);
                // Si el error fue de parámetros, el resultado trae la forma correcta del comando (ComandoDispatcher).
                for (String[] detalle : resolucion.resultado.detalles()) {
                    if ("Forma correcta".equals(detalle[0])) {
                        bloque.append("\nFormato:      ").append(detalle[1]);
                    }
                }
        }
        return bloque.toString();
    }

    /**
     * Une en una sola línea un texto que llega del correo, para que no pueda partir el log en varias.
     * Un salto de línea con su sangría (cabecera plegada) queda como un solo espacio, y un tabulador también:
     * Gmail pliega los asuntos largos con un tabulador, que al desplegar la cabecera queda entre las palabras.
     */
    static String enUnaLinea(String texto) {
        return texto == null ? "" : texto.replaceAll("[\\r\\n]+[ \\t]*", " ").replace('\t', ' ').strip();
    }

    private static final class Resolucion {
        final ResultadoOperacion resultado;
        final String destinatario;
        final String comando;
        final boolean esConfirmacion;

        Resolucion(ResultadoOperacion resultado, String destinatario, String comando) {
            this.resultado = resultado;
            this.destinatario = destinatario;
            this.comando = comando;
            this.esConfirmacion = resultado.esConfirmacionPendiente();
        }
    }

    private Resolucion resolver(SolicitudCorreo solicitud, boolean limiteSuperado) {
        if (limiteSuperado) {
            return new Resolucion(ResultadoOperacion.error("Límite de solicitudes",
                    "Se superó el máximo de " + limitePorHora + " correos por hora. Intente más tarde."),
                    solicitud.remitente, null);
        }
        if (solicitud.errorCodificacion != null) {
            // El asunto no pudo decodificarse con certeza: no se ejecuta nada, solo se explica el motivo.
            return new Resolucion(ResultadoOperacion.error("Asunto con codificación no válida",
                    conPuntoFinal(solicitud.errorCodificacion)), solicitud.remitente, null);
        }
        if (solicitud.comando == null) {
            ResultadoOperacion error = ResultadoOperacion.error("Sintaxis inválida",
                    conPuntoFinal(solicitud.errorSintaxis)
                            + " Formato esperado: COMANDO[\"param1\",\"param2\"]. Envíe HELP[\"*\"] para ver los comandos.");
            // Qué se esperaba (B2): la forma del comando que se quiso escribir, o la forma general.
            Optional<ComandoSpec> buscado = dispatcher.comandoLlamado(ComandoDispatcher.nombreEscrito(solicitud.asunto));
            if (buscado.isPresent()) {
                ComandoDispatcher.conFormaCorrecta(error, buscado.get());
            } else {
                error.detalle("Forma correcta", "COMANDO[\"dato1\",\"dato2\"]")
                        .detalle("Ejemplo para copiar en el asunto", "HELP[\"*\"]");
            }
            return new Resolucion(error, solicitud.remitente, null);
        }
        try {
            // Punto de guardado externo: un error inesperado no debe abortar la transacción
            // que también guarda la respuesta y el registro del mensaje.
            ComandoDispatcher.Despacho despacho = transacciones.conPuntoDeGuardado(
                    () -> dispatcher.despachar(solicitud.comando, solicitud.parametros, solicitud.remitente));
            return new Resolucion(despacho.resultado, despacho.destinatario, despacho.comando);
        } catch (AccesoDatosException e) {
            if (e.esTransitoria()) {
                // Se propaga: se revierte todo y el mensaje NO queda registrado, así se reintenta.
                throw e;
            }
            return errorInterno(solicitud, e);
        } catch (RuntimeException e) {
            // Un defecto de programación no transitorio se responde como error interno y se registra,
            // para que un mensaje problemático no se reintente indefinidamente bloqueando el buzón.
            return errorInterno(solicitud, e);
        }
    }

    private Resolucion errorInterno(SolicitudCorreo solicitud, RuntimeException e) {
        ERROR.log(Level.SEVERE, "Fallo inesperado procesando uidl=" + solicitud.uidl + " comando=" + solicitud.comando, e);
        return new Resolucion(ResultadoOperacion.error("Error interno",
                "No se pudo completar la operación por un problema técnico. No se modificó la base de datos."),
                solicitud.remitente, solicitud.comando);
    }

    // ---------------- Bandeja de salida ----------------

    /**
     * Devuelve a PENDIENTE las respuestas que quedaron FALLIDO (por ejemplo, tras corregir la
     * contraseña SMTP o cuando el servidor estuvo caído mucho tiempo). Es una acción explícita
     * del operador: así ninguna respuesta se abandona en silencio.
     */
    public int reactivarEnviosFallidos() {
        return transacciones.enTransaccion(salientes::reactivarFallidos);
    }

    /** Resultado de intentar enviar la siguiente respuesta pendiente. */
    public enum ResultadoEnvio {
        SIN_PENDIENTES,
        ENVIADO,
        FALLO_PERMANENTE,
        FALLO_TRANSITORIO
    }

    /**
     * Envía la siguiente respuesta pendiente en tres pasos:
     *   1. Una transacción corta toma la fila (SKIP LOCKED), CUENTA el intento y la reserva durante más tiempo que
     *      el plazo máximo del envío; se confirma ANTES de hablar con el servidor SMTP.
     *   2. El envío, sin ninguna transacción ni bloqueo abiertos.
     *   3. Otra transacción registra el resultado. Un envío aceptado se registra siempre (el correo salió). Un fallo
     *      solo se registra si el correo sigue PENDIENTE con el mismo número de intento: así un intento antiguo
     *      nunca deshace un envío ya registrado ni pisa la reserva de un intento más reciente.
     *
     * Garantía real: «al menos una vez», no «exactamente una vez». SMTP no permite saber si el servidor aceptó el
     * mensaje cuando la conexión se corta después de enviar los datos y antes de su respuesta, ni cuando el proceso
     * cae entre la aceptación y el paso 3. En esos casos el correo se reenvía en un intento posterior, con el mismo
     * Message-ID (los clientes de correo pueden reconocer el duplicado); la operación de negocio nunca se repite y
     * los reenvíos quedan limitados por procesamiento.max.intentos.envio.
     */
    public ResultadoEnvio enviarSiguientePendiente(EnviadorCorreo enviador) {
        Reserva reserva = transacciones.enTransaccion(() -> reservarSiguiente(enviador.plazoMaximo()));
        if (reserva == null) {
            return ResultadoEnvio.SIN_PENDIENTES;
        }
        if (reserva.agotada) {
            return ResultadoEnvio.FALLO_PERMANENTE;
        }
        CorreoSalienteRepositorio.Saliente saliente = reserva.saliente;
        // La reserva deja un minuto de margen sobre el plazo del envío. Si entre la reserva y este punto pasó más de
        // medio minuto (por ejemplo, el proceso quedó suspendido), otro ciclo podría tomar el correo mientras este lo
        // envía: no se envía y la reserva vence sola; el intento ya quedó contado.
        if (Duration.between(reserva.reservadoEn, reloj.instant()).compareTo(MARGEN_ANTES_DE_ENVIAR) > 0) {
            ERROR.warning(() -> "Envío id=" + saliente.id + " no iniciado: pasó demasiado tiempo desde su reserva");
            return ResultadoEnvio.FALLO_TRANSITORIO;
        }
        try {
            enviador.enviar(new EnviadorCorreo.CorreoPendiente(saliente.destinatario, saliente.asunto,
                    saliente.cuerpoTexto, saliente.cuerpoHtml, saliente.messageId, saliente.enRespuestaA,
                    saliente.adjuntoNombre, saliente.adjuntoTipo, saliente.adjuntoContenido));
        } catch (EnviadorCorreo.FalloEnvio e) {
            transacciones.enTransaccion(() -> registrarFalloEnvio(saliente, reserva, e.getMessage(), e.esPermanente()));
            return e.esPermanente() ? ResultadoEnvio.FALLO_PERMANENTE : ResultadoEnvio.FALLO_TRANSITORIO;
        }
        boolean registrado = transacciones.enTransaccion(() -> salientes.marcarEnviado(saliente.id));
        if (registrado) {
            ACCESO.info(() -> "ENVIADO id=" + saliente.id + " destinatario=" + saliente.destinatario);
            CONSOLA.info(() -> "RESPUESTA ENVIADA a " + saliente.destinatario);
        } else {
            // Otro intento ya lo había registrado como enviado: el destinatario pudo recibirlo dos veces.
            ACCESO.info(() -> "ENVIADO id=" + saliente.id + " (ya registrado por otro intento; posible duplicado)");
        }
        return ResultadoEnvio.ENVIADO;
    }

    /**
     * Correo tomado para enviar. intento es el número de intento que se reservó (el que debe coincidir al
     * registrar un fallo); agotada indica que ya no le quedaban intentos y no se envía.
     */
    private static final class Reserva {
        final CorreoSalienteRepositorio.Saliente saliente;
        final int intento;
        /** Hasta cuándo quedó reservado (en la base): con el intento identifica esta reserva al registrar un fallo. */
        final OffsetDateTime reservadoHasta;
        /** Cuándo se confirmó la reserva (reloj de la aplicación), para no empezar un envío demasiado tarde. */
        final java.time.Instant reservadoEn;
        final boolean agotada;

        Reserva(CorreoSalienteRepositorio.Saliente saliente, int intento, OffsetDateTime reservadoHasta,
                java.time.Instant reservadoEn, boolean agotada) {
            this.saliente = saliente;
            this.intento = intento;
            this.reservadoHasta = reservadoHasta;
            this.reservadoEn = reservadoEn;
            this.agotada = agotada;
        }
    }

    /** Tiempo máximo entre confirmar la reserva y empezar el envío (la reserva deja un minuto de margen). */
    static final Duration MARGEN_ANTES_DE_ENVIAR = Duration.ofSeconds(30);

    private Reserva reservarSiguiente(Duration plazoEnvio) {
        List<CorreoSalienteRepositorio.Saliente> pendientes = salientes.tomarPendientes(1);
        if (pendientes.isEmpty()) {
            return null;
        }
        CorreoSalienteRepositorio.Saliente saliente = pendientes.get(0);
        if (saliente.intentos >= maximoIntentosEnvio) {
            // Solo ocurre si los intentos anteriores no llegaron a registrar su resultado (por ejemplo, el proceso
            // cayó después del envío): se deja de reenviar y el operador decide con reintentar-envios.
            salientes.marcarAgotado(saliente.id, "Se agotaron los intentos de envío sin registrar su resultado");
            ERROR.warning(() -> "Envío id=" + saliente.id + " sin intentos disponibles: queda FALLIDO");
            return new Reserva(saliente, saliente.intentos, null, reloj.instant(), true);
        }
        int intento = saliente.intentos + 1;
        Optional<OffsetDateTime> reservadoHasta = salientes.reservarIntento(saliente.id, saliente.intentos,
                minutosDeReserva(intento, plazoEnvio));
        if (reservadoHasta.isEmpty()) {
            return null; // La fila cambió entre la lectura y la reserva (no ocurre con el bloqueo, pero no se arriesga).
        }
        return new Reserva(saliente, intento, reservadoHasta.get(), reloj.instant(), false);
    }

    /**
     * Tiempo durante el cual el correo reservado no vuelve a estar disponible: la espera habitual del reintento,
     * y nunca menos que el plazo máximo del envío más un minuto, para que dos ciclos no lo envíen a la vez.
     */
    static long minutosDeReserva(int intento, Duration plazoEnvio) {
        long minutosPlazo = (plazoEnvio.toSeconds() + 59) / 60 + 1;
        return Math.max(espera(intento).toMinutes(), minutosPlazo);
    }

    private static Duration espera(int intentos) {
        return Duration.ofMinutes(Math.min(60, 1L << Math.min(intentos, 6)));
    }

    /**
     * Los errores permanentes (5xx) o el exceso de intentos dejan el correo como FALLIDO;
     * los transitorios se reintentan con espera creciente (2, 4, 8... hasta 60 minutos).
     * Si el correo ya fue registrado como enviado o lo reservó un intento más reciente, el fallo no cambia nada.
     */
    private void registrarFalloEnvio(CorreoSalienteRepositorio.Saliente saliente, Reserva reserva, String error, boolean permanente) {
        int intento = reserva.intento;
        boolean definitivo = permanente || intento >= maximoIntentosEnvio;
        boolean registrado = salientes.registrarFallo(saliente.id, intento, reserva.reservadoHasta, error, definitivo,
                OffsetDateTime.now(reloj).plus(espera(intento)));
        if (registrado) {
            ERROR.warning(() -> "Envío fallido id=" + saliente.id + " a " + saliente.destinatario
                    + " (intento " + intento + " de " + maximoIntentosEnvio + "): " + error
                    + (definitivo ? ". Queda FALLIDO; no se reintenta (reintentar-envios lo vuelve a poner en cola)."
                            : ". Queda en la bandeja de salida y se reintentará."));
        } else {
            ERROR.warning(() -> "Envío fallido id=" + saliente.id + " intento=" + intento
                    + " ignorado: el correo ya estaba enviado o lo tomó un intento posterior");
        }
    }
}
