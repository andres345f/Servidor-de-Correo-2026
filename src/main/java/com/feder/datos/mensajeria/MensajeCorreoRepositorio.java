package com.feder.datos.mensajeria;

import com.feder.datos.RepositorioJdbc;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Registro persistente de los correos recibidos. La pareja (buzon, uidl) es única:
 * es la base de la deduplicación, para que un mismo correo no repita su operación.
 */
public class MensajeCorreoRepositorio extends RepositorioJdbc {

    /**
     * Intenta "reclamar" el mensaje. Devuelve su id si es la primera vez que se ve;
     * vacío si ya estaba registrado (otro ciclo o una instancia anterior ya lo procesó).
     */
    public Optional<Long> reclamar(String buzon, String uidl, String messageId, String remitente, String asunto) {
        return consultarUno("INSERT INTO mensaje_correo (buzon, uidl, message_id, remitente, asunto, estado) "
                        + "VALUES (?, ?, ?, lower(?), ?, 'PROCESANDO') ON CONFLICT (buzon, uidl) DO NOTHING RETURNING id",
                fila -> fila.getLong("id"),
                buzon, uidl, recortar(messageId, 255), recortar(remitente, 254), recortar(asunto, 500));
    }

    /**
     * Bloqueo transaccional por remitente (se libera solo al terminar la transacción): dos procesos
     * que reciban correos del mismo remitente cuentan y registran su cuota uno después del otro.
     */
    public void bloquearRemitente(String remitente) {
        consultar("SELECT pg_advisory_xact_lock(hashtext(lower(?))) IS NULL AS bloqueado", fila -> 1,
                remitente == null ? "" : sinNulos(remitente));
    }

    public void finalizar(long id, String estado, String resultado, String comando, String detalle) {
        actualizar("UPDATE mensaje_correo SET estado = ?, resultado = ?, comando = ?, detalle = ?, procesado_en = now() WHERE id = ?",
                estado, resultado, recortar(comando, 20), recortar(detalle, 500), id);
    }

    public Set<String> uidlsRegistrados(String buzon) {
        return new HashSet<>(consultar("SELECT uidl FROM mensaje_correo WHERE buzon = ?", fila -> fila.getString("uidl"), buzon));
    }

    /** Cantidad de correos recibidos de un remitente desde un instante (limitación de solicitudes). */
    public int contarDesde(String remitente, OffsetDateTime desde) {
        return consultarUno("SELECT count(*) AS n FROM mensaje_correo WHERE remitente = lower(?) AND recibido_en >= ?",
                fila -> fila.getInt("n"), remitente, desde).orElse(0);
    }

    /** Recorta al tamaño de la columna y elimina caracteres NUL, que PostgreSQL no admite en texto. */
    static String recortar(String valor, int maximo) {
        if (valor == null) {
            return null;
        }
        String limpio = sinNulos(valor);
        return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo);
    }
}
