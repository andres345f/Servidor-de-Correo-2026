package com.feder.negocio.mantenimiento;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.usuarios.RegistroConcesiones;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tareas periódicas que el modelo permite realizar SIN un usuario que las ejecute. Hoy: anular las
 * invitaciones vencidas (R096: PENDIENTE -> ANULADA sin autor al vencer fecha_expiracion).
 *
 * El vencimiento de lotes y la expiración de reservas NO se automatizan aquí a propósito: generan
 * movimientos de inventario, y el modelo exige que cada movimiento tenga un usuario que lo ejecuta
 * (A16). Por eso el personal los dispara con sus comandos (VENLTF y EXPRES).
 */
public class ServicioMantenimiento {

    private static final Logger ACCESO = Logger.getLogger("feder.access");
    private static final Logger ERROR = Logger.getLogger("feder.error");
    private static final Logger CONSOLA = Logger.getLogger("feder.consola");
    private static final int LOTE_POR_CICLO = 100;

    private final ContextoNegocio contexto;
    private final RegistroConcesiones concesiones;

    /** Tarea adicional de cada ciclo (hoy: confirmar pagos QR con el proveedor de pagos), o null. */
    private final Runnable tareaAdicional;

    public ServicioMantenimiento(ContextoNegocio contexto) {
        this(contexto, null);
    }

    public ServicioMantenimiento(ContextoNegocio contexto, Runnable tareaAdicional) {
        this.contexto = contexto;
        this.concesiones = new RegistroConcesiones(contexto);
        this.tareaAdicional = tareaAdicional;
    }

    /** Ejecuta las tareas; un fallo se registra y no detiene el procesamiento del correo. */
    public void ejecutar() {
        try {
            int anuladas = contexto.transacciones().enTransaccion(() -> concesiones.anularInvitacionesVencidas(LOTE_POR_CICLO));
            if (anuladas > 0) {
                ACCESO.info(() -> "MANTENIMIENTO invitaciones vencidas anuladas=" + anuladas);
                CONSOLA.info(() -> "Mantenimiento: " + anuladas + " invitación(es) vencida(s) anulada(s).");
            }
        } catch (RuntimeException e) {
            ERROR.log(Level.WARNING, "Mantenimiento periódico fallido; se reintentará en el próximo ciclo", e);
        }
        if (tareaAdicional != null) {
            try {
                tareaAdicional.run();
            } catch (RuntimeException e) {
                ERROR.log(Level.WARNING, "Tarea periódica fallida; se reintentará en el próximo ciclo", e);
            }
        }
    }
}
