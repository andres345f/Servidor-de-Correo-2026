package com.feder.negocio.inventario;

import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.DetalleCompraVista;
import com.feder.modelo.CompraFeder;
import com.feder.modelo.EstadoCompra;
import com.feder.negocio.comun.NegocioException;

/**
 * Recalcula el estado derivado de una compra después de cada recepción, rechazo o cancelación (R005).
 * Bloquea la fila de la compra después de la del detalle, en el orden que fija R012.
 */
final class RecalculoCompra {

    private final CompraRepositorio compras;
    private final DetalleCompraRepositorio detalles;

    RecalculoCompra(CompraRepositorio compras, DetalleCompraRepositorio detalles) {
        this.compras = compras;
        this.detalles = detalles;
    }

    EstadoCompra recalcular(long compraId) {
        CompraFeder compra = compras.bloquear(compraId)
                .orElseThrow(() -> NegocioException.noEncontrado("La compra ya no existe."));
        // R006: el pago al productor bloquea la misma fila. Si un pago se confirmó mientras esta operación
        // cambiaba las cantidades de la línea, se revierte todo: lo pagado no puede dejar de ser lo aceptado.
        if (compra.getFechaPagoProductor() != null) {
            throw NegocioException.estadoInvalido("La compra " + compra.getCodigo() + " fue pagada al productor mientras se "
                    + "procesaba la operación: sus cantidades ya no cambian y no se registró nada.");
        }
        EstadoCompra nuevo = EstadoCompraDerivado.derivar(detalles.deCompra(compraId).stream()
                .map(DetalleCompraVista::detalle).toList());
        if (nuevo != compra.getEstado()) {
            compras.actualizarEstado(compraId, nuevo);
        }
        return nuevo;
    }
}
