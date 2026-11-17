package com.feder.negocio.usuarios;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.ELMER;
import static com.feder.negocio.usuarios.ApoyoPruebas.JORGE;
import static com.feder.negocio.usuarios.ApoyoPruebas.LUCIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.contextoConReloj;
import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.negocio.usuarios.ApoyoPruebas.texto;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.usuarios.ConcesionRepositorio.Concesion;
import com.feder.modelo.EstadoConcesion;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

class ServicioRolesTest {

    private static final String NUEVA = "ventas.ana.rivero@feder.example";
    /** Invitación sembrada de Lucía (PERSONAL_FEDER), que vence el 2026-11-19 a las 09:30 de La Paz. */
    private static final long INVITACION_LUCIA = 6L;

    private ContextoNegocio ctx;
    private ServicioRoles roles;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        roles = new ServicioRoles(ctx);
    }

    private ServicioRoles.Invitacion invitarNueva(ServicioRoles servicio) {
        return servicio.invitar(solicitante(ADMIN), new ServicioRoles.DatosInvitacion(NUEVA, "99000020", null, "Ana", "Rivero Paz",
                "70000120", TipoRol.PERSONAL_FEDER, "Incorporación como auxiliar de ventas"));
    }

    @Test
    @DisplayName("R100/R091: invitar a una persona sin cuenta crea una cuenta inactiva sin contraseña y la concesión PENDIENTE")
    void invitarPersonaNueva() {
        enTransaccionRevertida(ctx, () -> {
            ServicioRoles.Invitacion inv = invitarNueva(roles);
            assertTrue(inv.cuentaNueva());
            assertFalse(inv.cuenta().getActivo());
            assertEquals(EstadoConcesion.PENDIENTE, inv.concesion().estado());
            assertEquals(ServicioRoles.PLAZO_INVITACION, Duration.between(inv.concesion().datos().getFechaAsignacion(),
                    inv.concesion().datos().getFechaExpiracion()));
            assertEquals(1, numero("SELECT count(*) FROM usuario WHERE correo = ? AND NOT activo AND password_hash IS NULL", NUEVA));
            assertEquals("INVITAR", texto("SELECT accion FROM bitacora WHERE entidad = 'USUARIO_ROL' AND entidad_id = ? AND usuario_id = 1",
                    inv.concesion().id()));
            assertFalse(solicitante(NUEVA).estaRegistrado(), "Antes de aceptar no ejerce ningún permiso");
        });
    }

    @Test
    @DisplayName("R097/R095: solo PERSONAL_FEDER o ADMINISTRADOR por invitación; correo y cédula de cuentas distintas detienen")
    void invitacionesRechazadas() {
        enTransaccionRevertida(ctx, () -> {
            Solicitante admin = solicitante(ADMIN);
            falla(Codigo.REGLA_DE_NEGOCIO, "Por invitación solo", () -> roles.invitar(admin, new ServicioRoles.DatosInvitacion(
                    NUEVA, "99000020", null, "Ana", "Rivero", "70000120", TipoRol.CLIENTE, "Motivo")));
            falla(Codigo.REGLA_DE_NEGOCIO, "cuentas distintas", () -> roles.invitar(admin, new ServicioRoles.DatosInvitacion(
                    MARIA, "99000007", null, "Ana", "Rivero", "70000120", TipoRol.PERSONAL_FEDER, "Motivo")));
            falla(Codigo.REGLA_DE_NEGOCIO, "otro correo", () -> roles.invitar(admin, new ServicioRoles.DatosInvitacion(
                    NUEVA, "99000007", null, "Ana", "Rivero", "70000120", TipoRol.PERSONAL_FEDER, "Motivo")));
            falla(Codigo.DUPLICADO, "PENDIENTE del rol PERSONAL_FEDER", () -> roles.invitar(admin, new ServicioRoles.DatosInvitacion(
                    LUCIA, "99000004", null, "Lucía", "Fernández", "70000104", TipoRol.PERSONAL_FEDER, "Tercera invitación")));
            falla(Codigo.SIN_PERMISO, "a sí mismo", () -> roles.invitar(admin, new ServicioRoles.DatosInvitacion(
                    ADMIN, "99000001", null, "Administración", "FEDER", "70000101", TipoRol.PERSONAL_FEDER, "Motivo")));
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> roles.invitar(solicitante(CARLA), new ServicioRoles.DatosInvitacion(
                    NUEVA, "99000020", null, "Ana", "Rivero", "70000120", TipoRol.PERSONAL_FEDER, "Motivo")));
            assertEquals(0, numero("SELECT count(*) FROM usuario WHERE correo = ?", NUEVA));
        });
    }

    @Test
    @DisplayName("Invitar a una cuenta existente se dirige a esa cuenta, sin crear otra")
    void invitarCuentaExistente() {
        enTransaccionRevertida(ctx, () -> {
            ServicioRoles.Invitacion inv = roles.invitar(solicitante(ADMIN), new ServicioRoles.DatosInvitacion(JORGE, "99000003", null,
                    "Jorge", "Suárez Vaca", "70000103", TipoRol.ADMINISTRADOR, "Incorporación como administrador de ventas"));
            assertFalse(inv.cuentaNueva());
            assertEquals(3L, inv.cuenta().getId());
            ServicioRoles.Aceptacion ac = roles.aceptarInvitacion(solicitante(JORGE), inv.concesion().id());
            assertFalse(ac.cuentaActivada(), "La cuenta ya estaba activa");
            assertTrue(solicitante(JORGE).tieneRol(TipoRol.ADMINISTRADOR));
        });
    }

    @Test
    @DisplayName("R100: al aceptar desde el correo invitado la cuenta se activa y la concesión pasa a VIGENTE en la misma transacción")
    void aceptarInvitacionActivaCuenta() {
        enTransaccionRevertida(ctx, () -> {
            long invitacion = invitarNueva(roles).concesion().id();
            ServicioRoles.Aceptacion ac = roles.aceptarInvitacion(solicitante(NUEVA), invitacion);
            assertFalse(ac.vencida());
            assertTrue(ac.cuentaActivada());
            assertEquals(EstadoConcesion.VIGENTE, ac.concesion().estado());
            assertEquals(1, numero("SELECT count(*) FROM usuario WHERE correo = ? AND activo AND password_hash IS NOT NULL", NUEVA));
            Solicitante ana = solicitante(NUEVA);
            assertTrue(ana.estaRegistrado() && ana.tieneRol(TipoRol.PERSONAL_FEDER) && !ana.tieneRol(TipoRol.CLIENTE),
                    "La invitación no concede CLIENTE");
            falla(Codigo.NO_ENCONTRADO, "No hay una invitación PENDIENTE", () -> roles.aceptarInvitacion(ana, invitacion));
        });
    }

    @Test
    @DisplayName("Invitación vencida: al intentar aceptarla pasa a ANULADA sin autor y no concede nada")
    void invitacionVencida() {
        ContextoNegocio tarde = contextoConReloj(OffsetDateTime.parse("2026-11-20T10:00:00-04:00"));
        ServicioRoles servicio = new ServicioRoles(tarde);
        enTransaccionRevertida(tarde, () -> {
            ServicioRoles.Aceptacion ac = servicio.aceptarInvitacion(solicitante(LUCIA), INVITACION_LUCIA);
            assertTrue(ac.vencida());
            assertEquals(EstadoConcesion.ANULADA, ac.concesion().estado());
            assertNull(ac.concesion().datos().getRevocadoPorId());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'USUARIO_ROL' AND entidad_id = 6 "
                    + "AND usuario_id IS NULL AND detalle ->> 'estado_nuevo' = 'ANULADA'"));
            assertFalse(solicitante(LUCIA).estaRegistrado());
        });
    }

    @Test
    @DisplayName("R091/R098: con la invitación anterior vencida, una invitación nueva la anula primero y queda PENDIENTE")
    void reinvitarTrasVencimiento() {
        ContextoNegocio tarde = contextoConReloj(OffsetDateTime.parse("2026-11-20T10:00:00-04:00"));
        ServicioRoles servicio = new ServicioRoles(tarde);
        enTransaccionRevertida(tarde, () -> {
            ServicioRoles.Invitacion inv = servicio.invitar(solicitante(ADMIN), new ServicioRoles.DatosInvitacion(LUCIA, "99000004",
                    null, "Lucía", "Fernández Ortiz", "70000104", TipoRol.PERSONAL_FEDER, "Tercera invitación como responsable de logística"));
            assertEquals(EstadoConcesion.PENDIENTE, inv.concesion().estado());
            assertEquals("ANULADA", texto("SELECT estado FROM usuario_rol WHERE id = 6"));
            assertEquals(List.of(EstadoConcesion.ANULADA, EstadoConcesion.ANULADA, EstadoConcesion.PENDIENTE),
                    servicio.concesionesDe(solicitante(ADMIN), LUCIA).stream().map(Concesion::estado).toList());
        });
    }

    @Test
    @DisplayName("La invitación sembrada se acepta dentro de su plazo")
    void invitacionSembradaDentroDelPlazo() {
        ContextoNegocio aTiempo = contextoConReloj(OffsetDateTime.parse("2026-11-15T09:00:00-04:00"));
        ServicioRoles servicio = new ServicioRoles(aTiempo);
        enTransaccionRevertida(aTiempo, () -> {
            ServicioRoles.Aceptacion ac = servicio.aceptarInvitacion(solicitante(LUCIA), INVITACION_LUCIA);
            assertEquals(EstadoConcesion.VIGENTE, ac.concesion().estado());
            assertTrue(solicitante(LUCIA).tieneRol(TipoRol.PERSONAL_FEDER));
        });
    }

    @Test
    @DisplayName("PENDIENTE -> ANULADA: la persona la rechaza (queda como revocado_por) o el ADMINISTRADOR la cancela")
    void rechazarYCancelarInvitacion() {
        enTransaccionRevertida(ctx, () -> {
            long invitacion = invitarNueva(roles).concesion().id();
            ServicioRoles.Anulacion respuesta = roles.rechazarInvitacion(solicitante(NUEVA), invitacion, "No continuaré el proceso");
            assertFalse(respuesta.vencida());
            Concesion rechazada = respuesta.concesion();
            assertEquals(EstadoConcesion.ANULADA, rechazada.estado());
            assertEquals(rechazada.datos().getUsuarioId(), rechazada.datos().getRevocadoPorId());
            Concesion cancelada = roles.cancelarInvitacion(solicitante(ADMIN), LUCIA, TipoRol.PERSONAL_FEDER, "El puesto ya fue cubierto")
                    .concesion();
            assertEquals(1L, cancelada.datos().getRevocadoPorId());
            assertEquals("El puesto ya fue cubierto", cancelada.datos().getMotivoRevocacion());
            falla(Codigo.NO_ENCONTRADO, "No hay una invitación PENDIENTE",
                    () -> roles.cancelarInvitacion(solicitante(ADMIN), LUCIA, TipoRol.PERSONAL_FEDER, "Otra vez"));
        });
    }

    @Test
    @DisplayName("R101/R088: revocar cambia solo esa concesión y surte efecto en la petición siguiente")
    void revocarPersonal() {
        enTransaccionRevertida(ctx, () -> {
            Concesion c = roles.revocar(solicitante(ADMIN), JORGE, TipoRol.PERSONAL_FEDER, "Finalización de su vínculo laboral");
            assertEquals(EstadoConcesion.REVOCADA, c.estado());
            assertEquals(1L, c.datos().getRevocadoPorId());
            Solicitante jorge = solicitante(JORGE);
            assertFalse(jorge.tieneRol(TipoRol.PERSONAL_FEDER));
            assertTrue(jorge.tieneRol(TipoRol.CLIENTE), "Las demás concesiones se conservan");
            assertEquals(1, numero("SELECT count(*) FROM usuario WHERE id = 3 AND activo"));
        });
    }

    @Test
    @DisplayName("R101/R073: revocar PRODUCTOR desactiva el perfil en la misma transacción y conserva su producción")
    void revocarProductorDesactivaPerfil() {
        enTransaccionRevertida(ctx, () -> {
            long producciones = numero("SELECT count(*) FROM produccion WHERE productor_id = 1");
            roles.revocar(solicitante(ADMIN), MARIA, TipoRol.PRODUCTOR, "Incumplimiento de las condiciones de calidad");
            assertEquals(0, numero("SELECT count(*) FROM productor WHERE id = 1 AND activo"));
            assertEquals("APROBADA", texto("SELECT estado_solicitud FROM productor WHERE id = 1"));
            assertEquals(producciones, numero("SELECT count(*) FROM produccion WHERE productor_id = 1"));
            assertTrue(solicitante(MARIA).tieneRol(TipoRol.CLIENTE));
        });
    }

    @Test
    @DisplayName("Revocar: solo ADMINISTRADOR, nunca un acceso propio, y solo concesiones VIGENTE")
    void revocacionesRechazadas() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> roles.revocar(solicitante(CARLA), JORGE, TipoRol.PERSONAL_FEDER, "Motivo"));
            falla(Codigo.SIN_PERMISO, "propio acceso", () -> roles.revocar(solicitante(ADMIN), ADMIN, TipoRol.ADMINISTRADOR, "Motivo"));
            falla(Codigo.NO_ENCONTRADO, "no tiene una concesión VIGENTE", () ->
                    roles.revocar(solicitante(ADMIN), LUCIA, TipoRol.PERSONAL_FEDER, "Motivo"));
            falla(Codigo.PARAMETRO_INVALIDO, "motivo", () -> roles.revocar(solicitante(ADMIN), JORGE, TipoRol.PERSONAL_FEDER, " "));
        });
    }

    @Test
    @DisplayName("R102: no se revoca la última concesión ADMINISTRADOR de una cuenta activa")
    void ultimoAdministradorAlRevocar() {
        enTransaccionRevertida(ctx, () -> {
            String segundo = SegundoAdministrador.incorporar(ctx, roles);
            Solicitante primeroDesactualizado = solicitante(ADMIN);
            roles.revocar(solicitante(segundo), ADMIN, TipoRol.ADMINISTRADOR, "Relevo de la administración");
            falla(Codigo.REGLA_DE_NEGOCIO, "última cuenta activa con ADMINISTRADOR",
                    () -> roles.revocar(primeroDesactualizado, segundo, TipoRol.ADMINISTRADOR, "Intento de dejar el sistema sin administrador"));
        });
    }

    @Test
    @DisplayName("Historial de concesiones: el titular ve las suyas; el ADMINISTRADOR las de cualquiera")
    void historialDeConcesiones() {
        enTransaccionRevertida(ctx, () -> {
            List<Concesion> lucia = roles.concesionesDe(solicitante(ADMIN), LUCIA);
            assertEquals(List.of(EstadoConcesion.ANULADA, EstadoConcesion.PENDIENTE), lucia.stream().map(Concesion::estado).toList());
            assertEquals(2, roles.concesionesDe(solicitante(MARIA), MARIA).size());
            falla(Codigo.SIN_PERMISO, "propia cuenta", () -> roles.concesionesDe(solicitante(MARIA), JORGE));
        });
    }

    @Test
    @DisplayName("R100: el código de aceptación queda ligado a la invitación; la de otra cuenta o una inexistente no se acepta")
    void invitacionDeOtraCuenta() {
        ContextoNegocio aTiempo = contextoConReloj(OffsetDateTime.parse("2026-11-15T09:00:00-04:00"));
        ServicioRoles servicio = new ServicioRoles(aTiempo);
        enTransaccionRevertida(aTiempo, () -> {
            long deAna = invitarNueva(servicio).concesion().id();
            falla(Codigo.NO_ENCONTRADO, "No hay una invitación PENDIENTE número " + deAna,
                    () -> servicio.aceptarInvitacion(solicitante(LUCIA), deAna));
            falla(Codigo.NO_ENCONTRADO, "No hay una invitación PENDIENTE", () -> servicio.rechazarInvitacion(solicitante(LUCIA),
                    deAna, "No es mía"));
            falla(Codigo.NO_ENCONTRADO, "No hay una invitación PENDIENTE", () -> servicio.aceptarInvitacion(solicitante(LUCIA), 999_999L));
            assertEquals("PENDIENTE", texto("SELECT estado FROM usuario_rol WHERE id = ?", deAna));
            assertEquals("PENDIENTE", texto("SELECT estado FROM usuario_rol WHERE id = ?", INVITACION_LUCIA));
        });
    }

    @Test
    @DisplayName("R096/R101/R001: rechazar una invitación ya vencida la anula como vencimiento, sin revocado_por ni autor")
    void rechazarInvitacionVencida() {
        ContextoNegocio tarde = contextoConReloj(OffsetDateTime.parse("2026-11-20T10:00:00-04:00"));
        ServicioRoles servicio = new ServicioRoles(tarde);
        enTransaccionRevertida(tarde, () -> {
            ServicioRoles.Anulacion r = servicio.rechazarInvitacion(solicitante(LUCIA), INVITACION_LUCIA, "Ya no me interesa");
            assertTrue(r.vencida());
            comprobarAnuladaPorVencimiento(r.concesion());
        });
    }

    @Test
    @DisplayName("R096/R101/R001: cancelar una invitación ya vencida la anula como vencimiento, sin revocado_por ni autor")
    void cancelarInvitacionVencida() {
        ContextoNegocio tarde = contextoConReloj(OffsetDateTime.parse("2026-11-20T10:00:00-04:00"));
        ServicioRoles servicio = new ServicioRoles(tarde);
        enTransaccionRevertida(tarde, () -> {
            ServicioRoles.Anulacion r = servicio.cancelarInvitacion(solicitante(ADMIN), LUCIA, TipoRol.PERSONAL_FEDER,
                    "El puesto ya fue cubierto");
            assertTrue(r.vencida());
            comprobarAnuladaPorVencimiento(r.concesion());
        });
    }

    private static void comprobarAnuladaPorVencimiento(Concesion c) {
        assertEquals(EstadoConcesion.ANULADA, c.estado());
        assertNull(c.datos().getRevocadoPorId());
        assertEquals("Invitación vencida sin respuesta", texto("SELECT detalle ->> 'justificacion' FROM bitacora "
                + "WHERE entidad = 'USUARIO_ROL' AND entidad_id = ? AND detalle ->> 'estado_nuevo' = 'ANULADA'", c.id()));
        assertEquals(0, numero("SELECT count(*) FROM bitacora WHERE entidad = 'USUARIO_ROL' AND entidad_id = ? "
                + "AND detalle ->> 'estado_nuevo' = 'ANULADA' AND usuario_id IS NOT NULL", c.id()));
    }

    @Test
    @DisplayName("R088/R100: la baja de una cuenta anula sus invitaciones pendientes; ya no puede reactivarse sola con ACEINV")
    void bajaDeCuentaAnulaInvitaciones() {
        enTransaccionRevertida(ctx, () -> {
            long invitacion = roles.invitar(solicitante(ADMIN), new ServicioRoles.DatosInvitacion(ELMER, "99000009", null, "Elmer",
                    "Rojas Cuéllar", "70000109", TipoRol.ADMINISTRADOR, "Incorporación como administrador")).concesion().id();
            new ServicioCuentas(ctx).desactivar(solicitante(ADMIN), ELMER, "Fraude detectado");
            assertEquals("ANULADA", texto("SELECT estado FROM usuario_rol WHERE id = ?", invitacion));
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol WHERE id = ? AND revocado_por_id = 1 "
                    + "AND motivo_revocacion = 'Fraude detectado'", invitacion));
            falla(Codigo.NO_ENCONTRADO, "No hay una invitación PENDIENTE",
                    () -> roles.aceptarInvitacion(solicitante(ELMER), invitacion));
            assertEquals(0, numero("SELECT count(*) FROM usuario WHERE correo = ? AND activo", ELMER));
            assertEquals(0, numero("SELECT count(*) FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id "
                    + "WHERE ur.usuario_id = 9 AND r.codigo = 'ADMINISTRADOR' AND ur.estado = 'VIGENTE'"));
        });
    }

    @Test
    @DisplayName("R100: invitar a una cuenta desactivada es posible y aceptar la invitación nueva la reactiva")
    void invitarCuentaYaDesactivada() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioCuentas(ctx).desactivar(solicitante(ADMIN), ELMER, "Baja temporal");
            ServicioRoles.Invitacion inv = roles.invitar(solicitante(ADMIN), new ServicioRoles.DatosInvitacion(ELMER, "99000009",
                    null, "Elmer", "Rojas Cuéllar", "70000109", TipoRol.PERSONAL_FEDER, "Reincorporación como personal"));
            assertFalse(inv.cuenta().getActivo());
            ServicioRoles.Aceptacion ac = roles.aceptarInvitacion(solicitante(ELMER), inv.concesion().id());
            assertTrue(ac.cuentaActivada());
            assertTrue(solicitante(ELMER).tieneRol(TipoRol.PERSONAL_FEDER));
        });
    }
}