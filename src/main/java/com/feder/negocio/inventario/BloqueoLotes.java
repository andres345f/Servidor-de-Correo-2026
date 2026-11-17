package com.feder.negocio.inventario;

import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.negocio.comun.NegocioException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Aplica el orden global de bloqueo de R037 a un conjunto de lotes: primero sus lotes de origen con
 * FOR SHARE (para leer la fecha de vencimiento sin que cambie, R035) y después los LOTE_FEDER con
 * FOR UPDATE, cada tabla en orden de identificador. Dos transacciones que bloquean lotes en común
 * siempre lo hacen en el mismo orden, así no se interbloquean entre ellas.
 */
final class BloqueoLotes {

    private final LoteFederRepositorio lotes;

    BloqueoLotes(LoteFederRepositorio lotes) {
        this.lotes = lotes;
    }

    /** Devuelve los lotes releídos después de bloquearlos, ordenados por identificador. */
    Map<Long, LoteFederVista> bloquear(Collection<LoteFederVista> candidatos) {
        TreeSet<Long> origenes = new TreeSet<>();
        TreeSet<Long> ids = new TreeSet<>();
        candidatos.forEach(l -> {
            origenes.add(l.loteOrigenId());
            ids.add(l.id());
        });
        origenes.forEach(lotes::bloquearOrigenCompartido);
        Map<Long, LoteFederVista> bloqueados = new LinkedHashMap<>();
        for (Long id : ids) {
            bloqueados.put(id, lotes.bloquear(id)
                    .orElseThrow(() -> NegocioException.noEncontrado("El lote de FEDER " + id + " ya no existe.")));
        }
        return bloqueados;
    }

    LoteFederVista bloquearUno(LoteFederVista lote) {
        return bloquear(List.of(lote)).get(lote.id());
    }
}
