package com.feder.negocio.reportes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.modelo.FormatoReporte;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloAyuda;
import com.feder.negocio.comandos.ValidadorParametros;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.indicadores.ModuloIndicadores;
import com.feder.negocio.indicadores.SqlPrueba;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import com.feder.presentacion.comandos.CommandParser;
import com.feder.presentacion.comandos.ComandoAnalizado;
import com.feder.presentacion.respuesta.exportacion.FabricaExportadores;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Comandos de CU7 y CU8 de extremo a extremo con el ComandoDispatcher real: lista blanca, validación de
 * parámetros, autorización por rol vigente y ejecución directa (son de solo lectura, no piden confirmación).
 */
class ComandosIndicadoresReportesTest {

    private static final String ADMIN = "administracion@feder.example";
    private static final String PERSONAL = "almacen.carla.mendez@feder.example";

    private static ContextoNegocio ctx;
    private static CatalogoComandos catalogo;
    private static ComandoDispatcher dispatcher;

    @BeforeAll
    static void preparar() {
        ctx = BaseDatosPrueba.contexto();
        catalogo = new CatalogoComandos();
        catalogo.registrarModulo(new ModuloAyuda());
        catalogo.registrarModulo(new ModuloIndicadores(ctx));
        catalogo.registrarModulo(new ModuloReportes(ctx));
        ServicioConfirmacion confirmaciones = new ServicioConfirmacion(new ConfirmacionRepositorio(), ctx.reloj(),
                Duration.ofMinutes(15), 20);
        dispatcher = new ComandoDispatcher(catalogo, new ServicioIdentidad(new IdentidadRepositorio()), confirmaciones,
                ctx.transacciones());
    }

    @Test
    @DisplayName("Los comandos de CU7 y CU8 son de solo lectura, con roles correctos y un ejemplo válido")
    void declaracionDeLosComandos() throws Exception {
        List<ComandoSpec> cu7 = catalogo.delCasoDeUso("CU7");
        List<ComandoSpec> cu8 = catalogo.delCasoDeUso("CU8");
        assertEquals(12, cu7.size());
        assertEquals(4, cu8.size());
        CommandParser parser = new CommandParser();
        for (ComandoSpec comando : catalogo.todos()) {
            if (!cu7.contains(comando) && !cu8.contains(comando)) {
                continue;
            }
            assertFalse(comando.modificaDatos(), comando.nombre() + " no debe modificar datos");
            assertTrue(comando.nombre().matches("[A-Z]{6}"), comando.nombre());
            assertEquals(ComandoSpec.Acceso.ROLES, comando.acceso());
            Set<TipoRol> esperados = cu7.contains(comando) ? Set.of(TipoRol.ADMINISTRADOR)
                    : Set.of(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER);
            assertEquals(esperados, comando.roles(), comando.nombre());
            ComandoAnalizado ejemplo = parser.analizar(comando.ejemplo());
            assertEquals(comando.nombre(), ejemplo.nombre());
            ValidadorParametros.validar(comando, ejemplo.parametros());
        }
    }

    @Test
    @DisplayName("INDRES del ADMINISTRADOR se ejecuta sin confirmación y no deja códigos pendientes")
    void resumenDelAdministrador() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            long antes = SqlPrueba.entero("SELECT count(*) FROM confirmacion_operacion");
            ComandoDispatcher.Despacho despacho = dispatcher.despachar("INDRES", List.of("2026-01-01", "2026-12-31"), ADMIN);
            ResultadoOperacion r = despacho.resultado;
            assertTrue(r.esExito(), r.mensaje());
            assertEquals("Resumen de indicadores financieros", r.titulo());
            ResultadoOperacion.Tabla tabla = ServicioReportesTest.tabla(r, "Indicadores financieros");
            assertEquals("1327.50", ServicioReportesTest.fila(tabla, "Ingresos reconocidos").get(1));
            assertEquals("800.50", ServicioReportesTest.fila(tabla, "Costo de ventas").get(1));
            assertEquals("499.00", ServicioReportesTest.fila(tabla, "Margen bruto").get(1));
            assertEquals("18615.00", ServicioReportesTest.fila(tabla, "Compras aceptadas").get(1));
            assertEquals("17629.80", ServicioReportesTest.fila(tabla, "Valor del inventario").get(1));
            assertEquals("411.00", ServicioReportesTest.fila(tabla, "Anticipos pendientes").get(1),
                    "Al 2026-12-31 los pagos de los pedidos 9 y 10 siguen siendo anticipos");
            assertNull(r.formatoExportacion(), "Los indicadores no adjuntan archivo");
            assertEquals(antes, SqlPrueba.entero("SELECT count(*) FROM confirmacion_operacion"));
        });
    }

    @Test
    @DisplayName("PERSONAL_FEDER no ejecuta comandos de CU7 pero sí los reportes de CU8")
    void permisosPorRol() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion denegado = dispatcher.despachar("INDING", List.of("2026-01-01", "2026-12-31"), PERSONAL).resultado;
            assertFalse(denegado.esExito());
            assertTrue(denegado.mensaje().contains("Roles autorizados: [ADMINISTRADOR]"), denegado.mensaje());

            ResultadoOperacion reporte = dispatcher.despachar("REPVEN", List.of("2026-01-01", "2026-12-31", "pdf", "*"), PERSONAL).resultado;
            assertTrue(reporte.esExito(), reporte.mensaje());
            assertEquals(FormatoReporte.PDF, reporte.formatoExportacion());

            ResultadoOperacion cliente = dispatcher.despachar("REPINV", List.of("2026-11-15", "EXCEL", "*"),
                    "elmer.rojas@feder.example").resultado;
            assertFalse(cliente.esExito());
            ResultadoOperacion productor = dispatcher.despachar("REPABA", List.of("2026-01-01", "2026-12-31", "EXCEL", "*"),
                    "maria.quispe@feder.example").resultado;
            assertFalse(productor.esExito());
        });
    }

    @Test
    @DisplayName("Errores de parámetros: formato desconocido y período invertido")
    void erroresDeParametros() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion formato = dispatcher.despachar("REPVEN", List.of("2026-01-01", "2026-12-31", "WORD", "*"), ADMIN).resultado;
            assertFalse(formato.esExito());
            assertTrue(formato.mensaje().contains("debe ser uno de: PDF, EXCEL"), formato.mensaje());

            ResultadoOperacion periodo = dispatcher.despachar("INDCOB", List.of("2026-12-31", "2026-01-01"), ADMIN).resultado;
            assertFalse(periodo.esExito());
            assertTrue(periodo.mensaje().contains("es posterior a la fecha hasta"), periodo.mensaje());
        });
    }

    @Test
    @DisplayName("REPTRZ acepta el código en minúsculas y su resultado se exporta a PDF y a Excel")
    void trazabilidadExportable() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                dispatcher.despachar("REPTRZ", List.of("ped-2026-0002", "EXCEL"), PERSONAL).resultado);
        assertTrue(r.esExito(), r.mensaje());
        assertEquals("Trazabilidad del pedido PED-2026-0002", r.titulo());
        byte[] pdf = FabricaExportadores.para(FormatoReporte.PDF).exportar(r);
        assertTrue(new String(pdf, 0, 8, StandardCharsets.ISO_8859_1).startsWith("%PDF-1.4"));
        String excel = new String(FabricaExportadores.para(r.formatoExportacion()).exportar(r), StandardCharsets.UTF_8);
        assertTrue(excel.contains("Comunidad Villa Colmenar"));
    }

    @Test
    @DisplayName("HELP del caso de uso CU7 lista sus comandos con el ejemplo")
    void ayudaDelCasoDeUso() {
        ResultadoOperacion ayuda = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                dispatcher.despachar("HELP", List.of("CU7"), ADMIN).resultado);
        ResultadoOperacion.Tabla tabla = ayuda.tablas().get(0);
        assertEquals(12, tabla.filas().size());
        assertEquals("INDING[\"2026-01-01\",\"2026-12-31\"]", ServicioReportesTest.fila(tabla, "INDING").get(2));
    }

    @Test
    @DisplayName("REPVEN con filtros por correo: los aplica, informa los que usó y rechaza una clave desconocida")
    void reporteConFiltros() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = dispatcher.despachar("REPVEN",
                    List.of("2026-01-01", "2026-12-31", "EXCEL", "tipo=ENCARGO;modalidad=RECOJO"), PERSONAL).resultado;
            assertTrue(r.esExito(), r.mensaje());
            assertEquals("tipo=ENCARGO; modalidad=RECOJO", ServicioReportesTest.detalle(r, "Filtros"));
            assertEquals(List.of("ENCARGO", "ESPERANDO_ABASTECIMIENTO", "1"),
                    ServicioReportesTest.fila(ServicioReportesTest.tabla(r, "Pedidos por estado"), "ENCARGO"));

            ResultadoOperacion error = dispatcher.despachar("REPINV", List.of("2026-11-15", "PDF", "color=rojo"), PERSONAL).resultado;
            assertFalse(error.esExito());
            assertTrue(error.mensaje().contains("Filtro desconocido «color»"), error.mensaje());
        });
    }

    @Test
    @DisplayName("INDCON e INDSER del ADMINISTRADOR: conciliaciones al corte y serie mensual, sin confirmación")
    void conciliacionesYSerie() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion conciliacion = dispatcher.despachar("INDCON", List.of("2026-11-15"), ADMIN).resultado;
            assertTrue(conciliacion.esExito(), conciliacion.mensaje());
            assertEquals("Sí", ServicioReportesTest.detalle(conciliacion, "Caja conciliada"));
            assertEquals("Sí", ServicioReportesTest.detalle(conciliacion, "Inventario conciliado"));
            ResultadoOperacion serie = dispatcher.despachar("INDSER", List.of("2026-03-15", "2026-05-10"), ADMIN).resultado;
            assertTrue(serie.esExito(), serie.mensaje());
            assertEquals(3, ServicioReportesTest.tabla(serie, "Indicadores por mes").filas().size());
            ResultadoOperacion denegado = dispatcher.despachar("INDCON", List.of("2026-11-15"), PERSONAL).resultado;
            assertFalse(denegado.esExito());
        });
    }
}
