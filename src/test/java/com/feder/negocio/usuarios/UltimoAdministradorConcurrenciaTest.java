package com.feder.negocio.usuarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * R102 con datos confirmados y dos transacciones reales: mientras otra operación tiene el bloqueo de la fila ROL
 * de ADMINISTRADOR y deja inactivo a un administrador, la operación concurrente espera y, al continuar, vuelve a
 * contar con los datos ya confirmados. Se ejecuta en la base solo con esquema, que se vacía al terminar.
 */
class UltimoAdministradorConcurrenciaTest {

    private static final String A = "primera.admin@feder.example";
    private static final String B = "segunda.admin@feder.example";

    private ContextoNegocio ctx;
    private boolean baseVaciaUsada;

    @BeforeEach
    void preparar() throws Exception {
        ctx = BaseVacia.contexto();
        baseVaciaUsada = true;
        BaseVacia.vaciar();
        new ServicioInicializacion(ctx).inicializarAdministrador(A, "99000070", "Primera", "Administradora", "70000170");
        ServicioRoles roles = new ServicioRoles(ctx);
        long invitacion = roles.invitar(resolver(A), new ServicioRoles.DatosInvitacion(B, "99000071", null, "Segunda", "Administradora",
                "70000171", TipoRol.ADMINISTRADOR, "Incorporación de una segunda administradora")).concesion().id();
        roles.aceptarInvitacion(resolver(B), invitacion);
    }

    @AfterEach
    void limpiar() throws Exception {
        // Si preparar() se omitió por una suposición fallida, esta no es la base solo con esquema: no se toca.
        if (baseVaciaUsada) {
            BaseVacia.vaciar();
        }
    }

    @Test
    @DisplayName("Desactivar cuenta: espera el bloqueo y rechaza dejar el sistema sin administrador")
    void desactivacionConcurrente() throws Exception {
        comprobarSerializacion(a -> () -> new ServicioCuentas(ctx).desactivar(a, B, "Relevo simultáneo"));
    }

    @Test
    @DisplayName("Revocar ADMINISTRADOR: espera el bloqueo y rechaza dejar el sistema sin administrador")
    void revocacionConcurrente() throws Exception {
        comprobarSerializacion(a -> () -> new ServicioRoles(ctx).revocar(a, B, TipoRol.ADMINISTRADOR, "Relevo simultáneo"));
    }

    private void comprobarSerializacion(Function<Solicitante, Callable<?>> operacionDeA) throws Exception {
        // La petición de A se resolvió cuando A todavía era administradora activa.
        Solicitante a = resolver(A);
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try (Connection otra = BaseVacia.conexionDirecta()) {
            otra.setAutoCommit(false);
            // Operación concurrente de B: toma el bloqueo y desactiva a A, todavía sin confirmar.
            otra.createStatement().execute("SELECT id FROM rol WHERE codigo = 'ADMINISTRADOR' FOR UPDATE");
            try (PreparedStatement u = otra.prepareStatement("UPDATE usuario SET activo = FALSE WHERE correo = ?")) {
                u.setString(1, A);
                u.executeUpdate();
            }
            Future<?> deA = hilo.submit(operacionDeA.apply(a));
            assertThrows(TimeoutException.class, () -> deA.get(700, TimeUnit.MILLISECONDS),
                    "La operación debe esperar el bloqueo de la fila ROL de ADMINISTRADOR");
            otra.commit();
            Exception e = assertThrows(Exception.class, () -> deA.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof NegocioException
                            && e.getCause().getMessage().contains("última cuenta activa con ADMINISTRADOR"),
                    () -> "Se esperaba el rechazo de R102: " + e.getCause());
        } finally {
            hilo.shutdownNow();
        }
        assertEquals(1, administradoresActivos(), "Queda exactamente un administrador activo");
    }

    private Solicitante resolver(String correo) {
        return ctx.transacciones().enTransaccion(() -> BaseDatosPrueba.solicitante(correo));
    }

    private static int administradoresActivos() throws Exception {
        try (Connection c = BaseVacia.conexionDirecta();
             ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM usuario_rol ur JOIN rol ro ON ro.id = ur.rol_id "
                     + "JOIN usuario u ON u.id = ur.usuario_id WHERE ro.codigo = 'ADMINISTRADOR' AND ur.estado = 'VIGENTE' AND u.activo")) {
            r.next();
            return r.getInt(1);
        }
    }
}
