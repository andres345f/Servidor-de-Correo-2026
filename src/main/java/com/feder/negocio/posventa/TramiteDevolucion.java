package com.feder.negocio.posventa;

import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio.Despacho;
import com.feder.datos.posventa.DevolucionRepositorio.DevolucionVista;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.Devolucion;
import com.feder.modelo.EstadoDevolucion;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoMovimiento;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.ventas.AccesoPedido;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Bloqueo de una devolución y de lo que la rodea en el orden de CU6 (PEDIDO, DETALLE_PEDIDO, DEVOLUCION y, al
 * final, el despacho devuelto), y su reincorporación al stock (R024, R044), que comparten todas sus operaciones.
 */
final class TramiteDevolucion {

    private final ContextoNegocio ctx;
    private final DevolucionRepositorio devoluciones;
    private final PedidoRepositorio pedidos;
    private final ReservaRepositorio reservas;
    private final ServicioInventario inventario;

    TramiteDevolucion(ContextoNegocio ctx, DevolucionRepositorio devoluciones, PedidoRepositorio pedidos, ReservaRepositorio reservas,
                      ServicioInventario inventario) {
        this.ctx = ctx;
        this.devoluciones = devoluciones;
        this.pedidos = pedidos;
        this.reservas = reservas;
        this.inventario = inventario;
    }

    /** Devolución bloqueada con su pedido y la línea vendida, también bloqueados. */
    record Bloqueada(DevolucionVista vista, Pedido pedido, DetallePedido linea, Devolucion devolucion) {
    }

    /** Un CLIENTE solo ve sus devoluciones; una ajena se informa igual que una inexistente. */
    Bloqueada bloquear(Solicitante solicitante, String codigo) {
        DevolucionVista vista = devoluciones.buscarPorCodigo(codigo)
                .filter(v -> solicitante.esPersonalInterno() || (AccesoPedido.esPropietarioDe(solicitante, v.clienteId())))
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la devolución " + codigo + ". Consulte LISDEV[\"*\"]."));
        Pedido pedido = pedidos.bloquear(vista.pedidoId()).orElseThrow();
        reservas.bloquearLinea(vista.detallePedidoId()).orElseThrow();
        DetallePedido linea = pedidos.lineas(pedido.getId()).stream().filter(l -> l.getId() == vista.detallePedidoId())
                .findFirst().orElseThrow();
        Devolucion devolucion = devoluciones.bloquear(vista.devolucion().getId()).orElseThrow();
        return new Bloqueada(vista, pedido, linea, devolucion);
    }

    /** R022: bloquea el despacho devuelto y lo devuelve con su lote y su cantidad. */
    Despacho despacho(Bloqueada b) {
        devoluciones.bloquearDespacho(b.devolucion().getMovimientoInventarioId());
        return devoluciones.despacho(b.devolucion().getMovimientoInventarioId(), b.linea().getId());
    }

    Devolucion releer(Bloqueada b) {
        return devoluciones.bloquear(b.devolucion().getId()).orElseThrow();
    }

    /**
     * R024 y R044: con la devolución APROBADA o COMPLETADA e inspeccionada, reincorpora al lote del despacho las
     * unidades aptas hasta min(cantidad_apta, cantidad_aprobada) que todavía no volvieron al stock. Las aptas que
     * exceden lo aprobado quedan en custodia y las no aptas, descartadas: ninguna de ellas entra al inventario.
     */
    Optional<MovimientoInventario> reincorporar(Bloqueada b, long autorId) {
        Devolucion d = releer(b);
        boolean decidida = d.getEstado() == EstadoDevolucion.APROBADA || d.getEstado() == EstadoDevolucion.COMPLETADA;
        if (!decidida || d.getCantidadApta() == null) {
            return Optional.empty();
        }
        BigDecimal objetivo = d.getCantidadApta().min(d.getCantidadAprobada());
        BigDecimal faltante = objetivo.subtract(devoluciones.sumaMovimientos(d.getId(), TipoMovimiento.ENTRADA_DEVOLUCION));
        if (faltante.signum() <= 0) {
            return Optional.empty();
        }
        long lote = devoluciones.despacho(d.getMovimientoInventarioId(), b.linea().getId()).loteFederId();
        MovimientoInventario entrada = inventario.registrarEntradaDevolucion(d.getId(), lote, faltante, autorId,
                "DEV" + d.getId() + "-REINGRESO-" + (devoluciones.contarMovimientos(d.getId(), TipoMovimiento.ENTRADA_DEVOLUCION) + 1));
        ctx.bitacora().registrar(autorId, AccionBitacora.CREAR, "MOVIMIENTO_INVENTARIO", entrada.getId(), DetalleJson.nuevo()
                .con("operacion", "REINCORPORACION_DEVOLUCION").con("devolucion", d.getCodigo()).con("cantidad", faltante)
                .con("clave_operacion", entrada.getClaveOperacion()));
        return Optional.of(entrada);
    }
}
