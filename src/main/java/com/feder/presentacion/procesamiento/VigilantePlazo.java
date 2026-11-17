package com.feder.presentacion.procesamiento;

import com.feder.presentacion.correo.CorreoException;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Plazo absoluto para cada operación con el servidor de correo.
 *
 * El tiempo de espera del socket solo limita cada lectura: un servidor que envía un byte cada pocos
 * segundos nunca lo agota y podría retener el ciclo durante horas. Este vigilante cierra la conexión
 * cuando una operación completa (conectar, autenticar, listar, descargar, salir) supera su plazo; la
 * lectura bloqueada termina entonces con un error y el ciclo sigue con el resto del trabajo.
 */
final class VigilantePlazo implements AutoCloseable {

    /** Una operación de protocolo que puede fallar con un error de correo. */
    interface Operacion<T> {
        T ejecutar() throws CorreoException;
    }

    /** Operación sin resultado. */
    interface Accion {
        void ejecutar() throws CorreoException;
    }

    private final Duration plazo;
    private final ScheduledExecutorService hilo;

    VigilantePlazo(String nombreHilo, Duration plazo) {
        this.plazo = plazo;
        this.hilo = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread h = new Thread(tarea, nombreHilo);
            h.setDaemon(true);
            return h;
        });
    }

    /** Ejecuta la operación; si supera el plazo, ejecuta alVencer (normalmente, cerrar el socket). */
    <T> T ejecutar(Operacion<T> operacion, Runnable alVencer) throws CorreoException {
        ScheduledFuture<?> vencimiento = hilo.schedule(alVencer, plazo.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return operacion.ejecutar();
        } finally {
            vencimiento.cancel(false);
        }
    }

    void ejecutar(Accion accion, Runnable alVencer) throws CorreoException {
        ejecutar(() -> {
            accion.ejecutar();
            return null;
        }, alVencer);
    }

    @Override
    public void close() {
        hilo.shutdownNow();
    }
}
