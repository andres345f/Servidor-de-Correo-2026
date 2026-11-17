package com.feder.negocio.pagos;

import com.feder.modelo.Pedido;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.util.Objects;

/**
 * Separación de funciones en los actos que mueven dinero o mercancía de un pedido: verificar o rechazar su pago,
 * cobrar su efectivo en la entrega, aprobar su devolución y registrar el resultado de sus reembolsos. Una cuenta
 * del personal que también es CLIENTE no hace esos actos sobre sus propios pedidos, igual que el modelo exige
 * un responsable distinto del titular en otros actos privilegiados.
 */
public final class SeparacionFunciones {

    private SeparacionFunciones() {
    }

    public static void exigirAjeno(Solicitante solicitante, Pedido pedido, String accion) {
        if (pedido.getUsuarioId() != null && Objects.equals(pedido.getUsuarioId(), solicitante.usuarioId())) {
            throw NegocioException.sinPermiso("No puede " + accion + " de su propio pedido " + pedido.getCodigo()
                    + ": otro miembro del personal de FEDER debe hacerlo.");
        }
    }
}
