package com.feder.negocio.inventario;

import com.feder.datos.inventario.AbastecimientoRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.inventario.ReservaRepositorio.LineaBloqueada;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoLoteFeder;
import com.feder.modelo.EstadoReserva;
import com.feder.modelo.Pedido;
import com.feder.modelo.ReservaStock;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.ServicioStock;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * LOTE_FEDER «Bloqueo o vencimiento» (R035): pasa un lote a BLOQUEADO o VENCIDO y, en la misma
 * transacción, libera sus reservas ACTIVA y las reasigna con FEFO desde otros lotes vendibles. Si no
 * alcanza el stock, la línea queda con cantidad pendiente y su pedido no se despacha hasta reasignarla
 * (abastecimiento manual) o cancelarlo.
 *
 * Orden de bloqueo (R037): los pedidos afectados, sus líneas, y después el lote junto con los
 * candidatos para reasignar en una sola pasada por identificador.
 */
final class InhabilitacionDeLotes {

    private final ContextoNegocio ctx;
    private final ServicioStock stock;
    private final LoteFederRepositorio lotes;
    private final ReservaRepositorio reservas;
    private final PedidoRepositorio pedidos;
    private final AbastecimientoRepositorio pendientes;
    private final ReservasDeStock reservasDeStock;

    InhabilitacionDeLotes(ContextoNegocio ctx, ServicioStock stock, LoteFederRepositorio lotes, ReservaRepositorio reservas,
                          PedidoRepositorio pedidos, AbastecimientoRepositorio pendientes, ReservasDeStock reservasDeStock) {
        this.ctx = ctx;
        this.stock = stock;
        this.lotes = lotes;
        this.reservas = reservas;
        this.pedidos = pedidos;
        this.pendientes = pendientes;
        this.reservasDeStock = reservasDeStock;
    }


    LoteInhabilitado inhabilitar(LoteFederVista sinBloqueo, EstadoLoteFeder destino, long usuarioId, String motivo) {
        if (destino != EstadoLoteFeder.BLOQUEADO && destino != EstadoLoteFeder.VENCIDO) {
            throw new IllegalArgumentException("Un lote solo se inhabilita como BLOQUEADO o VENCIDO");
        }
        Map<Long, Pedido> afectados = new LinkedHashMap<>();
        for (Long pedidoId : reservas.pedidosConReservaActivaEnLote(sinBloqueo.id())) {
            pedidos.bloquear(pedidoId).ifPresent(p -> afectados.put(pedidoId, p));
        }
        TreeSet<Long> lineasIds = new TreeSet<>();
        reservas.activasDeLote(sinBloqueo.id()).forEach(r -> lineasIds.add(r.getDetallePedidoId()));
        Map<Long, LineaBloqueada> lineas = reservasDeStock.bloquearLineas(lineasIds);
        Map<Long, LoteFederVista> bloqueados = reservasDeStock.bloquearCandidatos(List.of(sinBloqueo.productoId()), List.of(sinBloqueo));
        LoteFederVista lote = bloqueados.get(sinBloqueo.id());
        if (lote.estado() == EstadoLoteFeder.BLOQUEADO || lote.estado() == EstadoLoteFeder.VENCIDO) {
            throw NegocioException.estadoInvalido("El lote " + lote.codigo() + " ya está " + lote.estado() + ".");
        }
        return stock.vigilando(List.of(lote.productoId()), () -> {
            if (!lotes.inhabilitar(lote.id(), destino)) {
                throw NegocioException.estadoInvalido("El lote " + lote.codigo() + " cambió de estado durante la operación.");
            }
            List<ReservaStock> activas = reservas.activasDeLote(lote.id());
            if (!lineasIds.containsAll(activas.stream().map(ReservaStock::getDetallePedidoId).toList())) {
                throw NegocioException.estadoInvalido("El lote " + lote.codigo() + " recibió reservas nuevas durante la operación: "
                        + "vuelva a intentarlo.");
            }
            List<ReservaStock> liberadas = reservasDeStock.liberarReservas(activas, EstadoReserva.LIBERADA);
            List<ReservasDeStock.Pedida> pedidas = liberadas.stream()
                    .map(r -> new ReservasDeStock.Pedida(r.getDetallePedidoId(), r.getCantidad(), r.getFechaExpiracion())).toList();
            List<ReservaStock> nuevas = reservasDeStock.asignarDisponible(pedidas, lineas, bloqueados, Set.of(lote.id()), null);
            LoteInhabilitado resultado = new LoteInhabilitado(lotes.buscarPorId(lote.id()).orElseThrow(), lote.estado(), liberadas.size(),
                    suma(liberadas), suma(nuevas), sinCobertura(afectados));
            ctx.bitacora().registrar(usuarioId, AccionBitacora.MODIFICAR, "LOTE_FEDER", lote.id(),
                    DetalleJson.nuevo().con("estado_anterior", lote.estado()).con("estado_nuevo", destino)
                            .con("motivo", motivo).con("reservas_liberadas", liberadas.size())
                            .con("cantidad_liberada", resultado.liberado()).con("cantidad_reasignada", resultado.reasignado())
                            .con("pedidos_sin_cobertura", String.join(",", resultado.pedidosSinCobertura())));
            return resultado;
        });
    }

    private List<String> sinCobertura(Map<Long, Pedido> afectados) {
        List<String> codigos = new ArrayList<>();
        afectados.values().stream().filter(p -> pendientes.lineasSinCubrir(p.getId()) > 0)
                .forEach(p -> codigos.add(p.getCodigo()));
        return codigos;
    }

    private static BigDecimal suma(List<ReservaStock> lista) {
        return lista.stream().map(ReservaStock::getCantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
