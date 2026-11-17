package com.feder.negocio.posventa;

import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.consultas.ListadosGeneralesRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio.Despacho;
import com.feder.datos.posventa.DevolucionRepositorio.DevolucionVista;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.Devolucion;
import com.feder.modelo.EstadoDevolucion;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.Pedido;
import com.feder.modelo.Producto;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.ventas.AccesoPedido;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Devoluciones de productos entregados (DEVOLUCION, CU6): solicitud por el cliente, recepción física e
 * inspección por el personal (R022-R024). La decisión y su resolución están en {@link ServicioResolucionDevoluciones}.
 */
public class ServicioDevoluciones {

    /** Tope común de los listados (Listados): se pide una fila de más para saber si hay más registros. */
    private static final int LIMITE_LISTADO = Listados.A_PEDIR;

    private final ContextoNegocio ctx;
    private final DevolucionRepositorio devoluciones;
    private final PedidoRepositorio pedidos;
    private final ReservaRepositorio reservas;
    private final ProductoRepositorio productos;
    private final AccesoPedido acceso;
    private final TramiteDevolucion tramite;
    private final ListadosGeneralesRepositorio consultas = new ListadosGeneralesRepositorio();

    ServicioDevoluciones(ContextoNegocio ctx, DevolucionRepositorio devoluciones, PedidoRepositorio pedidos, ReservaRepositorio reservas,
                         ProductoRepositorio productos, TramiteDevolucion tramite) {
        this.ctx = ctx;
        this.devoluciones = devoluciones;
        this.pedidos = pedidos;
        this.reservas = reservas;
        this.productos = productos;
        this.acceso = new AccesoPedido(pedidos);
        this.tramite = tramite;
    }

    /** Devolución con su pedido y línea, y si ya existía con ese código (reenvío de la misma solicitud). */
    public record DevolucionRegistrada(DevolucionVista vista, boolean yaExistia) {
    }

    /**
     * El CLIENTE pide devolver unidades de un producto de su pedido ENTREGADO (R023). La solicitud se refiere a un
     * despacho concreto de la línea, con capacidad libre según R022 (a) con su fila bloqueada. El código identifica
     * la solicitud: si ya existe, se devuelve la registrada en lugar de crear otra.
     */
    public DevolucionRegistrada solicitar(Solicitante solicitante, String codigoPedido, String codigoProducto, BigDecimal cantidad,
                                         String motivo, String codigoDevolucion) {
        if (!solicitante.tieneRol(TipoRol.CLIENTE) && !solicitante.esPersonalInterno()) {
            throw NegocioException.sinPermiso("Solo el CLIENTE del pedido o el personal de FEDER solicitan devoluciones.");
        }
        String justificacion = recortar(Textos.obligatorio(motivo, "motivo de la devolución"));
        String codigoIndicado = CodigosDevolucion.validarIndicado(Textos.opcional(codigoDevolucion));
        return ctx.transacciones().enTransaccion(() -> {
            // El reenvío se reconoce con el pedido ya bloqueado: una copia simultánea espera y encuentra la registrada.
            Pedido pedido = acceso.bloquearVisible(solicitante, codigoPedido);
            Optional<DevolucionVista> previa = codigoIndicado == null ? Optional.empty() : devoluciones.buscarPorCodigo(codigoIndicado);
            if (previa.isPresent()) {
                return reenvio(solicitante, previa.get(), pedido, codigoProducto, cantidad, justificacion);
            }
            if (pedido.getEstado() != EstadoPedido.ENTREGADO) {
                throw NegocioException.estadoInvalido("Solo se devuelven productos de un pedido ENTREGADO; el pedido "
                        + pedido.getCodigo() + " está " + pedido.getEstado() + ".");
            }
            Producto producto = producto(codigoProducto);
            DetallePedido linea = linea(pedido, producto, codigoProducto);
            reservas.bloquearLinea(linea.getId()).orElseThrow();
            Cantidades.validarPositiva(cantidad, producto.getUnidadMedida(), "cantidad a devolver");
            Despacho despacho = elegirDespacho(linea, cantidad);
            Devolucion d = new Devolucion();
            d.setId(devoluciones.siguienteId());
            d.setCodigo(codigoIndicado != null ? codigoIndicado : CodigosDevolucion.generado(ctx, d.getId()));
            d.setMovimientoInventarioId(despacho.movimientoId());
            d.setCantidad(cantidad);
            d.setMotivo(justificacion);
            d.setFechaSolicitud(ctx.ahora());
            devoluciones.insertar(d);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, "DEVOLUCION", d.getId(), DetalleJson.nuevo()
                    .con("codigo", d.getCodigo()).con("pedido", pedido.getCodigo()).con("despacho", despacho.movimientoId())
                    .con("cantidad", cantidad).con("motivo", d.getMotivo()));
            return new DevolucionRegistrada(devoluciones.buscarPorCodigo(d.getCodigo()).orElseThrow(), false);
        });
    }

    /**
     * Un código ya registrado solo se acepta como reenvío de la misma solicitud: mismo pedido, producto, cantidad y
     * motivo. Si algo difiere, o el código es de la devolución de otro cliente, se rechaza sin revelar sus datos.
     */
    private DevolucionRegistrada reenvio(Solicitante solicitante, DevolucionVista v, Pedido pedido, String codigoProducto,
                                         BigDecimal cantidad, String motivo) {
        String codigo = v.devolucion().getCodigo();
        if (!solicitante.esPersonalInterno() && !AccesoPedido.esPropietarioDe(solicitante, v.clienteId())) {
            throw NegocioException.duplicado("El código de devolución " + codigo + " ya está en uso: elija otro.");
        }
        boolean misma = v.pedidoId() == pedido.getId()
                && v.detallePedidoId() == linea(pedido, producto(codigoProducto), codigoProducto).getId()
                && cantidad != null && v.devolucion().getCantidad().compareTo(cantidad) == 0
                && v.devolucion().getMotivo().equals(motivo);
        if (!misma) {
            throw NegocioException.duplicado("El código " + codigo + " ya identifica otra solicitud de devolución: use un código "
                    + "nuevo para esta.");
        }
        return new DevolucionRegistrada(v, true);
    }

    private Producto producto(String codigoProducto) {
        return productos.buscarPorCodigo(codigoProducto).map(p -> p.producto())
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el producto " + codigoProducto + "."));
    }

    private DetallePedido linea(Pedido pedido, Producto producto, String codigoProducto) {
        return pedidos.lineas(pedido.getId()).stream().filter(l -> l.getProductoId().equals(producto.getId()))
                .findFirst().orElseThrow(() -> NegocioException.regla("El pedido " + pedido.getCodigo() + " no incluye el producto "
                        + codigoProducto + "."));
    }

    private static String recortar(String texto) {
        return texto.length() <= 255 ? texto : texto.substring(0, 255);
    }

    /** R022 (a): el primer despacho de la línea cuya capacidad libre cubre la cantidad pedida, con su fila bloqueada. */
    private Despacho elegirDespacho(DetallePedido linea, BigDecimal cantidad) {
        BigDecimal mayorLibre = BigDecimal.ZERO;
        for (Despacho despacho : devoluciones.despachosDeLinea(linea.getId())) {
            devoluciones.bloquearDespacho(despacho.movimientoId());
            BigDecimal libre = despacho.cantidad().subtract(devoluciones.comprometidoDeDespacho(despacho.movimientoId()));
            if (libre.compareTo(cantidad) >= 0) {
                return despacho;
            }
            mayorLibre = mayorLibre.max(libre);
        }
        throw NegocioException.regla("No se puede solicitar la devolución de " + Cantidades.formatear(cantidad) + " de "
                + linea.getDescripcionVenta() + ": lo máximo devolvible en una sola solicitud es " + Cantidades.formatear(mayorLibre)
                + " (cada solicitud se refiere a un despacho, y cuentan las devoluciones en trámite o aprobadas).");
    }

    /**
     * Recepción física de la mercancía devuelta (R024), una sola vez, en cualquier estado salvo ANULADA (si llega
     * tras completar el reintegro se recibe igual, sin reabrir la compensación). R022 (b): una unidad física
     * solo vuelve una vez por despacho.
     */
    public DevolucionVista recibir(Solicitante solicitante, String codigo, BigDecimal cantidad) {
        Permisos.exigirPersonal(solicitante, "registrar la recepción de devoluciones");
        return ctx.transacciones().enTransaccion(() -> {
            TramiteDevolucion.Bloqueada b = tramite.bloquear(solicitante, codigo);
            Devolucion d = b.devolucion();
            if (d.getEstado() == EstadoDevolucion.ANULADA) {
                throw NegocioException.estadoInvalido("La devolución " + codigo + " está ANULADA: su mercancía pasó a su rectificación.");
            }
            if (d.getCantidadRecibida() != null) {
                throw NegocioException.estadoInvalido("La recepción de la devolución " + codigo + " ya se registró ("
                        + Cantidades.formatear(d.getCantidadRecibida()) + ").");
            }
            Cantidades.validarPositiva(cantidad, null, "cantidad recibida");
            if (cantidad.compareTo(d.getCantidad()) > 0) {
                throw NegocioException.regla("Se recibió más de lo reclamado (" + Cantidades.formatear(d.getCantidad()) + ").");
            }
            Despacho despacho = tramite.despacho(b);
            BigDecimal recibido = devoluciones.recibidoDeDespacho(despacho.movimientoId());
            if (recibido.add(cantidad).compareTo(despacho.cantidad()) > 0) {
                throw NegocioException.regla("Del despacho ya volvieron " + Cantidades.formatear(recibido) + " de "
                        + Cantidades.formatear(despacho.cantidad()) + " unidades: una unidad física solo vuelve una vez.");
            }
            if (!devoluciones.registrarRecepcion(d.getId(), cantidad, ctx.ahora())) {
                throw NegocioException.estadoInvalido("La devolución cambió mientras se registraba su recepción.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "DEVOLUCION", d.getId(), DetalleJson.nuevo()
                    .con("operacion", "RECEPCION").con("cantidad_recibida", cantidad));
            return devoluciones.buscarPorCodigo(codigo).orElseThrow();
        });
    }

    /**
     * Inspección de lo recibido (R024): fija las unidades aptas. Si la devolución ya está APROBADA o COMPLETADA,
     * en la misma transacción se reincorporan al stock hasta min(apta, aprobada).
     */
    public DevolucionVista inspeccionar(Solicitante solicitante, String codigo, BigDecimal apta) {
        Permisos.exigirPersonal(solicitante, "registrar la inspección de devoluciones");
        return ctx.transacciones().enTransaccion(() -> {
            TramiteDevolucion.Bloqueada b = tramite.bloquear(solicitante, codigo);
            Devolucion d = b.devolucion();
            if (d.getCantidadRecibida() == null) {
                throw NegocioException.estadoInvalido("Primero registre la recepción de la devolución " + codigo + " (RECDEV).");
            }
            if (d.getCantidadApta() != null) {
                throw NegocioException.estadoInvalido("La inspección de la devolución " + codigo + " ya se registró.");
            }
            if (d.getEstado() == EstadoDevolucion.ANULADA) {
                throw NegocioException.estadoInvalido("La devolución " + codigo + " está ANULADA.");
            }
            Cantidades.validarNoNegativa(apta, null, "cantidad apta");
            if (apta.compareTo(d.getCantidadRecibida()) > 0) {
                throw NegocioException.regla("Las unidades aptas no pueden superar las recibidas (" + Cantidades.formatear(d.getCantidadRecibida()) + ").");
            }
            if (!devoluciones.registrarInspeccion(d.getId(), apta, ctx.ahora())) {
                throw NegocioException.estadoInvalido("La devolución cambió mientras se registraba su inspección.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "DEVOLUCION", d.getId(), DetalleJson.nuevo()
                    .con("operacion", "INSPECCION").con("cantidad_apta", apta)
                    .con("cantidad_no_apta", d.getCantidadRecibida().subtract(apta)));
            tramite.reincorporar(b, solicitante.usuarioId());
            return devoluciones.buscarPorCodigo(codigo).orElseThrow();
        });
    }

    /** El personal ve todas las devoluciones; un CLIENTE solo las de sus pedidos. */
    public List<DevolucionVista> listar(Solicitante solicitante, EstadoDevolucion estado) {
        Long cliente = AccesoPedido.clienteFiltrado(solicitante);
        return ctx.transacciones().enTransaccion(() -> devoluciones.listar(cliente, estado, LIMITE_LISTADO));
    }

    /** Devolución con sus reembolsos y sus movimientos de inventario (filas genéricas, ver ListadosGeneralesRepositorio). */
    public record DevolucionConDetalle(DevolucionVista vista, List<List<Object>> reembolsos, List<List<Object>> movimientos) {
    }

    /**
     * Detalle de una devolución por su código (VERDEV, plan fase C, C4). Igual que LISDEV: el personal ve cualquiera;
     * un CLIENTE solo las de sus pedidos (una ajena se responde como inexistente).
     */
    public DevolucionConDetalle ver(Solicitante solicitante, String codigo) {
        Long cliente = AccesoPedido.clienteFiltrado(solicitante);
        return ctx.transacciones().enTransaccion(() -> {
            DevolucionVista v = devoluciones.buscarPorCodigo(codigo).filter(x -> cliente == null || x.clienteId() == cliente)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe la devolución " + codigo + ". Consulte LISDEV[\"*\"]."));
            long id = v.devolucion().getId();
            return new DevolucionConDetalle(v, consultas.reembolsosDeDevolucion(id), consultas.movimientosDeDevolucion(id));
        });
    }
}
