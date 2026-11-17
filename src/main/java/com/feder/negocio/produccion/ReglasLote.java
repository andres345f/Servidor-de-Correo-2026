package com.feder.negocio.produccion;

import com.feder.modelo.LoteProduccion;
import com.feder.modelo.UnidadMedida;
import com.feder.negocio.comun.NegocioException;

import java.time.LocalDate;

/**
 * Coherencia de los datos de un lote de origen (R040 y notas de LOTE_PRODUCCION): cantidades, vencimiento
 * con elaboración conocida, elaboración no anterior al inicio de su producción ni posterior a hoy.
 */
final class ReglasLote {

    private ReglasLote() {
    }

    static void validar(LoteProduccion lote, UnidadMedida unidad, LocalDate inicioProduccion, LocalDate hoy) {
        ReglasCantidades.positiva(lote.getCantidadProducida(), unidad, "La cantidad producida");
        ReglasCantidades.noNegativa(lote.getCantidadOfrecida(), unidad, "La cantidad ofrecida");
        if (lote.getCantidadOfrecida().compareTo(lote.getCantidadProducida()) > 0) {
            throw NegocioException.regla("La cantidad ofrecida a FEDER no puede superar la cantidad producida del lote.");
        }
        LocalDate elaboracion = lote.getFechaElaboracion();
        LocalDate vencimiento = lote.getFechaVencimiento();
        if (vencimiento != null && elaboracion == null) {
            throw NegocioException.regla("Un lote con fecha de vencimiento debe indicar su fecha de elaboración.");
        }
        if (elaboracion == null) {
            return;
        }
        // La elaboración es un hecho ya ocurrido: no se registra una fecha futura.
        if (elaboracion.isAfter(hoy)) {
            throw NegocioException.regla("La fecha de elaboración no puede ser posterior a hoy (" + hoy + ").");
        }
        if (vencimiento != null && vencimiento.isBefore(elaboracion)) {
            throw NegocioException.regla("La fecha de vencimiento no puede ser anterior a la fecha de elaboración.");
        }
        if (inicioProduccion != null && elaboracion.isBefore(inicioProduccion)) {
            throw NegocioException.regla("La fecha de elaboración del lote no puede ser anterior a la fecha de inicio de su "
                    + "producción (" + inicioProduccion + ").");
        }
    }
}
