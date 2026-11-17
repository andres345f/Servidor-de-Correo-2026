package com.feder.negocio.ventas;

import com.feder.datos.ventas.ReintegroCancelacionRepositorio;
import com.feder.datos.ventas.ReintegroCancelacionRepositorio.PagoCobrado;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Pedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementación por defecto de {@link PoliticaReintegro}: por cada pago PAGADO del pedido registra un
 * REEMBOLSO PENDIENTE por su saldo no reintegrado, con una clave estable (no se duplica si se repite).
 * Ejecutarlo ante la pasarela y conciliar pagos PENDIENTE corresponde a CU6.
 */
public class ReintegroPendiente implements PoliticaReintegro {

    private final ContextoNegocio ctx;
    private final ReintegroCancelacionRepositorio repositorio;

    public ReintegroPendiente(ContextoNegocio ctx, ReintegroCancelacionRepositorio repositorio) {
        this.ctx = ctx;
        this.repositorio = repositorio;
    }

    @Override
    public List<BigDecimal> alCancelar(Pedido pedido, Long responsableId, String motivo) {
        List<BigDecimal> importes = new ArrayList<>();
        for (PagoCobrado pago : repositorio.pagosCobrados(pedido.getId())) {
            BigDecimal saldo = pago.saldo();
            if (saldo.signum() <= 0) {
                continue;
            }
            String texto = "Cancelación del pedido " + pedido.getCodigo() + ": " + motivo;
            long id = repositorio.registrarReintegro(pago.id(), saldo, texto.length() <= 255 ? texto : texto.substring(0, 255),
                    "CANC-PED" + pedido.getId() + "-PAGO" + pago.id(), ctx.ahora());
            ctx.bitacora().registrar(responsableId, AccionBitacora.CREAR, "REEMBOLSO", id,
                    DetalleJson.nuevo().con("pago_id", pago.id()).con("monto", saldo).con("motivo", texto));
            importes.add(saldo);
        }
        // R049: el efectivo pendiente nunca se cobró; con la cancelación el intento termina sin cobro.
        for (Long pagoId : repositorio.rechazarEfectivoPendiente(pedido.getId())) {
            ctx.bitacora().registrar(responsableId, AccionBitacora.MODIFICAR, "PAGO", pagoId, DetalleJson.nuevo()
                    .con("estado_anterior", "PENDIENTE").con("estado_nuevo", "RECHAZADO")
                    .con("motivo", "Cancelación del pedido " + pedido.getCodigo()));
        }
        return importes;
    }
}
