package com.feder.negocio.indicadores;

import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoHandler;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

import java.time.LocalDate;
import java.util.function.BiFunction;

/**
 * Comandos de CU7 · Gestión de indicadores financieros. Todos son de solo lectura y solo los ejecuta un
 * ADMINISTRADOR vigente (SERVICIO_INDICADORES_REPORTES; ROL «Autorización por rol»).
 */
public final class ModuloIndicadores implements ModuloComandos {

    private static final String CASO_DE_USO = "CU7";
    private static final String DESDE = "desde";
    private static final String HASTA = "hasta";
    private static final String CORTE = "fecha_corte";
    private static final String EJEMPLO_PERIODO = "[\"2026-01-01\",\"2026-12-31\"]";
    private static final String EJEMPLO_CORTE = "[\"2026-11-15\"]";

    private final ServicioIndicadoresReportes servicio;

    public ModuloIndicadores(ContextoNegocio ctx) {
        this.servicio = new ServicioIndicadoresReportes(ctx);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(porPeriodo("INDING", "Ingresos reconocidos del período, con desglose por producto y categoría",
                (s, p) -> ResultadosIndicadores.ingresos(servicio.analizarVentas(s, p.desde(), p.hasta()))));
        catalogo.registrar(porPeriodo("INDCOS", "Costo de ventas, costo de posventa y mermas informativas del período",
                (s, p) -> ResultadosIndicadores.costos(servicio.analizarVentas(s, p.desde(), p.hasta()))));
        catalogo.registrar(porPeriodo("INDMAR", "Margen bruto comercial del período y margen porcentual",
                (s, p) -> ResultadosIndicadores.margen(servicio.analizarVentas(s, p.desde(), p.hasta()))));
        catalogo.registrar(alCorte("INDANT", "Anticipos pendientes a una fecha de corte",
                (s, corte) -> ResultadosIndicadores.anticipos(corte, servicio.anticiposPendientes(s, corte),
                        servicio.devolucionesPendientesDeReintegro(s, corte))));
        catalogo.registrar(alCorte("INDINV", "Valor del inventario de FEDER a costo a una fecha de corte",
                (s, corte) -> ResultadosIndicadores.inventario(corte, servicio.valorInventarioPorProducto(s, corte),
                        servicio.situacionDespachos(s, corte))));
        catalogo.registrar(porPeriodo("INDCOB", "Cobros confirmados del período por método de pago",
                (s, p) -> ResultadosIndicadores.cobros(servicio.analizarCaja(s, p.desde(), p.hasta()))));
        catalogo.registrar(porPeriodo("INDREI", "Reintegros ejecutados del período por método y motivo",
                (s, p) -> ResultadosIndicadores.reintegros(servicio.analizarCaja(s, p.desde(), p.hasta()))));
        catalogo.registrar(porPeriodo("INDCMP", "Costo de las compras aceptadas del período por productor y origen",
                (s, p) -> ResultadosIndicadores.compras(servicio.analizarCompras(s, p.desde(), p.hasta()))));
        catalogo.registrar(alCorte("INDCNR", "Costo no recuperado pendiente de cancelación a una fecha de corte",
                (s, corte) -> ResultadosIndicadores.costoNoRecuperado(corte, servicio.situacionDespachos(s, corte))));
        catalogo.registrar(porPeriodo("INDRES", "Resumen de todos los indicadores financieros del período",
                this::resumen));
        catalogo.registrar(porPeriodo("INDSER", "Serie mensual de los indicadores financieros del período (hasta 24 meses)",
                (s, p) -> ResultadosIndicadores.serieMensual(p, servicio.serieMensual(s, p.desde(), p.hasta()))));
        catalogo.registrar(alCorte("INDCON", "Conciliaciones de control de caja e inventario a una fecha de corte",
                (s, corte) -> ResultadosIndicadores.conciliaciones(servicio.conciliaciones(s, corte))));
    }

    private ResultadoOperacion resumen(Solicitante s, Periodo p) {
        LocalDate corte = p.hasta();
        return ResultadosIndicadores.resumen(servicio.analizarVentas(s, p.desde(), p.hasta()),
                servicio.analizarCaja(s, p.desde(), p.hasta()), servicio.analizarCompras(s, p.desde(), p.hasta()),
                servicio.anticiposPendientes(s, corte), servicio.valorInventario(s, corte),
                servicio.situacionDespachos(s, corte));
    }

    private static ComandoSpec porPeriodo(String nombre, String descripcion,
                                          BiFunction<Solicitante, Periodo, ResultadoOperacion> consulta) {
        ComandoHandler handler = (Parametros p, Solicitante s) ->
                consulta.apply(s, new Periodo(p.fecha(DESDE), p.fecha(HASTA)));
        return base(nombre, descripcion)
                .parametros(ParametroSpec.fecha(DESDE), ParametroSpec.fecha(HASTA))
                .ejemplo(nombre + EJEMPLO_PERIODO)
                .handler(handler)
                .construir();
    }

    private static ComandoSpec alCorte(String nombre, String descripcion,
                                       BiFunction<Solicitante, LocalDate, ResultadoOperacion> consulta) {
        return base(nombre, descripcion)
                .parametros(ParametroSpec.fecha(CORTE))
                .ejemplo(nombre + EJEMPLO_CORTE)
                .handler((p, s) -> consulta.apply(s, p.fecha(CORTE)))
                .construir();
    }

    private static ComandoSpec.Constructor base(String nombre, String descripcion) {
        return ComandoSpec.comando(nombre).casoDeUso(CASO_DE_USO).descripcion(descripcion).roles(TipoRol.ADMINISTRADOR);
    }
}
