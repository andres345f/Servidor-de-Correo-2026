package com.feder.negocio.usuarios;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.LUCIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.SILVIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.negocio.usuarios.ApoyoPruebas.texto;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ServicioCuentasTest {

    private ContextoNegocio ctx;
    private ServicioCuentas cuentas;
    private ServicioRoles roles;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        cuentas = new ServicioCuentas(ctx);
        roles = new ServicioRoles(ctx);
    }

    private static ServicioCuentas.DatosRegistro datos(String ci, String complemento, String telefono, boolean acepto) {
        return new ServicioCuentas.DatosRegistro(ci, complemento, "Lucas", "Pérez Vaca", telefono, acepto);
    }

    @Test
    @DisplayName("R093: el registro público crea en una transacción la cuenta activa, los términos y solo CLIENTE VIGENTE")
    void registroPublicoCreaCuentaYCliente() {
        enTransaccionRevertida(ctx, () -> {
            String correo = "lucas.perez@feder.example";
            Usuario u = cuentas.registrarCliente(solicitante(correo), datos("99000031", "1a", "71234567", true)).orElseThrow();
            assertNotNull(u.getId());
            assertEquals("1A", texto("SELECT ci_complemento FROM usuario WHERE id = ?", u.getId()));
            assertEquals(1, numero("SELECT count(*) FROM usuario WHERE id = ? AND activo AND password_hash IS NOT NULL "
                    + "AND fecha_aceptacion_terminos IS NOT NULL", u.getId()));
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id WHERE ur.usuario_id = ?",
                    u.getId()), "Siempre y solo CLIENTE");
            assertEquals("CLIENTE", texto("SELECT r.codigo FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                    + "WHERE ur.usuario_id = ? AND ur.estado = 'VIGENTE' AND ur.asignado_por_id IS NULL", u.getId()));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'ASIGNAR_ROL' AND usuario_id = ? "
                    + "AND detalle ->> 'rol' = 'CLIENTE'", u.getId()));
            Solicitante ahora = solicitante(correo);
            assertTrue(ahora.estaRegistrado() && ahora.tieneRol(TipoRol.CLIENTE));
        });
    }

    @Test
    @DisplayName("R094: sin aceptar los términos no se registra la cuenta")
    void registroExigeTerminos() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.REGLA_DE_NEGOCIO, "términos", () ->
                    cuentas.registrarCliente(solicitante("nadie@feder.example"), datos("99000031", null, "71234567", false)));
            assertEquals(0, numero("SELECT count(*) FROM usuario WHERE correo = 'nadie@feder.example'"));
        });
    }

    @Test
    @DisplayName("R095: correo o cédula ya registrados no crean nada; el servicio no lanza para poder avisar a la cuenta")
    void registroDuplicadoNoCreaNada() {
        enTransaccionRevertida(ctx, () -> {
            long antes = numero("SELECT count(*) FROM usuario");
            assertTrue(cuentas.registrarCliente(solicitante(MARIA), datos("99000031", null, "71234567", true)).isEmpty());
            assertTrue(cuentas.registrarCliente(solicitante("otra.persona@feder.example"), datos("99000006", null, "71234567", true))
                    .isEmpty());
            assertTrue(cuentas.registrarCliente(solicitante(LUCIA), datos("99000032", null, "71234567", true)).isEmpty());
            assertEquals(antes, numero("SELECT count(*) FROM usuario"));
            assertEquals(0, numero("SELECT count(*) FROM usuario WHERE correo = 'otra.persona@feder.example'"));
        });
    }

    @Test
    @DisplayName("Cédula y teléfono se validan como texto: se rechazan, no se corrigen")
    void validacionDeIdentidad() {
        enTransaccionRevertida(ctx, () -> {
            Solicitante s = solicitante("lucas.perez@feder.example");
            falla(Codigo.PARAMETRO_INVALIDO, "cédula", () -> cuentas.registrarCliente(s, datos("012345", null, "71234567", true)));
            falla(Codigo.PARAMETRO_INVALIDO, "cédula", () -> cuentas.registrarCliente(s, datos("99 000 31", null, "71234567", true)));
            falla(Codigo.PARAMETRO_INVALIDO, "teléfono", () -> cuentas.registrarCliente(s, datos("99000031", null, "81234567", true)));
            falla(Codigo.PARAMETRO_INVALIDO, "complemento", () -> cuentas.registrarCliente(s, datos("99000031", "A", "71234567", true)));
        });
    }

    @Test
    @DisplayName("R097: el titular activa CLIENTE por sí mismo con los términos; no se duplica la concesión")
    void activacionVoluntariaDeCliente() {
        enTransaccionRevertida(ctx, () -> {
            Usuario carla = cuentas.activarCliente(solicitante(CARLA), true);
            assertNotNull(carla.getFechaAceptacionTerminos());
            assertTrue(solicitante(CARLA).tieneRol(TipoRol.CLIENTE));
            falla(Codigo.DUPLICADO, "VIGENTE del rol CLIENTE", () -> cuentas.activarCliente(solicitante(CARLA), true));
            falla(Codigo.REGLA_DE_NEGOCIO, "términos", () -> cuentas.activarCliente(solicitante(ADMIN), false));
        });
    }

    @Test
    @DisplayName("El titular modifica sus datos propios con registro en bitácora")
    void actualizarPerfilPropio() {
        enTransaccionRevertida(ctx, () -> {
            Usuario maria = cuentas.actualizarPerfil(solicitante(MARIA), null, null, "71112233");
            assertEquals(71112233, maria.getTelefono());
            assertEquals("70000106", texto("SELECT detalle ->> 'telefono_anterior' FROM bitacora WHERE entidad = 'USUARIO' "
                    + "AND entidad_id = ? AND accion = 'MODIFICAR'", maria.getId()));
            falla(Codigo.REGLA_DE_NEGOCIO, "iguales", () -> cuentas.actualizarPerfil(solicitante(MARIA), null, null, "71112233"));
            falla(Codigo.PARAMETRO_INVALIDO, "letra", () -> cuentas.actualizarPerfil(solicitante(MARIA), "123", null, null));
        });
    }

    @Test
    @DisplayName("Baja lógica: el ADMINISTRADOR desactiva una cuenta; la cuenta deja de ejercer permisos")
    void desactivarYReactivarCuenta() {
        enTransaccionRevertida(ctx, () -> {
            Solicitante admin = solicitante(ADMIN);
            assertFalse(cuentas.desactivar(admin, SILVIA, "Cuenta duplicada informada por la titular").getActivo());
            assertFalse(solicitante(SILVIA).estaRegistrado(), "Una cuenta inactiva se resuelve como anónima");
            falla(Codigo.ESTADO_INVALIDO, "ya está inactiva", () -> cuentas.desactivar(admin, SILVIA, "Otra vez"));
            assertTrue(cuentas.reactivar(admin, SILVIA, "La titular aclaró la situación").getActivo());
            assertEquals(2, numero("SELECT count(*) FROM bitacora WHERE entidad = 'USUARIO' AND entidad_id = 10 AND accion = 'MODIFICAR'"));
        });
    }

    @Test
    @DisplayName("Desactivar: solo ADMINISTRADOR, nunca la propia cuenta; una cuenta invitada nunca activada usa ACEINV")
    void permisosDeDesactivacion() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> cuentas.desactivar(solicitante(CARLA), SILVIA, "Motivo"));
            falla(Codigo.SIN_PERMISO, "propia cuenta", () -> cuentas.desactivar(solicitante(ADMIN), ADMIN, "Motivo"));
            falla(Codigo.REGLA_DE_NEGOCIO, "ACEINV", () -> cuentas.reactivar(solicitante(ADMIN), LUCIA, "Motivo"));
            falla(Codigo.NO_ENCONTRADO, "No existe una cuenta", () -> cuentas.desactivar(solicitante(ADMIN), "x@feder.example", "Motivo"));
        });
    }

    @Test
    @DisplayName("R102: no se desactiva la última cuenta activa con ADMINISTRADOR vigente")
    void ultimoAdministradorAlDesactivar() {
        enTransaccionRevertida(ctx, () -> {
            String segundo = SegundoAdministrador.incorporar(ctx, roles);
            Solicitante primeroDesactualizado = solicitante(ADMIN);
            // El segundo administrador desactiva al primero: queda uno, se permite.
            cuentas.desactivar(solicitante(segundo), ADMIN, "Relevo de la administración");
            // El primero, con una petición resuelta antes de su baja, intenta desactivar al único que queda.
            falla(Codigo.REGLA_DE_NEGOCIO, "última cuenta activa con ADMINISTRADOR",
                    () -> cuentas.desactivar(primeroDesactualizado, segundo, "Intento de dejar el sistema sin administrador"));
            assertEquals(1, numero("SELECT count(*) FROM usuario WHERE correo = ? AND activo", segundo));
        });
    }

    @Test
    @DisplayName("Consulta de cuentas: cada uno la suya; el ADMINISTRADOR, cualquiera y el listado completo")
    void consultaYListado() {
        enTransaccionRevertida(ctx, () -> {
            ServicioCuentas.CuentaConRoles maria = cuentas.consultar(solicitante(MARIA), MARIA);
            assertTrue(maria.rolesVigentes().contains(TipoRol.PRODUCTOR) && maria.rolesVigentes().contains(TipoRol.CLIENTE));
            falla(Codigo.SIN_PERMISO, "propia cuenta", () -> cuentas.consultar(solicitante(MARIA), SILVIA));
            assertEquals(SILVIA, cuentas.consultar(solicitante(ADMIN), SILVIA).usuario().getCorreo());
            assertEquals(14, cuentas.listar(solicitante(ADMIN), null).size());
            assertEquals(1, cuentas.listar(solicitante(ADMIN), "quispe").size());
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> cuentas.listar(solicitante(CARLA), null));
        });
    }
}
