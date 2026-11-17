package com.feder.negocio.entregas;

import java.math.BigDecimal;

/**
 * Datos de un retorno físico de mercancía despachada (R029): la reserva, lo recibido, lo apto según la
 * inspección, el tipo de baja de lo no apto, el motivo, la evidencia y la clave de la operación.
 *
 * @param clave nula para que FEDER genere una; reenviar la misma clave no duplica el retorno.
 */
public record SolicitudRetorno(String codigoPedido, long reservaId, BigDecimal recibida, BigDecimal apta, TipoBajaRetorno tipoBaja,
                               String motivo, String evidencia, String clave) {

    /** Clase de baja de las unidades retornadas no aptas; si el lote de origen venció se usa BAJA_VENCIDO. */
    public enum TipoBajaRetorno {
        DANADO,
        DEFECTUOSO
    }
}
