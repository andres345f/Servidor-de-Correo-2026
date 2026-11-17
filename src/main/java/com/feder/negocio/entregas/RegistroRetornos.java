package com.feder.negocio.entregas;

import com.feder.datos.entregas.RetornoRepositorio;
import com.feder.datos.entregas.RetornoRepositorio.ReservaDespachada;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.TipoMovimiento;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.entregas.SolicitudRetorno.TipoBajaRetorno;
import com.feder.negocio.inventario.ServicioInventario;

import java.math.BigDecimal;

/**
 * Un retorno físico de mercancía despachada (ENTREGA «Retorno de mercancía», R029, R044): su ENTRADA_RETORNO_ENTREGA
 * en el lote de la salida, la baja en la misma transacción de lo recibido no apto, y su registro en BITACORA con la
 * reserva, las cantidades, el motivo y la evidencia. El llamador ya validó el pedido, la entrega y el autor.
 */
final class RegistroRetornos {

    private final ContextoNegocio ctx;
    private final ServicioInventario inventario;
    private final RetornoRepositorio retornos;
    private final MovimientoRepositorio movimientos;
    private final LoteFederRepositorio lotes;

    RegistroRetornos(ContextoNegocio ctx, ServicioInventario inventario, RetornoRepositorio retornos,
                     MovimientoRepositorio movimientos, LoteFederRepositorio lotes) {
        this.ctx = ctx;
        this.inventario = inventario;
        this.retornos = retornos;
        this.movimientos = movimientos;
        this.lotes = lotes;
    }

    /** Resultado del retorno: el movimiento, la baja (si hubo no aptos) y si la clave ya estaba registrada. */
    record Retorno(MovimientoInventario entrada, MovimientoInventario baja, boolean yaRegistrado) {
    }

    Retorno registrar(ReservaDespachada reserva, BigDecimal recibida, BigDecimal apta, TipoBajaRetorno tipoBaja, String motivo,
                      String evidencia, String claveIndicada, long autorId) {
        LoteFederVista lote = lotes.buscarPorId(reserva.loteFederId()).orElseThrow();
        Cantidades.validarPositiva(recibida, lote.unidad(), "cantidad recibida");
        Cantidades.validarNoNegativa(apta, lote.unidad(), "cantidad apta");
        if (apta.compareTo(recibida) > 0) {
            throw NegocioException.regla("La cantidad apta no puede superar la recibida.");
        }
        BigDecimal noApta = recibida.subtract(apta);
        TipoMovimiento baja = tipoDeBaja(lote, noApta, tipoBaja);
        String clave = claveIndicada != null ? claveIndicada
                : "RET-RES" + reserva.reservaId() + "-" + (retornos.contarRetornos(reserva.reservaId()) + 1);
        // Un reenvío con una clave ya confirmada devuelve el retorno registrado sin repetir sus bajas.
        boolean yaRegistrado = movimientos.buscarPorClave(clave).isPresent();
        MovimientoInventario entrada = inventario.registrarRetornoEntrega(reserva.reservaId(), recibida, autorId, clave, motivo);
        if (yaRegistrado) {
            return new Retorno(entrada, null, true);
        }
        MovimientoInventario salidaBaja = noApta.signum() == 0 ? null : inventario.registrarBajaDeRetorno(reserva.reservaId(),
                baja, noApta, autorId, clave + "-BAJA", motivo != null ? motivo : "Unidades retornadas no aptas para la venta");
        ctx.bitacora().registrar(autorId, AccionBitacora.CREAR, "MOVIMIENTO_INVENTARIO", entrada.getId(), DetalleJson.nuevo()
                .con("operacion", "RETORNO_ENTREGA").con("clave_operacion", clave).con("reserva_id", reserva.reservaId())
                .con("cantidad_recibida", recibida).con("cantidad_apta", apta).con("cantidad_no_apta", noApta)
                .con("tipo_baja", salidaBaja == null ? null : baja).con("motivo", motivo).con("evidencia", evidencia));
        return new Retorno(entrada, salidaBaja, false);
    }

    /** Lo no apto se da de baja como dañado o defectuoso; si el lote de origen venció, como BAJA_VENCIDO (R029). */
    private TipoMovimiento tipoDeBaja(LoteFederVista lote, BigDecimal noApta, TipoBajaRetorno indicado) {
        if (noApta.signum() == 0) {
            return null;
        }
        if (lote.vencido(FechasBolivia.hoy(ctx.reloj()))) {
            return TipoMovimiento.BAJA_VENCIDO;
        }
        if (indicado == null) {
            throw NegocioException.regla("Hay " + Cantidades.formatear(noApta) + " unidades no aptas: indique si están DANADO o DEFECTUOSO.");
        }
        return indicado == TipoBajaRetorno.DANADO ? TipoMovimiento.BAJA_DANADO : TipoMovimiento.BAJA_DEFECTUOSO;
    }
}
