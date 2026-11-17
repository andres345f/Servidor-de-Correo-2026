package com.feder.negocio.inventario;

import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.AbastecimientoRepositorio;
import com.feder.datos.inventario.AbastecimientoRepositorio.LineaPendiente;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.LoteFederVista;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.inventario.StockRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.EstadoLoteFeder;
import com.feder.modelo.EstadoReserva;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.ReservaStock;
import com.feder.modelo.TipoMovimiento;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.ServicioStock;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Entrada pública del inventario de FEDER (CU4) para los demás casos de uso.
 *
 * Todos los métodos se unen a la transacción en curso (o abren una) y respetan el orden global de
 * bloqueo de R037: PEDIDO, DETALLE_PEDIDO, LOTE_PRODUCCION, DETALLE_COMPRA, LOTE_FEDER, RESERVA_STOCK.
 * Quien llama desde CU5 o CU6 debe haber bloqueado antes la fila del PEDIDO (o de la DEVOLUCION) que
 * corresponda; estos métodos bloquean el resto. Si una regla no se cumple lanzan NegocioException y no
 * dejan cambios parciales cuando el llamador revierte su transacción o su punto de guardado.
 */
public class ServicioInventario {

    private final ContextoNegocio ctx;
    private final ServicioStock stock;
    private final LoteFederRepositorio lotes;
    private final MovimientoRepositorio movimientos;
    private final ReservaRepositorio reservas;
    private final ReservasDeStock reservasDeStock;
    private final MovimientosDeLote movimientosDeLote;
    private final AbastecimientoPedidos abastecimiento;
    private final InhabilitacionDeLotes inhabilitacion;

    public ServicioInventario(ContextoNegocio ctx, ServicioStock stock, LoteFederRepositorio lotes,
                              MovimientoRepositorio movimientos, ReservaRepositorio reservas, ProductoRepositorio productos) {
        this.ctx = ctx;
        this.stock = stock;
        this.lotes = lotes;
        this.movimientos = movimientos;
        this.reservas = reservas;
        this.reservasDeStock = new ReservasDeStock(ctx, stock, lotes, reservas, movimientos, productos);
        this.movimientosDeLote = new MovimientosDeLote(ctx, stock, lotes, movimientos, reservas);
        AbastecimientoRepositorio pendientes = new AbastecimientoRepositorio();
        PedidoRepositorio pedidos = new PedidoRepositorio();
        this.abastecimiento = new AbastecimientoPedidos(ctx, pendientes, pedidos, new HistorialPedidoRepositorio(), reservasDeStock);
        this.inhabilitacion = new InhabilitacionDeLotes(ctx, stock, lotes, reservas, pedidos, pendientes, reservasDeStock);
    }

    /** Construye el servicio con sus repositorios por defecto. */
    public static ServicioInventario crear(ContextoNegocio ctx) {
        ProductoRepositorio productos = new ProductoRepositorio();
        ServicioStock stock = new ServicioStock(ctx, new StockRepositorio(), productos);
        return new ServicioInventario(ctx, stock, new LoteFederRepositorio(), new MovimientoRepositorio(),
                new ReservaRepositorio(), productos);
    }

    public ServicioStock stock() {
        return stock;
    }

    // ------------------------------------------------------------------ reservas (CU5 y CU6)

    /**
     * Reserva con FEFO y UPDATE condicional (R037) las cantidades de varias líneas de pedido ya insertadas,
     * en una sola pasada con orden de bloqueo determinista. Cada línea queda cubierta por completo o se
     * lanza STOCK_INSUFICIENTE; nunca se reservan las mismas unidades dos veces.
     *
     * @param expiracion momento en que vencen las reservas (pedido INMEDIATO PENDIENTE); nulo si no caducan.
     */
    public List<ReservaStock> reservarLineas(List<LineaPorReservar> lineas, OffsetDateTime expiracion) {
        return ctx.transacciones().enTransaccion(() -> reservasDeStock.reservar(lineas, expiracion));
    }

    /**
     * Reserva para una sola línea (reasignación tras un bloqueo o abastecimiento de un encargo, CU6).
     * La suma de reservas ACTIVA o CONSUMIDA de la línea nunca supera su cantidad (R086).
     */
    public List<ReservaStock> reservarLinea(long detallePedidoId, BigDecimal cantidad, OffsetDateTime expiracion) {
        return reservarLineas(List.of(new LineaPorReservar(detallePedidoId, cantidad)), expiracion);
    }

    /**
     * Libera las reservas ACTIVA de un pedido (cancelación: LIBERADA; vencimiento del plazo de pago:
     * EXPIRADA). Cada reserva sale de ACTIVA una sola vez (R084). Devuelve cuántas liberó.
     */
    public int liberarReservasDePedido(long pedidoId, EstadoReserva destino) {
        return ctx.transacciones().enTransaccion(() -> reservasDeStock.liberar(pedidoId, destino));
    }

    /**
     * Despacho (R055, R083): comprueba que cada línea está cubierta por completo con reservas ACTIVA y que
     * ningún lote está vencido o bloqueado; pasa todas las reservas a CONSUMIDA con su SALIDA_VENTA
     * (clave DESP-RES-&lt;reserva&gt;) y descuenta saldo y reservado. Si algo falla no consume ninguna.
     * El cambio de estado del pedido y de la entrega lo hace el llamador (CU6) en la misma transacción.
     */
    public List<MovimientoInventario> consumirReservasDePedido(long pedidoId, long usuarioId) {
        return ctx.transacciones().enTransaccion(() -> reservasDeStock.consumir(pedidoId, usuarioId));
    }

    /**
     * R053: con la confirmación comercial de un pedido INMEDIATO sus reservas ACTIVA dejan de caducar
     * (fecha_expiracion nula). Lo llama CU6 en la misma transacción que pasa el pedido a CONFIRMADO.
     */
    public int quitarExpiracionDeReservas(long pedidoId) {
        return ctx.transacciones().enTransaccion(() -> reservas.quitarExpiracion(pedidoId));
    }

    // ------------------------------------------------------------------ abastecimiento (R056)

    /**
     * R056 (b): el personal abastece un pedido con stock vendible de FEDER. Reserva con FEFO lo que falte
     * de cada línea, todo lo que permita el stock (una línea puede quedar cubierta en parte), y pasa a
     * CONFIRMADO el ENCARGO que queda cubierto por completo. Bloquea el pedido y sus líneas antes que el
     * inventario. Devuelve una lista vacía si no había stock para reservar nada.
     */
    public List<PedidoAbastecido> abastecerPedido(long pedidoId, long usuarioId) {
        return ctx.transacciones().enTransaccion(() -> {
            AbastecimientoPedidos.Espera espera = abastecimiento.esperaDePedido(pedidoId);
            if (espera.vacia()) {
                throw NegocioException.estadoInvalido("El pedido no tiene líneas pendientes de abastecer: solo se abastece un "
                        + "ENCARGO en ESPERANDO_ABASTECIMIENTO o un pedido CONFIRMADO o EN_PREPARACION sin cobertura completa.");
            }
            Set<Long> productos = new TreeSet<>(espera.lineas().stream().map(LineaPendiente::productoId).toList());
            // Los lotes que el abastecimiento puede reservar se bloquean antes que la evaluación de la alerta (R070).
            reservasDeStock.bloquearCandidatos(productos, List.of());
            return stock.vigilando(productos, () -> abastecimiento.abastecer(espera, null, usuarioId,
                    "Abastecido por el personal con stock de FEDER"));
        });
    }

    /** R056 (a), paso 1: bloquea los pedidos que esperan el producto recibido (antes que el inventario). */
    AbastecimientoPedidos.Espera esperaDeProducto(long productoId, long loteOrigenId) {
        return abastecimiento.esperaDeProducto(productoId, loteOrigenId);
    }

    /**
     * Bloquea, en el orden de R037 y antes de vigilar la alerta de stock (R070), los lotes que una recepción puede
     * modificar: el LOTE_FEDER de la línea, si ya existe, y, si hay pedidos en espera, los lotes vendibles del producto.
     */
    void bloquearLotesDeRecepcion(long productoId, long detalleCompraId, boolean conVendibles) {
        List<LoteFederVista> deLaLinea = lotes.buscarPorDetalle(detalleCompraId).map(List::of).orElse(List.of());
        reservasDeStock.bloquearCandidatos(conVendibles ? List.of(productoId) : List.of(), deLaLinea);
    }

    /**
     * R056 (a), paso 2: las unidades recibidas cubren primero los pedidos en espera y solo el resto queda
     * libre. Se reserva como máximo lo aceptado, repartido con FEFO entre los lotes vendibles: el stock
     * vendible baja lo mismo y, si había un lote que vence antes, sale primero ese.
     */
    List<PedidoAbastecido> abastecerConEntrada(AbastecimientoPedidos.Espera espera, BigDecimal aceptada, long usuarioId,
                                               String codigoCompra) {
        return abastecimiento.abastecer(espera, aceptada, usuarioId, "Abastecido con la recepción de la compra " + codigoCompra);
    }

    // ------------------------------------------------------------------ bloqueo y vencimiento de lotes (R035)

    /** Bloquea un lote (mercancía dañada o con un error de origen): libera sus reservas y las reasigna con FEFO. */
    public LoteInhabilitado bloquearLote(Solicitante solicitante, String codigoLote, String motivo) {
        Permisos.exigirPersonal(solicitante, "bloquear lotes de FEDER");
        String motivoLimpio = Textos.obligatorio(motivo, "motivo del bloqueo");
        return ctx.transacciones().enTransaccion(() -> inhabilitacion.inhabilitar(buscarLote(codigoLote),
                EstadoLoteFeder.BLOQUEADO, solicitante.usuarioId(), motivoLimpio));
    }

    /**
     * Proceso diario de vencimiento (R035): pasa a VENCIDO cada lote DISPONIBLE o AGOTADO cuyo lote de
     * origen ya venció, libera sus reservas y las reasigna con FEFO. Cada lote se procesa con su propio
     * orden de bloqueo; si uno falla, no se aplica ninguno.
     */
    public List<LoteInhabilitado> vencerLotes(Solicitante solicitante) {
        Permisos.exigirPersonal(solicitante, "ejecutar el proceso de vencimiento de lotes");
        return ctx.transacciones().enTransaccion(() -> {
            List<LoteInhabilitado> vencidos = new ArrayList<>();
            for (LoteFederVista lote : lotes.porMarcarVencidos(stock.hoy())) {
                vencidos.add(inhabilitacion.inhabilitar(lote, EstadoLoteFeder.VENCIDO, solicitante.usuarioId(),
                        "Vencimiento del lote de origen " + lote.codigoLoteOrigen() + " (" + lote.fechaVencimiento() + ")"));
            }
            return vencidos;
        });
    }

    /**
     * Consulta del panel de R070: lotes con saldo que vencen entre hoy y hoy + {@code dias} y lotes ya
     * vencidos pendientes de baja (en cualquier estado), del vencimiento más cercano al más lejano.
     */
    public List<LoteFederVista> lotesPorVencer(int dias) {
        if (dias < 0 || dias > 365) {
            throw NegocioException.regla("Indique un número de días entre 0 y 365.");
        }
        return ctx.transacciones().enTransaccion(() -> lotes.conSaldoQueVencenHasta(stock.hoy().plusDays(dias)));
    }

    private LoteFederVista buscarLote(String codigo) {
        return lotes.buscarPorCodigo(codigo)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el lote de FEDER " + codigo + ". Consulte LISLTF[\"*\"]."));
    }

    // ------------------------------------------------------------------ entradas y salidas

    /** ENTRADA_COMPRA de una recepción (CU4). El llamador tiene bloqueado el DETALLE_COMPRA. */
    public LoteFederVista registrarEntradaCompra(long detalleCompraId, BigDecimal cantidad, String ubicacion,
                                                 long usuarioId, String claveOperacion) {
        return ctx.transacciones().enTransaccion(
                () -> movimientosDeLote.entradaCompra(detalleCompraId, cantidad, ubicacion, usuarioId, claveOperacion));
    }

    /** ANULACION_RECEPCION (R009): corrige una aceptación registrada de más; solo sale stock libre. */
    public LoteFederVista registrarAnulacionRecepcion(long detalleCompraId, BigDecimal cantidad, long usuarioId,
                                                      String claveOperacion, String motivo) {
        return ctx.transacciones().enTransaccion(
                () -> movimientosDeLote.anulacionRecepcion(detalleCompraId, cantidad, usuarioId, claveOperacion, motivo));
    }

    /**
     * ENTRADA_RETORNO_ENTREGA (R029, R044): mercancía despachada que vuelve a FEDER (entrega RETORNADA o
     * no recogida). Exige la reserva CONSUMIDA y la suma de retornos no supera su SALIDA_VENTA.
     */
    public MovimientoInventario registrarRetornoEntrega(long reservaId, BigDecimal cantidad, long usuarioId,
                                                        String claveOperacion, String motivo) {
        return ctx.transacciones().enTransaccion(
                () -> movimientosDeLote.retornoEntrega(reservaId, cantidad, usuarioId, claveOperacion, motivo));
    }

    /**
     * ENTRADA_DEVOLUCION (R024, R044): reingreso de unidades aptas en el lote del despacho devuelto.
     * El estado de la devolución y su tope los valida CU6 con la DEVOLUCION bloqueada.
     */
    public MovimientoInventario registrarEntradaDevolucion(long devolucionId, long loteFederId, BigDecimal cantidad,
                                                           long usuarioId, String claveOperacion) {
        return ctx.transacciones().enTransaccion(
                () -> movimientosDeLote.entradaDevolucion(devolucionId, loteFederId, cantidad, usuarioId, claveOperacion));
    }

    /**
     * Baja de unidades retornadas no aptas (R029): BAJA_DANADO, BAJA_DEFECTUOSO o, con el lote de origen
     * vencido, BAJA_VENCIDO, vinculada a la reserva; la suma de bajas no supera lo retornado (R044).
     */
    public MovimientoInventario registrarBajaDeRetorno(long reservaId, TipoMovimiento tipo, BigDecimal cantidad,
                                                       long usuarioId, String claveOperacion, String motivo) {
        return ctx.transacciones().enTransaccion(
                () -> movimientosDeLote.bajaDeRetorno(reservaId, tipo, cantidad, usuarioId, claveOperacion, motivo));
    }

    /** AJUSTE_NEGATIVO que compensa el reingreso de una devolución anulada por rectificación (R025, R043). */
    public MovimientoInventario registrarCompensacionDeDevolucion(long devolucionId, long loteFederId, BigDecimal cantidad,
                                                                  long usuarioId, String claveOperacion, String motivo) {
        return ctx.transacciones().enTransaccion(() -> movimientosDeLote.compensacionDeDevolucion(devolucionId, loteFederId,
                cantidad, usuarioId, claveOperacion, motivo));
    }

    /** SALIDA_REPOSICION (R026): unidades nuevas para una devolución con resolución REPOSICION, con FEFO. */
    public List<MovimientoInventario> registrarSalidaReposicion(long devolucionId, long productoId, BigDecimal cantidad,
                                                                long usuarioId, String claveOperacion) {
        return ctx.transacciones().enTransaccion(
                () -> movimientosDeLote.salidaReposicion(devolucionId, productoId, cantidad, usuarioId, claveOperacion));
    }

    /** Ajustes y bajas manuales (comando AJUSTK). */
    public MovimientoInventario registrarAjuste(Solicitante solicitante, SolicitudAjuste solicitud) {
        return ctx.transacciones().enTransaccion(() -> movimientosDeLote.ajuste(solicitante, solicitud));
    }

    // ------------------------------------------------------------------ consultas

    /** Existencia, reservado y stock vendible por producto (R070). */
    public List<StockRepositorio.ResumenStock> resumenStock(String filtro) {
        return ctx.transacciones().enTransaccion(() -> stock.resumen(filtro));
    }

    public List<LoteFederVista> listarLotes(String filtro, EstadoLoteFeder estado) {
        return ctx.transacciones().enTransaccion(() -> lotes.listar(filtro, estado));
    }

    /** Lote con sus movimientos en orden cronológico (trazabilidad, R042). */
    public record LoteConMovimientos(LoteFederVista lote, List<MovimientoInventario> movimientos) {
    }

    public LoteConMovimientos movimientosDeLote(String codigoLote) {
        return ctx.transacciones().enTransaccion(() -> {
            LoteFederVista lote = lotes.buscarPorCodigo(codigoLote)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe el lote de FEDER " + codigoLote + ". Consulte LISLTF[\"*\"]."));
            return new LoteConMovimientos(lote, movimientos.deLote(lote.id()));
        });
    }

    /** Movimientos de todos los lotes, del más reciente al más antiguo (LISMOV["*"]). */
    public List<MovimientoRepositorio.MovimientoConLote> movimientosRecientes(int maximo) {
        return ctx.transacciones().enTransaccion(() -> movimientos.recientes(maximo));
    }

    /** Todos los lotes con saldo, del vencimiento más cercano al más lejano (LISVEN["*"]). */
    public List<LoteFederVista> lotesConSaldoPorVencimiento(int maximo) {
        return ctx.transacciones().enTransaccion(() -> lotes.conSaldoPorVencimiento(maximo));
    }
}
