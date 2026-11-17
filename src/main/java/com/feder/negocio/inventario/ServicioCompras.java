package com.feder.negocio.inventario;

import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.DetalleCompraVista;
import com.feder.datos.compras.LoteOrigenRepositorio;
import com.feder.datos.compras.LoteOrigenRepositorio.LoteOrigen;
import com.feder.datos.compras.OperacionCompraRepositorio;
import com.feder.datos.compras.ProductorCompraRepositorio;
import com.feder.datos.compras.ProductorCompraRepositorio.Proveedor;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.CompraFeder;
import com.feder.modelo.DetalleCompra;
import com.feder.modelo.EstadoCompra;
import com.feder.modelo.EstadoLoteProduccion;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.math.BigDecimal;
import java.util.List;

/**
 * Compras de FEDER a productores (CU4): alta con su primera línea, nuevas líneas, pago al productor
 * y consultas. Las recepciones y cancelaciones de línea están en {@link ServicioRecepcion}.
 */
public class ServicioCompras {

    private static final String ENTIDAD = "COMPRA_FEDER";

    private final ContextoNegocio ctx;
    private final CompraRepositorio compras;
    private final DetalleCompraRepositorio detalles;
    private final LoteOrigenRepositorio lotesOrigen;
    private final ProductorCompraRepositorio productores;
    private final OperacionCompraRepositorio operaciones;
    private final LoteFederRepositorio lotesFeder;

    public ServicioCompras(ContextoNegocio ctx, CompraRepositorio compras, DetalleCompraRepositorio detalles,
                           LoteOrigenRepositorio lotesOrigen, ProductorCompraRepositorio productores,
                           OperacionCompraRepositorio operaciones, LoteFederRepositorio lotesFeder) {
        this.ctx = ctx;
        this.compras = compras;
        this.detalles = detalles;
        this.lotesOrigen = lotesOrigen;
        this.productores = productores;
        this.operaciones = operaciones;
        this.lotesFeder = lotesFeder;
    }

    /**
     * R004: registra la compra REGISTRADA con su primera línea (una compra sin detalles no existe, A12).
     * @param precio nulo para usar el precio ofrecido por el productor en el lote.
     */
    public CompraDetallada registrarCompra(Solicitante solicitante, String codigoProductor, String codigoLote,
                                           BigDecimal cantidad, BigDecimal precio, String observacion) {
        Permisos.exigirPersonal(solicitante, "registrar compras a productores");
        return ctx.transacciones().enTransaccion(() -> {
            Proveedor productor = productores.buscarPorCodigo(codigoProductor)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe el productor " + codigoProductor + "."));
            exigirHabilitado(productor);
            LoteOrigen lote = bloquearLote(codigoLote);
            DetalleCompra linea = nuevaLinea(lote, productor.id(), cantidad, precio);
            BigDecimal total = Importes.exigirMontoAdmitido(
                    Importes.subtotal(linea.getCantidadComprada(), linea.getPrecioUnitario()), "total de la compra");

            CompraFeder compra = new CompraFeder();
            compra.setId(compras.siguienteId());
            compra.setCodigo(String.format("CMP-%d-%03d", FechasBolivia.hoy(ctx.reloj()).getYear(), compra.getId()));
            compra.setProductorId(productor.id());
            compra.setFechaCompra(ctx.ahora());
            compra.setEstado(EstadoCompra.REGISTRADA);
            compra.setTotal(total);
            compra.setObservacion(Textos.opcional(observacion));
            compras.insertar(compra);

            linea.setCompraId(compra.getId());
            linea.setId(detalles.insertar(linea));
            lotesOrigen.sincronizarEstadoOferta(lote.id());
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, ENTIDAD, compra.getId(),
                    DetalleJson.nuevo().con("codigo", compra.getCodigo()).con("productor_id", productor.id())
                            .con("total", total).con("detalle_compra_id", linea.getId()).con("lote_origen", lote.codigo())
                            .con("cantidad_comprada", linea.getCantidadComprada())
                            .con("precio_unitario", linea.getPrecioUnitario()));
            return detallada(compra, productor);
        });
    }

    /** Agrega una línea mientras la compra sigue REGISTRADA; el total acordado suma la nueva línea. */
    public CompraDetallada agregarLinea(Solicitante solicitante, String codigoCompra, String codigoLote,
                                        BigDecimal cantidad, BigDecimal precio) {
        Permisos.exigirPersonal(solicitante, "modificar compras a productores");
        return ctx.transacciones().enTransaccion(() -> {
            // Orden de bloqueo: lote de origen antes que la compra, igual que en la recepción.
            LoteOrigen lote = bloquearLote(codigoLote);
            CompraFeder compra = compras.bloquearPorCodigo(codigoCompra).orElseThrow(() -> compraNoEncontrada(codigoCompra));
            if (compra.getEstado() != EstadoCompra.REGISTRADA) {
                throw NegocioException.estadoInvalido("Solo se agregan líneas a una compra REGISTRADA; la compra "
                        + compra.getCodigo() + " está " + compra.getEstado() + ".");
            }
            // R004: cada línea nueva exige que el productor siga habilitado, no solo el alta de la compra.
            Proveedor productor = productores.buscarPorId(compra.getProductorId()).orElseThrow();
            exigirHabilitado(productor);
            if (detalles.buscarPorCompraYLote(compra.getId(), lote.codigo()).isPresent()) {
                throw NegocioException.duplicado("La compra " + compra.getCodigo() + " ya tiene una línea del lote "
                        + lote.codigo() + " (un lote por compra).");
            }
            DetalleCompra linea = nuevaLinea(lote, compra.getProductorId(), cantidad, precio);
            BigDecimal totalNuevo = Importes.exigirMontoAdmitido(compra.getTotal().add(
                    Importes.subtotal(linea.getCantidadComprada(), linea.getPrecioUnitario())), "total de la compra");
            linea.setCompraId(compra.getId());
            linea.setId(detalles.insertar(linea));
            compras.actualizarTotal(compra.getId(), totalNuevo);
            lotesOrigen.sincronizarEstadoOferta(lote.id());
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, compra.getId(),
                    DetalleJson.nuevo().con("operacion", "AGREGAR_DETALLE").con("detalle_compra_id", linea.getId())
                            .con("lote_origen", lote.codigo()).con("cantidad_comprada", linea.getCantidadComprada())
                            .con("precio_unitario", linea.getPrecioUnitario())
                            .con("total_anterior", compra.getTotal()).con("total_nuevo", totalNuevo));
            compra.setTotal(totalNuevo);
            return detallada(compra, productor);
        });
    }

    /**
     * R006: registra el pago al productor de una compra RECIBIDA. Se paga lo aceptado; después las
     * cantidades de la compra ya no cambian (la base lo impide).
     */
    public CompraDetallada registrarPago(Solicitante solicitante, String codigoCompra) {
        Permisos.exigirPersonal(solicitante, "registrar pagos a productores");
        return ctx.transacciones().enTransaccion(() -> {
            CompraFeder compra = compras.bloquearPorCodigo(codigoCompra).orElseThrow(() -> compraNoEncontrada(codigoCompra));
            if (compra.getFechaPagoProductor() != null) {
                throw NegocioException.estadoInvalido("La compra " + compra.getCodigo() + " ya fue pagada el "
                        + FechasBolivia.formatear(compra.getFechaPagoProductor()) + ".");
            }
            if (compra.getEstado() != EstadoCompra.RECIBIDA) {
                throw NegocioException.estadoInvalido("Solo se paga una compra RECIBIDA (sin cantidades pendientes); la compra "
                        + compra.getCodigo() + " está " + compra.getEstado() + ".");
            }
            CompraDetallada detallada = detallada(compra, productores.buscarPorId(compra.getProductorId()).orElseThrow());
            BigDecimal importe = detallada.importeAPagar();
            compra.setFechaPagoProductor(ctx.ahora());
            if (!compras.registrarPago(compra.getId(), compra.getFechaPagoProductor())) {
                throw NegocioException.estadoInvalido("La compra " + compra.getCodigo() + " ya no admite el pago.");
            }
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, compra.getId(),
                    DetalleJson.nuevo().con("operacion", "PAGO_PRODUCTOR").con("importe_pagado", importe)
                            .con("productor_id", compra.getProductorId()));
            return detallada;
        });
    }

    /**
     * R012: corrige el precio pactado de una línea que nunca tuvo mercancía aceptada (sin LOTE_FEDER ni
     * recepción con cantidad aceptada, aunque se haya anulado) y cuya compra no se pagó. Bloquea el
     * detalle y después la compra, deja constancia CORRECCION_PRECIO y recalcula el total acordado.
     */
    public CompraDetallada corregirPrecio(Solicitante solicitante, String codigoCompra, String codigoLote,
                                          BigDecimal precioNuevo, String motivo) {
        Permisos.exigirPersonal(solicitante, "corregir precios de compra");
        BigDecimal precio = Importes.validarPrecio(precioNuevo, true, "precio unitario");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        return ctx.transacciones().enTransaccion(() -> {
            CompraFeder sinBloqueo = compras.buscarPorCodigo(codigoCompra).orElseThrow(() -> compraNoEncontrada(codigoCompra));
            DetalleCompraVista vista = detalles.buscarPorCompraYLote(sinBloqueo.getId(), codigoLote)
                    .orElseThrow(() -> NegocioException.noEncontrado("La compra " + codigoCompra + " no tiene una línea del lote " + codigoLote + "."));
            DetalleCompra linea = detalles.bloquear(vista.detalle().getId()).orElseThrow().detalle();
            CompraFeder compra = compras.bloquear(sinBloqueo.getId()).orElseThrow();
            if (compra.getFechaPagoProductor() != null) {
                throw NegocioException.estadoInvalido("La compra " + compra.getCodigo() + " ya fue pagada: el precio de adquisición es histórico.");
            }
            if (lotesFeder.buscarPorDetalle(linea.getId()).isPresent() || operaciones.tuvoAceptacion(linea.getId())) {
                throw NegocioException.regla("El precio de adquisición no se modifica desde la primera aceptación de mercancía de la línea.");
            }
            BigDecimal anterior = linea.getPrecioUnitario();
            if (anterior.compareTo(precio) == 0) {
                throw NegocioException.regla("El precio indicado es igual al precio pactado vigente.");
            }
            detalles.actualizarPrecio(linea.getId(), precio);
            BigDecimal total = Importes.exigirMontoAdmitido(detalles.totalAcordado(compra.getId()), "total de la compra");
            compras.actualizarTotal(compra.getId(), total);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "DETALLE_COMPRA", linea.getId(),
                    DetalleJson.nuevo().con("operacion", "CORRECCION_PRECIO").con("compra_id", compra.getId())
                            .con("precio_anterior", anterior).con("precio_nuevo", precio).con("motivo", motivoLimpio));
            compra.setTotal(total);
            return detallada(compra, productores.buscarPorId(compra.getProductorId()).orElseThrow());
        });
    }

    /** Rectificación documental de un precio de compra (R012): lo registrado, lo correcto y su diferencia. */
    public record RectificacionPrecio(String codigoCompra, String codigoLoteOrigen, BigDecimal precioRegistrado,
                                      BigDecimal precioCorrecto, BigDecimal cantidadAfectada, BigDecimal importeDiferencia) {
    }

    /**
     * R012 «RECTIFICACION_PRECIO»: una diferencia económica descubierta cuando el precio ya no puede
     * corregirse (hubo aceptación o pago) la documenta un ADMINISTRADOR vigente en BITACORA, sin cambiar
     * precio_unitario, el total de la compra ni los movimientos. La liquidación con el productor queda fuera del modelo.
     */
    public RectificacionPrecio rectificarPrecio(Solicitante solicitante, String codigoCompra, String codigoLote,
                                                BigDecimal precioCorrecto, BigDecimal cantidadAfectada, String motivo) {
        Permisos.exigirAdministrador(solicitante, "rectificar el precio de una compra");
        BigDecimal correcto = Importes.validarPrecio(precioCorrecto, true, "precio correcto");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo");
        return ctx.transacciones().enTransaccion(() -> {
            CompraFeder sinBloqueo = compras.buscarPorCodigo(codigoCompra).orElseThrow(() -> compraNoEncontrada(codigoCompra));
            DetalleCompraVista vista = detalles.buscarPorCompraYLote(sinBloqueo.getId(), codigoLote)
                    .orElseThrow(() -> NegocioException.noEncontrado("La compra " + codigoCompra + " no tiene una línea del lote " + codigoLote + "."));
            DetalleCompra linea = detalles.bloquear(vista.detalle().getId()).orElseThrow().detalle();
            CompraFeder compra = compras.bloquear(sinBloqueo.getId()).orElseThrow();
            boolean corregible = compra.getFechaPagoProductor() == null && lotesFeder.buscarPorDetalle(linea.getId()).isEmpty()
                    && !operaciones.tuvoAceptacion(linea.getId());
            if (corregible) {
                throw NegocioException.regla("La línea todavía no tuvo aceptación ni pago: corrija el precio directamente con PRECMP.");
            }
            BigDecimal registrado = linea.getPrecioUnitario();
            if (registrado.compareTo(correcto) == 0) {
                throw NegocioException.regla("El precio correcto es igual al precio registrado: no hay diferencia que rectificar.");
            }
            Cantidades.validarPositiva(cantidadAfectada, vista.unidad(), "cantidad afectada");
            if (cantidadAfectada.compareTo(linea.getCantidadRecibida()) > 0) {
                throw NegocioException.regla("La cantidad afectada (" + Cantidades.formatear(cantidadAfectada)
                        + ") supera lo aceptado de la línea (" + Cantidades.formatear(linea.getCantidadRecibida()) + ").");
            }
            BigDecimal diferencia = Importes.subtotal(cantidadAfectada, correcto.subtract(registrado));
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, "DETALLE_COMPRA", linea.getId(),
                    DetalleJson.nuevo().con("operacion", "RECTIFICACION_PRECIO").con("compra_id", compra.getId())
                            .con("precio_registrado", registrado).con("precio_correcto", correcto)
                            .con("cantidad_afectada", cantidadAfectada).con("importe_diferencia", diferencia)
                            .con("motivo", motivoLimpio));
            return new RectificacionPrecio(compra.getCodigo(), vista.codigoLoteOrigen(), registrado, correcto, cantidadAfectada, diferencia);
        });
    }

    /** El personal ve todas las compras; un PRODUCTOR sin rol de personal solo las suyas (R088). */
    public List<CompraRepositorio.CompraResumen> listar(Solicitante solicitante, EstadoCompra estado) {
        return ctx.transacciones().enTransaccion(() -> compras.listar(productorVisible(solicitante), estado));
    }

    public CompraDetallada ver(Solicitante solicitante, String codigoCompra) {
        return ctx.transacciones().enTransaccion(() -> {
            CompraFeder compra = compras.buscarPorCodigo(codigoCompra).orElseThrow(() -> compraNoEncontrada(codigoCompra));
            Long propio = productorVisible(solicitante);
            // R088: a un productor una compra ajena se le informa igual que una inexistente, para no revelar
            // qué códigos existen.
            if (propio != null && !propio.equals(compra.getProductorId())) {
                throw compraNoEncontrada(codigoCompra);
            }
            return detallada(compra, productores.buscarPorId(compra.getProductorId()).orElseThrow());
        });
    }

    /** Null si el solicitante ve todas las compras; el id de su perfil si solo ve las propias. */
    private Long productorVisible(Solicitante solicitante) {
        if (solicitante.esPersonalInterno()) {
            return null;
        }
        if (!solicitante.tieneRol(TipoRol.PRODUCTOR)) {
            throw NegocioException.sinPermiso("Solo el personal de FEDER y los productores consultan compras.");
        }
        return productores.buscarPorUsuario(solicitante.usuarioId()).map(Proveedor::id)
                .orElseThrow(() -> NegocioException.sinPermiso("Su cuenta no tiene un perfil de productor."));
    }

    private static void exigirHabilitado(Proveedor productor) {
        if (!productor.habilitado()) {
            throw NegocioException.regla("El productor " + productor.codigo()
                    + " no está habilitado para vender a FEDER (solicitud aprobada, perfil y cuenta activos).");
        }
    }

    /** R039 y R004: valida la línea con la fila del lote de origen ya bloqueada. */
    private DetalleCompra nuevaLinea(LoteOrigen lote, long productorId, BigDecimal cantidad, BigDecimal precio) {
        if (lote.productorId() != productorId) {
            throw NegocioException.regla("El lote " + lote.codigo() + " pertenece a otro productor: todas las líneas de una "
                    + "compra son de producciones de su propio productor.");
        }
        if (lote.estado() == EstadoLoteProduccion.RETIRADO) {
            throw NegocioException.regla("El lote " + lote.codigo() + " fue retirado de la oferta por su productor.");
        }
        if (!lote.productoActivo()) {
            throw NegocioException.regla("El producto " + lote.codigoProducto() + " del lote " + lote.codigo() + " está inactivo.");
        }
        Cantidades.validarPositiva(cantidad, lote.unidad(), "cantidad comprada");
        BigDecimal libre = lote.cantidadOfrecida().subtract(detalles.comprometidoDeLote(lote.id()));
        if (cantidad.compareTo(libre) > 0) {
            throw NegocioException.regla("La cantidad " + Cantidades.formatear(cantidad) + " supera lo que el lote "
                    + lote.codigo() + " todavía ofrece a FEDER (" + Cantidades.formatear(libre.max(BigDecimal.ZERO)) + " "
                    + lote.unidad() + ").");
        }
        BigDecimal precioPactado = precio != null ? precio : lote.precioOfrecido();
        if (precioPactado == null) {
            throw NegocioException.regla("El lote " + lote.codigo() + " no tiene precio ofrecido: indique el precio unitario pactado.");
        }
        DetalleCompra linea = new DetalleCompra();
        linea.setLoteOrigenId(lote.id());
        linea.setCantidadComprada(cantidad);
        linea.setPrecioUnitario(Importes.validarPrecio(precioPactado, true, "precio unitario"));
        return linea;
    }

    private LoteOrigen bloquearLote(String codigoLote) {
        return lotesOrigen.bloquearPorCodigo(codigoLote)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el lote de origen " + codigoLote + "."));
    }

    private CompraDetallada detallada(CompraFeder compra, Proveedor productor) {
        return new CompraDetallada(compra, productor, detalles.deCompra(compra.getId()));
    }

    static NegocioException compraNoEncontrada(String codigo) {
        return NegocioException.noEncontrado("No existe la compra " + codigo + ". Consulte LISCMP[\"*\"].");
    }
}
