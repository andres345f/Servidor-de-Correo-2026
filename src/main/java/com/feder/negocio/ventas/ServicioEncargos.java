package com.feder.negocio.ventas;

import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.datos.ventas.ProduccionRespaldoRepositorio;
import com.feder.datos.ventas.ProduccionRespaldoRepositorio.Produccion;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.EstadoProduccion;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoNotificacion;
import com.feder.modelo.TipoPedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.PedidoAbastecido;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Gestión de encargos por el personal: aceptación (PENDIENTE -> ESPERANDO_ABASTECIMIENTO), con la fecha
 * estimada de disponibilidad y, si la hay, la producción que respalda la estimación (A25, R062).
 * Cada fijación o cambio de la fecha estimada se registra en BITACORA y se comunica al cliente.
 */
public class ServicioEncargos {

    private static final Set<EstadoPedido> FECHA_EDITABLE = EnumSet.of(EstadoPedido.ESPERANDO_ABASTECIMIENTO,
            EstadoPedido.CONFIRMADO, EstadoPedido.EN_PREPARACION);

    private final ContextoNegocio ctx;
    private final PedidoRepositorio pedidos;
    private final ProduccionRespaldoRepositorio producciones;
    private final ReservaRepositorio reservas;
    private final CambioEstadoPedido cambioEstado;
    private final ServicioInventario inventario;

    public ServicioEncargos(ContextoNegocio ctx, PedidoRepositorio pedidos, HistorialPedidoRepositorio historial,
                            ProduccionRespaldoRepositorio producciones, ReservaRepositorio reservas,
                            ServicioInventario inventario) {
        this.ctx = ctx;
        this.pedidos = pedidos;
        this.producciones = producciones;
        this.reservas = reservas;
        this.cambioEstado = new CambioEstadoPedido(ctx, pedidos, historial);
        this.inventario = inventario;
    }

    /** Resultado de abastecer un pedido: el pedido actualizado y lo reservado en esta operación. */
    public record Abastecimiento(PedidoCompleto pedido, List<PedidoAbastecido> abastecidos) {
    }

    /**
     * R056 (b): el personal abastece un pedido con mercancía apta que ya está en el stock vendible de
     * FEDER: un ENCARGO en ESPERANDO_ABASTECIMIENTO o un pedido CONFIRMADO o EN_PREPARACION que perdió
     * cobertura por el bloqueo o el vencimiento de un lote. Un encargo cubierto por completo pasa a CONFIRMADO.
     */
    public Abastecimiento abastecer(Solicitante solicitante, String codigoPedido) {
        Permisos.exigirPersonal(solicitante, "abastecer pedidos");
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = pedidos.buscarPorCodigo(codigoPedido)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe el pedido " + codigoPedido + "."));
            List<PedidoAbastecido> abastecidos = inventario.abastecerPedido(pedido.getId(), solicitante.usuarioId());
            return new Abastecimiento(pedidos.buscarConLineas(codigoPedido).orElseThrow(), abastecidos);
        });
    }

    /**
     * A25 y R062: vincula la producción que respalda la estimación de una línea de un encargo todavía no
     * abastecido (PENDIENTE o ESPERANDO_ABASTECIMIENTO). La producción identifica la línea por su producto.
     */
    public PedidoCompleto vincularRespaldo(Solicitante solicitante, String codigoPedido, String codigoProduccion) {
        Permisos.exigirPersonal(solicitante, "vincular producciones de respaldo");
        return ctx.transacciones().enTransaccion(() -> {
            PedidoCompleto completo = bloquearEncargo(codigoPedido);
            Pedido pedido = completo.pedido();
            if (pedido.getEstado() != EstadoPedido.PENDIENTE && pedido.getEstado() != EstadoPedido.ESPERANDO_ABASTECIMIENTO) {
                throw NegocioException.estadoInvalido("Solo se vincula un respaldo a un encargo PENDIENTE o ESPERANDO_ABASTECIMIENTO; "
                        + "el encargo " + pedido.getCodigo() + " está " + pedido.getEstado() + ".");
            }
            String respaldo = vincularRespaldo(completo, codigoProduccion);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "PEDIDO", pedido.getId(),
                    DetalleJson.nuevo().con("operacion", "VINCULAR_RESPALDO").con("produccion", respaldo));
            return pedidos.buscarConLineas(pedido.getCodigo()).orElseThrow();
        });
    }

    /**
     * Acepta un encargo PENDIENTE: pasa a ESPERANDO_ABASTECIMIENTO con su fecha estimada.
     * @param codigoProduccion nulo si ninguna producción respalda la estimación.
     */
    public PedidoCompleto aceptar(Solicitante solicitante, String codigoPedido, LocalDate fechaEstimada,
                                  String codigoProduccion, String fundamento) {
        Permisos.exigirPersonal(solicitante, "aceptar encargos");
        String fundamentoLimpio = Textos.obligatorio(fundamento, "fundamento de la fecha estimada");
        exigirFechaFutura(fechaEstimada);
        return ctx.transacciones().enTransaccion(() -> {
            PedidoCompleto completo = bloquearEncargo(codigoPedido);
            Pedido pedido = completo.pedido();
            if (pedido.getEstado() != EstadoPedido.PENDIENTE) {
                throw NegocioException.estadoInvalido("Solo se acepta un encargo PENDIENTE; el encargo " + pedido.getCodigo()
                        + " está " + pedido.getEstado() + ".");
            }
            String respaldo = codigoProduccion == null ? null : vincularRespaldo(completo, codigoProduccion);
            cambioEstado.cambiar(pedido, EstadoPedido.ESPERANDO_ABASTECIMIENTO, solicitante.usuarioId(),
                    "Encargo aceptado" + (respaldo == null ? "" : "; respaldado por la producción " + respaldo));
            fijarFecha(solicitante, pedido, fechaEstimada, "Aceptación del encargo", fundamentoLimpio);
            return pedidos.buscarConLineas(pedido.getCodigo()).orElseThrow();
        });
    }

    /** Actualiza la fecha estimada de un encargo ya aceptado y todavía no despachado. */
    public PedidoCompleto actualizarFecha(Solicitante solicitante, String codigoPedido, LocalDate fechaEstimada, String motivo,
                                          String fundamento) {
        Permisos.exigirPersonal(solicitante, "actualizar la fecha estimada de encargos");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        String fundamentoLimpio = Textos.obligatorio(fundamento, "fundamento de la nueva fecha estimada");
        exigirFechaFutura(fechaEstimada);
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = bloquearEncargo(codigoPedido).pedido();
            if (!FECHA_EDITABLE.contains(pedido.getEstado())) {
                throw NegocioException.estadoInvalido("La fecha estimada se actualiza en un encargo aceptado y aún no despachado; "
                        + "el encargo " + pedido.getCodigo() + " está " + pedido.getEstado() + ".");
            }
            if (fechaEstimada.equals(pedido.getFechaEstimadaDisponibilidad())) {
                throw NegocioException.regla("La fecha indicada es igual a la fecha estimada vigente.");
            }
            fijarFecha(solicitante, pedido, fechaEstimada, motivoLimpio, fundamentoLimpio);
            return pedidos.buscarConLineas(pedido.getCodigo()).orElseThrow();
        });
    }

    private void fijarFecha(Solicitante solicitante, Pedido pedido, LocalDate fecha, String motivo, String fundamento) {
        LocalDate anterior = pedido.getFechaEstimadaDisponibilidad();
        pedidos.actualizarFechaEstimada(pedido.getId(), fecha);
        ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "PEDIDO", pedido.getId(),
                DetalleJson.nuevo().con("fecha_estimada_anterior", anterior == null ? null : anterior.toString())
                        .con("fecha_estimada_nueva", fecha.toString()).con("motivo", motivo).con("fundamento", fundamento));
        new NotificadorCliente(ctx).avisarConCorreo(pedido, TipoNotificacion.FECHA_ESTIMADA_ACTUALIZADA,
                "Fecha estimada de tu encargo", "El encargo " + pedido.getCodigo() + " tiene como fecha estimada de disponibilidad el "
                        + fecha + ". Es una estimación, no una fecha garantizada.");
        pedido.setFechaEstimadaDisponibilidad(fecha);
    }

    /**
     * R062: la producción debe estar PLANIFICADA o EN_CURSO, con fecha y cantidad estimadas, y su
     * capacidad libre (max(0, estimada - recibida) - compromiso vigente) debe cubrir la nueva línea.
     */
    private String vincularRespaldo(PedidoCompleto completo, String codigoProduccion) {
        Produccion produccion = producciones.bloquearPorCodigo(codigoProduccion)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la producción " + codigoProduccion + "."));
        if (produccion.estado() != EstadoProduccion.PLANIFICADA && produccion.estado() != EstadoProduccion.EN_CURSO) {
            throw NegocioException.regla("Solo respalda un encargo una producción PLANIFICADA o EN_CURSO; "
                    + produccion.codigo() + " está " + produccion.estado() + ".");
        }
        if (produccion.fechaDisponibilidadEstimada() == null || produccion.cantidadEstimada() == null) {
            throw NegocioException.regla("La producción " + produccion.codigo() + " no tiene fecha ni cantidad estimadas.");
        }
        DetallePedido linea = completo.lineas().stream().filter(l -> l.getProductoId() == produccion.productoId())
                .findFirst().orElseThrow(() -> NegocioException.regla("El encargo no tiene una línea del producto de la producción "
                        + produccion.codigo() + "."));
        if (linea.getProduccionId() != null) {
            throw NegocioException.regla("La línea de " + linea.getDescripcionVenta() + " ya está respaldada por otra producción; "
                    + "el vínculo se conserva como dato histórico.");
        }
        BigDecimal nueva = linea.getCantidad().subtract(reservas.cubiertaDeLinea(linea.getId()));
        BigDecimal capacidad = produccion.cantidadEstimada().subtract(producciones.recibida(produccion.id())).max(BigDecimal.ZERO);
        BigDecimal compromiso = producciones.compromisoVigente(produccion.id());
        if (compromiso.add(nueva).compareTo(capacidad) > 0) {
            throw NegocioException.regla("La producción " + produccion.codigo() + " no alcanza para respaldar el encargo: "
                    + "capacidad " + Cantidades.formatear(capacidad) + ", ya comprometido " + Cantidades.formatear(compromiso)
                    + ", se pide " + Cantidades.formatear(nueva) + ".");
        }
        pedidos.vincularRespaldo(linea.getId(), produccion.id());
        return produccion.codigo();
    }

    private PedidoCompleto bloquearEncargo(String codigo) {
        PedidoCompleto completo = pedidos.bloquearConLineas(codigo)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el pedido " + codigo + "."));
        if (completo.pedido().getTipo() != TipoPedido.ENCARGO) {
            throw NegocioException.regla("El pedido " + codigo + " es INMEDIATO: la fecha estimada solo existe en un ENCARGO.");
        }
        return completo;
    }

    private void exigirFechaFutura(LocalDate fecha) {
        if (fecha == null || fecha.isBefore(FechasBolivia.hoy(ctx.reloj()))) {
            throw NegocioException.regla("La fecha estimada de disponibilidad no puede ser anterior a hoy.");
        }
    }
}
