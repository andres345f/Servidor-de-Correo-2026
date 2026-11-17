package com.feder.negocio.inventario;

import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.DetalleCompraVista;
import com.feder.datos.compras.LoteOrigenRepositorio;
import com.feder.datos.compras.LoteOrigenRepositorio.LoteOrigen;
import com.feder.datos.compras.OperacionCompraRepositorio;
import com.feder.datos.compras.OperacionCompraRepositorio.OperacionRegistrada;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.CompraFeder;
import com.feder.modelo.EstadoCompra;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Operaciones sobre una línea de compra (R013 y R014): recepción física (aceptado y rechazado) y
 * cancelación de lo pendiente. Cada operación lleva una clave única; repetirla con la misma clave no
 * vuelve a aplicar nada y devuelve la operación ya registrada.
 *
 * Orden de bloqueo: LOTE_PRODUCCION, DETALLE_COMPRA, LOTE_FEDER y al final COMPRA_FEDER (R012, R037).
 */
public class ServicioRecepcion {

    private static final String ENTIDAD = "DETALLE_COMPRA";
    private static final String RECEPCION = "RECEPCION";
    private static final String CANCELACION = "CANCELACION";
    private static final String ANULACION = "ANULACION_RECEPCION";

    private final ContextoNegocio ctx;
    private final CompraRepositorio compras;
    private final DetalleCompraRepositorio detalles;
    private final LoteOrigenRepositorio lotesOrigen;
    private final OperacionCompraRepositorio operaciones;
    private final MovimientoRepositorio movimientos;
    private final LoteFederRepositorio lotesFeder;
    private final ServicioInventario inventario;
    private final RecalculoCompra recalculo;

    public ServicioRecepcion(ContextoNegocio ctx, CompraRepositorio compras, DetalleCompraRepositorio detalles,
                             LoteOrigenRepositorio lotesOrigen, OperacionCompraRepositorio operaciones,
                             MovimientoRepositorio movimientos, LoteFederRepositorio lotesFeder,
                             ServicioInventario inventario) {
        this.ctx = ctx;
        this.compras = compras;
        this.detalles = detalles;
        this.lotesOrigen = lotesOrigen;
        this.operaciones = operaciones;
        this.movimientos = movimientos;
        this.lotesFeder = lotesFeder;
        this.inventario = inventario;
        this.recalculo = new RecalculoCompra(compras, detalles);
    }

    /** Registra una entrega de la línea: solo lo aceptado entra al inventario (ENTRADA_COMPRA). */
    public ResultadoLinea recibir(Solicitante solicitante, SolicitudRecepcion s) {
        Permisos.exigirPersonal(solicitante, "registrar recepciones de compras");
        BigDecimal aceptada = s.aceptada() == null ? BigDecimal.ZERO : s.aceptada();
        BigDecimal rechazada = s.rechazada() == null ? BigDecimal.ZERO : s.rechazada();
        String clave = Textos.obligatorio(s.claveOperacion(), "clave de operación");
        String motivo = Textos.opcional(s.motivo());
        String documento = Textos.opcional(s.documentoEntrega());
        if (aceptada.signum() < 0 || rechazada.signum() < 0 || aceptada.add(rechazada).signum() == 0) {
            throw NegocioException.regla("Indique cantidades no negativas y que acepte o rechace al menos una unidad.");
        }
        if (rechazada.signum() > 0 && motivo == null) {
            throw NegocioException.regla("Todo rechazo se justifica: indique el motivo de las unidades rechazadas.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            CompraFeder compra = buscarCompra(s.codigoCompra());
            DetalleCompraVista vista = buscarLinea(compra, s.codigoLoteOrigen());
            // R056 (a): los pedidos que esperan este producto se bloquean primero, antes que el inventario (R037).
            AbastecimientoPedidos.Espera espera = aceptada.signum() > 0
                    ? inventario.esperaDeProducto(vista.productoId(), vista.detalle().getLoteOrigenId())
                    : AbastecimientoPedidos.Espera.NINGUNA;
            LoteOrigen origen = lotesOrigen.bloquearCompartido(vista.detalle().getLoteOrigenId()).orElseThrow();
            DetalleCompraVista linea = detalles.bloquear(vista.detalle().getId()).orElseThrow();
            Optional<ResultadoLinea> previa = operacionPrevia(clave, compra, linea, RECEPCION);
            if (previa.isPresent()) {
                return previa.get();
            }
            exigirClaveLibreEnMovimientos(clave);
            if (documento != null && operaciones.existeDocumento(compra.getId(), linea.detalle().getId(), documento)) {
                throw NegocioException.duplicado("La guía " + documento + " ya se registró para esta línea de la compra "
                        + compra.getCodigo() + ".");
            }
            Cantidades.validarNoNegativa(aceptada, linea.unidad(), "cantidad aceptada");
            Cantidades.validarNoNegativa(rechazada, linea.unidad(), "cantidad rechazada");
            exigirNoVencida(origen, aceptada);
            exigirNoPagada(compra);
            // Los lotes que la recepción modifica se bloquean antes que la evaluación de la alerta de stock (R037, R070).
            if (aceptada.signum() > 0) {
                inventario.bloquearLotesDeRecepcion(linea.productoId(), linea.detalle().getId(), !espera.vacia());
            }
            // La alerta de stock (R070) compara el estado previo a la recepción con el final, después de abastecer.
            return inventario.stock().vigilando(List.of(linea.productoId()),
                    () -> registrarRecepcion(solicitante, s, compra, linea, espera, clave, motivo, documento));
        });
    }

    private ResultadoLinea registrarRecepcion(Solicitante solicitante, SolicitudRecepcion s, CompraFeder compra,
                                              DetalleCompraVista linea, AbastecimientoPedidos.Espera espera, String clave,
                                              String motivo, String documento) {
        BigDecimal aceptada = s.aceptada() == null ? BigDecimal.ZERO : s.aceptada();
        BigDecimal rechazada = s.rechazada() == null ? BigDecimal.ZERO : s.rechazada();
        if (!detalles.registrarResolucion(linea.detalle().getId(), aceptada, rechazada, BigDecimal.ZERO)) {
            throw NegocioException.regla("Lo aceptado más lo rechazado (" + Cantidades.formatear(aceptada.add(rechazada))
                    + ") supera lo pendiente de la línea (" + Cantidades.formatear(linea.pendiente()) + ").");
        }
        String codigoLoteFeder = null;
        List<PedidoAbastecido> abastecidos = List.of();
        if (aceptada.signum() > 0) {
            codigoLoteFeder = inventario.registrarEntradaCompra(linea.detalle().getId(), aceptada,
                    Textos.opcional(s.ubicacion()), solicitante.usuarioId(), clave).codigo();
            abastecidos = inventario.abastecerConEntrada(espera, aceptada, solicitante.usuarioId(), compra.getCodigo());
        }
        DetalleJson detalle = DetalleJson.nuevo().con("clave_operacion", clave).con("operacion", RECEPCION)
                .con("compra_id", compra.getId()).con("cantidad_aceptada", aceptada)
                .con("cantidad_rechazada", rechazada).con("motivo", motivo == null ? "Recepción conforme" : motivo);
        if (documento != null) {
            detalle.con("documento_entrega", documento);
        }
        ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, linea.detalle().getId(), detalle);
        EstadoCompra estado = recalculo.recalcular(compra.getId());
        return new ResultadoLinea(compra.getCodigo(), detalles.bloquear(linea.detalle().getId()).orElseThrow(),
                codigoLoteFeder, estado, aceptada, rechazada, BigDecimal.ZERO, BigDecimal.ZERO, false, abastecidos);
    }

    /**
     * R014 (2): cancela formalmente cantidad pendiente de una línea, con motivo; libera la oferta del lote.
     * @param cantidad nulo para cancelar todo lo pendiente.
     */
    public ResultadoLinea cancelarPendiente(Solicitante solicitante, String codigoCompra, String codigoLoteOrigen,
                                           BigDecimal cantidad, String motivo, String claveOperacion) {
        Permisos.exigirPersonal(solicitante, "cancelar cantidades pendientes de compras");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        String clave = Textos.obligatorio(claveOperacion, "clave de operación");
        return ctx.transacciones().enTransaccion(() -> {
            CompraFeder compra = buscarCompra(codigoCompra);
            DetalleCompraVista vista = buscarLinea(compra, codigoLoteOrigen);
            LoteOrigen origen = lotesOrigen.bloquear(vista.detalle().getLoteOrigenId()).orElseThrow();
            DetalleCompraVista linea = detalles.bloquear(vista.detalle().getId()).orElseThrow();
            Optional<ResultadoLinea> previa = operacionPrevia(clave, compra, linea, CANCELACION);
            if (previa.isPresent()) {
                return previa.get();
            }
            exigirNoPagada(compra);
            BigDecimal pendiente = linea.pendiente();
            if (pendiente.signum() == 0) {
                throw NegocioException.estadoInvalido("La línea del lote " + linea.codigoLoteOrigen()
                        + " no tiene cantidad pendiente: lo aceptado nunca se cancela.");
            }
            BigDecimal aCancelar = cantidad == null ? pendiente : Cantidades.validarPositiva(cantidad, linea.unidad(), "cantidad a cancelar");
            if (!detalles.registrarResolucion(linea.detalle().getId(), BigDecimal.ZERO, BigDecimal.ZERO, aCancelar)) {
                throw NegocioException.regla("Solo se cancela cantidad pendiente: la línea tiene " + Cantidades.formatear(pendiente)
                        + " pendiente(s) y se pidieron cancelar " + Cantidades.formatear(aCancelar) + ".");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.ANULAR, ENTIDAD, linea.detalle().getId(),
                    DetalleJson.nuevo().con("clave_operacion", clave).con("operacion", CANCELACION)
                            .con("compra_id", compra.getId()).con("cantidad_cancelada", aCancelar).con("motivo", motivoLimpio));
            lotesOrigen.sincronizarEstadoOferta(origen.id());
            EstadoCompra estado = recalculo.recalcular(compra.getId());
            return new ResultadoLinea(compra.getCodigo(), detalles.bloquear(linea.detalle().getId()).orElseThrow(),
                    codigoLoteFeder(linea), estado, BigDecimal.ZERO, BigDecimal.ZERO, aCancelar, BigDecimal.ZERO, false, List.of());
        });
    }

    /**
     * R009 «Corrección de recepción»: anula una aceptación registrada de más con ANULACION_RECEPCION (no con
     * un ajuste), hasta lo aceptado neto y el stock libre del lote. Lo anulado vuelve a quedar pendiente o,
     * si la mercancía llegó pero no debía aceptarse, se registra como rechazada. No procede tras el pago.
     */
    public ResultadoLinea anularRecepcion(Solicitante solicitante, String codigoCompra, String codigoLoteOrigen,
                                          BigDecimal cantidad, boolean reclasificarComoRechazo, String motivo,
                                          String claveOperacion) {
        Permisos.exigirPersonal(solicitante, "corregir recepciones de compras");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        String clave = Textos.obligatorio(claveOperacion, "clave de operación");
        return ctx.transacciones().enTransaccion(() -> {
            CompraFeder compra = buscarCompra(codigoCompra);
            DetalleCompraVista vista = buscarLinea(compra, codigoLoteOrigen);
            lotesOrigen.bloquearCompartido(vista.detalle().getLoteOrigenId()).orElseThrow();
            DetalleCompraVista linea = detalles.bloquear(vista.detalle().getId()).orElseThrow();
            Optional<ResultadoLinea> previa = operacionPrevia(clave, compra, linea, ANULACION);
            if (previa.isPresent()) {
                return previa.get();
            }
            exigirClaveLibreEnMovimientos(clave);
            exigirNoPagada(compra);
            Cantidades.validarPositiva(cantidad, linea.unidad(), "cantidad anulada");
            if (cantidad.compareTo(linea.detalle().getCantidadRecibida()) > 0) {
                throw NegocioException.regla("La anulación (" + Cantidades.formatear(cantidad) + ") supera lo aceptado neto de la línea ("
                        + Cantidades.formatear(linea.detalle().getCantidadRecibida()) + ").");
            }
            LoteFederVista lote = inventario.registrarAnulacionRecepcion(linea.detalle().getId(), cantidad,
                    solicitante.usuarioId(), clave, motivoLimpio);
            if (!detalles.anularAceptacion(linea.detalle().getId(), cantidad, reclasificarComoRechazo)) {
                throw NegocioException.estadoInvalido("La cantidad aceptada de la línea cambió durante la corrección.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, linea.detalle().getId(),
                    DetalleJson.nuevo().con("clave_operacion", clave).con("operacion", ANULACION)
                            .con("compra_id", compra.getId()).con("cantidad_anulada", cantidad)
                            .con("cantidad_rechazada", reclasificarComoRechazo ? cantidad : BigDecimal.ZERO)
                            .con("lote_feder_id", lote.id()).con("motivo", motivoLimpio));
            EstadoCompra estado = recalculo.recalcular(compra.getId());
            return new ResultadoLinea(compra.getCodigo(), detalles.bloquear(linea.detalle().getId()).orElseThrow(), lote.codigo(),
                    estado, BigDecimal.ZERO, reclasificarComoRechazo ? cantidad : BigDecimal.ZERO, BigDecimal.ZERO, cantidad, false,
                    List.of());
        });
    }

    /**
     * R013: si la clave ya se confirmó para esta misma operación de la misma línea, se devuelve lo registrado
     * sin aplicar nada; si se usó para otra operación, se rechaza.
     */
    private Optional<ResultadoLinea> operacionPrevia(String clave, CompraFeder compra, DetalleCompraVista linea, String tipo) {
        Optional<OperacionRegistrada> registrada = operaciones.buscarPorClave(clave);
        if (registrada.isEmpty()) {
            return Optional.empty();
        }
        OperacionRegistrada op = registrada.get();
        if (op.detalleCompraId() != linea.detalle().getId() || !tipo.equals(op.operacion())) {
            throw NegocioException.duplicado("La clave de operación " + clave + " ya se usó en otra operación de compra: use una clave nueva.");
        }
        return Optional.of(new ResultadoLinea(compra.getCodigo(), linea, codigoLoteFeder(linea),
                compras.buscarPorCodigo(compra.getCodigo()).orElseThrow().getEstado(),
                op.aceptada(), op.rechazada(), op.cancelada(), op.anulada(), true, List.of()));
    }

    private String codigoLoteFeder(DetalleCompraVista linea) {
        return lotesFeder.buscarPorDetalle(linea.detalle().getId()).map(LoteFederVista::codigo).orElse(null);
    }

    private void exigirClaveLibreEnMovimientos(String clave) {
        if (movimientos.buscarPorClave(clave).isPresent()) {
            throw NegocioException.duplicado("La clave de operación " + clave + " ya se usó en un movimiento de inventario: use una clave nueva.");
        }
    }

    /** R035: no se acepta como DISPONIBLE mercancía ya vencida; se registra como rechazada. */
    private void exigirNoVencida(LoteOrigen origen, BigDecimal aceptada) {
        if (aceptada.signum() > 0 && origen.fechaVencimiento() != null
                && origen.fechaVencimiento().isBefore(FechasBolivia.hoy(ctx.reloj()))) {
            throw NegocioException.regla("El lote de origen " + origen.codigo() + " venció el "
                    + origen.fechaVencimiento() + ": esa mercancía no se acepta; regístrela como rechazada.");
        }
    }

    /** R006: después del pago al productor las cantidades de la compra no cambian. */
    private static void exigirNoPagada(CompraFeder compra) {
        if (compra.getFechaPagoProductor() != null) {
            throw NegocioException.estadoInvalido("La compra " + compra.getCodigo() + " ya fue pagada al productor: sus "
                    + "cantidades no cambian. Una diferencia posterior se trata con un ajuste o una baja de inventario.");
        }
    }

    private CompraFeder buscarCompra(String codigo) {
        return compras.buscarPorCodigo(codigo).orElseThrow(() -> ServicioCompras.compraNoEncontrada(codigo));
    }

    private DetalleCompraVista buscarLinea(CompraFeder compra, String codigoLoteOrigen) {
        return detalles.buscarPorCompraYLote(compra.getId(), codigoLoteOrigen)
                .orElseThrow(() -> NegocioException.noEncontrado("La compra " + compra.getCodigo()
                        + " no tiene una línea del lote de origen " + codigoLoteOrigen + "."));
    }
}
