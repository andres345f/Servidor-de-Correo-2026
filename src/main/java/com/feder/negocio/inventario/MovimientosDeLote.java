package com.feder.negocio.inventario;

import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoLoteFeder;
import com.feder.modelo.EstadoReserva;
import com.feder.modelo.LoteFeder;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.ReservaStock;
import com.feder.modelo.TipoMovimiento;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.ServicioStock;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Entradas y salidas físicas de los lotes de FEDER. Cada movimiento actualiza el saldo del lote en la
 * misma transacción (R038) y lleva una clave de operación única: repetir la operación con la misma
 * clave devuelve el movimiento ya registrado en lugar de duplicarlo.
 */
final class MovimientosDeLote {

    private static final Set<TipoMovimiento> AJUSTES = EnumSet.of(TipoMovimiento.AJUSTE_POSITIVO,
            TipoMovimiento.AJUSTE_NEGATIVO, TipoMovimiento.BAJA_VENCIDO, TipoMovimiento.BAJA_DANADO,
            TipoMovimiento.BAJA_DEFECTUOSO);
    /** movimiento_inventario.clave_operacion admite 64 caracteres; la reposición agrega "-n" a la clave. */
    static final int LARGO_MAXIMO_CLAVE_REPOSICION = 60;

    private final ContextoNegocio ctx;
    private final ServicioStock stock;
    private final LoteFederRepositorio lotes;
    private final MovimientoRepositorio movimientos;
    private final ReservaRepositorio reservas;
    private final BloqueoLotes bloqueo;

    MovimientosDeLote(ContextoNegocio ctx, ServicioStock stock, LoteFederRepositorio lotes,
                      MovimientoRepositorio movimientos, ReservaRepositorio reservas) {
        this.ctx = ctx;
        this.stock = stock;
        this.lotes = lotes;
        this.movimientos = movimientos;
        this.reservas = reservas;
        this.bloqueo = new BloqueoLotes(lotes);
    }

    /**
     * R014 y R036: lo aceptado entra al inventario. El LOTE_FEDER se crea con la primera aceptación y las
     * siguientes suman al mismo lote. El llamador ya tiene bloqueada la fila del DETALLE_COMPRA, así dos
     * recepciones de la misma línea nunca intentan crear el lote a la vez.
     */
    LoteFederVista entradaCompra(long detalleCompraId, BigDecimal cantidad, String ubicacion, long usuarioId, String clave) {
        Optional<LoteFederVista> existente = lotes.buscarPorDetalle(detalleCompraId);
        long loteId;
        if (existente.isPresent()) {
            loteId = bloqueo.bloquearUno(existente.get()).id();
            lotes.sumarEntrada(loteId, cantidad);
            if (ubicacion != null) {
                lotes.actualizarUbicacion(loteId, ubicacion);
            }
        } else {
            loteId = crearLote(detalleCompraId, cantidad, ubicacion);
        }
        insertar(loteId, usuarioId, null, null, TipoMovimiento.ENTRADA_COMPRA, cantidad, null, clave);
        return lotes.buscarPorId(loteId).orElseThrow();
    }

    /**
     * R009: ANULACION_RECEPCION de una aceptación registrada de más. Solo sale stock libre del lote
     * (las unidades reservadas no se anulan) y el movimiento original se conserva.
     */
    LoteFederVista anulacionRecepcion(long detalleCompraId, BigDecimal cantidad, long usuarioId, String clave, String motivo) {
        LoteFederVista lote = bloqueo.bloquearUno(lotes.buscarPorDetalle(detalleCompraId)
                .orElseThrow(() -> NegocioException.estadoInvalido("La línea no tiene lote de FEDER: nunca tuvo mercancía aceptada.")));
        Cantidades.validarPositiva(cantidad, lote.unidad(), "cantidad anulada");
        return stock.vigilando(List.of(lote.productoId()), () -> {
            if (!lotes.restarLibre(lote.id(), cantidad)) {
                throw NegocioException.regla("Solo se anula stock libre del lote " + lote.codigo() + " (libre: "
                        + Cantidades.formatear(lote.libre()) + "). Las unidades reservadas o vendidas conservan su origen.");
            }
            insertar(lote.id(), usuarioId, null, null, TipoMovimiento.ANULACION_RECEPCION, cantidad, motivo, clave);
            return lotes.buscarPorId(lote.id()).orElseThrow();
        });
    }

    private long crearLote(long detalleCompraId, BigDecimal cantidad, String ubicacion) {
        LoteFeder lote = new LoteFeder();
        lote.setId(lotes.siguienteId());
        lote.setCodigo(String.format("LF-%d-%03d", FechasBolivia.hoy(ctx.reloj()).getYear(), lote.getId()));
        lote.setDetalleCompraId(detalleCompraId);
        lote.setFechaIngreso(ctx.ahora());
        lote.setCantidadDisponible(cantidad);
        lote.setUbicacionAlmacen(ubicacion);
        lote.setEstado(EstadoLoteFeder.DISPONIBLE);
        lotes.insertar(lote);
        return lote.getId();
    }

    /** R029 y R044: retorno físico de una entrega; la suma de retornos no supera la salida de la reserva. */
    MovimientoInventario retornoEntrega(long reservaId, BigDecimal cantidad, long usuarioId, String clave, String motivo) {
        ReservaStock sinBloqueo = reservas.buscar(reservaId)
                .orElseThrow(() -> NegocioException.noEncontrado("La reserva " + reservaId + " no existe."));
        // Orden global: el lote antes que la reserva.
        LoteFederVista lote = bloqueo.bloquearUno(lotes.buscarPorId(sinBloqueo.getLoteFederId()).orElseThrow());
        Optional<MovimientoInventario> previo = previo(clave, TipoMovimiento.ENTRADA_RETORNO_ENTREGA, cantidad, lote.id(), reservaId, null);
        if (previo.isPresent()) {
            return previo.get();
        }
        ReservaStock reserva = reservas.bloquear(reservaId).orElseThrow();
        if (reserva.getEstado() != EstadoReserva.CONSUMIDA) {
            throw NegocioException.estadoInvalido("Solo se registra un retorno de una reserva CONSUMIDA (despachada).");
        }
        Cantidades.validarPositiva(cantidad, lote.unidad(), "cantidad retornada");
        BigDecimal salida = movimientos.salidaVentaDeReserva(reservaId).map(MovimientoInventario::getCantidad)
                .orElseThrow(() -> NegocioException.estadoInvalido("La reserva no tiene su salida de venta registrada."));
        BigDecimal retornado = movimientos.sumaDeReserva(reservaId, TipoMovimiento.ENTRADA_RETORNO_ENTREGA);
        if (retornado.add(cantidad).compareTo(salida) > 0) {
            throw NegocioException.regla("El retorno supera lo despachado: salieron " + Cantidades.formatear(salida)
                    + " y ya retornaron " + Cantidades.formatear(retornado) + ".");
        }
        lotes.sumarEntrada(lote.id(), cantidad);
        return insertar(lote.id(), usuarioId, reservaId, null, TipoMovimiento.ENTRADA_RETORNO_ENTREGA, cantidad,
                Textos.opcional(motivo), clave);
    }

    /**
     * R029 y R044: baja de unidades retornadas no aptas, vinculada a su reserva y en el lote de su salida.
     * La suma de las bajas de una reserva nunca supera lo retornado, comprobado con la reserva bloqueada.
     */
    MovimientoInventario bajaDeRetorno(long reservaId, TipoMovimiento tipo, BigDecimal cantidad, long usuarioId, String clave,
                                       String motivo) {
        if (tipo != TipoMovimiento.BAJA_DANADO && tipo != TipoMovimiento.BAJA_DEFECTUOSO && tipo != TipoMovimiento.BAJA_VENCIDO) {
            throw new IllegalArgumentException("Una baja de retorno es BAJA_DANADO, BAJA_DEFECTUOSO o BAJA_VENCIDO");
        }
        ReservaStock sinBloqueo = reservas.buscar(reservaId)
                .orElseThrow(() -> NegocioException.noEncontrado("La reserva " + reservaId + " no existe."));
        LoteFederVista lote = bloqueo.bloquearUno(lotes.buscarPorId(sinBloqueo.getLoteFederId()).orElseThrow());
        Optional<MovimientoInventario> previo = previo(clave, tipo, cantidad, lote.id(), reservaId, null);
        if (previo.isPresent()) {
            return previo.get();
        }
        reservas.bloquear(reservaId).orElseThrow();
        if (tipo == TipoMovimiento.BAJA_VENCIDO && !lote.vencido(stock.hoy())) {
            throw NegocioException.regla("BAJA_VENCIDO exige que el lote de origen haya vencido; el lote " + lote.codigo()
                    + " no está vencido.");
        }
        BigDecimal retornado = movimientos.sumaDeReserva(reservaId, TipoMovimiento.ENTRADA_RETORNO_ENTREGA);
        BigDecimal dadoDeBaja = movimientos.sumaDeReserva(reservaId, TipoMovimiento.BAJA_DANADO)
                .add(movimientos.sumaDeReserva(reservaId, TipoMovimiento.BAJA_DEFECTUOSO))
                .add(movimientos.sumaDeReserva(reservaId, TipoMovimiento.BAJA_VENCIDO));
        if (dadoDeBaja.add(cantidad).compareTo(retornado) > 0) {
            throw NegocioException.regla("Las bajas de la reserva superarían lo retornado (" + Cantidades.formatear(retornado) + ").");
        }
        if (!lotes.restarLibre(lote.id(), cantidad)) {
            throw NegocioException.regla("El lote " + lote.codigo() + " no tiene stock libre para dar de baja lo retornado.");
        }
        return insertar(lote.id(), usuarioId, reservaId, null, tipo, cantidad, Textos.obligatorio(motivo, "motivo de la baja"), clave);
    }

    /**
     * R025 (c): AJUSTE_NEGATIVO que compensa un reingreso de una devolución que se anula, en su mismo lote
     * y por la misma cantidad (compensación administrativa, no merma). Solo sale stock libre del lote.
     */
    MovimientoInventario compensacionDeDevolucion(long devolucionId, long loteFederId, BigDecimal cantidad, long usuarioId,
                                                  String clave, String motivo) {
        LoteFederVista lote = bloqueo.bloquearUno(lotes.buscarPorId(loteFederId)
                .orElseThrow(() -> NegocioException.noEncontrado("El lote de FEDER " + loteFederId + " no existe.")));
        Optional<MovimientoInventario> previo = previo(clave, TipoMovimiento.AJUSTE_NEGATIVO, cantidad, lote.id(), null, devolucionId);
        if (previo.isPresent()) {
            return previo.get();
        }
        return stock.vigilando(List.of(lote.productoId()), () -> {
            if (!lotes.restarLibre(lote.id(), cantidad)) {
                throw NegocioException.regla("Las unidades reingresadas en el lote " + lote.codigo() + " ya no están libres "
                        + "(se reservaron o vendieron): la devolución no puede anularse y su decisión queda en firme.");
            }
            return insertar(lote.id(), usuarioId, null, devolucionId, TipoMovimiento.AJUSTE_NEGATIVO, cantidad, motivo, clave);
        });
    }

    /**
     * Reingreso de unidades aptas de una devolución en el lote del despacho devuelto. Las condiciones
     * sobre la devolución (estado APROBADA o COMPLETADA y tope min(apta, aprobada), R024 y R044) las
     * comprueba el llamador de CU6 con la fila de la DEVOLUCION bloqueada.
     */
    MovimientoInventario entradaDevolucion(long devolucionId, long loteFederId, BigDecimal cantidad, long usuarioId, String clave) {
        LoteFederVista lote = bloqueo.bloquearUno(lotes.buscarPorId(loteFederId)
                .orElseThrow(() -> NegocioException.noEncontrado("El lote de FEDER " + loteFederId + " no existe.")));
        Optional<MovimientoInventario> previo = previo(clave, TipoMovimiento.ENTRADA_DEVOLUCION, cantidad, lote.id(), null, devolucionId);
        if (previo.isPresent()) {
            return previo.get();
        }
        Cantidades.validarPositiva(cantidad, lote.unidad(), "cantidad reingresada");
        lotes.sumarEntrada(lote.id(), cantidad);
        return insertar(lote.id(), usuarioId, null, devolucionId, TipoMovimiento.ENTRADA_DEVOLUCION, cantidad, null, clave);
    }

    /**
     * Salida de reposición de una devolución con resolución REPOSICION, repartida con FEFO entre lotes
     * vendibles con stock libre (R026, R043). Si hace falta más de un lote, cada movimiento usa la clave
     * indicada seguida de -1, -2, ...
     */
    List<MovimientoInventario> salidaReposicion(long devolucionId, long productoId, BigDecimal cantidad, long usuarioId, String clave) {
        if (cantidad == null || cantidad.signum() <= 0) {
            throw NegocioException.regla("La cantidad repuesta debe ser mayor que 0.");
        }
        // Cada movimiento usa la clave más "-n": la base debe dejar sitio al sufijo en los 64 caracteres.
        if (clave == null || clave.isBlank() || clave.length() > LARGO_MAXIMO_CLAVE_REPOSICION) {
            throw NegocioException.regla("La clave de una reposición es obligatoria y tiene como máximo "
                    + LARGO_MAXIMO_CLAVE_REPOSICION + " caracteres.");
        }
        List<MovimientoInventario> previos = previosConSufijo(clave);
        if (!previos.isEmpty()) {
            return exigirMismaReposicion(previos, devolucionId, cantidad, clave);
        }
        LocalDate hoy = stock.hoy();
        Map<Long, LoteFederVista> bloqueados = bloqueo.bloquear(lotes.candidatosVendibles(productoId, hoy));
        return stock.vigilando(List.of(productoId), () -> {
            List<LoteFederVista> vendibles = bloqueados.values().stream().filter(l -> l.vendible(hoy)).toList();
            Map<Long, BigDecimal> libres = new HashMap<>();
            vendibles.forEach(l -> libres.put(l.id(), l.libre()));
            if (!vendibles.isEmpty()) {
                Cantidades.validarPositiva(cantidad, vendibles.get(0).unidad(), "cantidad repuesta");
            }
            List<AsignadorFefo.Asignacion> asignaciones = AsignadorFefo.asignar(vendibles, libres, cantidad);
            if (AsignadorFefo.total(asignaciones).compareTo(cantidad) < 0) {
                throw new NegocioException(NegocioException.Codigo.STOCK_INSUFICIENTE,
                        "No hay stock libre suficiente para la reposición (" + Cantidades.formatear(cantidad) + ").");
            }
            List<MovimientoInventario> salidas = new ArrayList<>();
            for (int i = 0; i < asignaciones.size(); i++) {
                AsignadorFefo.Asignacion a = asignaciones.get(i);
                if (!lotes.restarLibre(a.loteFederId(), a.cantidad())) {
                    throw new NegocioException(NegocioException.Codigo.STOCK_INSUFICIENTE, "El stock libre cambió durante la reposición.");
                }
                salidas.add(insertar(a.loteFederId(), usuarioId, null, devolucionId, TipoMovimiento.SALIDA_REPOSICION,
                        a.cantidad(), null, clave + "-" + (i + 1)));
            }
            return salidas;
        });
    }

    /** Ajustes y bajas manuales (R041, R043): con motivo, por personal autorizado y registrados en BITACORA. */
    MovimientoInventario ajuste(Solicitante solicitante, SolicitudAjuste solicitud) {
        TipoMovimiento tipo = Objects.requireNonNull(solicitud.tipo());
        if (!AJUSTES.contains(tipo)) {
            throw NegocioException.regla("Tipo de ajuste no admitido: use " + AJUSTES + ".");
        }
        if (tipo == TipoMovimiento.AJUSTE_POSITIVO) {
            Permisos.exigirAdministrador(solicitante, "registrar un AJUSTE_POSITIVO");
        } else {
            Permisos.exigirPersonal(solicitante, "registrar ajustes y bajas de inventario");
        }
        String motivo = Textos.obligatorio(solicitud.motivo(), "motivo");
        String clave = Textos.obligatorio(solicitud.claveOperacion(), "clave de operación");
        String evidencia = Textos.opcional(solicitud.evidencia());
        if (tipo == TipoMovimiento.AJUSTE_POSITIVO && evidencia == null) {
            throw NegocioException.regla("Un AJUSTE_POSITIVO exige la evidencia del conteo físico: quién contó, cuándo y el acta o documento.");
        }
        LoteFederVista sinBloqueo = lotes.buscarPorCodigo(solicitud.codigoLote())
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el lote de FEDER " + solicitud.codigoLote() + "."));
        LoteFederVista lote = bloqueo.bloquearUno(sinBloqueo);
        // La clave se comprueba con el lote bloqueado: un reenvío simultáneo del mismo ajuste espera y
        // encuentra el movimiento ya confirmado.
        Optional<MovimientoInventario> previo = previo(clave, tipo, solicitud.cantidad(), lote.id(), null, null);
        if (previo.isPresent()) {
            return previo.get();
        }
        // Solo este lote queda bloqueado: un ajuste simultáneo en otro lote del mismo producto no espera esta fila. La
        // alerta de stock se evalúa serializada por producto dentro de vigilando, así el cruce del mínimo avisa una vez.
        return stock.vigilando(List.of(lote.productoId()),
                () -> aplicarAjuste(solicitante.usuarioId(), lote, tipo, solicitud.cantidad(), motivo, clave, evidencia));
    }

    private MovimientoInventario aplicarAjuste(long usuarioId, LoteFederVista lote, TipoMovimiento tipo, BigDecimal cantidad,
                                               String motivo, String clave, String evidencia) {
        Cantidades.validarPositiva(cantidad, lote.unidad(), "cantidad del ajuste");
        if (tipo == TipoMovimiento.BAJA_VENCIDO && !lote.vencido(stock.hoy())) {
            throw NegocioException.regla("BAJA_VENCIDO exige que el lote de origen haya vencido; el lote " + lote.codigo()
                    + " vence el " + FechasBolivia.formatear(lote.fechaVencimiento()) + ".");
        }
        MovimientoInventario movimiento;
        if (tipo == TipoMovimiento.AJUSTE_POSITIVO) {
            // El disparador valida el autor, la falta de pendiente en la compra y el límite de restitución.
            movimiento = insertar(lote.id(), usuarioId, null, null, tipo, cantidad, motivo, clave);
            lotes.sumarEntrada(lote.id(), cantidad);
        } else {
            if (!lotes.restarLibre(lote.id(), cantidad)) {
                throw NegocioException.regla("Solo se retira stock libre del lote " + lote.codigo() + ": disponible "
                        + Cantidades.formatear(lote.disponible()) + ", reservado " + Cantidades.formatear(lote.reservada())
                        + ". Las unidades reservadas se liberan antes con la cancelación o el bloqueo del pedido.");
            }
            movimiento = insertar(lote.id(), usuarioId, null, null, tipo, cantidad, motivo, clave);
        }
        DetalleJson detalle = DetalleJson.nuevo().con("tipo", tipo).con("lote_feder_id", lote.id())
                .con("cantidad", cantidad).con("motivo", motivo).con("clave_operacion", clave);
        if (evidencia != null) {
            detalle.con("evidencia", evidencia);
        }
        ctx.bitacora().registrar(usuarioId, AccionBitacora.CREAR, "MOVIMIENTO_INVENTARIO", movimiento.getId(), detalle);
        return movimiento;
    }

    /**
     * Si la clave ya se usó, devuelve ese movimiento solo cuando es la misma operación: mismo tipo, cantidad,
     * lote, reserva y devolución. Con cualquier diferencia la clave se rechaza: un falso «ya registrado»
     * dejaría sin aplicar una operación distinta.
     */
    private Optional<MovimientoInventario> previo(String clave, TipoMovimiento tipo, BigDecimal cantidad, long loteId,
                                                  Long reservaId, Long devolucionId) {
        Optional<MovimientoInventario> existente = movimientos.buscarPorClave(clave);
        existente.ifPresent(m -> {
            boolean misma = m.getTipo() == tipo && cantidad != null && m.getCantidad().compareTo(cantidad) == 0
                    && m.getLoteFederId() == loteId && Objects.equals(m.getReservaId(), reservaId)
                    && Objects.equals(m.getDevolucionId(), devolucionId);
            if (!misma) {
                throw claveUsada(clave);
            }
        });
        return existente;
    }

    private static List<MovimientoInventario> exigirMismaReposicion(List<MovimientoInventario> previos, long devolucionId,
                                                                    BigDecimal cantidad, String clave) {
        BigDecimal total = previos.stream().map(MovimientoInventario::getCantidad).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean misma = total.compareTo(cantidad) == 0 && previos.stream().allMatch(m ->
                m.getTipo() == TipoMovimiento.SALIDA_REPOSICION && Objects.equals(m.getDevolucionId(), devolucionId));
        if (!misma) {
            throw claveUsada(clave);
        }
        return previos;
    }

    private static NegocioException claveUsada(String clave) {
        return NegocioException.duplicado("La clave de operación " + clave
                + " ya se usó en otro movimiento de inventario: use una clave nueva.");
    }

    private List<MovimientoInventario> previosConSufijo(String clave) {
        List<MovimientoInventario> encontrados = new ArrayList<>();
        for (int i = 1; ; i++) {
            Optional<MovimientoInventario> m = movimientos.buscarPorClave(clave + "-" + i);
            if (m.isEmpty()) {
                return encontrados;
            }
            encontrados.add(m.get());
        }
    }

    private MovimientoInventario insertar(long loteId, long usuarioId, Long reservaId, Long devolucionId, TipoMovimiento tipo,
                                          BigDecimal cantidad, String motivo, String clave) {
        MovimientoInventario m = new MovimientoInventario();
        m.setLoteFederId(loteId);
        m.setUsuarioId(usuarioId);
        m.setReservaId(reservaId);
        m.setDevolucionId(devolucionId);
        m.setTipo(tipo);
        m.setCantidad(cantidad);
        m.setFecha(ctx.ahora());
        m.setMotivo(motivo);
        m.setClaveOperacion(clave);
        m.setId(movimientos.insertar(m));
        return m;
    }
}
