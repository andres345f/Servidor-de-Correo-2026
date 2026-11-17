package com.feder.negocio.pagos;

import com.feder.datos.pagos.PagoRepositorio;
import com.feder.datos.pagos.ReembolsoRepositorio;
import com.feder.datos.pagos.ReembolsoRepositorio.ReembolsoVista;
import com.feder.datos.posventa.DevolucionRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Devolucion;
import com.feder.modelo.EstadoDevolucion;
import com.feder.modelo.EstadoReembolso;
import com.feder.modelo.Pago;
import com.feder.modelo.Pedido;
import com.feder.modelo.Reembolso;
import com.feder.modelo.TipoNotificacion;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Reintegros de dinero al cliente (REEMBOLSO, R076-R082). Se crean PENDIENTE cuando se ordenan (cobro tardío
 * de un pedido cancelado o devolución aprobada con REEMBOLSO) y el personal registra después su resultado:
 * EJECUTADO con el comprobante, o FALLIDO, que puede sustituirse por otro dentro de los mismos límites, en el
 * mismo momento (FALREM) o después (SUSREM).
 *
 * Orden de bloqueo (R080): PEDIDO, DEVOLUCION si la hay, PAGO y por último el REEMBOLSO.
 */
public class ServicioReembolsos {

    /** Tope común de los listados (Listados): se pide una fila de más para saber si hay más registros. */
    private static final int LIMITE_LISTADO = Listados.A_PEDIR;
    private static final int LARGO_CLAVE = 64;

    private final ContextoNegocio ctx;
    private final ReembolsoRepositorio reembolsos;
    private final PagoRepositorio pagos;
    private final PedidoRepositorio pedidos;
    private final DevolucionRepositorio devoluciones;
    private final NotificadorCliente notificador;

    public ServicioReembolsos(ContextoNegocio ctx, ReembolsoRepositorio reembolsos, PagoRepositorio pagos,
                              PedidoRepositorio pedidos, DevolucionRepositorio devoluciones) {
        this.ctx = ctx;
        this.reembolsos = reembolsos;
        this.pagos = pagos;
        this.pedidos = pedidos;
        this.devoluciones = devoluciones;
        this.notificador = new NotificadorCliente(ctx);
    }

    /** Reembolso con el código de su pedido, para las respuestas. */
    public record ResultadoReembolso(Reembolso reembolso, String codigoPedido, Reembolso sustituto) {
    }

    // ------------------------------------------------------------------ creación (llamada dentro de otra operación)

    /**
     * R053 (4) y R081: un cobro capturado después de cancelar el pedido nunca lo reactiva; se devuelve con un
     * REEMBOLSO sin devolución. El pedido y el pago ya están bloqueados y el pago acaba de quedar PAGADO.
     */
    public Reembolso porCobroTardio(Pedido pedido, Pago pago, Long autorId) {
        BigDecimal libre = pago.getMonto().subtract(reembolsos.comprometidoDePago(pago.getId()));
        String motivo = "Cobro recibido después de la cancelación del pedido " + pedido.getCodigo();
        return crear(pago, null, libre, motivo, "TARD-PAGO" + pago.getId(), autorId);
    }

    /**
     * R026, R077, R078 y R081: reparte el importe reembolsable de una devolución recién APROBADA entre los pagos
     * PAGADO del pedido, sin superar lo libre de cada pago. El pedido y la devolución ya están bloqueados.
     */
    public List<Reembolso> paraDevolucion(Devolucion devolucion, Pedido pedido, Long autorId) {
        BigDecimal pendiente = devolucion.getImporteReembolsable().subtract(reembolsos.comprometidoDeDevolucion(devolucion.getId()));
        List<Reembolso> creados = new ArrayList<>();
        for (Pago pago : pagos.bloquearPagadosDePedido(pedido.getId())) {
            if (pendiente.signum() <= 0) {
                break;
            }
            BigDecimal libre = pago.getMonto().subtract(reembolsos.comprometidoDePago(pago.getId()));
            BigDecimal monto = libre.min(pendiente);
            if (monto.signum() > 0) {
                creados.add(crear(pago, devolucion.getId(), monto, recortar("Devolución " + devolucion.getCodigo() + ": "
                        + devolucion.getMotivo()), "DEV" + devolucion.getId() + "-PAGO" + pago.getId() + "-1", autorId));
                pendiente = pendiente.subtract(monto);
            }
        }
        if (pendiente.signum() > 0) {
            throw NegocioException.regla("Los pagos cobrados del pedido " + pedido.getCodigo() + " no alcanzan para reintegrar Bs "
                    + Importes.formatear(devolucion.getImporteReembolsable()) + ": cada reintegro se limita a lo cobrado y aún "
                    + "no reintegrado de su pago.");
        }
        return creados;
    }

    private Reembolso crear(Pago pago, Long devolucionId, BigDecimal monto, String motivo, String clave, Long autorId) {
        if (monto.signum() <= 0) {
            throw NegocioException.regla("El pago " + pago.getClaveIdempotencia() + " ya no tiene importe por reintegrar.");
        }
        Reembolso r = new Reembolso();
        r.setPagoId(pago.getId());
        r.setDevolucionId(devolucionId);
        r.setMonto(monto);
        r.setEstado(EstadoReembolso.PENDIENTE);
        r.setMotivo(recortar(motivo));
        r.setFechaSolicitud(ctx.ahora());
        r.setClaveIdempotencia(clave);
        r.setId(reembolsos.insertar(r));
        ctx.bitacora().registrar(autorId, AccionBitacora.CREAR, "REEMBOLSO", r.getId(), DetalleJson.nuevo()
                .con("pago_id", pago.getId()).con("devolucion_id", devolucionId).con("monto", monto)
                .con("clave_idempotencia", clave).con("motivo", r.getMotivo()));
        return r;
    }

    // ------------------------------------------------------------------ resultado del reintegro

    /** PENDIENTE -> EJECUTADO: el dinero se devolvió, con el comprobante del reintegro (R076, R046). */
    public ResultadoReembolso ejecutar(Solicitante solicitante, String clave, String referencia) {
        Permisos.exigirPersonal(solicitante, "registrar la ejecución de reembolsos");
        String comprobante = Textos.obligatorio(referencia, "comprobante del reintegro");
        return ctx.transacciones().enTransaccion(() -> {
            Bloqueado b = bloquear(clave, EstadoReembolso.PENDIENTE);
            SeparacionFunciones.exigirAjeno(solicitante, b.pedido(), "registrar la ejecución del reembolso");
            if (reembolsos.existeReferencia(comprobante)) {
                throw NegocioException.duplicado("El comprobante " + comprobante + " ya está registrado en otro reembolso.");
            }
            if (!reembolsos.ejecutar(b.reembolso().getId(), ctx.ahora(), comprobante)) {
                throw NegocioException.estadoInvalido("El reembolso cambió de estado durante la operación.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "REEMBOLSO", b.reembolso().getId(),
                    DetalleJson.nuevo().con("estado_anterior", EstadoReembolso.PENDIENTE).con("estado_nuevo", EstadoReembolso.EJECUTADO)
                            .con("referencia_externa", comprobante));
            notificador.avisarConCorreo(b.pedido(), TipoNotificacion.REEMBOLSO_EJECUTADO, "Reembolso ejecutado", "Le devolvimos Bs "
                    + Importes.formatear(b.reembolso().getMonto()) + " del pedido " + b.pedido().getCodigo()
                    + ". Comprobante: " + comprobante + ".");
            return new ResultadoReembolso(releer(clave), b.pedido().getCodigo(), null);
        });
    }

    /**
     * PENDIENTE -> FALLIDO: el reintegro no se pudo hacer. Con {@code sustituir} se crea en la misma transacción
     * otro REEMBOLSO PENDIENTE de la misma pareja (pago, devolución) y el mismo monto, dentro de los límites (R082).
     */
    public ResultadoReembolso fallar(Solicitante solicitante, String clave, String motivo, boolean sustituir) {
        Permisos.exigirPersonal(solicitante, "registrar reembolsos fallidos");
        String causa = Textos.obligatorio(motivo, "motivo del fallo");
        return ctx.transacciones().enTransaccion(() -> {
            Bloqueado b = bloquear(clave, EstadoReembolso.PENDIENTE);
            SeparacionFunciones.exigirAjeno(solicitante, b.pedido(), "registrar el fallo del reembolso");
            Reembolso fallido = b.reembolso();
            if (!reembolsos.marcarFallido(fallido.getId())) {
                throw NegocioException.estadoInvalido("El reembolso cambió de estado durante la operación.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "REEMBOLSO", fallido.getId(),
                    DetalleJson.nuevo().con("estado_anterior", EstadoReembolso.PENDIENTE).con("estado_nuevo", EstadoReembolso.FALLIDO)
                            .con("motivo", causa));
            Reembolso sustituto = sustituir ? sustituir(b, solicitante.usuarioId()) : null;
            return new ResultadoReembolso(releer(clave), b.pedido().getCodigo(), sustituto);
        });
    }

    /**
     * R082: ordena después otro REEMBOLSO PENDIENTE que sustituye a uno FALLIDO, cuando la pareja (pago, devolución)
     * ya no tiene ninguno PENDIENTE; lo hace el personal cuando el cliente aporta los datos para reintentar. El monto
     * es el del fallido, recortado a lo libre del pago y de la devolución (R077, R078, R081), con el mismo orden de
     * bloqueo que el resto de los reembolsos (R080).
     */
    public ResultadoReembolso sustituirFallido(Solicitante solicitante, String clave) {
        Permisos.exigirPersonal(solicitante, "ordenar reembolsos sustitutos");
        return ctx.transacciones().enTransaccion(() -> {
            Bloqueado b = bloquear(clave, EstadoReembolso.FALLIDO);
            SeparacionFunciones.exigirAjeno(solicitante, b.pedido(), "ordenar el reembolso");
            reembolsos.pendienteDePareja(b.pago().getId(), b.reembolso().getDevolucionId()).ifPresent(p -> {
                throw NegocioException.estadoInvalido("El pago ya tiene el reembolso " + p + " PENDIENTE por esta misma causa: "
                        + "regístrelo con EJEREM o FALREM antes de ordenar otro.");
            });
            Reembolso sustituto = sustituir(b, solicitante.usuarioId());
            return new ResultadoReembolso(b.reembolso(), b.pedido().getCodigo(), sustituto);
        });
    }

    private Reembolso sustituir(Bloqueado b, long autorId) {
        Reembolso fallido = b.reembolso();
        if (b.devolucion() != null && b.devolucion().getEstado() != EstadoDevolucion.APROBADA) {
            throw NegocioException.estadoInvalido("La devolución ya no está APROBADA: no admite otro reintegro.");
        }
        BigDecimal libreDevolucion = b.devolucion() == null ? fallido.getMonto()
                : b.devolucion().getImporteReembolsable().subtract(reembolsos.comprometidoDeDevolucion(b.devolucion().getId()));
        BigDecimal librePago = b.pago().getMonto().subtract(reembolsos.comprometidoDePago(b.pago().getId()));
        BigDecimal monto = fallido.getMonto().min(libreDevolucion).min(librePago);
        int numero = reembolsos.contarDePareja(b.pago().getId(), fallido.getDevolucionId()) + 1;
        String base = fallido.getClaveIdempotencia();
        String sufijo = "-S" + numero;
        String clave = (base.length() + sufijo.length() > LARGO_CLAVE ? base.substring(0, LARGO_CLAVE - sufijo.length()) : base) + sufijo;
        return crear(b.pago(), fallido.getDevolucionId(), monto, fallido.getMotivo(), clave, autorId);
    }

    /** El personal ve todos los reembolsos; un CLIENTE solo los de sus pedidos. */
    public List<ReembolsoVista> listar(Solicitante solicitante, EstadoReembolso estado) {
        Long cliente = AccesoPedido.clienteFiltrado(solicitante);
        return ctx.transacciones().enTransaccion(() -> reembolsos.listar(cliente, estado, LIMITE_LISTADO));
    }

    private record Bloqueado(Pedido pedido, Devolucion devolucion, Pago pago, Reembolso reembolso) {
    }

    private Bloqueado bloquear(String clave, EstadoReembolso esperado) {
        ReembolsoVista vista = reembolsos.buscarPorClave(Textos.obligatorio(clave, "clave del reembolso"))
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el reembolso " + clave + ". Consulte LISREM[\"*\"]."));
        Pedido pedido = pedidos.bloquear(vista.pedidoId()).orElseThrow();
        Devolucion devolucion = vista.reembolso().getDevolucionId() == null ? null
                : devoluciones.bloquear(vista.reembolso().getDevolucionId()).orElseThrow();
        Pago pago = pagos.bloquear(vista.reembolso().getPagoId()).orElseThrow();
        Reembolso reembolso = reembolsos.bloquear(vista.reembolso().getId()).orElseThrow();
        if (reembolso.getEstado() != esperado) {
            throw NegocioException.estadoInvalido("El reembolso " + clave + " está " + reembolso.getEstado()
                    + (esperado == EstadoReembolso.PENDIENTE ? ": solo cambia de estado un reembolso PENDIENTE."
                    : ": solo se sustituye un reembolso FALLIDO."));
        }
        return new Bloqueado(pedido, devolucion, pago, reembolso);
    }

    private Reembolso releer(String clave) {
        return reembolsos.buscarPorClave(clave).orElseThrow().reembolso();
    }

    private static String recortar(String texto) {
        return texto.length() <= 255 ? texto : texto.substring(0, 255);
    }
}
