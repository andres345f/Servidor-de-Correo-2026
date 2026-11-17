package com.feder;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.usuarios.ApoyoPruebas;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plan, fase C (2026-11-16): cada tabla tiene un listado y "*" lista todo, sin filtro, con un tope común de
 * {@link Listados#TOPE} filas. Se prueba con el catálogo completo y el despachador real, como llega un correo.
 */
class ListadosCompletosTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher despachador;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        CatalogoComandos catalogo = new CatalogoComandos();
        for (ModuloComandos modulo : Aplicacion.modulos(ctx, 30)) {
            catalogo.registrarModulo(modulo);
        }
        despachador = ApoyoPruebas.despachador(ctx, catalogo);
    }

    private ResultadoOperacion enviar(String remitente, String comando, String... valores) {
        ResultadoOperacion r = despachador.despachar(comando, List.of(valores), remitente).resultado;
        return r;
    }

    private static List<List<String>> filas(ResultadoOperacion r) {
        assertEquals(1, r.tablas().size(), "Se esperaba una sola tabla");
        return r.tablas().get(0).filas();
    }

    private static int columna(ResultadoOperacion r, String nombre) {
        int i = r.tablas().get(0).columnas().indexOf(nombre);
        assertTrue(i >= 0, "No existe la columna " + nombre + " en " + r.tablas().get(0).columnas());
        return i;
    }

    // ---------------- Paso 1: "*" en LISROL, LISBIT, LISVEN y LISMOV; LISUSU por rol ----------------

    @Test
    @DisplayName("LISROL[\"*\"]: el ADMINISTRADOR ve las concesiones de todas las cuentas, con el correo de cada una")
    void lisrolTodas() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = enviar(ADMIN, "LISROL", "*");
            assertTrue(r.esExito(), r::mensaje);
            assertEquals(numero("SELECT count(*) FROM usuario_rol"), filas(r).size());
            int cuenta = columna(r, "Cuenta");
            assertTrue(filas(r).stream().anyMatch(f -> f.get(cuenta).equals(MARIA)));
            assertFalse(enviar(MARIA, "LISROL", "*").esExito(), "Solo el ADMINISTRADOR lista todas las cuentas");
            assertTrue(enviar(MARIA, "LISROL", MARIA).esExito(), "Cada uno sigue viendo las suyas");
        });
    }

    @Test
    @DisplayName("LISBIT[\"*\",\"*\"]: toda la bitácora hasta el tope común")
    void lisbitTodo() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = enviar(ADMIN, "LISBIT", "*", "*");
            assertTrue(r.esExito(), r::mensaje);
            assertEquals(Math.min(Listados.TOPE, numero("SELECT count(*) FROM bitacora")), filas(r).size());
            assertTrue(enviar(ADMIN, "LISBIT", "*", "5").esExito(), "El número sigue funcionando");
        });
    }

    @Test
    @DisplayName("LISVEN[\"*\"]: todos los lotes de FEDER con saldo, del vencimiento más cercano al más lejano")
    void lisvenTodos() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = enviar(CARLA, "LISVEN", "*");
            assertTrue(r.esExito(), r::mensaje);
            assertEquals(numero("SELECT count(*) FROM lote_feder WHERE cantidad_disponible > 0"), filas(r).size());
            int vence = columna(r, "Vence");
            List<String> fechas = filas(r).stream().map(f -> f.get(vence)).filter(f -> !f.equals("Sin vencimiento")).toList();
            assertEquals(fechas.stream().sorted().toList(), fechas, "Ordenados por vencimiento");
            List<String> todas = filas(r).stream().map(f -> f.get(vence)).toList();
            assertEquals(fechas, todas.subList(0, fechas.size()), "Los que no vencen van al final");
            assertTrue(enviar(CARLA, "LISVEN", "30").esExito(), "Los días siguen funcionando");
        });
    }

    @Test
    @DisplayName("LISMOV[\"*\"]: movimientos de todos los lotes con su código de lote")
    void lismovTodos() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = enviar(CARLA, "LISMOV", "*");
            assertTrue(r.esExito(), r::mensaje);
            assertEquals(Math.min(Listados.TOPE, numero("SELECT count(*) FROM movimiento_inventario")), filas(r).size());
            int lote = columna(r, "Lote");
            assertTrue(filas(r).stream().allMatch(f -> f.get(lote).startsWith("LF-")));
            assertTrue(enviar(CARLA, "LISMOV", "LF-2026-001").esExito(), "Un lote sigue funcionando");
        });
    }

    @Test
    @DisplayName("LISUSU: \"*\" lista todas las cuentas; el nombre de un rol lista solo las que lo tienen vigente")
    void lisusuPorRol() {
        enTransaccionRevertida(ctx, () -> {
            assertEquals(numero("SELECT count(*) FROM usuario"), filas(enviar(ADMIN, "LISUSU", "*")).size());
            for (String rol : List.of("ADMINISTRADOR", "PERSONAL_FEDER", "CLIENTE", "PRODUCTOR")) {
                long esperadas = numero("SELECT count(DISTINCT ur.usuario_id) FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                        + "WHERE ur.estado = 'VIGENTE' AND r.codigo = ?", rol);
                assertTrue(esperadas > 0, "La semilla tiene cuentas con " + rol);
                ResultadoOperacion r = enviar(ADMIN, "LISUSU", rol.toLowerCase());
                assertEquals(esperadas, filas(r).size(), rol);
                int roles = columna(r, "Roles vigentes");
                assertTrue(filas(r).stream().allMatch(f -> f.get(roles).contains(rol)), rol);
            }
            assertEquals(filas(enviar(ADMIN, "LISUSU", "PERSONAL_FEDER")).size(),
                    filas(enviar(ADMIN, "LISUSU", "personal feder")).size(), "Con espacio vale igual");
            assertEquals(1, filas(enviar(ADMIN, "LISUSU", "quispe")).size(), "El filtro por texto sigue igual");
        });
    }

    // ---------------- Paso 2: tope común de 100 filas con aviso ----------------

    @Test
    @DisplayName("Con más de 100 registros, un listado muestra 100 y avisa que hay más; con menos, no avisa")
    void topeComunConAviso() {
        enTransaccionRevertida(ctx, () -> {
            ejecutar("INSERT INTO categoria (nombre) SELECT 'Prueba tope ' || n FROM generate_series(1, 150) AS n");
            ResultadoOperacion todas = enviar(CARLA, "LISCAT", "*");
            assertEquals(Listados.TOPE, filas(todas).size());
            assertTrue(todas.mensaje().contains("Se muestran los primeros 100 registros; hay más: use un filtro para acotar."),
                    todas::mensaje);
            ResultadoOperacion pocas = enviar(CARLA, "LISCAT", "Prueba tope 14");
            assertEquals(11, filas(pocas).size(), "Contienen el texto: Prueba tope 14 y de 140 a 149");
            assertFalse(pocas.mensaje().contains("hay más"), pocas::mensaje);
        });
    }

    // ---------------- Paso 3: un listado para cada tabla que no lo tenía; LISNOT del administrador ----------------

    @Test
    @DisplayName("Los 7 listados nuevos con \"*\" muestran todas las filas de su tabla (hasta el tope)")
    void listadosNuevosConComodin() {
        enTransaccionRevertida(ctx, () -> {
            String[][] casos = {
                    {"LISRLS", "rol"}, {"LISDCM", "detalle_compra"}, {"LISCAR", "item_carrito"},
                    {"LISDPE", "detalle_pedido"}, {"LISRES", "reserva_stock"}, {"LISHIS", "historial_pedido"},
                    {"LISFAC", "factura"}};
            for (String[] caso : casos) {
                ResultadoOperacion r = enviar(ADMIN, caso[0], "*");
                assertTrue(r.esExito(), caso[0] + ": " + r.mensaje());
                assertEquals(Math.min(Listados.TOPE, numero("SELECT count(*) FROM " + caso[1])), filas(r).size(), caso[0]);
                assertTrue(filas(r).stream().flatMap(List::stream).noneMatch("null"::equals), caso[0] + " sin celdas \"null\"");
            }
        });
    }

    @Test
    @DisplayName("Los listados nuevos filtran por su dato y avisan si el dato no existe")
    void listadosNuevosConFiltro() {
        enTransaccionRevertida(ctx, () -> {
            String pedido = ApoyoPruebas.texto("SELECT pe.codigo FROM pedido pe JOIN detalle_pedido dp ON dp.pedido_id = pe.id "
                    + "ORDER BY pe.id LIMIT 1");
            for (String comando : List.of("LISDPE", "LISHIS")) {
                ResultadoOperacion r = enviar(CARLA, comando, pedido.toLowerCase());
                assertTrue(r.esExito(), comando + ": " + r.mensaje());
                assertTrue(filas(r).size() > 0 && filas(r).stream().allMatch(f -> f.get(0).equals(pedido)), comando);
            }
            String compra = ApoyoPruebas.texto("SELECT c.codigo FROM compra_feder c JOIN detalle_compra dc ON dc.compra_id = c.id "
                    + "ORDER BY c.id LIMIT 1");
            assertEquals(numero("SELECT count(*) FROM detalle_compra dc JOIN compra_feder c ON c.id = dc.compra_id WHERE c.codigo = ?",
                    compra), filas(enviar(CARLA, "LISDCM", compra)).size());
            assertEquals(numero("SELECT count(*) FROM factura WHERE estado = 'VIGENTE'"),
                    filas(enviar(CARLA, "LISFAC", "vigente")).size());
            assertEquals(1, filas(enviar(ADMIN, "LISRLS", "cliente")).size());
            for (String comando : List.of("LISDPE", "LISRES", "LISHIS")) {
                ResultadoOperacion r = enviar(CARLA, comando, "PED-1999-0001");
                assertFalse(r.esExito(), comando);
                assertTrue(r.mensaje().contains("No existe el pedido PED-1999-0001"), comando + ": " + r.mensaje());
            }
            assertTrue(enviar(CARLA, "LISDCM", "CMP-1999-001").mensaje().contains("No existe la compra"));
            assertTrue(enviar(CARLA, "LISCAR", "nadie@feder.example").mensaje().contains("No existe la cuenta"));
        });
    }

    @Test
    @DisplayName("Permisos: los listados nuevos son del personal; LISRLS, solo del ADMINISTRADOR; un cliente no los usa")
    void listadosNuevosPermisos() {
        enTransaccionRevertida(ctx, () -> {
            for (String comando : List.of("LISDCM", "LISCAR", "LISDPE", "LISRES", "LISHIS", "LISFAC")) {
                assertTrue(enviar(CARLA, comando, "*").esExito(), "El personal usa " + comando);
                assertFalse(enviar(ApoyoPruebas.SILVIA, comando, "*").esExito(), "Un cliente no usa " + comando);
            }
            assertFalse(enviar(CARLA, "LISRLS", "*").esExito(), "LISRLS es solo del ADMINISTRADOR");
        });
    }

    @Test
    @DisplayName("LISNOT: el ADMINISTRADOR ve las notificaciones de todos con su destinatario; los demás, solo las suyas")
    void lisnotDelAdministrador() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion admin = enviar(ADMIN, "LISNOT", "*");
            assertTrue(admin.esExito(), admin::mensaje);
            assertEquals(Math.min(Listados.TOPE, numero("SELECT count(*) FROM notificacion")), filas(admin).size());
            columna(admin, "Destinatario");
            long propias = numero("SELECT count(*) FROM notificacion n JOIN usuario u ON u.id = n.usuario_id WHERE u.correo = ?",
                    CARLA);
            assertEquals(Math.min(Listados.TOPE, propias), filas(enviar(CARLA, "LISNOT", "*")).size());
        });
    }

    // ---------------- Paso 4: VERCOM, VERCAT y VERDEV ----------------

    @Test
    @DisplayName("VERCOM: el personal ve la comunidad y sus productores; un cliente, solo los datos y si está activa")
    void vercom() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion personal = enviar(CARLA, "VERCOM", "1");
            assertTrue(personal.esExito(), personal::mensaje);
            assertEquals("1", ApoyoPruebas.detalle(personal, "N.º"));
            assertEquals(numero("SELECT count(*) FROM productor WHERE comunidad_agricola_id = 1"), filas(personal).size());
            ResultadoOperacion cliente = enviar(ApoyoPruebas.SILVIA, "VERCOM", "1");
            assertTrue(cliente.esExito(), cliente::mensaje);
            assertTrue(cliente.tablas().isEmpty(), "Un cliente no ve los correos de los productores");
            ejecutar("UPDATE comunidad_agricola SET activa = FALSE WHERE id = 1");
            assertTrue(enviar(CARLA, "VERCOM", "1").esExito(), "El personal ve también las inactivas");
            assertTrue(enviar(ApoyoPruebas.SILVIA, "VERCOM", "1").mensaje().contains("No existe la comunidad 1"));
            assertTrue(enviar(CARLA, "VERCOM", "999").mensaje().contains("No existe la comunidad 999"));
        });
    }

    @Test
    @DisplayName("VERCAT: cualquiera ve una categoría activa con sus productos activos; las inactivas, solo el personal")
    void vercat() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = enviar("visitante@correo.example", "VERCAT", "miel y derivados");
            assertTrue(r.esExito(), r::mensaje);
            assertEquals(String.valueOf(numero("SELECT count(*) FROM producto p JOIN categoria c ON c.id = p.categoria_id "
                    + "WHERE c.nombre = 'Miel y derivados' AND p.activo")), ApoyoPruebas.detalle(r, "Productos activos"));
            ejecutar("UPDATE categoria SET activa = FALSE WHERE nombre = 'Harinas'");
            assertTrue(enviar("visitante@correo.example", "VERCAT", "Harinas").mensaje().contains("No existe la categoría"));
            assertTrue(enviar(CARLA, "VERCAT", "Harinas").esExito(), "El personal ve también las inactivas");
        });
    }

    @Test
    @DisplayName("VERDEV: el personal ve cualquier devolución con reembolsos y movimientos; el cliente, solo las suyas")
    void verdev() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = enviar(CARLA, "VERDEV", "dev-2026-0001");
            assertTrue(r.esExito(), r::mensaje);
            assertEquals("DEV-2026-0001", ApoyoPruebas.detalle(r, "Código"));
            assertEquals(2, r.tablas().size(), "Reembolsos y movimientos de inventario");
            assertEquals(numero("SELECT count(*) FROM movimiento_inventario m JOIN devolucion d ON d.id = m.devolucion_id "
                    + "WHERE d.codigo = 'DEV-2026-0001'"), r.tablas().get(1).filas().size());
            String duenio = ApoyoPruebas.texto("SELECT u.correo FROM devolucion d JOIN movimiento_inventario m "
                    + "ON m.id = d.movimiento_inventario_id JOIN reserva_stock rs ON rs.id = m.reserva_id "
                    + "JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id JOIN pedido pe ON pe.id = dp.pedido_id "
                    + "JOIN usuario u ON u.id = pe.usuario_id WHERE d.codigo = 'DEV-2026-0001'");
            ResultadoOperacion propia = enviar(duenio, "VERDEV", "DEV-2026-0001");
            assertTrue(propia.esExito(), propia::mensaje);
            assertEquals(1, propia.tablas().size(), "El cliente ve sus reembolsos, no los movimientos de inventario");
            String otro = duenio.equals(ApoyoPruebas.SILVIA) ? ApoyoPruebas.ELMER : ApoyoPruebas.SILVIA;
            assertTrue(enviar(otro, "VERDEV", "DEV-2026-0001").mensaje().contains("No existe la devolución"),
                    "Una devolución ajena se responde como inexistente");
        });
    }

    // ---------------- Paso 5: DESPRD ----------------

    @Test
    @DisplayName("DESPRD pide CONFIRMAR, revoca el rol PRODUCTOR como REVROL, deja el perfil inactivo y REHPRD lo revierte")
    void desprd() {
        enTransaccionRevertida(ctx, () -> {
            String sqlRol = "SELECT count(*) FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id JOIN usuario u ON u.id = ur.usuario_id "
                    + "WHERE u.correo = ? AND r.codigo = 'PRODUCTOR' AND ur.estado = 'VIGENTE'";
            assertEquals(1, numero(sqlRol, MARIA), "María es productora (PROD-0001)");
            long revocacionesAntes = numero("SELECT count(*) FROM bitacora WHERE accion = 'REVOCAR_ROL'");
            ResultadoOperacion pedido = enviar(ADMIN, "DESPRD", "PROD-0001", "Dejó de producir");
            assertEquals("Confirmación requerida", pedido.titulo(), "Modifica datos: pide CONFIRMAR");
            assertEquals(1, numero(sqlRol, MARIA), "Nada cambia antes de confirmar");
            ResultadoOperacion r = ApoyoPruebas.confirmado(despachador, ADMIN, "DESPRD", "PROD-0001", "Dejó de producir");
            assertTrue(r.esExito(), r::mensaje);
            assertEquals("Productor dado de baja", r.titulo());
            assertEquals(0, numero(sqlRol, MARIA), "Rol PRODUCTOR revocado");
            assertEquals(0, numero("SELECT count(*) FROM productor WHERE codigo_productor = 'PROD-0001' AND activo"), "Perfil inactivo");
            assertEquals(revocacionesAntes + 1, numero("SELECT count(*) FROM bitacora WHERE accion = 'REVOCAR_ROL'"),
                    "Queda en la bitácora como una revocación");
            long id = numero("SELECT id FROM productor WHERE codigo_productor = 'PROD-0001'");
            assertTrue(ApoyoPruebas.confirmado(despachador, ADMIN, "REHPRD", String.valueOf(id), "Volvió a producir").esExito());
            assertEquals(1, numero(sqlRol, MARIA), "REHPRD lo revierte");
            assertFalse(enviar(CARLA, "DESPRD", "PROD-0001", "Sin permiso").esExito(), "Solo el ADMINISTRADOR da de baja");
        });
    }

    private static void ejecutar(String sql) {
        try (java.sql.Statement s = com.feder.datos.GestorTransacciones.conexionActual().createStatement()) {
            s.executeUpdate(sql);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
