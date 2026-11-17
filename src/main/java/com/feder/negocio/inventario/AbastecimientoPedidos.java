package com.feder.negocio.inventario;

import com.feder.datos.inventario.AbastecimientoRepositorio;
import com.feder.datos.inventario.AbastecimientoRepositorio.LineaPendiente;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.HistorialPedido;
import com.feder.modelo.Pedido;
import com.feder.modelo.ReservaStock;
import com.feder.modelo.TipoNotificacion;
import com.feder.modelo.TipoPedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.notificaciones.NotificadorCliente;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Abastecimiento de pedidos con stock de FEDER (PEDIDO «Encargo sin stock ficticio», R056): reserva
 * unidades para las líneas pendientes de los ENCARGO en ESPERANDO_ABASTECIMIENTO y de los pedidos
 * CONFIRMADO o EN_PREPARACION que perdieron cobertura. Ocurre al registrar una ENTRADA_COMPRA (en la
 * misma transacción) o cuando el personal abastece un pedido con stock vendible.
 *
 * Procedimiento en dos pasos para respetar el orden global de R037: {@link #esperaDeProducto} o
 * {@link #esperaDePedido} identifican los candidatos sin bloquear y bloquean PEDIDO y DETALLE_PEDIDO;
 * después {@link #abastecer} reserva (con los lotes bloqueados) y confirma los encargos cubiertos.
 */
final class AbastecimientoPedidos {

    private final ContextoNegocio ctx;
    private final AbastecimientoRepositorio pendientes;
    private final PedidoRepositorio pedidos;
    private final HistorialPedidoRepositorio historial;
    private final ReservasDeStock reservas;

    AbastecimientoPedidos(ContextoNegocio ctx, AbastecimientoRepositorio pendientes, PedidoRepositorio pedidos,
                          HistorialPedidoRepositorio historial, ReservasDeStock reservas) {
        this.ctx = ctx;
        this.pendientes = pendientes;
        this.pedidos = pedidos;
        this.historial = historial;
        this.reservas = reservas;
    }

    /** Pedidos ya bloqueados y sus líneas pendientes, en el orden de prioridad del reparto. */
    record Espera(Map<Long, Pedido> pedidos, List<LineaPendiente> lineas) {

        static final Espera NINGUNA = new Espera(Map.of(), List.of());

        boolean vacia() {
            return lineas.isEmpty();
        }
    }

    /** R056 (a): líneas que esperan el producto recibido, primero las respaldadas por la producción del lote. */
    Espera esperaDeProducto(long productoId, long loteOrigenId) {
        return bloquear(pendientes.deProducto(productoId, loteOrigenId));
    }

    /** R056 (b): líneas pendientes de un pedido concreto. */
    Espera esperaDePedido(long pedidoId) {
        return bloquear(pendientes.dePedido(pedidoId));
    }

    /**
     * Bloquea los pedidos y después todas sus líneas, cada tabla por identificador, y relee su estado:
     * un pedido que dejó de esperar (por ejemplo, cancelado) se salta.
     */
    private Espera bloquear(List<LineaPendiente> candidatas) {
        if (candidatas.isEmpty()) {
            return Espera.NINGUNA;
        }
        Map<Long, Pedido> bloqueados = new LinkedHashMap<>();
        for (Long id : new TreeSet<>(candidatas.stream().map(LineaPendiente::pedidoId).toList())) {
            pedidos.bloquear(id).filter(AbastecimientoPedidos::admiteAbastecimiento).ifPresent(p -> bloqueados.put(id, p));
        }
        TreeSet<Long> lineasIds = new TreeSet<>();
        bloqueados.keySet().forEach(id -> pedidos.lineas(id).forEach(l -> lineasIds.add(l.getId())));
        reservas.bloquearLineas(lineasIds);
        List<LineaPendiente> vigentes = candidatas.stream().filter(l -> bloqueados.containsKey(l.pedidoId())).toList();
        return new Espera(bloqueados, vigentes);
    }

    static boolean admiteAbastecimiento(Pedido p) {
        return (p.getTipo() == TipoPedido.ENCARGO && p.getEstado() == EstadoPedido.ESPERANDO_ABASTECIMIENTO)
                || p.getEstado() == EstadoPedido.CONFIRMADO || p.getEstado() == EstadoPedido.EN_PREPARACION;
    }

    /**
     * Reserva para las líneas en espera hasta {@code tope} unidades (nulo = todo el stock vendible) y pasa
     * a CONFIRMADO cada ENCARGO cuyas líneas quedaron todas cubiertas. Un pedido CONFIRMADO o
     * EN_PREPARACION que recupera cobertura conserva su estado.
     *
     * @param origen texto del historial: por qué se abasteció (recepción de una compra o abastecimiento manual).
     */
    List<PedidoAbastecido> abastecer(Espera espera, BigDecimal tope, long usuarioId, String origen) {
        if (espera.vacia()) {
            return List.of();
        }
        List<LineaPorReservar> enPrioridad = espera.lineas().stream()
                .map(l -> new LineaPorReservar(l.detallePedidoId(), l.pendiente())).toList();
        List<ReservaStock> creadas = reservas.reservarDisponible(enPrioridad, tope);
        Map<Long, BigDecimal> reservadoPorLinea = new LinkedHashMap<>();
        creadas.forEach(r -> reservadoPorLinea.merge(r.getDetallePedidoId(), r.getCantidad(), BigDecimal::add));
        List<PedidoAbastecido> resultado = new ArrayList<>();
        for (Pedido pedido : espera.pedidos().values()) {
            BigDecimal reservado = pedidos.lineas(pedido.getId()).stream().map(DetallePedido::getId)
                    .map(id -> reservadoPorLinea.getOrDefault(id, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean cubierto = pendientes.lineasSinCubrir(pedido.getId()) == 0;
            boolean confirmado = cubierto && pedido.getEstado() == EstadoPedido.ESPERANDO_ABASTECIMIENTO
                    && confirmarEncargo(pedido, usuarioId, origen);
            if (reservado.signum() > 0 || confirmado) {
                resultado.add(new PedidoAbastecido(pedido.getCodigo(), reservado, cubierto, confirmado));
            }
        }
        return resultado;
    }

    /**
     * ESPERANDO_ABASTECIMIENTO -> CONFIRMADO, con su HISTORIAL_PEDIDO y el aviso ENCARGO_ABASTECIDO al cliente.
     * Es la única transición que hace el inventario; la escribe aquí porque CU4 no depende del módulo de ventas.
     */
    private boolean confirmarEncargo(Pedido pedido, long usuarioId, String origen) {
        if (!pedidos.cambiarEstado(pedido.getId(), EstadoPedido.ESPERANDO_ABASTECIMIENTO, EstadoPedido.CONFIRMADO)) {
            return false;
        }
        HistorialPedido h = new HistorialPedido();
        h.setPedidoId(pedido.getId());
        h.setUsuarioId(usuarioId);
        h.setEstadoAnterior(EstadoPedido.ESPERANDO_ABASTECIMIENTO);
        h.setEstadoNuevo(EstadoPedido.CONFIRMADO);
        h.setFecha(ctx.ahora());
        h.setComentario(origen.length() <= 255 ? origen : origen.substring(0, 255));
        h.setId(historial.registrar(h));
        pedido.setEstado(EstadoPedido.CONFIRMADO);
        // R046 y CU6: el aviso de encargo abastecido también llega por correo al cliente.
        new NotificadorCliente(ctx).avisarConCorreo(pedido, TipoNotificacion.ENCARGO_ABASTECIDO,
                "Tu encargo está abastecido", "El encargo " + pedido.getCodigo() + " ya tiene reservadas todas sus unidades "
                        + "y pasó a CONFIRMADO.");
        return true;
    }
}
