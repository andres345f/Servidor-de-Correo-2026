package com.feder.negocio.produccion;

import com.feder.datos.produccion.LoteProduccionRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoLoteProduccion;
import com.feder.modelo.LoteProduccion;
import com.feder.modelo.Produccion;
import com.feder.modelo.UnidadMedida;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.usuarios.FormatoNegocio;

/**
 * Registro de un lote de origen («Asignar lotes» de CU3): valida, asigna el código de trazabilidad,
 * lo vincula con su producción y deja constancia en BITACORA (R040). Lo usan el alta de lotes y el
 * alta directa de una producción TERMINADA, que crea su primer lote en la misma transacción (R061).
 *
 * La validación está separada del registro para comprobar el lote antes de escribir la producción.
 */
class AltaLote {

    private final ContextoNegocio ctx;
    private final LoteProduccionRepositorio lotes = new LoteProduccionRepositorio();

    AltaLote(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    /** Arma y valida el lote contra los datos de su producción, sin escribir nada. */
    LoteProduccion preparar(Produccion produccion, UnidadMedida unidad, ServicioLotes.DatosLote datos) {
        LoteProduccion lote = new LoteProduccion();
        lote.setFechaElaboracion(datos.fechaElaboracion());
        lote.setFechaVencimiento(datos.fechaVencimiento());
        lote.setCantidadProducida(datos.cantidadProducida());
        lote.setCantidadOfrecida(datos.cantidadOfrecida());
        lote.setPrecioOfrecido(ReglasCantidades.precio(datos.precioOfrecido()));
        lote.setEstado(EstadoLoteProduccion.DISPONIBLE);
        ReglasLote.validar(lote, unidad, produccion.getFechaInicio(), FormatoNegocio.hoy(ctx.reloj()));
        return lote;
    }

    /** Registra un lote ya preparado en la producción indicada. */
    LoteProduccion registrar(long autorId, Produccion produccion, LoteProduccion lote) {
        long id = lotes.reservarId();
        lote.setId(id);
        lote.setProduccionId(produccion.getId());
        lote.setCodigoLote(String.format("LP-%d-%03d", FormatoNegocio.hoy(ctx.reloj()).getYear(), id));
        lotes.insertar(lote);
        ctx.bitacora().registrar(autorId, AccionBitacora.CREAR, "LOTE_PRODUCCION", id, DetalleJson.nuevo()
                .con("codigo_lote", lote.getCodigoLote())
                .con("produccion_id", produccion.getId())
                .con("cantidad_producida", lote.getCantidadProducida())
                .con("cantidad_ofrecida", lote.getCantidadOfrecida()));
        return lote;
    }

    LoteProduccion crear(long autorId, Produccion produccion, UnidadMedida unidad, ServicioLotes.DatosLote datos) {
        return registrar(autorId, produccion, preparar(produccion, unidad, datos));
    }
}
