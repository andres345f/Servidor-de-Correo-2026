package com.feder.datos.compras;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Consulta de las operaciones de línea ya registradas en BITACORA (DETALLE_COMPRA «Recepción idempotente», R013).
 * Se apoya en el índice único uq_bitacora_clave_operacion; la inserción la hace ServicioBitacora.
 */
public class OperacionCompraRepositorio extends RepositorioJdbc {

    /** Operación confirmada con una clave: qué línea, qué tipo y con qué cantidades. */
    public record OperacionRegistrada(long detalleCompraId, String operacion, BigDecimal aceptada, BigDecimal rechazada,
                                      BigDecimal cancelada, BigDecimal anulada) {
    }

    public Optional<OperacionRegistrada> buscarPorClave(String claveOperacion) {
        return consultarUno("SELECT entidad_id, detalle ->> 'operacion' AS operacion, "
                        + "COALESCE((detalle ->> 'cantidad_aceptada')::numeric, 0) AS aceptada, "
                        + "COALESCE((detalle ->> 'cantidad_rechazada')::numeric, 0) AS rechazada, "
                        + "COALESCE((detalle ->> 'cantidad_cancelada')::numeric, 0) AS cancelada, "
                        + "COALESCE((detalle ->> 'cantidad_anulada')::numeric, 0) AS anulada "
                        + "FROM bitacora WHERE entidad = 'DETALLE_COMPRA' AND detalle ->> 'clave_operacion' = ?",
                fila -> new OperacionRegistrada(fila.getLong("entidad_id"), fila.getString("operacion"),
                        fila.getBigDecimal("aceptada"), fila.getBigDecimal("rechazada"), fila.getBigDecimal("cancelada"),
                        fila.getBigDecimal("anulada")),
                claveOperacion);
    }

    /** R013: una misma guía de entrega no se registra dos veces para la misma línea. */
    public boolean existeDocumento(long compraId, long detalleCompraId, String documento) {
        return consultarUno("SELECT count(*) AS n FROM bitacora WHERE entidad = 'DETALLE_COMPRA' "
                        + "AND detalle ->> 'operacion' = 'RECEPCION' AND detalle ->> 'compra_id' = ? AND entidad_id = ? "
                        + "AND detalle ->> 'documento_entrega' = ?",
                fila -> fila.getInt("n") > 0, String.valueOf(compraId), detalleCompraId, documento).orElse(false);
    }

    /** R012 (1): la línea tuvo alguna aceptación de mercancía, aunque después se haya anulado. */
    public boolean tuvoAceptacion(long detalleCompraId) {
        return consultarUno("SELECT count(*) AS n FROM bitacora WHERE entidad = 'DETALLE_COMPRA' AND entidad_id = ? "
                        + "AND detalle ->> 'operacion' = 'RECEPCION' AND jsonb_typeof(detalle -> 'cantidad_aceptada') = 'number' "
                        + "AND (detalle ->> 'cantidad_aceptada')::numeric > 0",
                fila -> fila.getInt("n") > 0, detalleCompraId).orElse(false);
    }
}
