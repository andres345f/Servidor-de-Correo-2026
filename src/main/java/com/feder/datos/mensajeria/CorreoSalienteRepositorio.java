package com.feder.datos.mensajeria;

import com.feder.datos.RepositorioJdbc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Bandeja de salida persistente. Las respuestas se guardan en la misma transacción que la
 * operación de negocio y se envían después; si el SMTP falla, quedan PENDIENTE para reintentar.
 */
public class CorreoSalienteRepositorio extends RepositorioJdbc {

    /** Respuesta lista para enviar. */
    public static final class Saliente {
        public final long id;
        public final String destinatario;
        public final String asunto;
        public final String cuerpoTexto;
        public final String cuerpoHtml;
        public final String messageId;
        public final String enRespuestaA;
        public final int intentos;
        public final String adjuntoNombre;
        public final String adjuntoTipo;
        public final byte[] adjuntoContenido;

        Saliente(long id, String destinatario, String asunto, String cuerpoTexto, String cuerpoHtml,
                 String messageId, String enRespuestaA, int intentos,
                 String adjuntoNombre, String adjuntoTipo, byte[] adjuntoContenido) {
            this.id = id;
            this.destinatario = destinatario;
            this.asunto = asunto;
            this.cuerpoTexto = cuerpoTexto;
            this.cuerpoHtml = cuerpoHtml;
            this.messageId = messageId;
            this.enRespuestaA = enRespuestaA;
            this.intentos = intentos;
            this.adjuntoNombre = adjuntoNombre;
            this.adjuntoTipo = adjuntoTipo;
            this.adjuntoContenido = adjuntoContenido;
        }
    }

    public long encolar(Long mensajeCorreoId, String destinatario, String asunto, String cuerpoTexto,
                        String cuerpoHtml, String messageId, String enRespuestaA,
                        String adjuntoNombre, String adjuntoTipo, byte[] adjuntoContenido, Long notificacionId) {
        return insertar("INSERT INTO correo_saliente (mensaje_correo_id, destinatario, asunto, cuerpo_texto, cuerpo_html, "
                        + "message_id, en_respuesta_a, adjunto_nombre, adjunto_tipo, adjunto_contenido, notificacion_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                mensajeCorreoId, destinatario, MensajeCorreoRepositorio.recortar(asunto, 300), sinNulos(cuerpoTexto),
                sinNulos(cuerpoHtml), messageId, MensajeCorreoRepositorio.recortar(enRespuestaA, 255),
                adjuntoNombre, adjuntoTipo, adjuntoContenido, notificacionId);
    }

    /**
     * Cuenta el intento y aplaza el siguiente ANTES de enviar (se confirma en su propia transacción). Mientras
     * dura la reserva ningún otro ciclo vuelve a tomar este correo, y si el proceso cae a mitad del envío el
     * reintento queda contado. Solo reserva si el correo sigue PENDIENTE con los intentos leídos.
     *
     * Devuelve el instante hasta el que queda reservado: junto con el número de intento identifica ESTA reserva
     * (aunque un operador reactive el correo y el número de intento vuelva a empezar), así que un fallo solo se
     * registra si coinciden los dos. Vacío si el correo ya no estaba disponible.
     */
    public Optional<OffsetDateTime> reservarIntento(long id, int intentosLeidos, long minutosDeReserva) {
        return consultarUno("UPDATE correo_saliente SET intentos = intentos + 1, "
                        // clock_timestamp(): cada reserva recibe su propio instante, aunque ocurran en la misma transacción.
                        + "proximo_intento = clock_timestamp() + make_interval(mins => ?) "
                        + "WHERE id = ? AND estado = 'PENDIENTE' AND intentos = ? RETURNING proximo_intento",
                fila -> fila.getObject("proximo_intento", OffsetDateTime.class), (int) minutosDeReserva, id, intentosLeidos);
    }

    /**
     * Correo que agotó sus intentos sin que ninguno registrara su resultado: queda FALLIDO (el operador decide con
     * reintentar-envios). Se llama con la fila bloqueada, en la misma transacción que la leyó.
     */
    public void marcarAgotado(long id, String error) {
        boolean cambio = actualizar("UPDATE correo_saliente SET ultimo_error = ?, estado = 'FALLIDO' "
                + "WHERE id = ? AND estado = 'PENDIENTE'", MensajeCorreoRepositorio.recortar(error, 500), id) == 1;
        if (cambio) {
            actualizar("UPDATE notificacion n SET estado = 'FALLIDA' FROM correo_saliente cs "
                    + "WHERE cs.id = ? AND n.id = cs.notificacion_id AND n.estado <> 'ENVIADA'", id);
        }
    }

    /** Devuelve a PENDIENTE las respuestas FALLIDO para reintentarlas; devuelve cuántas cambió. */
    public int reactivarFallidos() {
        return actualizar("UPDATE correo_saliente SET estado = 'PENDIENTE', intentos = 0, proximo_intento = now() "
                + "WHERE estado = 'FALLIDO'");
    }

    /**
     * Toma los pendientes cuyo reintento ya venció. SKIP LOCKED evita que dos procesos
     * envíen el mismo correo si por error se ejecutan a la vez.
     */
    public List<Saliente> tomarPendientes(int maximo) {
        return consultar("SELECT id, destinatario, asunto, cuerpo_texto, cuerpo_html, message_id, en_respuesta_a, intentos, "
                        + "adjunto_nombre, adjunto_tipo, adjunto_contenido "
                        + "FROM correo_saliente WHERE estado = 'PENDIENTE' AND proximo_intento <= now() "
                        + "ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                fila -> new Saliente(fila.getLong("id"), fila.getString("destinatario"), fila.getString("asunto"),
                        fila.getString("cuerpo_texto"), fila.getString("cuerpo_html"), fila.getString("message_id"),
                        fila.getString("en_respuesta_a"), fila.getInt("intentos"), fila.getString("adjunto_nombre"),
                        fila.getString("adjunto_tipo"), fila.getBytes("adjunto_contenido")),
                maximo);
    }

    /**
     * Registra que el servidor SMTP aceptó el correo. Un envío aceptado se registra siempre, aunque otro intento lo
     * hubiera marcado como fallido (el correo salió de verdad). Devuelve false si ya estaba registrado como enviado.
     */
    public boolean marcarEnviado(long id) {
        // El intento ya se contó en reservarIntento.
        boolean cambio = actualizar("UPDATE correo_saliente SET estado = 'ENVIADO', enviado_en = now(), ultimo_error = NULL "
                + "WHERE id = ? AND estado <> 'ENVIADO'", id) == 1;
        // R045: la notificación del canal CORREO que entrega este envío queda ENVIADA, una sola vez.
        actualizar("UPDATE notificacion n SET estado = 'ENVIADA', fecha_envio = now() FROM correo_saliente cs "
                + "WHERE cs.id = ? AND n.id = cs.notificacion_id AND n.estado <> 'ENVIADA'", id);
        // Una respuesta de confirmación contiene un código de un solo uso: una vez enviada ya no
        // hace falta conservarlo en la base de datos.
        actualizar("UPDATE correo_saliente cs SET cuerpo_texto = '[Contenido omitido: incluía un código de confirmación]', "
                + "cuerpo_html = NULL FROM mensaje_correo m WHERE cs.id = ? AND m.id = cs.mensaje_correo_id "
                + "AND m.resultado = 'CONFIRMACION'", id);
        return cambio;
    }

    /**
     * Registra el fallo de la reserva identificada por {@code intento} y {@code reservadoHasta}: reintento posterior
     * o FALLIDO definitivo. Solo cambia el correo si sigue PENDIENTE con esa misma reserva (nadie lo reservó después
     * ni se reactivó); devuelve false si el fallo llegó tarde y se ignoró.
     */
    public boolean registrarFallo(long id, int intento, OffsetDateTime reservadoHasta, String error, boolean definitivo,
                                  OffsetDateTime proximoIntento) {
        boolean cambio = actualizar("UPDATE correo_saliente SET ultimo_error = ?, estado = ?, proximo_intento = ? "
                        + "WHERE id = ? AND estado = 'PENDIENTE' AND intentos = ? AND proximo_intento = ?",
                MensajeCorreoRepositorio.recortar(error, 500), definitivo ? "FALLIDO" : "PENDIENTE", proximoIntento,
                id, intento, reservadoHasta) == 1;
        if (cambio) {
            // R045: el intento fallido deja la notificación FALLIDA (se reintenta con el mismo correo); nunca deshace un ENVIADA.
            actualizar("UPDATE notificacion n SET estado = 'FALLIDA' FROM correo_saliente cs "
                    + "WHERE cs.id = ? AND n.id = cs.notificacion_id AND n.estado <> 'ENVIADA'", id);
        }
        return cambio;
    }
}
