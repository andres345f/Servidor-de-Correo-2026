package com.feder.negocio.productores;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.DANIELA;
import static com.feder.negocio.usuarios.ApoyoPruebas.ELMER;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.PEDRO;
import static com.feder.negocio.usuarios.ApoyoPruebas.SILVIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.negocio.usuarios.ApoyoPruebas.texto;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.datos.productores.ProductorRepositorio.PerfilProductor;
import com.feder.modelo.EstadoSolicitudProductor;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.negocio.usuarios.ServicioCuentas;
import com.feder.negocio.usuarios.ServicioRoles;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Solicitud, revisión, rehabilitación y cambio de comunidad del productor (R072 a R075, R008). */
class ServicioProductoresTest {

    private ContextoNegocio ctx;
    private ServicioProductores productores;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        productores = new ServicioProductores(ctx);
    }

    @Test
    @DisplayName("R075: un CLIENTE solicita; queda PENDIENTE, inactiva y sin rol PRODUCTOR, con bitácora CREAR")
    void solicitudNueva() {
        enTransaccionRevertida(ctx, () -> {
            PerfilProductor p = productores.solicitar(solicitante(DANIELA), 2, "Colmenas familiares junto al río");
            assertEquals(EstadoSolicitudProductor.PENDIENTE, p.productor().getEstadoSolicitud());
            assertFalse(p.productor().getActivo());
            assertNull(p.productor().getCodigoProductor());
            assertFalse(solicitante(DANIELA).tieneRol(TipoRol.PRODUCTOR));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'CREAR' AND entidad = 'PRODUCTOR' "
                    + "AND entidad_id = ? AND usuario_id = 11", p.productor().getId()));
        });
    }

    @Test
    @DisplayName("Solicitudes rechazadas: sin CLIENTE, comunidad inactiva o con una solicitud ya PENDIENTE o APROBADA")
    void solicitudesRechazadas() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.SIN_PERMISO, "CLIENTE", () -> productores.solicitar(solicitante(CARLA), 2, "Taller"));
            falla(Codigo.REGLA_DE_NEGOCIO, "inactiva", () -> productores.solicitar(solicitante(DANIELA), 4, "Taller"));
            falla(Codigo.ESTADO_INVALIDO, "PENDIENTE", () -> productores.solicitar(solicitante(ELMER), 2, "Otra ubicación"));
            falla(Codigo.ESTADO_INVALIDO, "ya fue aprobada", () -> productores.solicitar(solicitante(MARIA), 1, "Taller"));
        });
    }

    @Test
    @DisplayName("Tras un rechazo el titular corrige sus datos y se reabre el mismo perfil")
    void reabrirSolicitudRechazada() {
        enTransaccionRevertida(ctx, () -> {
            PerfilProductor p = productores.solicitar(solicitante(SILVIA), 2, "Chaco familiar verificable en Buena Vista");
            assertEquals(5L, p.productor().getId());
            assertEquals(EstadoSolicitudProductor.PENDIENTE, p.productor().getEstadoSolicitud());
            assertNull(p.productor().getFechaRevision());
            assertNull(p.productor().getMotivoRechazo());
            assertEquals(2L, p.productor().getComunidadAgricolaId());
            assertEquals("RECHAZADA", texto("SELECT detalle ->> 'estado_anterior' FROM bitacora WHERE entidad = 'PRODUCTOR' "
                    + "AND entidad_id = 5 AND accion = 'CREAR' ORDER BY id DESC LIMIT 1"));
        });
    }

    @Test
    @DisplayName("R074/R075: aprobar asigna código y afiliación, activa el perfil y concede PRODUCTOR; CLIENTE se conserva")
    void aprobarSolicitud() {
        enTransaccionRevertida(ctx, () -> {
            PerfilProductor p = productores.aprobar(solicitante(ADMIN), 4, "Identidad verificada y colmenas comprobadas");
            assertEquals(EstadoSolicitudProductor.APROBADA, p.productor().getEstadoSolicitud());
            assertEquals("PROD-0004", p.productor().getCodigoProductor());
            assertEquals(FormatoNegocio.hoy(ctx.reloj()), p.productor().getFechaAfiliacion());
            assertTrue(p.productor().getActivo());
            assertTrue(solicitante(ELMER).tieneRol(TipoRol.PRODUCTOR) && solicitante(ELMER).tieneRol(TipoRol.CLIENTE));
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol WHERE usuario_id = 9 AND rol_id = 3 AND estado = 'VIGENTE' "
                    + "AND asignado_por_id = 1"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'APROBAR_SOLICITUD' AND entidad_id = 4 AND usuario_id = 1"));
            falla(Codigo.ESTADO_INVALIDO, "Solo se decide una solicitud PENDIENTE", () ->
                    productores.aprobar(solicitante(ADMIN), 4, "Otra vez"));
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> productores.rechazar(solicitante(CARLA), 4, "Motivo"));
        });
    }

    @Test
    @DisplayName("R074: nadie decide sobre su propia solicitud")
    void revisionIndependiente() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioCuentas(ctx).activarCliente(solicitante(ADMIN), true);
            PerfilProductor propio = productores.solicitar(solicitante(ADMIN), 1, "Taller de demostración");
            falla(Codigo.SIN_PERMISO, "propia solicitud", () ->
                    productores.aprobar(solicitante(ADMIN), propio.productor().getId(), "Autoaprobación"));
        });
    }

    @Test
    @DisplayName("R075: el rechazo exige motivo, no concede PRODUCTOR y queda visible para el titular")
    void rechazarSolicitud() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.PARAMETRO_INVALIDO, "motivo", () -> productores.rechazar(solicitante(ADMIN), 4, ""));
            productores.rechazar(solicitante(ADMIN), 4, "No se pudo verificar la actividad declarada");
            PerfilProductor visto = productores.consultar(solicitante(ELMER), null);
            assertEquals(EstadoSolicitudProductor.RECHAZADA, visto.productor().getEstadoSolicitud());
            assertEquals("No se pudo verificar la actividad declarada", visto.productor().getMotivoRechazo());
            assertFalse(solicitante(ELMER).tieneRol(TipoRol.PRODUCTOR));
        });
    }

    @Test
    @DisplayName("R073: tras revocar PRODUCTOR, la rehabilitación concede una concesión nueva y conserva código y afiliación")
    void rehabilitarProductor() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.ESTADO_INVALIDO, "ya está activo", () -> productores.rehabilitar(solicitante(ADMIN), 1, "Motivo"));
            new ServicioRoles(ctx).revocar(solicitante(ADMIN), MARIA, TipoRol.PRODUCTOR, "Revisión de calidad pendiente");
            PerfilProductor p = productores.rehabilitar(solicitante(ADMIN), 1, "Revisión de calidad superada");
            assertTrue(p.productor().getActivo());
            assertEquals("PROD-0001", p.productor().getCodigoProductor());
            assertEquals("2026-01-26", p.productor().getFechaAfiliacion().toString());
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol WHERE usuario_id = 6 AND rol_id = 3 AND estado = 'REVOCADA'"));
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol WHERE usuario_id = 6 AND rol_id = 3 AND estado = 'VIGENTE'"));
            falla(Codigo.ESTADO_INVALIDO, "no se rehabilita", () -> productores.rehabilitar(solicitante(ADMIN), 4, "Motivo"));
        });
    }

    @Test
    @DisplayName("R072/R066: el cambio de comunidad lo hace el ADMINISTRADOR hacia una activa y no altera la producción registrada")
    void cambiarComunidad() {
        enTransaccionRevertida(ctx, () -> {
            PerfilProductor p = productores.cambiarComunidad(solicitante(ADMIN), 2, 3, "Trasladó su apiario a El Palmar");
            assertEquals(3L, p.productor().getComunidadAgricolaId());
            assertEquals(0, numero("SELECT count(*) FROM produccion WHERE productor_id = 2 AND comunidad_origen_id <> 2"));
            assertEquals("2", texto("SELECT detalle ->> 'comunidad_anterior_id' FROM bitacora WHERE entidad = 'PRODUCTOR' "
                    + "AND entidad_id = 2 AND detalle ->> 'operacion' = 'CAMBIO_COMUNIDAD'"));
            falla(Codigo.REGLA_DE_NEGOCIO, "inactiva", () -> productores.cambiarComunidad(solicitante(ADMIN), 2, 4, "Motivo"));
            falla(Codigo.REGLA_DE_NEGOCIO, "ya pertenece", () -> productores.cambiarComunidad(solicitante(ADMIN), 2, 3, "Motivo"));
            falla(Codigo.ESTADO_INVALIDO, "APROBADA", () -> productores.cambiarComunidad(solicitante(ADMIN), 4, 1, "Motivo"));
        });
    }

    @Test
    @DisplayName("R072: el productor actualiza su ubicación de actividad; no cambia la ubicación de sus producciones")
    void actualizarUbicacion() {
        enTransaccionRevertida(ctx, () -> {
            String antes = texto("SELECT string_agg(ubicacion_produccion, '|' ORDER BY id) FROM produccion WHERE productor_id = 1");
            productores.actualizarUbicacion(solicitante(MARIA), "Nuevo taller de extracción, zona sur");
            assertEquals("Nuevo taller de extracción, zona sur", texto("SELECT ubicacion_actividad FROM productor WHERE id = 1"));
            assertEquals(antes, texto("SELECT string_agg(ubicacion_produccion, '|' ORDER BY id) FROM produccion WHERE productor_id = 1"));
            falla(Codigo.SIN_PERMISO, "PRODUCTOR", () -> productores.actualizarUbicacion(solicitante(DANIELA), "Taller"));
        });
    }

    @Test
    @DisplayName("Consulta de perfiles: el titular el suyo, el personal interno cualquiera; listado por estado")
    void consultaYListado() {
        enTransaccionRevertida(ctx, () -> {
            assertEquals("PROD-0002", productores.consultar(solicitante(CARLA), "PROD-0002").productor().getCodigoProductor());
            assertEquals(2L, productores.consultar(solicitante(PEDRO), null).productor().getId());
            assertEquals(2L, productores.consultar(solicitante(PEDRO), "2").productor().getId());
            falla(Codigo.SIN_PERMISO, "propio perfil", () -> productores.consultar(solicitante(MARIA), "PROD-0002"));
            falla(Codigo.NO_ENCONTRADO, "no tiene un perfil", () -> productores.consultar(solicitante(DANIELA), null));
            assertEquals(1, productores.listar(solicitante(CARLA), EstadoSolicitudProductor.PENDIENTE).size());
            assertEquals(5, productores.listar(solicitante(ADMIN), null).size());
            falla(Codigo.SIN_PERMISO, "consultar productores", () -> productores.listar(solicitante(MARIA), null));
        });
    }
}
