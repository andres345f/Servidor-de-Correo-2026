package com.feder.negocio.inventario;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ModuloAyuda;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Utilidades de las pruebas de CU4 y CU5: consultas directas, despachador y aserciones de errores. */
public final class ApoyoCu45 {

    public static final String ADMIN = "administracion@feder.example";
    public static final String CARLA = "almacen.carla.mendez@feder.example";
    public static final String JORGE = "ventas.jorge.suarez@feder.example";
    public static final String MARIA = "maria.quispe@feder.example";
    public static final String PEDRO = "pedro.chuve@feder.example";
    public static final String ELMER = "elmer.rojas@feder.example";
    public static final String DANIELA = "daniela.flores@feder.example";
    public static final String MARCO = "marco.antelo@feder.example";
    public static final String PATRICIA = "patricia.vargas@feder.example";
    public static final String ANDRES = "andres.molina@feder.example";

    private ApoyoCu45() {
    }

    /** Valor de la primera columna de la primera fila, con la conexión de la transacción en curso. */
    public static Object valor(String sql, Object... parametros) {
        try (PreparedStatement s = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                s.setObject(i + 1, parametros[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static long numero(String sql, Object... parametros) {
        Object v = valor(sql, parametros);
        return v == null ? 0 : ((Number) v).longValue();
    }

    public static BigDecimal decimal(String sql, Object... parametros) {
        Object v = valor(sql, parametros);
        return v == null ? null : new BigDecimal(v.toString());
    }

    public static String texto(String sql, Object... parametros) {
        Object v = valor(sql, parametros);
        return v == null ? null : v.toString();
    }

    public static int ejecutar(String sql, Object... parametros) {
        return ejecutar(GestorTransacciones.conexionActual(), sql, parametros);
    }

    public static int ejecutar(Connection conexion, String sql, Object... parametros) {
        try (PreparedStatement s = conexion.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                s.setObject(i + 1, parametros[i]);
            }
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Cadena de origen de un lote de FEDER creada para una prueba: producción, lote de origen, compra, línea y lote. */
    public record LoteFixture(long produccionId, long loteOrigenId, long compraId, long detalleId, long loteFederId) {
    }

    /**
     * Crea con SQL directo (sin bitácora ni movimientos) un lote de FEDER DISPONIBLE del productor 1
     * (comunidad 1), para preparar escenarios de FEFO y de concurrencia.
     */
    public static LoteFixture crearLote(Connection c, String sufijo, long productoId, LocalDate vence, BigDecimal cantidad) {
        long produccion = insertarId(c, "INSERT INTO produccion (productor_id, producto_id, comunidad_origen_id, codigo, fecha_inicio, "
                + "estado, nombre_comunidad_origen, municipio_origen, departamento_origen) VALUES (1, ?, 1, ?, DATE '2026-09-01', "
                + "'TERMINADA', 'Comunidad Totaizal de San Juan', 'San José de Chiquitos', 'Santa Cruz') RETURNING id",
                productoId, "PRD-T-" + sufijo);
        long origen = insertarId(c, "INSERT INTO lote_produccion (produccion_id, codigo_lote, fecha_elaboracion, fecha_vencimiento, "
                + "cantidad_producida, cantidad_ofrecida, precio_ofrecido, estado) VALUES (?, ?, DATE '2026-10-01', ?, ?, ?, 10.00, "
                + "'AGOTADO') RETURNING id", produccion, "LP-T-" + sufijo, vence == null ? null : java.sql.Date.valueOf(vence),
                cantidad, cantidad);
        long compra = insertarId(c, "INSERT INTO compra_feder (productor_id, codigo, fecha_compra, estado, total) "
                + "VALUES (1, ?, now(), 'RECIBIDA', round(? * 10.00, 2)) RETURNING id", "CMP-T-" + sufijo, cantidad);
        long detalle = insertarId(c, "INSERT INTO detalle_compra (compra_id, lote_origen_id, cantidad_comprada, precio_unitario, "
                + "cantidad_recibida) VALUES (?, ?, ?, 10.00, ?) RETURNING id", compra, origen, cantidad, cantidad);
        long lote = insertarId(c, "INSERT INTO lote_feder (detalle_compra_id, codigo, fecha_ingreso, cantidad_disponible, estado) "
                + "VALUES (?, ?, now(), ?, 'DISPONIBLE') RETURNING id", detalle, "LF-T-" + sufijo, cantidad);
        return new LoteFixture(produccion, origen, compra, detalle, lote);
    }

    public static long insertarId(Connection c, String sql, Object... parametros) {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                s.setObject(i + 1, parametros[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void assertIgual(String esperado, BigDecimal real) {
        assertEquals(0, new BigDecimal(esperado).compareTo(real), "esperado " + esperado + " y se obtuvo " + real);
    }

    /** Ejecuta y exige una NegocioException del código indicado cuyo mensaje contenga el fragmento. */
    public static NegocioException assertNegocio(NegocioException.Codigo codigo, String fragmento, Executable accion) {
        NegocioException e = assertThrows(NegocioException.class, accion);
        assertEquals(codigo, e.codigo(), "código del error: " + e.getMessage());
        assertTrue(e.getMessage().contains(fragmento), "mensaje: " + e.getMessage());
        return e;
    }

    /** Despachador real con identidad y confirmación por correo, sobre los módulos indicados (más HELP). */
    public static ComandoDispatcher despachador(ContextoNegocio ctx, ModuloComandos... modulos) {
        return new ComandoDispatcher(catalogo(modulos), new ServicioIdentidad(new IdentidadRepositorio()),
                new ServicioConfirmacion(new ConfirmacionRepositorio(), Clock.systemUTC(), Duration.ofMinutes(30), 100),
                ctx.transacciones());
    }

    public static CatalogoComandos catalogo(ModuloComandos... modulos) {
        CatalogoComandos catalogo = new CatalogoComandos();
        catalogo.registrarModulo(new ModuloAyuda());
        for (ModuloComandos m : modulos) {
            catalogo.registrarModulo(m);
        }
        return catalogo;
    }

    public static String detalle(ResultadoOperacion r, String clave) {
        return r.detalles().stream().filter(d -> d[0].equals(clave)).map(d -> d[1]).findFirst()
                .orElseThrow(() -> new AssertionError("La respuesta no tiene el detalle " + clave + ": " + r.titulo()));
    }

    /** Envía el comando, comprueba que pide confirmación y lo confirma con el código recibido. */
    public static ResultadoOperacion confirmarComando(ComandoDispatcher d, String comando, List<String> valores, String correo) {
        ResultadoOperacion pedido = d.despachar(comando, valores, correo).resultado;
        assertEquals("Confirmación requerida", pedido.titulo(), "el comando " + comando + " debe pedir confirmación: " + pedido.mensaje());
        String asunto = detalle(pedido, "Asunto para confirmar");
        String codigo = asunto.substring(asunto.indexOf("[\"") + 2, asunto.indexOf("\"]"));
        return d.despachar(ComandoDispatcher.COMANDO_CONFIRMAR, List.of(codigo), correo).resultado;
    }
}
