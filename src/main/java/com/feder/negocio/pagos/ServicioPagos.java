package com.feder.negocio.pagos;

import com.feder.datos.pagos.PagoRepositorio;
import com.feder.datos.pagos.PagoRepositorio.PagoVista;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoPago;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.Factura;
import com.feder.modelo.MetodoPago;
import com.feder.modelo.Pago;
import com.feder.modelo.Pedido;
import com.feder.modelo.Reembolso;
import com.feder.modelo.TipoNotificacion;
import com.feder.modelo.TipoPedido;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.ventas.AccesoPedido;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pagos multicanal de un pedido (PAGO, R047-R051). Cada intento es un PAGO nuevo identificado por su clave de
 * idempotencia: reenviar el mismo intento no crea otro. Un pago QR o TARJETA sale de PENDIENTE solo cuando el
 * personal verifica el cobro (la pasarela local no está conectada a un proveedor); el efectivo se confirma
 * al entregar el pedido. Todas las operaciones bloquean primero la fila del PEDIDO.
 */
public class ServicioPagos {

    /** Tope común de los listados (Listados): se pide una fila de más para saber si hay más registros. */
    private static final int LIMITE_LISTADO = Listados.A_PEDIR;
    private static final Pattern CLAVE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final String PREFIJO_GENERADO = "PAG-";
    private static final Set<EstadoPedido> ADMITEN_CAMBIO_DE_EFECTIVO = EnumSet.of(EstadoPedido.PENDIENTE,
            EstadoPedido.ESPERANDO_ABASTECIMIENTO);
    private static final Set<EstadoPedido> SIN_COBROS = EnumSet.of(EstadoPedido.CANCELADO, EstadoPedido.ENTREGADO);

    private final ContextoNegocio ctx;
    private final PagoRepositorio pagos;
    private final PedidoRepositorio pedidos;
    private final AccesoPedido acceso;
    private final CobrosPedido cobros;
    private final ServicioReembolsos reembolsos;
    private final ConfirmacionComercial confirmacion;
    private final PasarelaPagos pasarela;
    private final ServicioFacturas facturas;
    private final NotificadorCliente notificador;

    public ServicioPagos(ContextoNegocio ctx, PagoRepositorio pagos, PedidoRepositorio pedidos, ServicioReembolsos reembolsos,
                         ConfirmacionComercial confirmacion, PasarelaPagos pasarela, ServicioFacturas facturas) {
        this.ctx = ctx;
        this.pagos = pagos;
        this.pedidos = pedidos;
        this.acceso = new AccesoPedido(pedidos);
        this.cobros = new CobrosPedido(ctx, pagos);
        this.reembolsos = reembolsos;
        this.confirmacion = confirmacion;
        this.pasarela = pasarela;
        this.facturas = facturas;
        this.notificador = new NotificadorCliente(ctx);
    }

    /** Intento registrado: el pago, si ya existía (reenvío) y las indicaciones para pagar. */
    public record RegistroPago(Pago pago, String codigoPedido, boolean yaExistia, String indicaciones) {
    }

    /**
     * Resultado de verificar un pago: si el pedido quedó confirmado, si el cobro llegó tarde y se reintegra, y la
     * factura emitida al completar el total.
     */
    public record Verificacion(Pago pago, Pedido pedido, boolean yaProcesado, boolean pedidoConfirmado, Reembolso reintegroTardio,
                               Factura factura) {
    }

    // ------------------------------------------------------------------ registro del intento

    /**
     * Registra un intento de cobro. QR y TARJETA aceptan un anticipo o el saldo; EFECTIVO es siempre el saldo
     * completo, que se cobra al entregar (R047 (2), R049). Un pedido tiene como máximo un intento PENDIENTE (R051).
     *
     * @param clave clave de idempotencia del intento; nula para que FEDER la genere.
     */
    public RegistroPago registrar(Solicitante solicitante, String codigoPedido, MetodoPago metodo, BigDecimal monto, String clave) {
        if (!solicitante.esPersonalInterno() && !solicitante.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Solo el CLIENTE del pedido o el personal de FEDER registran pagos.");
        }
        BigDecimal importe = Importes.validarMonto(monto, "monto del pago");
        String claveIndicada = validarClave(clave);
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = acceso.bloquearVisible(solicitante, codigoPedido);
            Optional<RegistroPago> reenvio = reenvio(pedido, metodo, importe, claveIndicada);
            if (reenvio.isPresent()) {
                return reenvio.get();
            }
            exigirCobrable(pedido, metodo, importe);
            Pago pago = new Pago();
            pago.setPedidoId(pedido.getId());
            pago.setMetodo(metodo);
            pago.setMonto(importe);
            pago.setEstado(EstadoPago.PENDIENTE);
            pago.setFechaRegistro(ctx.ahora());
            pago.setClaveIdempotencia(claveIndicada != null ? claveIndicada
                    : String.format("%s%s-%02d", PREFIJO_GENERADO, pedido.getCodigo(), pagos.contarDePedido(pedido.getId()) + 1));
            pago.setId(pagos.insertar(pago));
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, "PAGO", pago.getId(), DetalleJson.nuevo()
                    .con("pedido", pedido.getCodigo()).con("metodo", metodo).con("monto", importe)
                    .con("clave_idempotencia", pago.getClaveIdempotencia()));
            return new RegistroPago(pago, pedido.getCodigo(), false, indicaciones(pago, pedido));
        });
    }

    /** Un reenvío del mismo intento (misma clave, o el mismo PENDIENTE) devuelve el pago ya registrado. */
    private Optional<RegistroPago> reenvio(Pedido pedido, MetodoPago metodo, BigDecimal importe, String clave) {
        if (clave != null) {
            Optional<Pago> mismaClave = pagos.buscarPorClave(clave);
            if (mismaClave.isPresent()) {
                Pago p = mismaClave.get();
                if (p.getPedidoId() != pedido.getId().longValue() || p.getMetodo() != metodo || p.getMonto().compareTo(importe) != 0) {
                    throw NegocioException.duplicado("La clave " + clave + " ya identifica otro intento de pago: use una clave nueva.");
                }
                return Optional.of(new RegistroPago(p, pedido.getCodigo(), true, indicacionesDeReenvio(p, pedido)));
            }
        }
        Optional<Pago> pendiente = pagos.pendienteDePedido(pedido.getId());
        if (pendiente.isEmpty()) {
            return Optional.empty();
        }
        Pago p = pendiente.get();
        if (clave == null && p.getMetodo() == metodo && p.getMonto().compareTo(importe) == 0) {
            return Optional.of(new RegistroPago(p, pedido.getCodigo(), true, indicaciones(p, pedido)));
        }
        throw NegocioException.regla("El pedido " + pedido.getCodigo() + " ya tiene un intento de pago PENDIENTE (" + p.getMetodo()
                + ", Bs " + Importes.formatear(p.getMonto()) + ", clave " + p.getClaveIdempotencia() + "). FEDER debe verificarlo "
                + "o rechazarlo antes de registrar otro intento.");
    }

    /** R051: lo pagado nunca supera el total; el efectivo contra entrega es por el saldo completo. */
    private void exigirCobrable(Pedido pedido, MetodoPago metodo, BigDecimal importe) {
        if (SIN_COBROS.contains(pedido.getEstado())) {
            throw NegocioException.estadoInvalido("El pedido " + pedido.getCodigo() + " está " + pedido.getEstado()
                    + ": ya no admite pagos.");
        }
        BigDecimal saldo = pedido.getTotal().subtract(cobros.pagado(pedido.getId()));
        if (saldo.signum() <= 0) {
            throw NegocioException.regla("El pedido " + pedido.getCodigo() + " ya está pagado por completo.");
        }
        if (importe.compareTo(saldo) > 0) {
            throw NegocioException.regla("El pago de Bs " + Importes.formatear(importe) + " supera el saldo del pedido (Bs "
                    + Importes.formatear(saldo) + ").");
        }
        if (metodo == MetodoPago.EFECTIVO && importe.compareTo(saldo) != 0) {
            throw NegocioException.regla("El pago en efectivo contra entrega es por el saldo completo del pedido: Bs "
                    + Importes.formatear(saldo) + ".");
        }
    }

    private String indicaciones(Pago pago, Pedido pedido) {
        if (pago.getMetodo() == MetodoPago.EFECTIVO) {
            return "Pagará Bs " + Importes.formatear(pago.getMonto()) + " en efectivo al recibir el pedido; FEDER confirmará el cobro "
                    + "en ese momento." + (pedido.getTipo() == TipoPedido.INMEDIATO && pedido.getEstado() == EstadoPedido.PENDIENTE
                    ? " El personal de FEDER debe aceptar antes el pedido para esta modalidad." : "");
        }
        // R048 y R053 (3): el intento no vence por el paso del tiempo; sigue PENDIENTE hasta que el personal lo verifica.
        return pasarela.prepararCobro(new PasarelaPagos.SolicitudCobro(pago.getClaveIdempotencia(), pedido.getCodigo(),
                pago.getMetodo(), pago.getMonto())).indicaciones();
    }

    /**
     * R048 y R051: un intento que ya salió de PENDIENTE no vuelve a presentarse a la pasarela ni invita a pagar de
     * nuevo; solo se informa cómo terminó.
     */
    private String indicacionesDeReenvio(Pago pago, Pedido pedido) {
        return switch (pago.getEstado()) {
            case PENDIENTE -> indicaciones(pago, pedido);
            case PAGADO -> "Este intento ya fue cobrado y confirmado: no vuelva a pagarlo.";
            case RECHAZADO -> "Este intento terminó RECHAZADO sin cobro: para pagar, registre un intento nuevo con otra clave.";
        };
    }

    /** R051: las claves con el prefijo PAG- las genera FEDER, así que no se aceptan como clave indicada. */
    private static String validarClave(String clave) {
        String limpia = Textos.opcional(clave);
        if (limpia != null && !CLAVE.matcher(limpia).matches()) {
            throw NegocioException.regla("La clave del pago admite hasta 64 letras, dígitos, '.', '-' o '_'.");
        }
        if (limpia != null && limpia.regionMatches(true, 0, PREFIJO_GENERADO, 0, PREFIJO_GENERADO.length())) {
            throw NegocioException.regla("Las claves que empiezan por " + PREFIJO_GENERADO + " las asigna FEDER: elija otra clave "
                    + "o deje el campo vacío.");
        }
        return limpia;
    }

    // ------------------------------------------------------------------ verificación por el personal

    /**
     * R048: el personal verifica el cobro de un intento QR o TARJETA con la referencia de la transacción y el
     * importe efectivamente cobrado. Si el importe no coincide o el total se superaría, el pago sigue PENDIENTE.
     * Si el pedido ya estaba CANCELADO, el cobro tardío se reintegra (R053 (4)); si no, un INMEDIATO PENDIENTE
     * que queda pagado por completo pasa a CONFIRMADO en la misma transacción.
     */
    public Verificacion confirmar(Solicitante solicitante, String clave, String referencia, BigDecimal montoCobrado) {
        Permisos.exigirPersonal(solicitante, "verificar pagos");
        String referenciaExterna = Textos.obligatorio(referencia, "referencia de la transacción externa");
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = bloquearPedidoDe(clave);
            SeparacionFunciones.exigirAjeno(solicitante, pedido, "verificar el pago");
            Pago pago = pagos.bloquear(pagos.buscarPorClave(clave).orElseThrow().getId()).orElseThrow();
            if (pago.getEstado() == EstadoPago.PAGADO && referenciaExterna.equals(pago.getReferenciaExterna())) {
                return new Verificacion(pago, pedido, true, false, null, null);
            }
            exigirVerificable(pago, clave, referenciaExterna, montoCobrado);
            BigDecimal pagado = cobros.pagado(pedido.getId());
            if (pagado.add(pago.getMonto()).compareTo(pedido.getTotal()) > 0) {
                throw NegocioException.regla("Confirmar este pago superaría el total del pedido (pagado Bs " + Importes.formatear(pagado)
                        + " de Bs " + Importes.formatear(pedido.getTotal()) + "). El pago sigue PENDIENTE para conciliarlo.");
            }
            if (!pagos.confirmar(pago.getId(), ctx.ahora(), referenciaExterna)) {
                throw NegocioException.estadoInvalido("El pago cambió de estado durante la verificación.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "PAGO", pago.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", EstadoPago.PENDIENTE).con("estado_nuevo", EstadoPago.PAGADO)
                    .con("verificacion", "MANUAL").con("pasarela", pasarela.nombre()).con("referencia_externa", referenciaExterna)
                    .con("monto_cobrado", montoCobrado));
            Pago confirmado = pagos.buscarPorClave(clave).orElseThrow();
            return trasCobro(solicitante, pedido, confirmado, pagado.add(pago.getMonto()));
        });
    }

    private void exigirVerificable(Pago pago, String clave, String referencia, BigDecimal montoCobrado) {
        if (pago.getEstado() != EstadoPago.PENDIENTE) {
            throw NegocioException.estadoInvalido("El pago " + clave + " ya está " + pago.getEstado() + " y no cambia: un estado "
                    + "final no se modifica" + (pago.getEstado() == EstadoPago.RECHAZADO
                    ? "; un cobro posterior a un rechazo se concilia con el proveedor." : "."));
        }
        if (pago.getMetodo() == MetodoPago.EFECTIVO) {
            throw NegocioException.regla("El pago en efectivo se confirma al entregar el pedido (ENTPED), cuando FEDER recibe el dinero.");
        }
        if (pagos.buscarPorReferencia(referencia).isPresent()) {
            throw NegocioException.duplicado("La referencia " + referencia + " ya está asociada a otro pago.");
        }
        if (montoCobrado == null || montoCobrado.compareTo(pago.getMonto()) != 0) {
            throw NegocioException.regla("El importe cobrado (Bs " + Importes.formatear(montoCobrado) + ") no coincide con el del "
                    + "intento (Bs " + Importes.formatear(pago.getMonto()) + "). El pago sigue PENDIENTE para revisión.");
        }
    }

    private Verificacion trasCobro(Solicitante solicitante, Pedido pedido, Pago pago, BigDecimal pagado) {
        if (pedido.getEstado() == EstadoPedido.CANCELADO) {
            Reembolso tardio = reembolsos.porCobroTardio(pedido, pago, solicitante.usuarioId());
            notificador.avisarConCorreo(pedido, TipoNotificacion.PAGO_CONFIRMADO, "Pago recibido de un pedido cancelado",
                    "Recibimos Bs " + Importes.formatear(pago.getMonto()) + " del pedido " + pedido.getCodigo()
                            + ", que ya estaba cancelado: el pedido no se reactiva y le reintegraremos ese importe.");
            return new Verificacion(pago, pedido, false, false, tardio, null);
        }
        notificador.avisarConCorreo(pedido, TipoNotificacion.PAGO_CONFIRMADO, "Pago confirmado", "Recibimos Bs "
                + Importes.formatear(pago.getMonto()) + " del pedido " + pedido.getCodigo() + " (" + pago.getMetodo() + ").");
        boolean completa = pagado.compareTo(pedido.getTotal()) == 0;
        boolean inmediatoPendiente = pedido.getTipo() == TipoPedido.INMEDIATO && pedido.getEstado() == EstadoPedido.PENDIENTE;
        boolean confirmado = false;
        if (completa && inmediatoPendiente) {
            confirmacion.confirmar(pedido, solicitante.usuarioId(), "Pago por " + pago.getMetodo() + " verificado por el personal", false);
            confirmado = true;
        } else if (inmediatoPendiente) {
            confirmacion.anticipoConfirmado(pedido);
        }
        // R030: la factura se emite en la misma transacción en que lo pagado alcanza el total.
        Factura factura = completa ? facturas.alCompletarPago(pedido, solicitante.usuarioId()).orElse(null) : null;
        return new Verificacion(pago, pedido, false, confirmado, null, factura);
    }

    /**
     * R048: un resultado definitivo sin cobro deja el intento RECHAZADO, con el motivo en BITACORA. El efectivo de un
     * pedido ya aceptado contra entrega no se rechaza así: sigue PENDIENTE hasta cobrarse en la entrega y solo termina
     * RECHAZADO si el pedido se cancela (R049).
     */
    public Verificacion rechazar(Solicitante solicitante, String clave, String motivo) {
        Permisos.exigirPersonal(solicitante, "rechazar pagos");
        String causa = Textos.obligatorio(motivo, "motivo del rechazo");
        return ctx.transacciones().enTransaccion(() -> {
            Pedido pedido = bloquearPedidoDe(clave);
            SeparacionFunciones.exigirAjeno(solicitante, pedido, "rechazar el pago");
            Pago pago = pagos.bloquear(pagos.buscarPorClave(clave).orElseThrow().getId()).orElseThrow();
            if (pago.getEstado() != EstadoPago.PENDIENTE) {
                throw NegocioException.estadoInvalido("El pago " + clave + " ya está " + pago.getEstado() + " y no cambia.");
            }
            if (pago.getMetodo() == MetodoPago.EFECTIVO && !ADMITEN_CAMBIO_DE_EFECTIVO.contains(pedido.getEstado())) {
                throw NegocioException.estadoInvalido("El pedido " + pedido.getCodigo() + " ya fue aceptado contra entrega: su pago en "
                        + "efectivo sigue PENDIENTE hasta cobrarse al entregarlo (ENTPED), aunque una entrega falle. Si no se "
                        + "entregará, cancele el pedido y el pago terminará sin cobro.");
            }
            if (!pagos.rechazar(pago.getId())) {
                throw NegocioException.estadoInvalido("El pago cambió de estado durante la operación.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "PAGO", pago.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", EstadoPago.PENDIENTE).con("estado_nuevo", EstadoPago.RECHAZADO).con("motivo", causa));
            notificador.avisarConCorreo(pedido, TipoNotificacion.PAGO_RECHAZADO, "Pago rechazado", "El intento de pago "
                    + pago.getClaveIdempotencia() + " del pedido " + pedido.getCodigo() + " por Bs " + Importes.formatear(pago.getMonto())
                    + " no se cobró. Motivo: " + causa + ". Puede registrar un nuevo intento con REGPAG.");
            return new Verificacion(pagos.buscarPorClave(clave).orElseThrow(), pedido, false, false, null, null);
        });
    }

    private Pedido bloquearPedidoDe(String clave) {
        Pago leido = pagos.buscarPorClave(Textos.obligatorio(clave, "clave del pago"))
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el pago " + clave + ". Consulte LISPAG[\"*\"]."));
        return pedidos.bloquear(leido.getPedidoId()).orElseThrow();
    }

    // ------------------------------------------------------------------ consultas

    /** El personal ve todos los pagos; un CLIENTE solo los de sus pedidos. @param codigoPedido nulo para todos. */
    public List<PagoVista> listar(Solicitante solicitante, String codigoPedido) {
        Long cliente = AccesoPedido.clienteFiltrado(solicitante);
        return ctx.transacciones().enTransaccion(() -> {
            Long pedidoId = codigoPedido == null ? null : acceso.visible(solicitante, codigoPedido).getId();
            return pagos.listar(cliente, pedidoId, LIMITE_LISTADO);
        });
    }
}
