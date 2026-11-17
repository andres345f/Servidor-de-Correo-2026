package com.feder.negocio.reportes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.modelo.FormatoReporte;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.indicadores.ResultadosPrueba;
import com.feder.negocio.indicadores.ServicioIndicadoresReportes;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.datos.GestorTransacciones;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reportes de CU8 sobre la semilla: contenido según el rol, reconstrucción a una fecha de corte y la cadena
 * de trazabilidad de un pedido entregado. El reloj se fija en el 2026-11-15 (fecha de referencia de la semilla).
 */
class ServicioReportesTest {

    private static final LocalDate INICIO = LocalDate.of(2026, 1, 1);
    private static final LocalDate FIN = LocalDate.of(2026, 12, 31);
    private static final LocalDate HOY = LocalDate.of(2026, 11, 15);
    private static final String ADMIN = "administracion@feder.example";
    private static final String PERSONAL = "ventas.jorge.suarez@feder.example";

    private static ContextoNegocio ctx;
    private static ServicioIndicadoresReportes servicio;

    @BeforeAll
    static void preparar() {
        Clock reloj = Clock.fixed(Instant.parse("2026-11-15T16:00:00Z"), ZoneOffset.UTC);
        ctx = new ContextoNegocio(new GestorTransacciones(BaseDatosPrueba.conexion()), reloj,
                new ServicioBitacora(new BitacoraRepositorio()), new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
        servicio = new ServicioIndicadoresReportes(ctx);
    }

    // ---- Ventas ----

    @Test
    @DisplayName("Reporte de ventas del ADMINISTRADOR: cantidades, estados, encargos e importes agregados")
    void reporteVentasAdministrador() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteVentas(solicitante(ADMIN), INICIO, FIN, FormatoReporte.PDF));
        assertTrue(r.esExito());
        assertEquals(FormatoReporte.PDF, r.formatoExportacion());
        assertEquals("reporte_ventas_2026-01-01_2026-12-31", r.nombreArchivo());

        ResultadoOperacion.Tabla vendidas = tabla(r, "Cantidades entregadas");
        assertEquals(List.of("FED-ACE-020", "Aceite de totaí 20 ml", "UNIDAD", "14", "1"), fila(vendidas, "FED-ACE-020"));
        assertEquals(List.of("FED-MIE-GRA", "Miel de abeja a granel", "KILOGRAMO", "3.75", "1"), fila(vendidas, "FED-MIE-GRA"));

        ResultadoOperacion.Tabla estados = tabla(r, "Pedidos por estado");
        assertEquals(List.of("INMEDIATO", "ENTREGADO", "5"), fila(estados, "INMEDIATO", "ENTREGADO"));
        assertEquals(List.of("ENCARGO", "ESPERANDO_ABASTECIMIENTO", "1"), fila(estados, "ENCARGO"));
        assertEquals(List.of("INMEDIATO", "10", "5", "2", "0"), fila(tabla(r, "Eventos de pedidos"), "INMEDIATO"));

        List<String> encargo = fila(tabla(r, "Encargos en espera"), "PED-2026-0008");
        assertEquals("12", encargo.get(5), "Pendiente de abastecer");
        assertEquals("PRD-2026-011", encargo.get(6));

        ResultadoOperacion.Tabla entregas = tabla(r, "Entregas pendientes");
        assertEquals("Despachado sin entregar", fila(entregas, "PED-2026-0009").get(5));
        assertEquals("Pendiente antes del despacho", fila(entregas, "PED-2026-0010").get(5));

        assertEquals(List.of("FED-MIE-500 Miel de abeja multifloral 500 g", "UNIDAD", "1", "1", "1", "1", "1"),
                fila(tabla(r, "Posventa por producto"), "FED-MIE-500 Miel de abeja multifloral 500 g"));
        assertEquals(3, tabla(r, "Devoluciones con hechos").filas().size());

        assertEquals("1327.50", detalle(r, "Ingresos reconocidos (Bs)"));
        assertEquals("50.00", detalle(r, "Importe cobrado por envío, aparte (Bs)"));
        assertEquals("1920.50", detalle(r, "Cobros confirmados (Bs)"));
        assertEquals("132.00", detalle(r, "Reintegros ejecutados (Bs)"));
        assertEquals("1223.00", detalle(r, "Saldo pendiente de cobro de pedidos vigentes (Bs)"));
        assertEquals("1", fila(tabla(r, "Ranking por importe bruto"), "1").get(0));
        assertEquals(List.of("QR", "1027.50", "76.00", "0.00"), fila(tabla(r, "Cobros y reintegros por método"), "QR"));
    }

    @Test
    @DisplayName("Reporte de ventas de PERSONAL_FEDER: sin importes agregados, costos ni márgenes")
    void reporteVentasPersonal() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteVentas(solicitante(PERSONAL), INICIO, FIN, FormatoReporte.EXCEL));
        assertEquals(FormatoReporte.EXCEL, r.formatoExportacion());
        assertTrue(r.detalles().stream().noneMatch(d -> d[0].contains("(Bs)")), "Ningún importe agregado en los detalles");
        for (ResultadoOperacion.Tabla t : r.tablas()) {
            assertFalse(t.titulo().startsWith("Ranking") || t.titulo().startsWith("Cobros") || t.titulo().startsWith("Ingreso"),
                    "Tabla reservada al ADMINISTRADOR: " + t.titulo());
            assertTrue(t.columnas().stream().noneMatch(c -> c.contains("Costo") || c.contains("Margen")));
        }
        // Los importes individuales de cada pedido sí son operativos.
        assertEquals(List.of("PED-2026-0008", "ENCARGO", "ESPERANDO_ABASTECIMIENTO", "Encargo sin anticipo", "1080.00",
                "0.00", "1080.00"), fila(tabla(r, "Saldo pendiente"), "PED-2026-0008"));
        assertEquals("Cobrado por anticipado", fila(tabla(r, "Saldo pendiente"), "PED-2026-0009").get(3));
    }

    @Test
    @DisplayName("Saldos: solo un acuerdo en efectivo abierto al corte es «Efectivo contra entrega»; un efectivo RECHAZADO no")
    void efectivoContraEntregaSoloSiSigueAbierto() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            // PED-2026-0010: un intento en efectivo terminó RECHAZADO y el cliente pagó después por QR (pago 10).
            ApoyoCu45.ejecutar("INSERT INTO pago (pedido_id, metodo, monto, estado, fecha_registro, clave_idempotencia) "
                    + "VALUES (10, 'EFECTIVO', 218.00, 'RECHAZADO', TIMESTAMPTZ '2026-11-13 20:05:30+00', 'prueba-efectivo-rechazado')");
            // PED-2026-0011: su único intento PENDIENTE es en efectivo contra entrega.
            ApoyoCu45.ejecutar("UPDATE pago SET metodo = 'EFECTIVO' WHERE id = 11");
            ResultadoOperacion r = servicio.generarReporteVentas(solicitante(PERSONAL), INICIO, HOY, FormatoReporte.PDF);
            assertEquals("Cobrado por anticipado", fila(tabla(r, "Saldo pendiente"), "PED-2026-0010").get(3));
            assertEquals("Efectivo contra entrega", fila(tabla(r, "Saldo pendiente"), "PED-2026-0011").get(3));
            // PED-2026-0002 se cobró en efectivo al entregarlo (2026-06-12): al corte del 2026-06-11 seguía contra entrega.
            ResultadoOperacion junio = servicio.generarReporteVentas(solicitante(PERSONAL), INICIO, LocalDate.of(2026, 6, 11),
                    FormatoReporte.PDF);
            assertEquals("Efectivo contra entrega", fila(tabla(junio, "Saldo pendiente"), "PED-2026-0002").get(3));
        });
    }

    @Test
    @DisplayName("Los tiempos de un pedido sin su evento final se muestran abiertos y no se promedian")
    void tiemposDePreparacionYEntrega() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteVentas(solicitante(PERSONAL), LocalDate.of(2026, 11, 1), HOY, FormatoReporte.PDF));
        ResultadoOperacion.Tabla tiempos = tabla(r, "Tiempos de preparación");
        // PED-2026-0007: preparación 08:45 -> despacho 10:30 = 1.75 h; despacho -> entrega 2 días menos 40 min = 47.33 h.
        assertEquals(List.of("PED-2026-0007", "2026-11-07 08:45", "2026-11-07 10:30", "2026-11-09 09:50", "1.75", "47.33"),
                fila(tiempos, "PED-2026-0007"));
        assertEquals("abierto", fila(tiempos, "PED-2026-0009").get(5));
        assertEquals("47.33", detalle(r, "Promedio de entrega (horas, solo pedidos entregados)"));
    }

    // ---- Inventario ----

    @Test
    @DisplayName("Inventario al 2026-11-15 del ADMINISTRADOR: alertas, vencimientos, pendientes y costos")
    void reporteInventarioAdministrador() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteInventario(solicitante(ADMIN), HOY, FormatoReporte.EXCEL));
        assertEquals("reporte_inventario_2026-11-15", r.nombreArchivo());
        ResultadoOperacion.Tabla stock = tabla(r, "Stock vendible");
        assertEquals("181", fila(stock, "FED-ACE-020").get(4));
        assertEquals(List.of("70", "80", "Bajo mínimo"), fila(stock, "FED-CHO-100").subList(4, 7));
        assertEquals(List.of("0", "10", "Agotado"), fila(stock, "FED-ACE-100").subList(4, 7));
        assertTrue(stock.filas().stream().noneMatch(f -> f.get(0).equals("FED-MER-MAN")), "Inactivo y sin lotes: no se lista");
        assertEquals("Próximo a vencer", fila(tabla(r, "Avisos de vencimiento"), "LF-2026-006").get(5));

        ResultadoOperacion.Tabla pendiente = tabla(r, "Mercancía despachada pendiente");
        assertEquals(2, pendiente.filas().size());
        assertEquals("Costo (Bs)", pendiente.columnas().get(5));
        assertEquals("17629.80", detalle(r, "Valor del inventario a costo (Bs)"));
        assertEquals("97.00", detalle(r, "Costo de la mercancía pendiente de entrega (Bs)"));
        assertEquals("0.00", detalle(r, "Costo no recuperado pendiente de cancelación (Bs)"));
    }

    @Test
    @DisplayName("Inventario a una fecha pasada: saldos y reservas reconstruidos, sin costos para PERSONAL_FEDER")
    void reporteInventarioPasadoPersonal() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteInventario(solicitante("almacen.carla.mendez@feder.example"),
                        LocalDate.of(2026, 6, 30), FormatoReporte.PDF));
        ResultadoOperacion.Tabla stock = tabla(r, "Stock vendible");
        assertEquals("196", fila(stock, "FED-ACE-020").get(4), "200 recibidas - 4 vendidas; la baja es de julio");
        assertEquals("147", fila(stock, "FED-MIE-500").get(4), "Sin las 2 reservas que se crearon en noviembre");
        assertTrue(r.detalles().stream().noneMatch(d -> d[0].contains("(Bs)")));
        for (ResultadoOperacion.Tabla t : r.tablas()) {
            assertTrue(t.columnas().stream().noneMatch(c -> c.contains("Costo") || c.contains("Valor")), t.titulo());
        }
    }

    // ---- Trazabilidad ----

    @Test
    @DisplayName("Trazabilidad de PED-2026-0002: de cada reserva hasta la comunidad de origen, con la reposición")
    void trazabilidadPedidoEntregado() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteTrazabilidad(solicitante(PERSONAL), "PED-2026-0002", FormatoReporte.PDF));
        assertEquals("trazabilidad_PED-2026-0002", r.nombreArchivo());
        ResultadoOperacion.Tabla origen = tabla(r, "Origen de lo despachado");
        assertEquals(3, origen.filas().size());
        assertEquals(List.of("3", "Miel de abeja multifloral 500 g - Frasco de vidrio de 500 g (UNIDAD)", "3",
                        "2026-06-11 09:30", "LF-2026-003", "CMP-2026-002", "PROD-0002", "LP-2026-003", "PRD-2026-003",
                        "Comunidad Villa Colmenar", "Buena Vista", "Santa Cruz", "Sí"),
                fila(origen, "3"));
        assertEquals(List.of("DEV-2026-0001", "12", "SALIDA_VENTA", "LF-2026-003", "1", "2026-06-22 10:00",
                "Comunidad Villa Colmenar (Buena Vista, Santa Cruz)"), fila(tabla(r, "Reposiciones"), "DEV-2026-0001"));
        assertTrue(tabla(r, "Retornos y bajas").filas().isEmpty());
        for (ResultadoOperacion.Tabla t : r.tablas()) {
            assertTrue(t.columnas().stream().noneMatch(c -> c.contains("Bs") || c.contains("Precio")), "Sin costos: " + t.titulo());
        }
    }

    @Test
    @DisplayName("Trazabilidad: un encargo sin despacho lo indica y un código inexistente se rechaza")
    void trazabilidadSinDespachoYCodigoInexistente() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion encargo = servicio.generarReporteTrazabilidad(solicitante(ADMIN), "PED-2026-0008", FormatoReporte.EXCEL);
            assertTrue(detalle(encargo, "Despachos").contains("todavía no tiene reservas consumidas"));
            NegocioException e = assertThrows(NegocioException.class,
                    () -> servicio.generarReporteTrazabilidad(solicitante(ADMIN), "PED-2099-9999", FormatoReporte.PDF));
            assertEquals(NegocioException.Codigo.NO_ENCONTRADO, e.codigo());
            assertEquals("No existe un pedido con el código PED-2099-9999.", e.getMessage());
        });
    }

    @Test
    @DisplayName("Trazabilidad: un error de lote de origen documentado en la bitácora aparece como advertencia")
    void trazabilidadConAdvertenciaDeOrigen() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            com.feder.negocio.indicadores.SqlPrueba.ejecutar("INSERT INTO bitacora (usuario_id, fecha, accion, entidad, entidad_id, detalle) "
                    + "VALUES (1, '2026-06-20 10:00:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 3, "
                    + "'{\"operacion\": \"ERROR_LOTE_ORIGEN\", \"compra_id\": 2, \"motivo\": \"El lote correcto era otro\"}')");
            return servicio.generarReporteTrazabilidad(solicitante(ADMIN), "PED-2026-0002", FormatoReporte.PDF);
        });
        assertTrue(detalle(r, "Advertencia de origen").contains("El lote correcto era otro"));
    }

    // ---- Abastecimiento ----

    @Test
    @DisplayName("Abastecimiento 2026 del ADMINISTRADOR: líneas, participación, productores sin datos personales e importes")
    void reporteAbastecimientoAdministrador() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteAbastecimiento(solicitante(ADMIN), INICIO, FIN, FormatoReporte.EXCEL));
        ResultadoOperacion.Tabla compras = tabla(r, "Compras del período");
        assertEquals(10, compras.filas().size());
        List<String> chocolate = fila(compras, "CMP-2026-003", "2026-08-12 11:00", "PROD-0003", "RECIBIDA_PARCIAL",
                "FED-CHO-100 Chocolate artesanal 70 % cacao");
        assertEquals(List.of("120", "80", "10", "0", "30", "17.00"), chocolate.subList(7, 13));
        List<String> aceite = fila(tabla(r, "Aceptado en el período"), "FED-ACE-020 Aceite de totaí 20 ml");
        assertEquals(List.of("200", "0", "200"), aceite.subList(4, 7));
        assertEquals("100.00", fila(tabla(r, "Participación de cada productor"), "FED-ACE-020 Aceite de totaí 20 ml").get(4));

        ResultadoOperacion.Tabla productores = tabla(r, "Productores registrados");
        assertEquals(5, productores.filas().size());
        assertEquals(List.of("Solicitud 4", "Comunidad Villa Colmenar (Buena Vista)", "", "PENDIENTE", "No", "No", "No"),
                fila(productores, "Solicitud 4"));
        for (List<String> f : productores.filas()) {
            assertTrue(f.stream().noneMatch(v -> v.contains("@") || v.contains("99000") || v.contains("7000")),
                    "Sin correo, cédula ni teléfono: " + f);
        }
        assertEquals("18615.00", detalle(r, "Compras aceptadas a costo (Bs)"));
        assertEquals("21950.00", detalle(r, "Importe acordado de las compras del período (Bs)"));
        assertEquals("13905.00", detalle(r, "Importe pagado a productores en el período (Bs)"));
    }

    @Test
    @DisplayName("Abastecimiento de PERSONAL_FEDER: solo cantidades")
    void reporteAbastecimientoPersonal() {
        ResultadoOperacion r = BaseDatosPrueba.enTransaccionRevertida(ctx, () ->
                servicio.generarReporteAbastecimiento(solicitante(PERSONAL), INICIO, FIN, FormatoReporte.PDF));
        assertTrue(r.detalles().stream().noneMatch(d -> d[0].contains("(Bs)")));
        for (ResultadoOperacion.Tabla t : r.tablas()) {
            assertTrue(t.columnas().stream().noneMatch(c -> c.contains("Bs")), t.titulo());
        }
        assertEquals(5, r.tablas().size());
    }

    // ---- Permisos ----

    @Test
    @DisplayName("PRODUCTOR, CLIENTE y cuentas sin rol vigente no acceden a ningún reporte")
    void reportesSoloParaPersonalInterno() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            for (String correo : List.of("maria.quispe@feder.example", "elmer.rojas@feder.example",
                    "hugo.paniagua@feder.example", "desconocido@feder.example")) {
                Solicitante s = solicitante(correo);
                for (org.junit.jupiter.api.function.Executable reporte : List.<org.junit.jupiter.api.function.Executable>of(
                        () -> servicio.generarReporteVentas(s, INICIO, FIN, FormatoReporte.PDF),
                        () -> servicio.generarReporteInventario(s, HOY, FormatoReporte.PDF),
                        () -> servicio.generarReporteTrazabilidad(s, "PED-2026-0002", FormatoReporte.PDF),
                        () -> servicio.generarReporteAbastecimiento(s, INICIO, FIN, FormatoReporte.PDF))) {
                    NegocioException e = assertThrows(NegocioException.class, reporte, correo);
                    assertEquals(NegocioException.Codigo.SIN_PERMISO, e.codigo());
                }
            }
        });
    }

    // ---- Apoyo ----

    private static Solicitante solicitante(String correo) {
        return BaseDatosPrueba.solicitante(correo);
    }

    static ResultadoOperacion.Tabla tabla(ResultadoOperacion r, String prefijo) {
        return ResultadosPrueba.tabla(r, prefijo);
    }

    static List<String> fila(ResultadoOperacion.Tabla tabla, String... inicio) {
        return ResultadosPrueba.fila(tabla, inicio);
    }

    static String detalle(ResultadoOperacion r, String clave) {
        return ResultadosPrueba.detalle(r, clave);
    }
}
