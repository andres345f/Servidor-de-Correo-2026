package com.feder.negocio.reportes;

import com.feder.modelo.FormatoReporte;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.indicadores.ServicioIndicadoresReportes;

/**
 * Comandos de CU8 · Reportes y estadísticas. Son de solo lectura y los consultan ADMINISTRADOR y
 * PERSONAL_FEDER; el contenido de cada reporte depende del rol (PERSONAL_FEDER no ve importes agregados,
 * costos, márgenes ni el valor del inventario) y no del formato del archivo adjunto.
 *
 * Los reportes de ventas, inventario y abastecimiento reciben al final sus filtros opcionales en un solo
 * parámetro "clave=valor;clave=valor", o "*" para no filtrar.
 */
public final class ModuloReportes implements ModuloComandos {

    /** Propiedad de configuración del backend con los días de «próximo a vencer» (PRODUCTO «Stock vendible derivado»). */
    public static final String PROPIEDAD_DIAS_PROXIMO_VENCIMIENTO = "feder.inventario.dias_proximo_vencimiento";

    private static final String CASO_DE_USO = "CU8";
    private static final String DESDE = "desde";
    private static final String HASTA = "hasta";
    private static final String FORMATO = "formato";
    private static final String FILTROS = "filtros";
    private static final int LONGITUD_FILTROS = 200;

    private final ServicioIndicadoresReportes servicio;

    public ModuloReportes(ContextoNegocio ctx) {
        this(ctx, ServicioIndicadoresReportes.DIAS_PROXIMO_VENCIMIENTO_POR_DEFECTO);
    }

    /** @param diasProximoVencimiento valor de {@link #PROPIEDAD_DIAS_PROXIMO_VENCIMIENTO} en la configuración. */
    public ModuloReportes(ContextoNegocio ctx, int diasProximoVencimiento) {
        this.servicio = new ServicioIndicadoresReportes(ctx, diasProximoVencimiento);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(base("REPVEN", "Reporte de ventas y pedidos de un período, como PDF o EXCEL. Filtros: "
                        + "producto, categoria, estado, tipo, modalidad y metodo (este solo en los cobros)")
                .parametros(ParametroSpec.fecha(DESDE), ParametroSpec.fecha(HASTA), formato(), filtros())
                .ejemplo("REPVEN[\"2026-01-01\",\"2026-12-31\",\"PDF\",\"*\"]")
                .handler((p, s) -> servicio.generarReporteVentas(s, p.fecha(DESDE), p.fecha(HASTA), formato(p), filtros(p)))
                .construir());
        catalogo.registrar(base("REPINV", "Reporte de inventario de FEDER a una fecha de corte, como PDF o EXCEL. "
                        + "Filtros: producto, categoria, productor, comunidad, estado del lote y desde/hasta de los movimientos")
                .parametros(ParametroSpec.fecha("fecha_corte"), formato(), filtros())
                .ejemplo("REPINV[\"2026-11-15\",\"EXCEL\",\"*\"]")
                .handler((p, s) -> servicio.generarReporteInventario(s, p.fecha("fecha_corte"), formato(p), filtros(p)))
                .construir());
        catalogo.registrar(base("REPTRZ", "Trazabilidad de un pedido hasta la comunidad de origen, como PDF o EXCEL")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), formato())
                .ejemplo("REPTRZ[\"PED-2026-0002\",\"PDF\"]")
                .handler((p, s) -> servicio.generarReporteTrazabilidad(s, p.texto("codigo_pedido"), formato(p)))
                .construir());
        catalogo.registrar(base("REPABA", "Reporte de abastecimiento: compras, origen y productores, como PDF o EXCEL. "
                        + "Filtros: producto, categoria, productor, comunidad y estado de la compra")
                .parametros(ParametroSpec.fecha(DESDE), ParametroSpec.fecha(HASTA), formato(), filtros())
                .ejemplo("REPABA[\"2026-01-01\",\"2026-12-31\",\"EXCEL\",\"productor=PROD-0001\"]")
                .handler((p, s) -> servicio.generarReporteAbastecimiento(s, p.fecha(DESDE), p.fecha(HASTA), formato(p),
                        filtros(p)))
                .construir());
    }

    private static ParametroSpec formato() {
        return ParametroSpec.enumeracion(FORMATO, FormatoReporte.class);
    }

    private static ParametroSpec filtros() {
        return ParametroSpec.texto(FILTROS, LONGITUD_FILTROS).conComodin();
    }

    private static FormatoReporte formato(Parametros parametros) {
        return parametros.enumeracion(FORMATO, FormatoReporte.class);
    }

    /** "*" significa sin filtros; el servicio valida las claves y los valores. */
    private static String filtros(Parametros parametros) {
        return parametros.esComodin(FILTROS) ? null : parametros.texto(FILTROS);
    }

    private static ComandoSpec.Constructor base(String nombre, String descripcion) {
        return ComandoSpec.comando(nombre).casoDeUso(CASO_DE_USO).descripcion(descripcion)
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER);
    }
}
