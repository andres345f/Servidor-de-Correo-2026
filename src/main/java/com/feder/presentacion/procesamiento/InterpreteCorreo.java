package com.feder.presentacion.procesamiento;

import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.mensajeria.SolicitudCorreo;
import com.feder.presentacion.comandos.CommandParser;
import com.feder.presentacion.comandos.ComandoAnalizado;
import com.feder.presentacion.comandos.ErrorSintaxisException;
import com.feder.presentacion.correo.mime.CorreoEntrante;
import com.feder.presentacion.correo.mime.EmailMessageParser;
import com.feder.presentacion.correo.mime.MensajeInvalidoException;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Convierte los bytes de un correo recibido en una {@link SolicitudCorreo} para la capa de negocio:
 * extrae From y Subject, descarta rebotes y respuestas automáticas, rechaza los asuntos que no pudieron
 * decodificarse con certeza (codificación MIME inválida) y analiza la sintaxis del comando.
 */
public class InterpreteCorreo {

    /**
     * Asunto que se registra y se repite en la respuesta cuando no pudo decodificarse: un texto fijo. Así ningún
     * dato de un asunto dudoso (por ejemplo, un código de CONFIRMAR) queda en el registro de mensajes.
     */
    public static final String ASUNTO_NO_DECODIFICABLE = "(asunto con codificación no válida)";

    private static final Logger ERROR = Logger.getLogger("feder.error");

    private final EmailMessageParser analizadorMensajes = new EmailMessageParser();
    private final CommandParser analizadorComandos = new CommandParser();
    private final String buzon;
    private final String direccionSistema;

    public InterpreteCorreo(String buzon, String direccionSistema) {
        this.buzon = buzon;
        this.direccionSistema = direccionSistema;
    }

    public SolicitudCorreo interpretar(String uidl, byte[] crudo) {
        CorreoEntrante correo;
        try {
            correo = analizadorMensajes.analizar(crudo);
        } catch (MensajeInvalidoException e) {
            // Sin cabeceras confiables no sabemos a quién responder: se registra y no se contesta.
            ERROR.warning(() -> "Mensaje con cabeceras inválidas uidl=" + uidl + ": " + e.getMessage());
            return SolicitudCorreo.automatica(buzon, uidl, null, "desconocido", null);
        }
        // Se conserva el asunto tal como llegó para mostrarlo junto al decodificado, salvo si parece un CONFIRMAR
        // (aunque su codificación sea inválida): el original llevaría el código secreto, solo disfrazado.
        String legible = correo.subject() == null ? "" : correo.subject().strip().toUpperCase(Locale.ROOT);
        String original = legible.startsWith(ComandoDispatcher.COMANDO_CONFIRMAR) ? null : correo.subjectOriginal();
        return crearSolicitud(uidl, correo).conAsuntoOriginal(original);
    }

    private SolicitudCorreo crearSolicitud(String uidl, CorreoEntrante correo) {
        String remitente = correo.from();
        boolean decodificado = correo.errorCodificacionAsunto() == null;
        String asunto = !decodificado ? ASUNTO_NO_DECODIFICABLE : correo.subject() == null ? "" : correo.subject();
        // Un correo enviado por el propio sistema (por ejemplo, una respuesta reenviada) nunca se procesa:
        // así una respuesta automática no puede convertirse en un comando nuevo.
        if (correo.esRespuestaAutomatica() || remitente.equalsIgnoreCase(direccionSistema)) {
            return SolicitudCorreo.automatica(buzon, uidl, correo.messageId(), remitente, asunto);
        }
        if (!decodificado) {
            // Nunca se adivinan caracteres: un asunto que no se pudo decodificar con certeza no se ejecuta.
            // Se comprueba después de descartar las respuestas automáticas, para no contestar a otros sistemas.
            return SolicitudCorreo.conErrorCodificacion(buzon, uidl, correo.messageId(), remitente, asunto,
                    "No se pudo decodificar el asunto del correo: " + correo.errorCodificacionAsunto()
                            + ". No se ejecutó ningún comando. Vuelva a enviarlo escrito como texto simple"
                            + " o con una codificación válida (por ejemplo, UTF-8)");
        }
        try {
            ComandoAnalizado comando = analizadorComandos.analizar(asunto);
            return SolicitudCorreo.conComando(buzon, uidl, correo.messageId(), remitente, asunto,
                    comando.nombre(), comando.parametros());
        } catch (ErrorSintaxisException e) {
            return SolicitudCorreo.conErrorSintaxis(buzon, uidl, correo.messageId(), remitente, asunto, e.getMessage());
        }
    }
}
