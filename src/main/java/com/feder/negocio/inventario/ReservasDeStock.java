package com.feder.negocio.inventario;

import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.inventario.ReservaRepositorio.LineaBloqueada;
import com.feder.modelo.EstadoReserva;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.Producto;
import com.feder.modelo.ReservaStock;
import com.feder.modelo.TipoMovimiento;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.ServicioStock;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.comun.NegocioException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ciclo de las reservas de stock: reservar (R037, R086), liberar (R084) y consumir en el despacho (R083).
 * Lo usa {@link ServicioInventario}, que es la entrada pública para CU5 y CU6.
 */
final class ReservasDeStock {

    private final ContextoNegocio ctx;
    private final ServicioStock stock;
    private final LoteFederRepositorio lotes;
    private final ReservaRepositorio reservas;
    private final MovimientoRepositorio movimientos;
    private final ProductoRepositorio productos;
    private final BloqueoLotes bloqueo;

    ReservasDeStock(ContextoNegocio ctx, ServicioStock stock, LoteFederRepositorio lotes, ReservaRepositorio reservas,
                    MovimientoRepositorio movimientos, ProductoRepositorio productos) {
        this.ctx = ctx;
        this.stock = stock;
        this.lotes = lotes;
        this.reservas = reservas;
        this.movimientos = movimientos;
        this.productos = productos;
        this.bloqueo = new BloqueoLotes(lotes);
    }

    List<ReservaStock> reservar(List<LineaPorReservar> pedidas, OffsetDateTime expiracion) {
        // 1. Líneas de pedido bloqueadas por identificador (R086) antes que cualquier fila de inventario.
        List<LineaPorReservar> ordenadas = pedidas.stream()
                .sorted(Comparator.comparingLong(LineaPorReservar::detallePedidoId)).toList();
        Map<Long, LineaBloqueada> lineas = new LinkedHashMap<>();
        for (LineaPorReservar pedida : ordenadas) {
            LineaBloqueada linea = reservas.bloquearLinea(pedida.detallePedidoId())
                    .orElseThrow(() -> NegocioException.noEncontrado("La línea de pedido " + pedida.detallePedidoId() + " no existe."));
            BigDecimal sinCubrir = linea.cantidad().subtract(reservas.cubiertaDeLinea(linea.id()));
            if (pedida.cantidad().signum() <= 0 || pedida.cantidad().compareTo(sinCubrir) > 0) {
                throw NegocioException.regla("La reserva de " + Cantidades.formatear(pedida.cantidad())
                        + " excede lo que falta cubrir de la línea (" + Cantidades.formatear(sinCubrir) + ").");
            }
            lineas.put(linea.id(), linea);
        }
        TreeSet<Long> productoIds = new TreeSet<>();
        lineas.values().forEach(l -> productoIds.add(l.productoId()));
        return reservarBloqueando(ordenadas, lineas, productoIds, expiracion);
    }

    private List<ReservaStock> reservarBloqueando(List<LineaPorReservar> ordenadas, Map<Long, LineaBloqueada> lineas,
                                                  TreeSet<Long> productoIds, OffsetDateTime expiracion) {
        LocalDate hoy = stock.hoy();
        Map<Long, LoteFederVista> bloqueados = bloquearCandidatos(productoIds, List.of());
        Map<Long, BigDecimal> libres = libresVendibles(bloqueados, Set.of(), hoy);
        // La alerta de stock se evalúa con los lotes ya bloqueados: dos reservas del mismo producto
        // se serializan y solo la que deja el producto en alerta avisa al personal (R070).
        return stock.vigilando(productoIds, () -> asignarYReservar(ordenadas, lineas, bloqueados, libres, hoy, expiracion));
    }

    /**
     * Pasos 2 y 3 de R037: candidatos vendibles de los productos calculados sin bloquear (más los lotes
     * {@code extra}), bloqueados en el orden global y releídos con sus valores bloqueados.
     */
    Map<Long, LoteFederVista> bloquearCandidatos(Collection<Long> productoIds, Collection<LoteFederVista> extra) {
        LocalDate hoy = stock.hoy();
        List<LoteFederVista> candidatos = new ArrayList<>(extra);
        new TreeSet<>(productoIds).forEach(id -> candidatos.addAll(lotes.candidatosVendibles(id, hoy)));
        return bloqueo.bloquear(candidatos);
    }

    private static Map<Long, BigDecimal> libresVendibles(Map<Long, LoteFederVista> bloqueados, Set<Long> excluidos, LocalDate hoy) {
        Map<Long, BigDecimal> libres = new HashMap<>();
        bloqueados.values().stream().filter(l -> l.vendible(hoy) && !excluidos.contains(l.id()))
                .forEach(l -> libres.put(l.id(), l.libre().max(BigDecimal.ZERO)));
        return libres;
    }

    /** Cantidad pedida para una línea, con el plazo de la reserva que se cree (nulo si no caduca). */
    record Pedida(long detallePedidoId, BigDecimal cantidad, OffsetDateTime expiracion) {
    }

    /**
     * R056: reserva para las líneas indicadas, en el orden recibido (la prioridad comercial), todo lo que
     * permita el stock vendible y como máximo {@code tope} en total (nulo = sin tope). A diferencia de
     * {@link #reservar}, una línea puede quedar cubierta solo en parte. El llamador ya bloqueó los pedidos
     * y evalúa la alerta de stock (R070) alrededor de toda su operación.
     */
    List<ReservaStock> reservarDisponible(List<LineaPorReservar> enPrioridad, BigDecimal tope) {
        if (enPrioridad.isEmpty()) {
            return List.of();
        }
        Map<Long, LineaBloqueada> lineas = bloquearLineas(enPrioridad.stream().map(LineaPorReservar::detallePedidoId).toList());
        TreeSet<Long> productoIds = new TreeSet<>();
        lineas.values().forEach(l -> productoIds.add(l.productoId()));
        Map<Long, LoteFederVista> bloqueados = bloquearCandidatos(productoIds, List.of());
        List<Pedida> pedidas = enPrioridad.stream().map(l -> new Pedida(l.detallePedidoId(), l.cantidad(), null)).toList();
        return asignarDisponible(pedidas, lineas, bloqueados, Set.of(), tope);
    }

    /** Bloquea las líneas de pedido por identificador (R086) y las devuelve indexadas por id. */
    Map<Long, LineaBloqueada> bloquearLineas(Collection<Long> detallePedidoIds) {
        Map<Long, LineaBloqueada> lineas = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(detallePedidoIds)) {
            lineas.put(id, reservas.bloquearLinea(id)
                    .orElseThrow(() -> NegocioException.noEncontrado("La línea de pedido " + id + " no existe.")));
        }
        return lineas;
    }

    /**
     * Reparto FEFO con los lotes ya bloqueados, sin exceder lo que falta cubrir de cada línea (R086) ni el
     * tope. Los lotes {@code excluidos} (por ejemplo, el que se acaba de bloquear) no se usan.
     */
    List<ReservaStock> asignarDisponible(List<Pedida> pedidas, Map<Long, LineaBloqueada> lineas,
                                         Map<Long, LoteFederVista> bloqueados, Set<Long> excluidos, BigDecimal tope) {
        LocalDate hoy = stock.hoy();
        Map<Long, BigDecimal> libres = libresVendibles(bloqueados, excluidos, hoy);
        BigDecimal restante = tope;
        List<ReservaStock> creadas = new ArrayList<>();
        for (Pedida pedida : pedidas) {
            LineaBloqueada linea = lineas.get(pedida.detallePedidoId());
            BigDecimal cantidad = pedida.cantidad().min(linea.cantidad().subtract(reservas.cubiertaDeLinea(linea.id())));
            if (restante != null) {
                cantidad = cantidad.min(restante);
            }
            if (cantidad.signum() <= 0) {
                continue;
            }
            List<LoteFederVista> delProducto = bloqueados.values().stream()
                    .filter(l -> l.productoId() == linea.productoId() && libres.containsKey(l.id())).toList();
            List<AsignadorFefo.Asignacion> asignaciones = AsignadorFefo.asignar(delProducto, libres, cantidad);
            for (AsignadorFefo.Asignacion a : asignaciones) {
                if (!lotes.reservar(a.loteFederId(), a.cantidad())) {
                    throw stockInsuficiente(linea.productoId(), a.cantidad());
                }
                creadas.add(insertarReserva(a, linea.id(), pedida.expiracion()));
            }
            if (restante != null) {
                restante = restante.subtract(AsignadorFefo.total(asignaciones));
            }
        }
        return creadas;
    }

    /**
     * R084: cierra las reservas indicadas (con sus lotes ya bloqueados) y descuenta lo reservado del lote.
     * Solo quien logra sacar la reserva de ACTIVA descuenta su cantidad. Devuelve las que cerró.
     */
    List<ReservaStock> liberarReservas(List<ReservaStock> activas, EstadoReserva destino) {
        if (destino != EstadoReserva.LIBERADA && destino != EstadoReserva.EXPIRADA) {
            throw new IllegalArgumentException("Una reserva solo se libera como LIBERADA o EXPIRADA");
        }
        List<ReservaStock> liberadas = new ArrayList<>();
        OffsetDateTime ahora = ctx.ahora();
        for (ReservaStock r : activas) {
            if (reservas.cerrar(r.getId(), destino, ahora)) {
                lotes.liberarReservado(r.getLoteFederId(), r.getCantidad());
                liberadas.add(r);
            }
        }
        return liberadas;
    }

    /** 4. Reparto FEFO con los valores bloqueados y UPDATE condicional por lote. */
    private List<ReservaStock> asignarYReservar(List<LineaPorReservar> ordenadas, Map<Long, LineaBloqueada> lineas,
                                                Map<Long, LoteFederVista> bloqueados, Map<Long, BigDecimal> libres,
                                                LocalDate hoy, OffsetDateTime expiracion) {
        List<ReservaStock> creadas = new ArrayList<>();
        for (LineaPorReservar pedida : ordenadas) {
            LineaBloqueada linea = lineas.get(pedida.detallePedidoId());
            List<LoteFederVista> delProducto = bloqueados.values().stream()
                    .filter(l -> l.productoId() == linea.productoId() && l.vendible(hoy)).toList();
            List<AsignadorFefo.Asignacion> asignaciones = AsignadorFefo.asignar(delProducto, libres, pedida.cantidad());
            if (AsignadorFefo.total(asignaciones).compareTo(pedida.cantidad()) < 0) {
                throw stockInsuficiente(linea.productoId(), pedida.cantidad());
            }
            for (AsignadorFefo.Asignacion a : asignaciones) {
                if (!lotes.reservar(a.loteFederId(), a.cantidad())) {
                    throw stockInsuficiente(linea.productoId(), pedida.cantidad());
                }
                creadas.add(insertarReserva(a, linea.id(), expiracion));
            }
        }
        return creadas;
    }

    private ReservaStock insertarReserva(AsignadorFefo.Asignacion a, long detallePedidoId, OffsetDateTime expiracion) {
        ReservaStock r = new ReservaStock();
        r.setLoteFederId(a.loteFederId());
        r.setDetallePedidoId(detallePedidoId);
        r.setCantidad(a.cantidad());
        r.setEstado(EstadoReserva.ACTIVA);
        r.setFechaCreacion(ctx.ahora());
        r.setFechaExpiracion(expiracion);
        r.setId(reservas.insertar(r));
        return r;
    }

    int liberar(long pedidoId, EstadoReserva destino) {
        List<ReservaStock> activas = reservas.activasDePedido(pedidoId);
        TreeSet<Long> ids = new TreeSet<>();
        activas.forEach(r -> ids.add(r.getLoteFederId()));
        ids.forEach(id -> lotes.bloquear(id));
        return liberarReservas(activas, destino).size();
    }

    List<MovimientoInventario> consumir(long pedidoId, long usuarioId) {
        LocalDate hoy = stock.hoy();
        for (LineaBloqueada linea : reservas.bloquearLineasDePedido(pedidoId)) {
            if (reservas.activaDeLinea(linea.id()).compareTo(linea.cantidad()) != 0) {
                throw NegocioException.estadoInvalido("El pedido no puede despacharse: la línea del producto "
                        + codigoProducto(linea.productoId()) + " no está cubierta por completo con reservas activas.");
            }
        }
        List<ReservaStock> activas = reservas.activasDePedido(pedidoId);
        List<LoteFederVista> afectados = new ArrayList<>();
        activas.forEach(r -> afectados.add(lotes.buscarPorId(r.getLoteFederId()).orElseThrow()));
        Map<Long, LoteFederVista> bloqueados = bloqueo.bloquear(afectados);
        for (LoteFederVista lote : bloqueados.values()) {
            if (!lote.vendible(hoy)) {
                throw NegocioException.regla("El lote " + lote.codigo() + " está " + (lote.vencido(hoy) ? "vencido" : lote.estado())
                        + ": no se despacha desde él. Sus reservas deben liberarse y reasignarse antes del despacho.");
            }
        }
        List<MovimientoInventario> salidas = new ArrayList<>();
        OffsetDateTime ahora = ctx.ahora();
        for (ReservaStock r : activas) {
            if (!reservas.cerrar(r.getId(), EstadoReserva.CONSUMIDA, ahora)) {
                throw NegocioException.estadoInvalido("Una reserva del pedido cambió de estado durante el despacho.");
            }
            MovimientoInventario salida = new MovimientoInventario();
            salida.setLoteFederId(r.getLoteFederId());
            salida.setUsuarioId(usuarioId);
            salida.setReservaId(r.getId());
            salida.setTipo(TipoMovimiento.SALIDA_VENTA);
            salida.setCantidad(r.getCantidad());
            salida.setFecha(ahora);
            salida.setClaveOperacion("DESP-RES-" + r.getId());
            salida.setId(movimientos.insertar(salida));
            lotes.consumirReservado(r.getLoteFederId(), r.getCantidad());
            salidas.add(salida);
        }
        return salidas;
    }

    NegocioException stockInsuficiente(long productoId, BigDecimal pedida) {
        Producto p = productos.buscarPorId(productoId).orElseThrow().producto();
        return new NegocioException(NegocioException.Codigo.STOCK_INSUFICIENTE, "Stock insuficiente de " + p.getNombre()
                + " (" + p.getCodigo() + "): se pidieron " + Cantidades.formatear(pedida) + " y el stock vendible es "
                + Cantidades.formatear(stock.vendible(productoId)) + " " + p.getUnidadMedida() + ".");
    }

    private String codigoProducto(long productoId) {
        return productos.buscarPorId(productoId).map(p -> p.producto().getCodigo()).orElse(String.valueOf(productoId));
    }
}
