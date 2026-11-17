package com.feder.negocio.mensajeria;

import com.feder.negocio.comun.ResultadoOperacion;

import java.util.ArrayList;
import java.util.List;

/**
 * Avisos a terceros acumulados mientras se procesa UN correo. Se guardan en la bandeja de salida al
 * final, en la misma transacción que la operación; si la operación falla, se descartan con ella.
 * El procesamiento es secuencial (un correo por vez), por eso basta con un ThreadLocal.
 */
public final class AvisosPendientes {

    /** Un aviso: a quién va y qué dice (el formato lo decide la capa de presentación). */
    public static final class Aviso {
        public final String destinatario;
        public final ResultadoOperacion contenido;
        /** NOTIFICACION del canal CORREO que este aviso entrega (R045), si la hay. */
        Long notificacionId;

        Aviso(String destinatario, ResultadoOperacion contenido) {
            this.destinatario = destinatario;
            this.contenido = contenido;
        }
    }

    private static final ThreadLocal<List<Aviso>> ACTUALES = new ThreadLocal<>();

    private AvisosPendientes() {
    }

    static void iniciar() {
        ACTUALES.set(new ArrayList<>());
    }

    static List<Aviso> terminar() {
        List<Aviso> avisos = ACTUALES.get();
        ACTUALES.remove();
        return avisos == null ? List.of() : avisos;
    }

    /** Devuelve false si no hay un correo en proceso (por ejemplo, un comando de instalación). */
    static boolean agregar(String destinatario, ResultadoOperacion contenido) {
        List<Aviso> avisos = ACTUALES.get();
        if (avisos == null) {
            return false;
        }
        avisos.add(new Aviso(destinatario, contenido));
        return true;
    }

    /**
     * Asocia el aviso de la posición indicada (la marca tomada antes de agregarlo) con la NOTIFICACION del canal
     * CORREO que entrega: así el envío posterior puede pasarla a ENVIADA o FALLIDA (R045).
     */
    public static void vincularNotificacion(int posicion, long notificacionId) {
        List<Aviso> avisos = ACTUALES.get();
        if (avisos != null && posicion >= 0 && posicion < avisos.size()) {
            avisos.get(posicion).notificacionId = notificacionId;
        }
    }

    /** Posición actual, para poder descartar los avisos de una operación que luego falla. */
    public static int marca() {
        List<Aviso> avisos = ACTUALES.get();
        return avisos == null ? 0 : avisos.size();
    }

    /** Descarta los avisos agregados después de la marca (la operación que los generó se revirtió). */
    public static void revertirA(int marca) {
        List<Aviso> avisos = ACTUALES.get();
        if (avisos != null) {
            while (avisos.size() > marca) {
                avisos.remove(avisos.size() - 1);
            }
        }
    }
}
