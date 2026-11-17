package com.feder.negocio.posventa;

import com.feder.datos.pagos.ReembolsoRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio.Atribuido;
import com.feder.datos.posventa.DevolucionRepositorio.Despacho;
import com.feder.datos.posventa.DevolucionRepositorio.DevolucionVista;
import com.feder.datos.posventa.DevolucionRepositorio.Reingreso;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Devolucion;
import com.feder.modelo.EstadoDevolucion;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.Reembolso;
import com.feder.modelo.ResolucionDevolucion;
import com.feder.modelo.TipoMovimiento;
import com.feder.modelo.TipoNotificacion;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.pagos.CobrosPedido;
import com.feder.negocio.pagos.SeparacionFunciones;
import com.feder.negocio.pagos.ServicioReembolsos;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Decisión y cierre de una devolución (R018, R020, R021, R024-R026): aprobación con REPOSICION o REEMBOLSO,
 * rechazo, entrega de la reposición, cierre COMPLETADA y anulación por rectificación. Lo hace el personal, con
 * el pedido, la línea, la devolución y el despacho bloqueados en ese orden.
 */
public class ServicioResolucionDevoluciones {

    private static final Pattern CLAVE_REPOSICION_GENERADA = Pattern.compile("(?i)DEV\\d+-REPO-\\d+(-\\d+)?");

    private final ContextoNegocio ctx;
    private final DevolucionRepositorio devoluciones;
    private final ReembolsoRepositorio reembolsos;
    private final TramiteDevolucion tramite;
    private final ServicioReembolsos servicioReembolsos;
    private final ServicioInventario inventario;
    private final CobrosPedido cobros;
    private final NotificadorCliente notificador;

    ServicioResolucionDevoluciones(ContextoNegocio ctx, DevolucionRepositorio devoluciones, ReembolsoRepositorio reembolsos,
                                   TramiteDevolucion tramite, ServicioReembolsos servicioReembolsos, ServicioInventario inventario,
                                   CobrosPedido cobros) {
        this.ctx = ctx;
        this.devoluciones = devoluciones;
        this.reembolsos = reembolsos;
        this.tramite = tramite;
        this.servicioReembolsos = servicioReembolsos;
        this.inventario = inventario;
        this.cobros = cobros;
        this.notificador = new NotificadorCliente(ctx);
    }

    /** Devolución aprobada con los reintegros ordenados y lo reincorporado al stock en la misma operación. */
    public record Aprobacion(DevolucionVista vista, List<Reembolso> reintegros, BigDecimal reincorporado) {
    }

    /** Devolución anulada y la devolución nueva que la rectifica (A39). */
    public record Rectificacion(DevolucionVista anulada, DevolucionVista rectificacion) {
    }

    // ------------------------------------------------------------------ decisión

    /**
     * SOLICITADA -> APROBADA. Con REEMBOLSO fija una sola vez el importe atribuido sobre la línea (R018, R020: el
     * total del pedido no supera lo cobrado) y ordena los reintegros PENDIENTE; con REPOSICION el importe es nulo.
     * Si la mercancía ya se inspeccionó, las aptas aprobadas vuelven al stock en la misma transacción.
     */
    public Aprobacion aprobar(Solicitante solicitante, String codigo, ResolucionDevolucion resolucion, BigDecimal cantidadAprobada,
                              String comentario) {
        Permisos.exigirPersonal(solicitante, "aprobar devoluciones");
        if (resolucion == null) {
            throw NegocioException.regla("Indique la resolución: REPOSICION o REEMBOLSO.");
        }
        String nota = Textos.opcional(comentario);
        return ctx.transacciones().enTransaccion(() -> {
            TramiteDevolucion.Bloqueada b = tramite.bloquear(solicitante, codigo);
            SeparacionFunciones.exigirAjeno(solicitante, b.pedido(), "aprobar la devolución");
            Devolucion d = b.devolucion();
            exigirEstado(d, EstadoDevolucion.SOLICITADA, "aprobar");
            Cantidades.validarPositiva(cantidadAprobada, null, "cantidad aprobada");
            if (cantidadAprobada.compareTo(d.getCantidad()) > 0) {
                throw NegocioException.regla("La cantidad aprobada no puede superar la reclamada (" + Cantidades.formatear(d.getCantidad()) + ").");
            }
            BigDecimal importe = resolucion == ResolucionDevolucion.REEMBOLSO ? importe(b, cantidadAprobada) : null;
            exigirLimiteDespacho(b, d.getCantidad(), cantidadAprobada);
            if (!devoluciones.aprobar(d.getId(), resolucion, cantidadAprobada, importe, ctx.ahora())) {
                throw NegocioException.estadoInvalido("La devolución cambió de estado durante la aprobación.");
            }
            Devolucion aprobada = tramite.releer(b);
            List<Reembolso> reintegros = importe == null || importe.signum() == 0 ? List.of()
                    : servicioReembolsos.paraDevolucion(aprobada, b.pedido(), solicitante.usuarioId());
            BigDecimal reincorporado = tramite.reincorporar(b, solicitante.usuarioId()).map(MovimientoInventario::getCantidad)
                    .orElse(BigDecimal.ZERO);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "DEVOLUCION", d.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", EstadoDevolucion.SOLICITADA).con("estado_nuevo", EstadoDevolucion.APROBADA)
                    .con("resolucion", resolucion).con("cantidad_aprobada", cantidadAprobada).con("importe_reembolsable", importe)
                    .con("comentario", nota));
            notificador.avisarConCorreo(b.pedido(), TipoNotificacion.DEVOLUCION_RESUELTA, "Devolución aprobada", "Aprobamos la devolución "
                    + codigo + " del pedido " + b.pedido().getCodigo() + " por " + Cantidades.formatear(cantidadAprobada) + " unidad(es): "
                    + (importe == null ? "le entregaremos la reposición en FEDER." : "le reembolsaremos Bs " + Importes.formatear(importe) + "."));
            return new Aprobacion(devoluciones.buscarPorCodigo(codigo).orElseThrow(), reintegros, reincorporado);
        });
    }

    /** R018 y R020: importe atribuido sobre la línea, que sumado a lo atribuido del pedido no supera lo cobrado. */
    private BigDecimal importe(TramiteDevolucion.Bloqueada b, BigDecimal cantidadAprobada) {
        Atribuido atribuido = devoluciones.atribuidoDeLinea(b.linea().getId());
        BigDecimal importe = AtribucionImporte.calcular(b.linea().getSubtotal(), b.linea().getCantidad(), atribuido.cantidadAprobada(),
                atribuido.importe(), cantidadAprobada);
        BigDecimal pagado = cobros.pagado(b.pedido().getId());
        BigDecimal atribuidoPedido = devoluciones.atribuidoDePedido(b.pedido().getId());
        if (atribuidoPedido.add(importe).compareTo(pagado) > 0) {
            throw NegocioException.regla("El reembolso de Bs " + Importes.formatear(importe) + " superaría lo cobrado del pedido (Bs "
                    + Importes.formatear(pagado) + ", ya atribuido Bs " + Importes.formatear(atribuidoPedido) + "). Resuelva con REPOSICION.");
        }
        return importe;
    }

    /** R022 (a): lo solicitado y lo aprobado del despacho no supera su cantidad, con su fila bloqueada. */
    private void exigirLimiteDespacho(TramiteDevolucion.Bloqueada b, BigDecimal antes, BigDecimal ahora) {
        Despacho despacho = tramite.despacho(b);
        BigDecimal comprometido = devoluciones.comprometidoDeDespacho(despacho.movimientoId()).subtract(antes).add(ahora);
        if (comprometido.compareTo(despacho.cantidad()) > 0) {
            throw NegocioException.regla("Las devoluciones del despacho superarían lo despachado (" + Cantidades.formatear(despacho.cantidad()) + ").");
        }
    }

    /** SOLICITADA -> RECHAZADA: lo recibido queda en custodia de FEDER, fuera del inventario vendible (R024 (4)). */
    public DevolucionVista rechazar(Solicitante solicitante, String codigo, String motivo) {
        Permisos.exigirPersonal(solicitante, "rechazar devoluciones");
        String causa = Textos.obligatorio(motivo, "motivo del rechazo");
        return ctx.transacciones().enTransaccion(() -> {
            TramiteDevolucion.Bloqueada b = tramite.bloquear(solicitante, codigo);
            exigirEstado(b.devolucion(), EstadoDevolucion.SOLICITADA, "rechazar");
            if (!devoluciones.rechazar(b.devolucion().getId(), ctx.ahora())) {
                throw NegocioException.estadoInvalido("La devolución cambió de estado durante el rechazo.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "DEVOLUCION", b.devolucion().getId(),
                    DetalleJson.nuevo().con("estado_anterior", EstadoDevolucion.SOLICITADA).con("estado_nuevo", EstadoDevolucion.RECHAZADA)
                            .con("motivo", causa));
            notificador.avisarConCorreo(b.pedido(), TipoNotificacion.DEVOLUCION_RESUELTA, "Devolución rechazada", "No aprobamos la "
                    + "devolución " + codigo + " del pedido " + b.pedido().getCodigo() + ". Motivo: " + causa + ".");
            return devoluciones.buscarPorCodigo(codigo).orElseThrow();
        });
    }

    // ------------------------------------------------------------------ resolución y cierre

    /**
     * R026: entrega de la reposición por recojo en FEDER. Cada SALIDA_REPOSICION se registra al entregarla, con
     * FEFO y solo del stock libre, sin superar la cantidad aprobada, con la devolución bloqueada.
     */
    public List<MovimientoInventario> reponer(Solicitante solicitante, String codigo, BigDecimal cantidad, String claveIndicada) {
        Permisos.exigirPersonal(solicitante, "entregar reposiciones");
        return ctx.transacciones().enTransaccion(() -> {
            TramiteDevolucion.Bloqueada b = tramite.bloquear(solicitante, codigo);
            Devolucion d = b.devolucion();
            exigirEstado(d, EstadoDevolucion.APROBADA, "entregar la reposición de");
            if (d.getResolucion() != ResolucionDevolucion.REPOSICION) {
                throw NegocioException.regla("La devolución " + codigo + " se resolvió con REEMBOLSO: no admite reposición.");
            }
            Cantidades.validarPositiva(cantidad, null, "cantidad repuesta");
            BigDecimal repuesto = devoluciones.sumaMovimientos(d.getId(), TipoMovimiento.SALIDA_REPOSICION);
            String indicada = Textos.opcional(claveIndicada);
            if (indicada != null && CLAVE_REPOSICION_GENERADA.matcher(indicada).matches()) {
                throw NegocioException.regla("Las claves DEV<n>-REPO-<n> las asigna FEDER: elija otra clave o deje el campo vacío.");
            }
            String clave = indicada != null ? indicada
                    : "DEV" + d.getId() + "-REPO-" + (devoluciones.contarMovimientos(d.getId(), TipoMovimiento.SALIDA_REPOSICION) + 1);
            if (repuesto.add(cantidad).compareTo(d.getCantidadAprobada()) > 0 && !reposicionRegistrada(clave)) {
                throw NegocioException.regla("La reposición superaría lo aprobado: aprobado " + Cantidades.formatear(d.getCantidadAprobada())
                        + ", ya repuesto " + Cantidades.formatear(repuesto) + ".");
            }
            boolean nueva = !reposicionRegistrada(clave);
            List<MovimientoInventario> salidas = inventario.registrarSalidaReposicion(d.getId(), b.linea().getProductoId(), cantidad,
                    solicitante.usuarioId(), clave);
            if (nueva) {
                ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, "MOVIMIENTO_INVENTARIO", salidas.get(0).getId(),
                        DetalleJson.nuevo().con("operacion", "REPOSICION_DEVOLUCION").con("devolucion", codigo)
                                .con("cantidad", cantidad).con("clave_operacion", clave).con("movimientos", salidas.size()));
            }
            return salidas;
        });
    }

    private boolean reposicionRegistrada(String clave) {
        return devoluciones.existeMovimientoConClave(clave + "-1");
    }

    /**
     * APROBADA -> COMPLETADA (R026): con REEMBOLSO, lo EJECUTADO iguala el importe; con REPOSICION, lo repuesto
     * iguala lo aprobado; y la mercancía recibida está resuelta (inspeccionada y reincorporada hasta lo aprobado).
     */
    public DevolucionVista completar(Solicitante solicitante, String codigo) {
        Permisos.exigirPersonal(solicitante, "completar devoluciones");
        return ctx.transacciones().enTransaccion(() -> {
            TramiteDevolucion.Bloqueada b = tramite.bloquear(solicitante, codigo);
            Devolucion d = b.devolucion();
            exigirEstado(d, EstadoDevolucion.APROBADA, "completar");
            exigirCompensacionCompleta(d);
            exigirMercanciaResuelta(d);
            if (!devoluciones.completar(d.getId(), ctx.ahora())) {
                throw NegocioException.estadoInvalido("La devolución cambió de estado durante el cierre.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "DEVOLUCION", d.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", EstadoDevolucion.APROBADA).con("estado_nuevo", EstadoDevolucion.COMPLETADA));
            notificador.avisar(b.pedido(), TipoNotificacion.DEVOLUCION_RESUELTA, "Devolución completada", "La devolución " + codigo
                    + " del pedido " + b.pedido().getCodigo() + " quedó completada.");
            return devoluciones.buscarPorCodigo(codigo).orElseThrow();
        });
    }

    private void exigirCompensacionCompleta(Devolucion d) {
        if (d.getResolucion() == ResolucionDevolucion.REEMBOLSO) {
            BigDecimal ejecutado = reembolsos.ejecutadoDeDevolucion(d.getId());
            if (ejecutado.compareTo(d.getImporteReembolsable()) != 0) {
                throw NegocioException.regla("Para completarla, los reembolsos EJECUTADO deben sumar Bs "
                        + Importes.formatear(d.getImporteReembolsable()) + "; van Bs " + Importes.formatear(ejecutado) + ".");
            }
        } else {
            BigDecimal repuesto = devoluciones.sumaMovimientos(d.getId(), TipoMovimiento.SALIDA_REPOSICION);
            if (repuesto.compareTo(d.getCantidadAprobada()) != 0) {
                throw NegocioException.regla("Para completarla, la reposición entregada debe ser " + Cantidades.formatear(d.getCantidadAprobada())
                        + "; va " + Cantidades.formatear(repuesto) + " (REPDEV).");
            }
        }
    }

    private void exigirMercanciaResuelta(Devolucion d) {
        if (d.getCantidadRecibida() == null) {
            return;
        }
        if (d.getCantidadApta() == null) {
            throw NegocioException.regla("La mercancía recibida aún no se inspeccionó (INSDEV).");
        }
        BigDecimal esperado = d.getCantidadApta().min(d.getCantidadAprobada());
        if (devoluciones.sumaMovimientos(d.getId(), TipoMovimiento.ENTRADA_DEVOLUCION).compareTo(esperado) != 0) {
            throw NegocioException.regla("Las unidades aptas aprobadas aún no se reincorporaron al stock.");
        }
    }

    // ------------------------------------------------------------------ rectificación

    /**
     * R025: una aprobación equivocada pasa a ANULADA solo con sus efectos conciliados: (a) sin reembolsos PENDIENTE ni
     * EJECUTADO, (b) sin reposiciones entregadas y (c) cada reingreso compensado con un AJUSTE_NEGATIVO en su lote. La
     * decisión correcta se registra como una devolución nueva SOLICITADA sobre el mismo despacho, vinculada a la anulada
     * y con la mercancía recibida que hereda; después se aprueba o rechaza como cualquier otra.
     */
    public Rectificacion anular(Solicitante solicitante, String codigo, String motivo) {
        Permisos.exigirPersonal(solicitante, "anular devoluciones por rectificación");
        String causa = Textos.obligatorio(motivo, "motivo de la rectificación");
        return ctx.transacciones().enTransaccion(() -> {
            TramiteDevolucion.Bloqueada b = tramite.bloquear(solicitante, codigo);
            Devolucion d = b.devolucion();
            exigirEstado(d, EstadoDevolucion.APROBADA, "anular");
            if (reembolsos.comprometidoDeDevolucion(d.getId()).signum() > 0) {
                throw NegocioException.regla("La devolución tiene reembolsos PENDIENTE o EJECUTADO: el dinero ordenado o devuelto no se "
                        + "compensa y la decisión queda en firme.");
            }
            if (devoluciones.sumaMovimientos(d.getId(), TipoMovimiento.SALIDA_REPOSICION).signum() > 0) {
                throw NegocioException.regla("Ya se entregó reposición de esta devolución: la decisión queda en firme y se completa.");
            }
            for (Reingreso r : devoluciones.reingresosPorLote(d.getId())) {
                if (r.cantidad().signum() > 0) {
                    inventario.registrarCompensacionDeDevolucion(d.getId(), r.loteFederId(), r.cantidad(), solicitante.usuarioId(),
                            "DEV" + d.getId() + "-COMPENSA-L" + r.loteFederId(), "Compensación por rectificación de la devolución " + codigo);
                }
            }
            if (!devoluciones.anular(d.getId(), ctx.ahora(), causa)) {
                throw NegocioException.estadoInvalido("La devolución cambió de estado durante la anulación.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.ANULAR, "DEVOLUCION", d.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", EstadoDevolucion.APROBADA).con("estado_nuevo", EstadoDevolucion.ANULADA).con("motivo", causa));
            Devolucion nueva = rectificar(b, d, solicitante.usuarioId());
            notificador.avisarConCorreo(b.pedido(), TipoNotificacion.DEVOLUCION_RESUELTA, "Devolución en revisión", "La decisión sobre "
                    + "la devolución " + codigo + " se anuló por un error y la revisaremos de nuevo como " + nueva.getCodigo() + ".");
            return new Rectificacion(devoluciones.buscarPorCodigo(codigo).orElseThrow(),
                    devoluciones.buscarPorCodigo(nueva.getCodigo()).orElseThrow());
        });
    }

    private Devolucion rectificar(TramiteDevolucion.Bloqueada b, Devolucion anulada, long autorId) {
        exigirLimiteDespacho(b, BigDecimal.ZERO, anulada.getCantidad());
        Devolucion nueva = new Devolucion();
        nueva.setId(devoluciones.siguienteId());
        // El código sale del identificador nuevo, en el formato que solo FEDER asigna, para que nadie pueda ocuparlo antes.
        nueva.setCodigo(CodigosDevolucion.generado(ctx, nueva.getId()));
        nueva.setMovimientoInventarioId(anulada.getMovimientoInventarioId());
        nueva.setDevolucionAnuladaId(anulada.getId());
        nueva.setCantidad(anulada.getCantidad());
        nueva.setMotivo(anulada.getMotivo());
        nueva.setFechaSolicitud(ctx.ahora());
        nueva.setCantidadRecibida(anulada.getCantidadRecibida());
        nueva.setFechaRecepcion(anulada.getFechaRecepcion());
        nueva.setCantidadApta(anulada.getCantidadApta());
        nueva.setFechaInspeccion(anulada.getFechaInspeccion());
        devoluciones.insertar(nueva);
        ctx.bitacora().registrar(autorId, AccionBitacora.CREAR, "DEVOLUCION", nueva.getId(), DetalleJson.nuevo()
                .con("codigo", nueva.getCodigo()).con("rectifica", anulada.getCodigo()).con("cantidad", nueva.getCantidad()));
        return nueva;
    }

    private static void exigirEstado(Devolucion d, EstadoDevolucion esperado, String accion) {
        if (d.getEstado() != esperado) {
            throw NegocioException.estadoInvalido("Solo se puede " + accion + " una devolución " + esperado + "; la devolución "
                    + d.getCodigo() + " está " + d.getEstado() + ".");
        }
    }
}
