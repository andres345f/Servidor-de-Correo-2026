package com.feder.negocio.indicadores;

import com.feder.datos.reportes.IndicadoresInventarioRepositorio.MercanciaDespachada;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Mercancía despachada que a una fecha de corte no está en el inventario ni entregada: pendiente de entrega
 * (EN_CAMINO, FALLIDA o LISTA_PARA_RECOJO) o en entregas NO_RECUPERADA pendientes de cancelación.
 *
 * El estado de cada entrega se toma de sus transiciones en BITACORA. Si para algún pedido DESPACHADO al corte
 * esas transiciones faltan o no encadenan, el resultado no es determinable y no se estima.
 */
public record SituacionDespachos(LocalDate corte, List<MercanciaDespachada> filas, List<String> pedidosNoDeterminables) {

    public boolean esDeterminable() {
        return pedidosNoDeterminables.isEmpty();
    }

    /** Texto con el que todos los indicadores y reportes informan un resultado no determinable. */
    public String describirNoDeterminable() {
        return "No determinable: faltan o no encadenan las transiciones de entrega registradas en la bitácora "
                + "de los pedidos " + String.join(", ", pedidosNoDeterminables);
    }

    public List<MercanciaDespachada> pendientesDeEntrega() {
        return filas.stream().filter(f -> !f.noRecuperada()).toList();
    }

    public List<MercanciaDespachada> noRecuperadas() {
        return filas.stream().filter(MercanciaDespachada::noRecuperada).toList();
    }

    public Optional<BigDecimal> costoPendienteDeEntrega() {
        return sumarSiDeterminable(f -> !f.noRecuperada());
    }

    /** Costo no recuperado pendiente de cancelación; vacío si no es determinable. */
    public Optional<BigDecimal> costoNoRecuperado() {
        return sumarSiDeterminable(MercanciaDespachada::noRecuperada);
    }

    private Optional<BigDecimal> sumarSiDeterminable(Predicate<MercanciaDespachada> filtro) {
        if (!esDeterminable()) {
            return Optional.empty();
        }
        return Optional.of(filas.stream().filter(filtro).map(MercanciaDespachada::costo)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
