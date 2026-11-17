package com.feder.negocio.indicadores;

import com.feder.datos.reportes.FiltrosConsulta;
import com.feder.datos.reportes.IndicadoresCajaRepositorio;
import com.feder.datos.reportes.IndicadoresComercialesRepositorio;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio.MovimientoACosto;
import com.feder.datos.reportes.IndicadoresInventarioRepositorio.ValorProducto;
import com.feder.datos.reportes.ReporteAbastecimientoRepositorio;
import com.feder.datos.reportes.ReporteInventarioRepositorio;
import com.feder.datos.reportes.ReporteTrazabilidadRepositorio;
import com.feder.datos.reportes.ReporteVentasRepositorio;
import com.feder.modelo.FormatoReporte;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Interfaz SERVICIO_INDICADORES_REPORTES del modelo (CU7 y CU8): consultas sobre los registros existentes,
 * sin tablas propias ni valores derivados guardados. Ninguna operación modifica datos.
 *
 * Las operaciones de CU7 solo las ejecuta un ADMINISTRADOR vigente. Los reportes de CU8 los consultan
 * ADMINISTRADOR y PERSONAL_FEDER; para PERSONAL_FEDER no incluyen importes agregados, costos, márgenes ni el
 * valor del inventario. Los importes devueltos se redondean half-up a 2 decimales; los cálculos intermedios
 * se hacen a precisión completa.
 */
public class ServicioIndicadoresReportes {

    /** Días que se consideran «próximo a vencer» si la configuración no indica otro valor. */
    public static final int DIAS_PROXIMO_VENCIMIENTO_POR_DEFECTO = 30;

    private final ContextoNegocio ctx;
    private final IndicadoresComercialesRepositorio comerciales = new IndicadoresComercialesRepositorio();
    private final IndicadoresCajaRepositorio caja = new IndicadoresCajaRepositorio();
    private final IndicadoresInventarioRepositorio inventario = new IndicadoresInventarioRepositorio();
    private final GeneradorReporteVentas reporteVentas;
    private final GeneradorReporteInventario reporteInventario;
    private final GeneradorReporteTrazabilidad reporteTrazabilidad;
    private final GeneradorReporteAbastecimiento reporteAbastecimiento;

    public ServicioIndicadoresReportes(ContextoNegocio ctx) {
        this(ctx, DIAS_PROXIMO_VENCIMIENTO_POR_DEFECTO);
    }

    /** @param diasProximoVencimiento N de «lotes próximos a vencer» (PRODUCTO «Stock vendible derivado»). */
    public ServicioIndicadoresReportes(ContextoNegocio ctx, int diasProximoVencimiento) {
        this.ctx = Objects.requireNonNull(ctx);
        this.reporteVentas = new GeneradorReporteVentas(new ReporteVentasRepositorio());
        this.reporteInventario = new GeneradorReporteInventario(new ReporteInventarioRepositorio(), ctx.reloj(),
                diasProximoVencimiento);
        this.reporteTrazabilidad = new GeneradorReporteTrazabilidad(new ReporteTrazabilidadRepositorio());
        this.reporteAbastecimiento = new GeneradorReporteAbastecimiento(new ReporteAbastecimientoRepositorio());
    }

    // ------------------------------------------------------------------------------------------------
    // CU7 · Indicadores financieros (solo ADMINISTRADOR vigente)
    // ------------------------------------------------------------------------------------------------

    /** Ingreso comercial reconocido de mercadería (R059): subtotal entregado - reembolsos por devolución. */
    public BigDecimal ingresosReconocidos(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        Periodo periodo = new Periodo(desde, hasta);
        IndicadoresComercialesRepositorio.ImportesVenta ventas = leer(() -> comerciales.ingresos(periodo.desde(), periodo.hasta()));
        return Formatos.redondear(ventas.subtotalEntregado().subtract(ventas.reembolsosPorDevolucion()));
    }

    public BigDecimal costoDeVentas(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        return Formatos.redondear(analizarVentas(solicitante, desde, hasta).costoDeVentas());
    }

    public BigDecimal margenBruto(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        return Formatos.redondear(analizarVentas(solicitante, desde, hasta).margenBruto());
    }

    /** Anticipos a una fecha de corte: pasivo, no ingreso. */
    public BigDecimal anticiposPendientes(Solicitante solicitante, LocalDate fechaCorte) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        LocalDate corte = Periodo.exigirCorte(fechaCorte);
        return Formatos.redondear(leer(() -> caja.anticiposPendientes(corte)));
    }

    /** Valor del inventario a costo, reconstruido con los movimientos hasta el corte. */
    public BigDecimal valorInventario(Solicitante solicitante, LocalDate fechaCorte) {
        return Formatos.redondear(totalValor(valorInventarioPorProducto(solicitante, fechaCorte)));
    }

    public BigDecimal cobrosConfirmados(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        return Formatos.redondear(analizarCaja(solicitante, desde, hasta).cobrosConfirmados());
    }

    public BigDecimal reintegrosEjecutados(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        return Formatos.redondear(analizarCaja(solicitante, desde, hasta).reintegrosEjecutados());
    }

    public BigDecimal comprasAceptadas(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        return Formatos.redondear(analizarCompras(solicitante, desde, hasta).comprasAceptadas());
    }

    /**
     * Costo no recuperado pendiente de cancelación a una fecha de corte. Vacío cuando no es determinable
     * porque faltan o no encadenan las transiciones de alguna entrega en BITACORA: en ese caso no se estima.
     */
    public Optional<BigDecimal> costoNoRecuperadoPendiente(Solicitante solicitante, LocalDate fechaCorte) {
        return situacionDespachos(solicitante, fechaCorte).costoNoRecuperado().map(Formatos::redondear);
    }

    // ---- Desgloses, serie mensual y conciliaciones de CU7, con las mismas fórmulas y fechas ----

    public AnalisisComercial analizarVentas(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        return analisisComercial(new Periodo(desde, hasta));
    }

    public AnalisisCaja analizarCaja(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        return analisisCaja(new Periodo(desde, hasta));
    }

    public AnalisisCompras analizarCompras(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        return analisisCompras(new Periodo(desde, hasta), FiltrosConsulta.Compras.NINGUNO);
    }

    public List<ValorProducto> valorInventarioPorProducto(Solicitante solicitante, LocalDate fechaCorte) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        LocalDate corte = Periodo.exigirCorte(fechaCorte);
        return leer(() -> inventario.valorPorProducto(corte, FiltrosConsulta.Lotes.NINGUNO));
    }

    public SituacionDespachos situacionDespachos(Solicitante solicitante, LocalDate fechaCorte) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        return situacion(Periodo.exigirCorte(fechaCorte), FiltrosConsulta.Lotes.NINGUNO);
    }

    /** Dato informativo de CU7: devoluciones aprobadas pendientes de reintegro al corte (no resta ingreso). */
    public BigDecimal devolucionesPendientesDeReintegro(Solicitante solicitante, LocalDate fechaCorte) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        LocalDate corte = Periodo.exigirCorte(fechaCorte);
        return Formatos.redondear(leer(() -> caja.devolucionesPendientesDeReintegro(corte)));
    }

    /** Cada indicador de período presentado como serie mensual (nota de SERVICIO_INDICADORES_REPORTES). */
    public List<IndicadoresDelMes> serieMensual(Solicitante solicitante, LocalDate desde, LocalDate hasta) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        List<Periodo> meses = new Periodo(desde, hasta).meses();
        return leer(() -> meses.stream().map(mes -> new IndicadoresDelMes(mes.desde().toString().substring(0, 7),
                analisisComercial(mes), analisisCaja(mes), analisisCompras(mes, FiltrosConsulta.Compras.NINGUNO))).toList());
    }

    /** Conciliaciones de control a una fecha de corte, con valores acumulados desde el primer registro. */
    public ConciliacionControl conciliaciones(Solicitante solicitante, LocalDate fechaCorte) {
        AutorizacionIndicadores.exigirAdministrador(solicitante);
        Periodo acumulado = Periodo.acumuladoHasta(fechaCorte);
        LocalDate corte = acumulado.hasta();
        return leer(() -> {
            AnalisisComercial ventas = analisisComercial(acumulado);
            AnalisisCaja cajaAcumulada = analisisCaja(acumulado);
            MovimientoACosto movimientos = inventario.movimientosACosto(acumulado.desde(), corte);
            return new ConciliacionControl(corte, cajaAcumulada.cobrosConfirmados(), cajaAcumulada.reintegrosEjecutados(),
                    ventas.ingresosReconocidos(), ventas.envioCobrado(), caja.anticiposPendientes(corte),
                    movimientos.entradas(), movimientos.salidas(),
                    totalValor(inventario.valorPorProducto(corte, FiltrosConsulta.Lotes.NINGUNO)));
        });
    }

    // ------------------------------------------------------------------------------------------------
    // CU8 · Reportes (ADMINISTRADOR completo; PERSONAL_FEDER sin importes agregados ni costos)
    // ------------------------------------------------------------------------------------------------

    public ResultadoOperacion generarReporteVentas(Solicitante solicitante, LocalDate desde, LocalDate hasta,
                                                   FormatoReporte formato) {
        return generarReporteVentas(solicitante, desde, hasta, formato, null);
    }

    /** @param filtros "clave=valor;..." con producto, categoria, estado, tipo, modalidad y metodo; "*" o null = sin filtros. */
    public ResultadoOperacion generarReporteVentas(Solicitante solicitante, LocalDate desde, LocalDate hasta,
                                                   FormatoReporte formato, String filtros) {
        AutorizacionIndicadores.exigirPersonalInterno(solicitante);
        Periodo periodo = new Periodo(desde, hasta);
        FormatoReporte destino = exigirFormato(formato);
        FiltrosReporte filtro = FiltrosReporte.analizar(filtros, FiltrosReporte.Reporte.VENTAS);
        boolean importes = AutorizacionIndicadores.veImportes(solicitante);
        return leer(() -> {
            Optional<GeneradorReporteVentas.ImportesVentas> agregados = importes
                    ? Optional.of(new GeneradorReporteVentas.ImportesVentas(analisisComercial(periodo), analisisCaja(periodo)))
                    : Optional.empty();
            return reporteVentas.generar(periodo, filtro, situacion(periodo.hasta(), FiltrosConsulta.Lotes.NINGUNO), agregados)
                    .exportarComo(destino, "reporte_ventas_" + periodo.sufijoArchivo());
        });
    }

    public ResultadoOperacion generarReporteInventario(Solicitante solicitante, LocalDate fechaCorte,
                                                       FormatoReporte formato) {
        return generarReporteInventario(solicitante, fechaCorte, formato, null);
    }

    /**
     * @param filtros "clave=valor;..." con producto, categoria, productor, comunidad, estado (del lote) y desde/hasta
     *                (período de los movimientos); "*" o null = sin filtros.
     */
    public ResultadoOperacion generarReporteInventario(Solicitante solicitante, LocalDate fechaCorte,
                                                       FormatoReporte formato, String filtros) {
        AutorizacionIndicadores.exigirPersonalInterno(solicitante);
        LocalDate corte = Periodo.exigirCorte(fechaCorte);
        FormatoReporte destino = exigirFormato(formato);
        FiltrosReporte filtro = FiltrosReporte.analizar(filtros, FiltrosReporte.Reporte.INVENTARIO);
        boolean importes = AutorizacionIndicadores.veImportes(solicitante);
        FiltrosConsulta.Lotes lotes = filtro.aLotes();
        return leer(() -> {
            Optional<BigDecimal> valor = importes ? Optional.of(totalValor(inventario.valorPorProducto(corte, lotes)))
                    : Optional.empty();
            return reporteInventario.generar(corte, filtro, situacion(corte, lotes), valor)
                    .exportarComo(destino, "reporte_inventario_" + corte);
        });
    }

    public ResultadoOperacion generarReporteTrazabilidad(Solicitante solicitante, String codigoPedido,
                                                         FormatoReporte formato) {
        AutorizacionIndicadores.exigirPersonalInterno(solicitante);
        if (codigoPedido == null || codigoPedido.isBlank()) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO, "Indique el código del pedido.");
        }
        FormatoReporte destino = exigirFormato(formato);
        String codigo = codigoPedido.strip();
        return leer(() -> reporteTrazabilidad.generar(codigo).exportarComo(destino, "trazabilidad_" + codigo));
    }

    public ResultadoOperacion generarReporteAbastecimiento(Solicitante solicitante, LocalDate desde, LocalDate hasta,
                                                           FormatoReporte formato) {
        return generarReporteAbastecimiento(solicitante, desde, hasta, formato, null);
    }

    /** @param filtros "clave=valor;..." con producto, categoria, productor, comunidad y estado (de la compra). */
    public ResultadoOperacion generarReporteAbastecimiento(Solicitante solicitante, LocalDate desde, LocalDate hasta,
                                                           FormatoReporte formato, String filtros) {
        AutorizacionIndicadores.exigirPersonalInterno(solicitante);
        Periodo periodo = new Periodo(desde, hasta);
        FormatoReporte destino = exigirFormato(formato);
        FiltrosReporte filtro = FiltrosReporte.analizar(filtros, FiltrosReporte.Reporte.ABASTECIMIENTO);
        boolean importes = AutorizacionIndicadores.veImportes(solicitante);
        return leer(() -> reporteAbastecimiento
                .generar(periodo, filtro, analisisCompras(periodo, filtro.aCompras()).lineas(), importes)
                .exportarComo(destino, "reporte_abastecimiento_" + periodo.sufijoArchivo()));
    }

    // ------------------------------------------------------------------------------------------------

    private AnalisisComercial analisisComercial(Periodo periodo) {
        return leer(() -> new AnalisisComercial(periodo,
                comerciales.ingresos(periodo.desde(), periodo.hasta()),
                comerciales.desglosePorProducto(periodo.desde(), periodo.hasta()).stream()
                        .map(DesgloseComercial::de).toList()));
    }

    private AnalisisCaja analisisCaja(Periodo periodo) {
        return leer(() -> new AnalisisCaja(periodo, caja.cobrosPorMetodo(periodo.desde(), periodo.hasta()),
                caja.reintegrosPorMetodo(periodo.desde(), periodo.hasta())));
    }

    private AnalisisCompras analisisCompras(Periodo periodo, FiltrosConsulta.Compras filtro) {
        return new AnalisisCompras(periodo, leer(() -> inventario.aceptadoPorOrigen(periodo.desde(), periodo.hasta(), filtro)));
    }

    private SituacionDespachos situacion(LocalDate corte, FiltrosConsulta.Lotes filtro) {
        return leer(() -> new SituacionDespachos(corte, inventario.mercanciaDespachada(corte, filtro),
                inventario.pedidosNoDeterminables(corte)));
    }

    private static BigDecimal totalValor(List<ValorProducto> productos) {
        return productos.stream().map(ValorProducto::valor).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static FormatoReporte exigirFormato(FormatoReporte formato) {
        if (formato == null) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "Indique el formato del reporte: PDF o EXCEL.");
        }
        return formato;
    }

    /** Las consultas se unen a la transacción del correo en curso o abren una propia si no la hay. */
    private <T> T leer(Supplier<T> consulta) {
        return ctx.transacciones().enTransaccion(consulta);
    }
}
