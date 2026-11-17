package com.feder.presentacion.correo.mime;

import com.feder.presentacion.correo.ValidadorDireccion;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Construye los bytes RFC 5322 de una respuesta automática del sistema.
 *
 * Todo el resultado es ASCII: el asunto y el nombre del remitente se codifican con RFC 2047
 * y los cuerpos van en Base64 UTF-8. Las cabeceras Auto-Submitted y X-Auto-Response-Suppress
 * evitan que otros sistemas automáticos contesten y se forme un bucle.
 */
public final class EmailResponseBuilder {

    private static final String CRLF = "\r\n";
    private static final DateTimeFormatter FORMATO_FECHA =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH);
    private static final Pattern MESSAGE_ID = Pattern.compile("<[\\x21-\\x3B\\x3D\\x3F-\\x7E]{1,250}>");
    private static final Pattern DOMINIO = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Base64.Encoder BASE64_MIME = Base64.getMimeEncoder(76, CRLF.getBytes(StandardCharsets.US_ASCII));

    private final String direccionRemitente;
    private final String nombreRemitente;
    private final String dominioMessageId;
    private final Clock reloj;

    /** Usa el dominio de la dirección del remitente para los Message-ID y el reloj del sistema. */
    public EmailResponseBuilder(String direccionRemitente, String nombreRemitente) {
        this(direccionRemitente, nombreRemitente, dominioDe(direccionRemitente), Clock.systemDefaultZone());
    }

    public EmailResponseBuilder(String direccionRemitente, String nombreRemitente, String dominioMessageId, Clock reloj) {
        this.direccionRemitente = ValidadorDireccion.exigirValida(direccionRemitente, "remitente");
        this.nombreRemitente = CodificadorCabeceras.exigirSinSaltos(nombreRemitente, "From");
        if (dominioMessageId == null || !DOMINIO.matcher(dominioMessageId).matches()) {
            throw new IllegalArgumentException("El dominio para Message-ID no es válido");
        }
        this.dominioMessageId = dominioMessageId;
        this.reloj = Objects.requireNonNull(reloj, "reloj");
    }

    /** Genera un Message-ID único, por si quien llama necesita conocerlo antes de construir el mensaje. */
    public String generarMessageId() {
        return "<" + UUID.randomUUID().toString().replace("-", "") + "." + reloj.millis() + "@" + dominioMessageId + ">";
    }

    public byte[] construir(CorreoSaliente correo) {
        return construir(correo, null);
    }

    /**
     * Construye el mensaje con un archivo adjunto opcional. Con adjunto la estructura es
     * multipart/mixed: primero el cuerpo (texto o texto+HTML) y después el archivo en Base64.
     */
    public byte[] construir(CorreoSaliente correo, AdjuntoCorreo adjunto) {
        Objects.requireNonNull(correo, "correo");
        String destinatario = ValidadorDireccion.exigirValida(correo.destinatario(), "destinatario");
        String asunto = correo.asunto() == null ? "" : correo.asunto();
        String messageId = correo.messageId() == null ? generarMessageId()
                : exigirMessageId(correo.messageId(), "Message-ID");
        String inReplyTo = correo.inReplyTo() == null ? null : exigirMessageId(correo.inReplyTo(), "In-Reply-To");

        StringBuilder m = new StringBuilder(4096);
        cabecera(m, "Date", FORMATO_FECHA.format(ZonedDateTime.now(reloj)));
        cabecera(m, "From", CodificadorCabeceras.buzon(nombreRemitente, direccionRemitente));
        cabecera(m, "To", "<" + destinatario + ">");
        cabecera(m, "Subject", CodificadorCabeceras.textoNoEstructurado(asunto, "Subject"));
        cabecera(m, "Message-ID", messageId);
        if (inReplyTo != null) {
            cabecera(m, "In-Reply-To", inReplyTo);
            cabecera(m, "References", inReplyTo);
        }
        cabecera(m, "MIME-Version", "1.0");
        cabecera(m, "Auto-Submitted", "auto-replied");
        cabecera(m, "X-Auto-Response-Suppress", "All");

        if (adjunto == null) {
            cuerpo(m, correo);
        } else {
            String mixto = nuevoSeparador();
            cabecera(m, "Content-Type", "multipart/mixed;" + CRLF + " boundary=\"" + mixto + "\"");
            m.append(CRLF);
            m.append("--").append(mixto).append(CRLF);
            cuerpo(m, correo);
            m.append("--").append(mixto).append(CRLF);
            cabecera(m, "Content-Type", adjunto.tipoMime() + "; name=\"" + adjunto.nombreArchivo() + "\"");
            cabecera(m, "Content-Disposition", "attachment;" + CRLF + " filename=\"" + adjunto.nombreArchivo() + "\"");
            cabecera(m, "Content-Transfer-Encoding", "base64");
            m.append(CRLF).append(BASE64_MIME.encodeToString(adjunto.contenido())).append(CRLF);
            m.append("--").append(mixto).append("--").append(CRLF);
        }
        return m.toString().getBytes(StandardCharsets.US_ASCII);
    }

    /** Cabeceras de contenido y cuerpo: solo texto, o texto y HTML como alternativas. */
    private static void cuerpo(StringBuilder m, CorreoSaliente correo) {
        String texto = correo.cuerpoTexto() == null ? "" : correo.cuerpoTexto();
        if (correo.cuerpoHtml() == null) {
            cabecerasDeParte(m, "text/plain");
            m.append(CRLF).append(base64(texto));
        } else {
            String separador = nuevoSeparador();
            // Se pliega antes de boundary para no superar los 78 caracteres recomendados por línea.
            cabecera(m, "Content-Type", "multipart/alternative;" + CRLF + " boundary=\"" + separador + "\"");
            m.append(CRLF);
            parte(m, separador, "text/plain", texto);
            parte(m, separador, "text/html", correo.cuerpoHtml());
            m.append("--").append(separador).append("--").append(CRLF);
        }
    }

    /** "=_" no puede aparecer en Base64, así que el separador nunca colisiona con el contenido. */
    private static String nuevoSeparador() {
        return "=_FEDER_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static void parte(StringBuilder m, String separador, String tipo, String contenido) {
        m.append("--").append(separador).append(CRLF);
        cabecerasDeParte(m, tipo);
        m.append(CRLF).append(base64(contenido));
    }

    private static void cabecerasDeParte(StringBuilder m, String tipo) {
        cabecera(m, "Content-Type", tipo + "; charset=UTF-8");
        cabecera(m, "Content-Transfer-Encoding", "base64");
    }

    private static void cabecera(StringBuilder m, String nombre, String valor) {
        m.append(nombre).append(": ").append(valor).append(CRLF);
    }

    /** Base64 en líneas de 76 caracteres; antes se normalizan los saltos a CRLF (forma canónica de text/*). */
    private static String base64(String contenido) {
        String canonico = contenido.replaceAll("\r\n|\r|\n", CRLF);
        String codificado = BASE64_MIME.encodeToString(canonico.getBytes(StandardCharsets.UTF_8));
        return codificado.isEmpty() ? "" : codificado + CRLF;
    }

    private static String exigirMessageId(String valor, String campo) {
        CodificadorCabeceras.exigirSinSaltos(valor, campo);
        if (!MESSAGE_ID.matcher(valor).matches()) {
            throw new IllegalArgumentException("El valor de " + campo + " no tiene el formato <id@dominio>");
        }
        return valor;
    }

    private static String dominioDe(String direccion) {
        ValidadorDireccion.exigirValida(direccion, "remitente");
        return direccion.substring(direccion.indexOf('@') + 1);
    }
}
