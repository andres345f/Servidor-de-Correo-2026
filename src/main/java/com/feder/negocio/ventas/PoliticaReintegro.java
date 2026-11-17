package com.feder.negocio.ventas;

import com.feder.modelo.Pedido;

import java.math.BigDecimal;
import java.util.List;

/**
 * Qué hacer con los pagos cobrados de un pedido que se cancela (R031, R052: los pagos PAGADO se
 * reintegran con REEMBOLSO). Se invoca dentro de la transacción de la cancelación, con el pedido ya
 * en CANCELADO. CU6 puede sustituir la implementación por defecto ({@link ReintegroPendiente}).
 */
public interface PoliticaReintegro {

    /** @return importes de los reintegros solicitados (vacío si el pedido no tenía cobros). */
    List<BigDecimal> alCancelar(Pedido pedido, Long responsableId, String motivo);
}
