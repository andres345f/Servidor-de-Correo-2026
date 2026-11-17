package com.feder.negocio.usuarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ModuloAyuda;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.negocio.produccion.ModuloProduccion;
import com.feder.negocio.productores.ModuloProductores;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.function.Executable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Apoyo común de las pruebas de CU1, CU2 y CU3: despachador real, confirmaciones y consultas de verificación. */
public final class ApoyoPruebas {

    public static final String ADMIN = "administracion@feder.example";
    public static final String CARLA = "almacen.carla.mendez@feder.example";
    public static final String JORGE = "ventas.jorge.suarez@feder.example";
    public static final String LUCIA = "logistica.lucia.fernandez@feder.example";
    public static final String HUGO = "hugo.paniagua@feder.example";
    public static final String MARIA = "maria.quispe@feder.example";
    public static final String PEDRO = "pedro.chuve@feder.example";
    public static final String ROSA = "rosa.tomicha@feder.example";
    public static final String ELMER = "elmer.rojas@feder.example";
    public static final String SILVIA = "silvia.anez@feder.example";
    public static final String DANIELA = "daniela.flores@feder.example";

    private static final Pattern CODIGO = Pattern.compile("CONFIRMAR\\[\"([A-Z0-9]+)\"\\]");

    private ApoyoPruebas() {
    }

    /** Contexto con un reloj fijo, para reglas que dependen de la fecha (vencimiento de invitaciones). */
    public static ContextoNegocio contextoConReloj(OffsetDateTime instante) {
        Clock reloj = Clock.fixed(instante.toInstant(), ZoneOffset.UTC);
        return new ContextoNegocio(new GestorTransacciones(BaseDatosPrueba.conexion()), reloj,
                new ServicioBitacora(new BitacoraRepositorio()), new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
    }

    /** Catálogo con HELP y los tres módulos de CU1 a CU3, y un despachador con identidad y confirmación reales. */
    public static ComandoDispatcher despachador(ContextoNegocio ctx) {
        return despachador(ctx, catalogo(ctx));
    }

    /** Despachador real sobre un catálogo dado (por ejemplo, con un comando de prueba adicional). */
    public static ComandoDispatcher despachador(ContextoNegocio ctx, CatalogoComandos catalogo) {
        ServicioConfirmacion confirmacion = new ServicioConfirmacion(new ConfirmacionRepositorio(), ctx.reloj(),
                Duration.ofMinutes(30), 50);
        return new ComandoDispatcher(catalogo, new ServicioIdentidad(new IdentidadRepositorio()), confirmacion,
                ctx.transacciones());
    }

    public static CatalogoComandos catalogo(ContextoNegocio ctx) {
        CatalogoComandos catalogo = new CatalogoComandos();
        catalogo.registrarModulo(new ModuloAyuda());
        catalogo.registrarModulo(new ModuloUsuarios(ctx));
        catalogo.registrarModulo(new ModuloProductores(ctx));
        catalogo.registrarModulo(new ModuloProduccion(ctx));
        return catalogo;
    }

    /** Dentro de la transacción de la prueba, María (productora) recibe además ADMINISTRADOR por invitación y aceptación. */
    public static void mariaTambienAdministradora(ContextoNegocio ctx) {
        ServicioRoles roles = new ServicioRoles(ctx);
        long invitacion = roles.invitar(BaseDatosPrueba.solicitante(ADMIN), new ServicioRoles.DatosInvitacion(MARIA, "99000006", null,
                "María", "Quispe Mamani", "70000106", com.feder.modelo.TipoRol.ADMINISTRADOR, "Apoyo a la administración"))
                .concesion().id();
        roles.aceptarInvitacion(BaseDatosPrueba.solicitante(MARIA), invitacion);
    }

    /** Envía un comando de escritura: comprueba que pide confirmación y la confirma desde la misma dirección. */
    public static ResultadoOperacion confirmado(ComandoDispatcher d, String remitente, String comando, String... valores) {
        ResultadoOperacion pedido = d.despachar(comando, List.of(valores), remitente).resultado;
        assertTrue(pedido.esExito(), () -> "La solicitud debía pedir confirmación: " + pedido.mensaje());
        assertEquals("Confirmación requerida", pedido.titulo());
        return d.despachar(ComandoDispatcher.COMANDO_CONFIRMAR, List.of(codigo(pedido)), remitente).resultado;
    }

    public static String codigo(ResultadoOperacion r) {
        Matcher m = CODIGO.matcher(detalle(r, "Asunto para confirmar"));
        assertTrue(m.matches(), "La respuesta no trae el asunto de confirmación");
        return m.group(1);
    }

    public static String detalle(ResultadoOperacion r, String clave) {
        return r.detalles().stream().filter(d -> d[0].equals(clave)).map(d -> d[1]).findFirst().orElse(null);
    }

    /** Comprueba el código y el mensaje de una NegocioException. */
    public static NegocioException falla(NegocioException.Codigo codigo, String fragmento, Executable accion) {
        NegocioException e = assertThrows(NegocioException.class, accion);
        assertEquals(codigo, e.codigo(), () -> "Mensaje recibido: " + e.getMessage());
        assertTrue(e.getMessage().contains(fragmento), () -> "El mensaje «" + e.getMessage() + "» no contiene «" + fragmento + "»");
        return e;
    }

    /** Consulta de verificación sobre la transacción en curso (solo lectura). */
    public static long numero(String sql, Object... parametros) {
        Object valor = valor(sql, parametros);
        assertNotNull(valor, "La consulta no devolvió un valor: " + sql);
        return ((Number) valor).longValue();
    }

    public static String texto(String sql, Object... parametros) {
        Object valor = valor(sql, parametros);
        return valor == null ? null : valor.toString();
    }

    private static Object valor(String sql, Object... parametros) {
        try (PreparedStatement s = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            for (int i = 0; i < parametros.length; i++) {
                s.setObject(i + 1, parametros[i]);
            }
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getObject(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falló la consulta de verificación: " + sql, e);
        }
    }
}
