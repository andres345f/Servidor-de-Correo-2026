package com.feder.negocio.produccion;

import com.feder.datos.produccion.TrazabilidadRepositorio;
import com.feder.datos.produccion.TrazabilidadRepositorio.TrazaLote;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

/**
 * Trazabilidad de origen de un lote de producción (R066): muestra el origen con la instantánea guardada al crear
 * la producción e indica si la comunidad está hoy inactiva. A clientes y visitantes no se muestran los datos
 * personales del productor ni la ubicación de elaboración.
 */
public class ServicioTrazabilidad {

    /** Recorrido del lote, cómo se declaró su procedencia y si quien consulta puede ver los datos del productor. */
    public record Trazabilidad(TrazaLote traza, String procedencia, boolean vistaCompleta) {
    }

    private final ContextoNegocio ctx;
    private final TrazabilidadRepositorio trazas = new TrazabilidadRepositorio();
    private final TitularProduccion titular = new TitularProduccion();

    public ServicioTrazabilidad(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    public Trazabilidad trazar(Solicitante solicitante, String codigoLote) {
        return ctx.transacciones().enTransaccion(() -> {
            TrazaLote traza = trazas.porCodigoLote(codigoLote)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe el lote " + codigoLote + "."));
            String procedencia = trazas.origenDeclarado(traza.produccion().getId()).orElse(OrigenProduccion.ORIGEN_VIGENTE);
            return new Trazabilidad(traza, procedencia, titular.veDatosDelProductor(solicitante, traza.usuarioProductorId()));
        });
    }
}
