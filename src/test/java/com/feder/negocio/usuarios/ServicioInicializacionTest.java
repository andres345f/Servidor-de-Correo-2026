package com.feder.negocio.usuarios;

import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** R099 «Inicialización segura»: la primera concesión ADMINISTRADOR, solo si no existe ninguna. */
class ServicioInicializacionTest {

    @AfterEach
    void limpiarBaseVacia() throws Exception {
        if (vaciaUsada) {
            BaseVacia.vaciar();
        }
    }

    private boolean vaciaUsada;

    private ContextoNegocio vacia() throws Exception {
        ContextoNegocio ctx = BaseVacia.contexto();
        vaciaUsada = true;
        BaseVacia.vaciar();
        return ctx;
    }

    @Test
    @DisplayName("Sobre la semilla ya existe una concesión ADMINISTRADOR: se rechaza sin crear nada")
    void rechazaSiYaEstaInicializado() {
        ContextoNegocio ctx = BaseDatosPrueba.contexto();
        enTransaccionRevertida(ctx, () -> {
            long cuentas = numero("SELECT count(*) FROM usuario");
            falla(Codigo.REGLA_DE_NEGOCIO, "ya está inicializado", () -> new ServicioInicializacion(ctx)
                    .inicializarAdministrador("nuevo.admin@feder.example", "99000050", "Nuevo", "Administrador", "70000150"));
            assertEquals(cuentas, numero("SELECT count(*) FROM usuario"));
        });
    }

    @Test
    @DisplayName("En una base sin administrador crea la cuenta activa y la concesión inicial sin asignado_por, con bitácora sin autor")
    void creaElPrimerAdministrador() throws Exception {
        ContextoNegocio ctx = vacia();
        ServicioInicializacion servicio = new ServicioInicializacion(ctx);
        enTransaccionRevertida(ctx, () -> {
            ServicioInicializacion.Inicializacion ini = servicio.inicializarAdministrador(" Administracion@Feder.Example ",
                    "99000001", "Administración", "FEDER", "70000101");
            assertEquals("administracion@feder.example", ini.cuenta().getCorreo());
            assertTrue(ini.cuenta().getActivo());
            assertEquals(1, numero("SELECT count(*) FROM usuario WHERE id = ? AND password_hash LIKE 'sin-acceso-web$%'",
                    ini.cuenta().getId()));
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id WHERE ur.id = ? "
                    + "AND r.codigo = 'ADMINISTRADOR' AND ur.estado = 'VIGENTE' AND ur.asignado_por_id IS NULL", ini.concesionId()));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'ASIGNAR_ROL' AND entidad = 'USUARIO_ROL' "
                    + "AND entidad_id = ? AND usuario_id IS NULL", ini.concesionId()));
            falla(Codigo.REGLA_DE_NEGOCIO, "ya está inicializado", () -> servicio.inicializarAdministrador(
                    "otro.admin@feder.example", "99000002", "Otro", "Administrador", "70000102"));
        });
    }

    @Test
    @DisplayName("Datos inválidos se rechazan antes de tocar la base")
    void validaDatos() throws Exception {
        ServicioInicializacion servicio = new ServicioInicializacion(vacia());
        falla(Codigo.PARAMETRO_INVALIDO, "correo", () -> servicio.inicializarAdministrador("sin-arroba", "99000001", "A", "B", "70000101"));
        falla(Codigo.PARAMETRO_INVALIDO, "cédula", () -> servicio.inicializarAdministrador("a@feder.example", "0999", "A", "B", "70000101"));
    }

    @Test
    @DisplayName("Dos instalaciones simultáneas: la segunda espera el bloqueo de la fila ROL y, al verla confirmada, se rechaza")
    void instalacionesSimultaneasSeSerializan() throws Exception {
        ContextoNegocio ctx = vacia();
        ExecutorService hilo = Executors.newSingleThreadExecutor();
        try (Connection otra = BaseVacia.conexionDirecta()) {
            otra.setAutoCommit(false);
            // Otra instalación toma el bloqueo y crea su concesión inicial sin confirmar todavía.
            otra.createStatement().execute("SELECT id FROM rol WHERE codigo = 'ADMINISTRADOR' FOR UPDATE");
            try (PreparedStatement u = otra.prepareStatement("INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, "
                    + "telefono, activo) VALUES ('primera.instalacion@feder.example', 'sin-acceso-web$x', 'Primera', 'Instalación', "
                    + "99000060, 70000160, TRUE)")) {
                u.executeUpdate();
            }
            otra.createStatement().execute("INSERT INTO usuario_rol (usuario_id, rol_id, estado, fecha_activacion) "
                    + "SELECT u.id, r.id, 'VIGENTE', now() FROM usuario u, rol r "
                    + "WHERE u.correo = 'primera.instalacion@feder.example' AND r.codigo = 'ADMINISTRADOR'");
            Future<ServicioInicializacion.Inicializacion> segunda = hilo.submit(() -> new ServicioInicializacion(ctx)
                    .inicializarAdministrador("segunda.instalacion@feder.example", "99000061", "Segunda", "Instalación", "70000161"));
            assertThrows(TimeoutException.class, () -> segunda.get(700, TimeUnit.MILLISECONDS),
                    "La segunda instalación debe esperar el bloqueo de la fila ROL de ADMINISTRADOR");
            otra.commit();
            Exception e = assertThrows(Exception.class, () -> segunda.get(10, TimeUnit.SECONDS));
            assertTrue(e.getCause() instanceof NegocioException && e.getCause().getMessage().contains("ya está inicializado"),
                    () -> "Se esperaba el rechazo por instalación ya hecha: " + e.getCause());
        } finally {
            hilo.shutdownNow();
        }
        try (Connection c = BaseVacia.conexionDirecta();
             java.sql.ResultSet r = c.createStatement().executeQuery("SELECT count(*) FROM usuario_rol")) {
            r.next();
            assertEquals(1, r.getInt(1), "Solo existe una concesión inicial");
        }
    }
}
