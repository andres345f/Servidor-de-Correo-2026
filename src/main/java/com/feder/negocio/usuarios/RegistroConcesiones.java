package com.feder.negocio.usuarios;

import com.feder.datos.usuarios.ConcesionRepositorio;
import com.feder.datos.usuarios.ConcesionRepositorio.Concesion;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoConcesion;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Altas y transiciones de concesiones junto con su registro en BITACORA (R096, R097, R001).
 * Cada método cambia la fila y deja constancia en la misma transacción, con el formato de detalle
 * de la bitácora: rol, estado anterior, estado nuevo y justificación.
 *
 * No decide quién puede hacer cada cosa: eso lo validan los servicios que lo usan.
 */
public class RegistroConcesiones {

    private static final String ENTIDAD = "USUARIO_ROL";

    private final ContextoNegocio ctx;
    private final ConcesionRepositorio concesiones = new ConcesionRepositorio();

    public RegistroConcesiones(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    /** Alta VIGENTE directa (CLIENTE, PRODUCTOR al aprobar o rehabilitar, ADMINISTRADOR inicial). */
    public long concederVigente(long usuarioId, TipoRol rol, Long asignadoPorId, Long autorId, String justificacion) {
        exigirSinConcesionAbierta(usuarioId, rol);
        long id = concesiones.insertarVigente(usuarioId, concesiones.idRol(rol), asignadoPorId, ctx.ahora(), justificacion);
        auditar(autorId, AccionBitacora.ASIGNAR_ROL, id, rol, null, EstadoConcesion.VIGENTE, justificacion);
        return id;
    }

    /** Invitación PENDIENTE a un rol privilegiado (R100); el plazo se cuenta desde el mismo instante de la asignación. */
    public long invitar(long usuarioId, TipoRol rol, long asignadoPorId, Duration plazo, String justificacion) {
        exigirSinConcesionAbierta(usuarioId, rol);
        OffsetDateTime asignacion = ctx.ahora();
        long id = concesiones.insertarPendiente(usuarioId, concesiones.idRol(rol), asignadoPorId, asignacion,
                asignacion.plus(plazo), justificacion);
        auditar(asignadoPorId, AccionBitacora.INVITAR, id, rol, null, EstadoConcesion.PENDIENTE, justificacion);
        return id;
    }

    public void activar(Concesion concesion, long autorId, String justificacion) {
        exigir(concesiones.activar(concesion.id(), ctx.ahora()), concesion);
        auditar(autorId, AccionBitacora.ASIGNAR_ROL, concesion.id(), concesion.rol(), EstadoConcesion.PENDIENTE,
                EstadoConcesion.VIGENTE, justificacion);
    }

    /**
     * PENDIENTE -> ANULADA. Sin autor ni revocado_por solo cuando la invitación vence (R001, R101).
     * El motivo de anulación es obligatorio cuando lo decide una persona.
     */
    public void anular(Concesion concesion, Long revocadoPorId, String motivo, String justificacion) {
        exigir(concesiones.anular(concesion.id(), revocadoPorId, ctx.ahora(), motivo), concesion);
        auditar(revocadoPorId, AccionBitacora.REVOCAR_ROL, concesion.id(), concesion.rol(), EstadoConcesion.PENDIENTE,
                EstadoConcesion.ANULADA, justificacion);
    }

    /**
     * Una invitación PENDIENTE cuyo plazo ya pasó se trata siempre como vencimiento automático: ANULADA sin
     * revocado_por ni autor (R096, R101, R001), aunque la anulación ocurra al responderla o cancelarla.
     * Devuelve true si la anuló por vencimiento.
     */
    public boolean anularSiVencida(Concesion concesion) {
        if (concesion.estado() != EstadoConcesion.PENDIENTE || ctx.ahora().isBefore(concesion.datos().getFechaExpiracion())) {
            return false;
        }
        anular(concesion, null, null, "Invitación vencida sin respuesta");
        return true;
    }

    /**
     * Mantenimiento periódico: anula sin autor las invitaciones cuyo plazo venció (R096), aunque nadie
     * haya intentado responderlas. Debe llamarse dentro de una transacción. Devuelve cuántas anuló.
     */
    public int anularInvitacionesVencidas(int maximo) {
        int anuladas = 0;
        for (Concesion concesion : concesiones.bloquearInvitacionesVencidas(maximo)) {
            if (anularSiVencida(concesion)) {
                anuladas++;
            }
        }
        return anuladas;
    }

    public void revocar(Concesion concesion, long revocadoPorId, String motivo) {
        exigir(concesiones.revocar(concesion.id(), revocadoPorId, ctx.ahora(), motivo), concesion);
        auditar(revocadoPorId, AccionBitacora.REVOCAR_ROL, concesion.id(), concesion.rol(), EstadoConcesion.VIGENTE,
                EstadoConcesion.REVOCADA, motivo);
    }

    /** R098: como máximo una concesión PENDIENTE o VIGENTE por pareja (usuario, rol). */
    private void exigirSinConcesionAbierta(long usuarioId, TipoRol rol) {
        concesiones.buscarAbierta(usuarioId, rol).ifPresent(c -> {
            throw NegocioException.duplicado("La cuenta ya tiene una concesión " + c.estado() + " del rol " + rol
                    + ". No se duplica una asignación vigente ni se invita dos veces al mismo rol.");
        });
    }

    private static void exigir(boolean cambiada, Concesion concesion) {
        if (!cambiada) {
            throw NegocioException.estadoInvalido("La concesión " + concesion.id() + " ya cambió de estado; "
                    + "consulte su situación actual antes de repetir la operación.");
        }
    }

    private void auditar(Long autorId, AccionBitacora accion, long concesionId, TipoRol rol,
                         EstadoConcesion anterior, EstadoConcesion nuevo, String justificacion) {
        ctx.bitacora().registrar(autorId, accion, ENTIDAD, concesionId, DetalleJson.nuevo()
                .con("rol", rol)
                .con("estado_anterior", anterior)
                .con("estado_nuevo", nuevo)
                .con("justificacion", justificacion));
    }
}
