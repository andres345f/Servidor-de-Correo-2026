package com.feder.presentacion.procesamiento;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ejecuta ciclos de correo de forma continua hasta que se pide detenerlo.
 *
 * - Espera el intervalo configurado entre ciclos (no un valor fijo de 10 segundos).
 * - Si el servidor de correo o la base de datos fallan, aumenta la espera (hasta 10 minutos)
 *   para no saturar un servidor caído; vuelve al intervalo normal en cuanto un ciclo funciona.
 * - El apagado es ordenado: se termina el ciclo en curso antes de salir.
 */
public class ProcesadorContinuo {

    private static final Logger ACCESO = Logger.getLogger("feder.access");
    private static final Logger ERROR = Logger.getLogger("feder.error");
    private static final Logger CONSOLA = Logger.getLogger("feder.consola");
    private static final Duration ESPERA_MAXIMA = Duration.ofMinutes(10);

    private final CicloCorreo ciclo;
    private final Duration intervalo;
    private final CountDownLatch parada = new CountDownLatch(1);
    private final CountDownLatch terminado = new CountDownLatch(1);

    private final MonitorServicios monitor;

    public ProcesadorContinuo(CicloCorreo ciclo, Duration intervalo) {
        this(ciclo, intervalo, MonitorServicios.basico());
    }

    /** @param monitor informa en pantalla las caídas de la base y del correo, sus motivos y la recuperación. */
    public ProcesadorContinuo(CicloCorreo ciclo, Duration intervalo, MonitorServicios monitor) {
        this.ciclo = ciclo;
        this.intervalo = intervalo;
        this.monitor = monitor;
    }

    /** Bucle principal; bloquea el hilo que lo llama hasta que se invoque {@link #detener()}. */
    public void ejecutar() {
        ACCESO.info(() -> "Procesador iniciado; intervalo=" + intervalo.toSeconds() + "s");
        CONSOLA.info(() -> "Esperando correos (revisión cada " + intervalo.toSeconds() + " s).");
        int fallosSeguidos = 0;
        try {
            while (parada.getCount() > 0) {
                boolean huboFallo;
                Throwable fallaBd = null;
                Throwable fallaCorreo = null;
                boolean otroFallo = false;
                try {
                    CicloCorreo.Resumen resumen = ciclo.ejecutar();
                    huboFallo = resumen.errorRecepcion != null;
                    fallaBd = resumen.fallaBaseDatos;
                    fallaCorreo = resumen.fallaCorreo;
                    otroFallo = huboFallo && fallaBd == null && fallaCorreo == null;
                } catch (RuntimeException e) {
                    ERROR.log(Level.SEVERE, "Ciclo fallido", e);
                    huboFallo = true;
                    otroFallo = true;
                }
                fallosSeguidos = huboFallo ? fallosSeguidos + 1 : 0;
                Duration espera = esperaSiguiente(fallosSeguidos);
                monitor.registrar(fallaBd, fallaCorreo, otroFallo, fallosSeguidos, espera);
                if (parada.await(espera.toMillis(), TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ACCESO.info("Procesador detenido");
            CONSOLA.info("Sistema FEDER detenido.");
            terminado.countDown();
        }
    }

    /** Pide la detención y espera a que el ciclo en curso termine (máximo 60 segundos). */
    public void detener() {
        parada.countDown();
        try {
            terminado.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Duration esperaSiguiente(int fallosSeguidos) {
        if (fallosSeguidos == 0) {
            return intervalo;
        }
        // Espera creciente: intervalo x 2^fallos, con un máximo de 10 minutos.
        long factor = 1L << Math.min(fallosSeguidos, 6);
        Duration espera = intervalo.multipliedBy(factor);
        return espera.compareTo(ESPERA_MAXIMA) > 0 ? ESPERA_MAXIMA : espera;
    }
}
