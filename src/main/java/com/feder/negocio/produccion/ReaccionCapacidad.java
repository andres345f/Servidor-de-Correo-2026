package com.feder.negocio.produccion;

import com.feder.datos.produccion.BalanceProduccionRepositorio;
import com.feder.datos.produccion.BalanceProduccionRepositorio.EncargoRespaldado;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Produccion;
import com.feder.modelo.TipoNotificacion;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;

import java.util.List;

/**
 * «Cancelación o reducción» (R064): es una reacción, no una prohibición. Si la producción ya no alcanza para lo
 * comprometido con encargos, cada encargo afectado pierde su fecha estimada y su cliente recibe una notificación.
 * Los pedidos conservan su estado; el cliente puede cancelarlos (CU5).
 */
class ReaccionCapacidad {

    private final ContextoNegocio ctx;
    private final BalanceProduccionRepositorio balances = new BalanceProduccionRepositorio();

    ReaccionCapacidad(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    BalanceProduccion balance(Produccion produccion) {
        return BalanceProduccion.de(balances.sumas(produccion.getId()), produccion.getCantidadEstimada());
    }

    /** Llamar con la producción ya bloqueada y actualizada. Devuelve cuántos encargos perdieron su fecha. */
    int trasCancelar(Produccion produccion, long autorId) {
        if (!balance(produccion).compromisoSinTransito()) {
            return 0;
        }
        return retirarFechas(produccion, autorId, "Cancelación de la producción " + produccion.getCodigo());
    }

    int trasReducirEstimacion(Produccion produccion, long autorId) {
        if (!balance(produccion).capacidadInsuficiente()) {
            return 0;
        }
        return retirarFechas(produccion, autorId, "Reducción de la cantidad estimada de la producción " + produccion.getCodigo());
    }

    private int retirarFechas(Produccion produccion, long autorId, String motivo) {
        List<EncargoRespaldado> encargos = balances.encargosConFechaEstimada(produccion.getId());
        for (EncargoRespaldado encargo : encargos) {
            balances.quitarFechaEstimada(encargo.pedidoId());
            ctx.notificaciones().sobrePedido(encargo.clienteId(), encargo.pedidoId(), TipoNotificacion.FECHA_ESTIMADA_ACTUALIZADA,
                    "Fecha estimada de tu encargo " + encargo.codigo(),
                    "La producción que respaldaba tu encargo ya no alcanza para cubrirlo, por eso tu encargo queda sin fecha "
                            + "estimada. Tu pedido conserva su estado y puedes cancelarlo si lo prefieres.");
            ctx.bitacora().registrar(autorId, AccionBitacora.MODIFICAR, "PEDIDO", encargo.pedidoId(), DetalleJson.nuevo()
                    .con("fecha_estimada_anterior", encargo.fechaEstimada())
                    .con("fecha_estimada_nueva", null)
                    .con("motivo", motivo)
                    .con("fundamento", "La capacidad de la producción ya no cubre el compromiso vigente de sus encargos"));
        }
        return encargos.size();
    }
}
