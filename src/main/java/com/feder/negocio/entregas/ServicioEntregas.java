package com.feder.negocio.entregas;

import com.feder.datos.entregas.RetornoRepositorio;
import com.feder.datos.entregas.RetornoRepositorio.ReservaDespachada;
import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.EntregaRepositorio.EntregaVista;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.Entrega;
import com.feder.modelo.EstadoEntrega;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.Factura;
import com.feder.modelo.Pago;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoNotificacion;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.pagos.CobrosPedido;
import com.feder.negocio.pagos.SeparacionFunciones;
import com.feder.negocio.pagos.ServicioFacturas;
import com.feder.negocio.ventas.AccesoPedido;
import com.feder.negocio.ventas.CambioEstadoPedido;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Logística de entrega después del despacho (ENTREGA, R027-R029, R049, R057): incidencias y reintentos,
 * retornos de mercancía, cierre de la búsqueda y entrega con cobro completo. Las operaciones manuales las
 * registra PERSONAL_FEDER o un ADMINISTRADOR vigente, con la fila del PEDIDO bloqueada.
 */
public class ServicioEntregas {

    /** Tope común de los listados (Listados): se pide una fila de más para saber si hay más registros. */
    private static final int LIMITE_LISTADO = Listados.A_PEDIR;
    private static final Pattern CLAVE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,58}");
    /** Formas de clave que genera FEDER (retorno sin clave, no recogido y la baja de cada retorno): no se aceptan indicadas. */
    private static final Pattern CLAVE_RESERVADA = Pattern.compile("(?i)RET-RES\\d+-\\d+|RET-NORECOGIDO-RES\\d+|.*-BAJA");
    private static final Set<EstadoEntrega> ADMITEN_RETORNO = EnumSet.of(EstadoEntrega.FALLIDA, EstadoEntrega.RETORNADA,
            EstadoEntrega.NO_RECUPERADA);
    private static final Set<EstadoEntrega> ESTADOS_MANUALES = EnumSet.of(EstadoEntrega.FALLIDA, EstadoEntrega.EN_CAMINO,
            EstadoEntrega.RETORNADA, EstadoEntrega.NO_RECUPERADA);

    private final ContextoNegocio ctx;
    private final PedidoRepositorio pedidos;
    private final EntregaRepositorio entregas;
    private final RetornoRepositorio retornos;
    private final CambioEstadoPedido cambioPedido;
    private final CambioEstadoEntrega cambioEntrega;
    private final RegistroRetornos registro;
    private final CobrosPedido cobros;
    private final ServicioFacturas facturas;
    private final NotificadorCliente notificador;

    public ServicioEntregas(ContextoNegocio ctx, PedidoRepositorio pedidos, EntregaRepositorio entregas, RetornoRepositorio retornos,
                            CambioEstadoPedido cambioPedido, RegistroRetornos registro, CobrosPedido cobros,
                            ServicioFacturas facturas) {
        this.ctx = ctx;
        this.pedidos = pedidos;
        this.entregas = entregas;
        this.retornos = retornos;
        this.cambioPedido = cambioPedido;
        this.cambioEntrega = new CambioEstadoEntrega(ctx, entregas);
        this.registro = registro;
        this.cobros = cobros;
        this.facturas = facturas;
        this.notificador = new NotificadorCliente(ctx);
    }

    /** Pedido con lo despachado y lo retornado por reserva. */
    public record SituacionEntrega(PedidoCompleto pedido, List<ReservaDespachada> despachadas) {
    }

    /** Pedido entregado y, si los hubo, el efectivo cobrado en la entrega y la factura emitida con ese cobro. */
    public record Entregado(PedidoCompleto pedido, Pago efectivoCobrado, Factura factura) {
    }

    /** Retorno registrado, con la situación de la entrega después de él. */
    public record RetornoRegistrado(SituacionEntrega situacion, String clave, BigDecimal recibida, BigDecimal noApta, boolean yaRegistrado) {
    }

    // ------------------------------------------------------------------ transiciones manuales

    /**
     * Transiciones logísticas tras el despacho: EN_CAMINO -> FALLIDA, reintento FALLIDA -> EN_CAMINO (sin retornos),
     * cierre con RETORNADA (todo volvió) o NO_RECUPERADA (parte o todo no volvió). Un RECOJO no recogido pasa a
     * RETORNADA registrando en la misma transacción el retorno de todo lo despachado, que nunca salió de FEDER.
     */
    public SituacionEntrega cambiarEstado(Solicitante solicitante, String codigo, EstadoEntrega nuevo, String observacion) {
        Permisos.exigirPersonal(solicitante, "registrar el avance de las entregas");
        if (!ESTADOS_MANUALES.contains(nuevo)) {
            throw NegocioException.regla("Con ESTENT se registran FALLIDA, EN_CAMINO (reintento), RETORNADA o NO_RECUPERADA; "
                    + "el despacho lo registra DESPED y la entrega al cliente ENTPED.");
        }
        String nota = Textos.obligatorio(observacion, "observación (incidencia, documento o constancia)");
        return ctx.transacciones().enTransaccion(() -> {
            PedidoCompleto completo = bloquear(codigo);
            exigirDespachado(completo.pedido());
            Entrega entrega = completo.entrega();
            List<ReservaDespachada> despachadas = retornos.despachadasDePedido(completo.pedido().getId());
            if (nuevo == EstadoEntrega.EN_CAMINO && retornos.tieneRetornos(completo.pedido().getId())) {
                throw NegocioException.estadoInvalido("La entrega ya tiene mercancía retornada: no vuelve a EN_CAMINO.");
            }
            if (nuevo == EstadoEntrega.RETORNADA && entrega.getEstado() == EstadoEntrega.LISTA_PARA_RECOJO) {
                retornarNoRecogido(despachadas, nota, solicitante.usuarioId());
                despachadas = retornos.despachadasDePedido(completo.pedido().getId());
            }
            exigirCierreCoherente(nuevo, despachadas);
            cambioEntrega.cambiar(entrega, nuevo, solicitante.usuarioId(), nota);
            return situacion(codigo);
        });
    }

    private void retornarNoRecogido(List<ReservaDespachada> despachadas, String evidencia, long autorId) {
        for (ReservaDespachada r : despachadas) {
            if (r.noRecuperado().signum() > 0) {
                registro.registrar(r, r.noRecuperado(), r.noRecuperado(), null, "Pedido no recogido en FEDER", evidencia,
                        "RET-NORECOGIDO-RES" + r.reservaId(), autorId);
            }
        }
    }

    /** R029: RETORNADA exige que todo haya vuelto; NO_RECUPERADA, que falte algo por volver. */
    private static void exigirCierreCoherente(EstadoEntrega nuevo, List<ReservaDespachada> despachadas) {
        BigDecimal pendiente = despachadas.stream().map(ReservaDespachada::noRecuperado).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (nuevo == EstadoEntrega.RETORNADA && pendiente.signum() > 0) {
            throw NegocioException.regla("RETORNADA exige que toda la mercancía haya vuelto: faltan " + Cantidades.formatear(pendiente)
                    + " unidades. Registre los retornos con RETENT o cierre la búsqueda como NO_RECUPERADA.");
        }
        if (nuevo == EstadoEntrega.NO_RECUPERADA && pendiente.signum() == 0) {
            throw NegocioException.regla("Toda la mercancía volvió a FEDER: la entrega se cierra como RETORNADA.");
        }
    }

    // ------------------------------------------------------------------ retornos

    /**
     * R029: un retorno físico por reserva, con la entrega FALLIDA, RETORNADA o NO_RECUPERADA. Antes de la
     * cancelación lo registra el personal; después de cancelar el pedido, solo un ADMINISTRADOR y con motivo.
     */
    public RetornoRegistrado registrarRetorno(Solicitante solicitante, SolicitudRetorno s) {
        Permisos.exigirPersonal(solicitante, "registrar retornos de mercancía");
        String evidencia = Textos.obligatorio(s.evidencia(), "evidencia del retorno (guía del transportista, acta o constancia)");
        String motivo = Textos.opcional(s.motivo());
        String clave = Textos.opcional(s.clave());
        if (clave != null && !CLAVE.matcher(clave).matches()) {
            throw NegocioException.regla("La clave del retorno admite hasta 59 letras, dígitos, '.', '-' o '_'.");
        }
        if (clave != null && CLAVE_RESERVADA.matcher(clave).matches()) {
            throw NegocioException.regla("Las claves RET-RES<n>-<n>, RET-NORECOGIDO-RES<n> y las terminadas en -BAJA las asigna "
                    + "FEDER: elija otra clave o deje el campo vacío.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            PedidoCompleto completo = bloquear(s.codigoPedido());
            Pedido pedido = completo.pedido();
            if (pedido.getEstado() == EstadoPedido.CANCELADO) {
                Permisos.exigirAdministrador(solicitante, "registrar una recuperación posterior a la cancelación");
                if (motivo == null) {
                    throw NegocioException.regla("Una recuperación posterior a la cancelación exige motivo.");
                }
            } else {
                exigirDespachado(pedido);
            }
            if (!ADMITEN_RETORNO.contains(completo.entrega().getEstado())) {
                throw NegocioException.estadoInvalido("Un retorno se registra con la entrega FALLIDA, RETORNADA o NO_RECUPERADA; "
                        + "la entrega está " + completo.entrega().getEstado() + ".");
            }
            ReservaDespachada reserva = retornos.despachadasDePedido(pedido.getId()).stream()
                    .filter(r -> r.reservaId() == s.reservaId()).findFirst()
                    .orElseThrow(() -> NegocioException.noEncontrado("La reserva " + s.reservaId() + " no es una reserva despachada del "
                            + "pedido " + pedido.getCodigo() + ". Consulte las reservas con VERPED."));
            RegistroRetornos.Retorno r = registro.registrar(reserva, s.recibida(), s.apta() == null ? s.recibida() : s.apta(),
                    s.tipoBaja(), motivo, evidencia, clave, solicitante.usuarioId());
            return new RetornoRegistrado(situacion(pedido.getCodigo()), r.entrada().getClaveOperacion(), r.entrada().getCantidad(),
                    r.baja() == null ? BigDecimal.ZERO : r.baja().getCantidad(), r.yaRegistrado());
        });
    }

    // ------------------------------------------------------------------ entrega al cliente

    /**
     * R027, R049 y R057: la ENTREGA pasa a ENTREGADA (con su fecha) y el PEDIDO a ENTREGADO en la misma transacción,
     * solo con el cobro completo; el efectivo contra entrega se confirma en ese momento, con quien lo recibió.
     */
    public Entregado entregar(Solicitante solicitante, String codigo, String observacion) {
        Permisos.exigirPersonal(solicitante, "registrar entregas");
        String nota = Textos.opcional(observacion);
        return ctx.transacciones().enTransaccion(() -> {
            PedidoCompleto completo = bloquear(codigo);
            Pedido pedido = completo.pedido();
            exigirDespachado(pedido);
            if (retornos.tieneRetornos(pedido.getId())) {
                throw NegocioException.estadoInvalido("La entrega ya tiene mercancía retornada: no puede pasar a ENTREGADA.");
            }
            Entrega entrega = completo.entrega();
            if (!TransicionesEntrega.permitida(entrega.getModalidad(), entrega.getEstado(), EstadoEntrega.ENTREGADA)) {
                throw NegocioException.estadoInvalido("La entrega está " + entrega.getEstado() + ": solo se entrega desde EN_CAMINO "
                        + "(delivery) o LISTA_PARA_RECOJO (recojo); tras una FALLIDA registre antes el reintento con ESTENT.");
            }
            if (!cobros.pagadoPorCompleto(pedido)) {
                SeparacionFunciones.exigirAjeno(solicitante, pedido, "recibir el cobro en efectivo");
            }
            // El pago se bloquea y confirma antes que la entrega, según el orden de bloqueo de CU6.
            Optional<Pago> efectivo = cobros.cobrarEnLaEntrega(pedido, solicitante.usuarioId());
            cambioEntrega.cambiar(entrega, EstadoEntrega.ENTREGADA, solicitante.usuarioId(), nota);
            cambioPedido.cambiar(pedido, EstadoPedido.ENTREGADO, solicitante.usuarioId(),
                    efectivo.isPresent() ? "Entregado y cobrado en efectivo" : nota);
            // R030: con el efectivo cobrado el pedido queda pagado por completo y se factura en la misma transacción.
            Optional<Factura> factura = facturas.alCompletarPago(pedido, solicitante.usuarioId());
            notificador.avisarConCorreo(pedido, TipoNotificacion.CAMBIO_ESTADO_PEDIDO, "Tu pedido fue entregado", "El pedido " + codigo
                    + " fue entregado. Gracias por apoyar a las comunidades productoras.");
            return new Entregado(pedidos.buscarConLineas(codigo).orElseThrow(), efectivo.orElse(null), factura.orElse(null));
        });
    }

    // ------------------------------------------------------------------ consultas

    /** El personal ve todas las entregas; un CLIENTE solo las de sus pedidos. */
    public List<EntregaVista> listar(Solicitante solicitante, EstadoEntrega estado) {
        Long cliente = AccesoPedido.clienteFiltrado(solicitante);
        return ctx.transacciones().enTransaccion(() -> entregas.listar(estado, cliente, LIMITE_LISTADO));
    }

    /** Situación de la entrega de un pedido, con lo despachado y retornado por reserva. */
    public SituacionEntrega situacion(Solicitante solicitante, String codigo) {
        if (!solicitante.esPersonalInterno() && !solicitante.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Solo el CLIENTE del pedido o el personal de FEDER consultan su entrega.");
        }
        return ctx.transacciones().enTransaccion(() -> situacion(new AccesoPedido(pedidos).visible(solicitante, codigo).getCodigo()));
    }

    private SituacionEntrega situacion(String codigo) {
        PedidoCompleto completo = pedidos.buscarConLineas(codigo).orElseThrow();
        return new SituacionEntrega(completo, new ArrayList<>(retornos.despachadasDePedido(completo.pedido().getId())));
    }

    private PedidoCompleto bloquear(String codigo) {
        return pedidos.bloquearConLineas(codigo)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el pedido " + codigo + ". Consulte LISPED[\"*\"]."));
    }

    private static void exigirDespachado(Pedido pedido) {
        if (pedido.getEstado() != EstadoPedido.DESPACHADO) {
            throw NegocioException.estadoInvalido("La logística de la entrega se registra con el pedido DESPACHADO; el pedido "
                    + pedido.getCodigo() + " está " + pedido.getEstado() + ".");
        }
    }
}
