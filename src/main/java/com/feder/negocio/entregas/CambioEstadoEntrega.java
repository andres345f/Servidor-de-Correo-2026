package com.feder.negocio.entregas;

import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Entrega;
import com.feder.modelo.EstadoEntrega;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;

/**
 * R027: cada transición de la ENTREGA es condicional sobre su estado anterior, se hace con la fila del PEDIDO
 * ya bloqueada por el llamador y queda en BITACORA (MODIFICAR, ENTREGA) con el estado anterior y el nuevo.
 */
final class CambioEstadoEntrega {

    private final ContextoNegocio ctx;
    private final EntregaRepositorio entregas;

    CambioEstadoEntrega(ContextoNegocio ctx, EntregaRepositorio entregas) {
        this.ctx = ctx;
        this.entregas = entregas;
    }

    void cambiar(Entrega entrega, EstadoEntrega nuevo, long autorId, String observacion) {
        EstadoEntrega anterior = entrega.getEstado();
        if (!TransicionesEntrega.permitida(entrega.getModalidad(), anterior, nuevo)) {
            throw NegocioException.estadoInvalido("Una entrega " + entrega.getModalidad() + " no puede pasar de " + anterior
                    + " a " + nuevo + ".");
        }
        boolean aplicado = nuevo == EstadoEntrega.ENTREGADA
                ? entregas.marcarEntregada(entrega.getId(), anterior, ctx.ahora())
                : entregas.cambiarEstado(entrega.getId(), anterior, nuevo);
        if (!aplicado) {
            throw NegocioException.estadoInvalido("La entrega cambió de estado mientras se procesaba la operación.");
        }
        if (observacion != null) {
            entregas.anotar(entrega.getId(), nuevo + ": " + observacion);
        }
        ctx.bitacora().registrar(autorId, AccionBitacora.MODIFICAR, "ENTREGA", entrega.getId(), DetalleJson.nuevo()
                .con("estado_anterior", anterior).con("estado_nuevo", nuevo).con("observacion", observacion));
        entrega.setEstado(nuevo);
    }
}
