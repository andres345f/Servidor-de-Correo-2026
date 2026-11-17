package com.feder.negocio.pagos;

import com.feder.datos.pagos.FacturaRepositorio;
import com.feder.datos.pagos.PagoRepositorio;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.EstadoFactura;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.Factura;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.ventas.AccesoPedido;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Facturas al cliente final (FACTURA, R030). Se emite una por pedido, automáticamente, cuando la suma de sus pagos
 * PAGADO iguala su total, y nunca para un pedido CANCELADO; sus líneas son los DETALLE_PEDIDO, que no cambian. La
 * emisión se coordina con la cancelación bloqueando la fila del PEDIDO. FACPED devuelve la factura ya emitida (o la
 * emite si un pedido pagado quedó sin ella). Este sistema no está conectado a un servicio de facturación fiscal: el
 * código de autorización queda vacío.
 */
public class ServicioFacturas {

    private static final Pattern NIT_CI = Pattern.compile("[0-9A-Za-z-]{4,20}");
    /** factura.razon_social es VARCHAR(150); factura.nit_ci, VARCHAR(20) (el patrón NIT_CI ya lo respeta). */
    static final int LARGO_RAZON_SOCIAL = 150;

    private final ContextoNegocio ctx;
    private final FacturaRepositorio facturas;
    private final PedidoRepositorio pedidos;
    private final CuentaRepositorio cuentas;
    private final AccesoPedido acceso;
    private final CobrosPedido cobros;

    public ServicioFacturas(ContextoNegocio ctx, FacturaRepositorio facturas, PedidoRepositorio pedidos, PagoRepositorio pagos,
                            CuentaRepositorio cuentas) {
        this.ctx = ctx;
        this.facturas = facturas;
        this.pedidos = pedidos;
        this.cuentas = cuentas;
        this.acceso = new AccesoPedido(pedidos);
        this.cobros = new CobrosPedido(ctx, pagos);
    }

    /** Factura con su pedido y las líneas facturadas. */
    public record FacturaDetallada(Factura factura, Pedido pedido, List<DetallePedido> lineas, boolean yaExistia) {
    }

    /**
     * Emite la factura del pedido. Sin NIT o razón social se usan la cédula y el nombre del cliente (recortado a
     * 150 caracteres). Los datos indicados se validan contra las columnas (20 y 150) antes de escribir. Si ya
     * existe, la devuelve sin emitir otra (una factura por pedido).
     */
    public FacturaDetallada emitir(Solicitante solicitante, String codigoPedido, String nitCi, String razonSocial) {
        exigirClienteOPersonal(solicitante);
        String nit = Textos.opcional(nitCi);
        if (nit != null && !NIT_CI.matcher(nit).matches()) {
            throw NegocioException.regla("El NIT o CI admite de 4 a 20 letras, dígitos o '-'.");
        }
        String razon = Textos.opcional(razonSocial);
        if (razon != null && Textos.longitud(razon) > LARGO_RAZON_SOCIAL) {
            throw NegocioException.regla("La razón social admite como máximo " + LARGO_RAZON_SOCIAL + " caracteres.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = acceso.bloquearVisible(solicitante, codigoPedido);
            var existente = facturas.dePedido(pedido.getId());
            if (existente.isPresent()) {
                return new FacturaDetallada(existente.get(), pedido, pedidos.lineas(pedido.getId()), true);
            }
            if (pedido.getEstado() == EstadoPedido.CANCELADO) {
                throw NegocioException.estadoInvalido("El pedido " + pedido.getCodigo() + " está CANCELADO: nunca se factura, "
                        + "aunque haya recibido un cobro tardío destinado a reembolso.");
            }
            if (!cobros.pagadoPorCompleto(pedido)) {
                throw NegocioException.regla("La factura se emite cuando los pagos confirmados igualan el total del pedido: lleva "
                        + "pagado Bs " + Importes.formatear(cobros.pagado(pedido.getId())) + " de Bs "
                        + Importes.formatear(pedido.getTotal()) + ".");
            }
            Factura f = crear(pedido, nit, razon, solicitante.usuarioId());
            return new FacturaDetallada(f, pedido, pedidos.lineas(pedido.getId()), false);
        });
    }

    /**
     * R030: la factura se emite en la misma transacción en que los pagos PAGADO igualan el total, con la cédula y el
     * nombre del cliente. La llaman la verificación del pago y la entrega con cobro en efectivo, con el PEDIDO ya
     * bloqueado; si el pedido está CANCELADO, no está pagado por completo o ya tiene factura, no hace nada.
     */
    public Optional<Factura> alCompletarPago(Pedido pedido, Long autorId) {
        if (pedido.getEstado() == EstadoPedido.CANCELADO || facturas.dePedido(pedido.getId()).isPresent()
                || !cobros.pagadoPorCompleto(pedido)) {
            return Optional.empty();
        }
        return Optional.of(crear(pedido, null, null, autorId));
    }

    private Factura crear(Pedido pedido, String nit, String razon, Long autorId) {
        Usuario cliente = cuentas.buscarPorId(pedido.getUsuarioId()).orElseThrow();
        Factura f = new Factura();
        f.setId(facturas.siguienteId());
        f.setNumero(String.format("FAC-%d-%06d", FechasBolivia.hoy(ctx.reloj()).getYear(), f.getId()));
        f.setPedidoId(pedido.getId());
        f.setFechaEmision(ctx.ahora());
        f.setNitCi(nit != null ? nit : documento(cliente));
        f.setRazonSocial(razon != null ? razon : razonSocialDelCliente(cliente));
        f.setTotal(pedido.getTotal());
        f.setEstado(EstadoFactura.VIGENTE);
        facturas.insertar(f);
        ctx.bitacora().registrar(autorId, AccionBitacora.CREAR, "FACTURA", f.getId(), DetalleJson.nuevo()
                .con("numero", f.getNumero()).con("pedido", pedido.getCodigo()).con("total", f.getTotal()));
        return f;
    }

    /**
     * R030: una factura emitida nunca se borra ni se anula por la cancelación del pedido. Su anulación la registra
     * un ADMINISTRADOR cuando el procedimiento fiscal lo determina, con la referencia de ese procedimiento.
     */
    public Factura anular(Solicitante solicitante, String numero, String motivo) {
        Permisos.exigirAdministrador(solicitante, "anular facturas");
        String fundamento = Textos.obligatorio(motivo, "motivo y referencia del procedimiento fiscal");
        return ctx.transacciones().enTransaccion(() -> {
            Factura f = facturas.buscarPorNumero(numero)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe la factura " + numero + "."));
            pedidos.bloquear(f.getPedidoId()).orElseThrow();
            if (!facturas.anular(f.getId(), ctx.ahora())) {
                throw NegocioException.estadoInvalido("La factura " + numero + " ya está ANULADA.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.ANULAR, "FACTURA", f.getId(), DetalleJson.nuevo()
                    .con("numero", numero).con("estado_anterior", EstadoFactura.VIGENTE).con("estado_nuevo", EstadoFactura.ANULADA)
                    .con("motivo", fundamento));
            return facturas.buscarPorNumero(numero).orElseThrow();
        });
    }

    public FacturaDetallada ver(Solicitante solicitante, String codigoPedido) {
        exigirClienteOPersonal(solicitante);
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = acceso.visible(solicitante, codigoPedido);
            Factura f = facturas.dePedido(pedido.getId()).orElseThrow(() -> NegocioException.noEncontrado("El pedido "
                    + pedido.getCodigo() + " todavía no tiene factura."));
            return new FacturaDetallada(f, pedido, pedidos.lineas(pedido.getId()), true);
        });
    }

    /**
     * Razón social por defecto: el nombre completo del cliente. Nombres y apellidos admiten 80 caracteres cada uno,
     * así que juntos pueden superar los 150 de la columna: se recorta para que la factura automática nunca haga
     * fallar (y revertir) la confirmación del pago o la entrega que la emite.
     */
    static String razonSocialDelCliente(Usuario u) {
        return Textos.recortar(u.getNombres() + " " + u.getApellidos(), LARGO_RAZON_SOCIAL);
    }

    /** CI (INTEGER de hasta 9 dígitos) y complemento de 2 caracteres: nunca supera los 20 de nit_ci. */
    private static String documento(Usuario u) {
        return u.getCi() + (u.getCiComplemento() == null ? "" : "-" + u.getCiComplemento());
    }

    private static void exigirClienteOPersonal(Solicitante s) {
        if (!s.esPersonalInterno() && !s.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Solo el CLIENTE del pedido o el personal de FEDER gestionan su factura.");
        }
    }
}
