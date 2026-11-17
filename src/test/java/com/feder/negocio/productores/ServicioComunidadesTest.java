package com.feder.negocio.productores;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.DANIELA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.falla;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.negocio.usuarios.ApoyoPruebas.texto;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.ComunidadAgricola;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** R008 «Gestión y baja lógica de la comunidad». */
class ServicioComunidadesTest {

    private ContextoNegocio ctx;
    private ServicioComunidades comunidades;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        comunidades = new ServicioComunidades(ctx);
    }

    @Test
    @DisplayName("Productores y clientes ven solo las comunidades activas; el personal interno, todas")
    void listadoSegunRol() {
        enTransaccionRevertida(ctx, () -> {
            assertEquals(3, comunidades.listar(solicitante(DANIELA), null).size());
            assertEquals(3, comunidades.listar(solicitante(MARIA), null).size());
            assertEquals(4, comunidades.listar(solicitante(CARLA), null).size());
            assertEquals(1, comunidades.listar(solicitante(ADMIN), "yapacan").size());
        });
    }

    @Test
    @DisplayName("PERSONAL_FEDER registra una comunidad (departamento por defecto) con bitácora; no se duplica nombre y municipio")
    void registrarComunidad() {
        enTransaccionRevertida(ctx, () -> {
            ComunidadAgricola c = comunidades.crear(solicitante(CARLA), new ServicioComunidades.DatosComunidad(
                    "Comunidad San Antonio", "San Antonio de Lomerío", null, "Artesanas del jabón de totaí"));
            assertEquals("Santa Cruz", c.getDepartamento());
            assertTrue(c.getActiva());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE accion = 'CREAR' AND entidad = 'COMUNIDAD_AGRICOLA' "
                    + "AND entidad_id = ? AND usuario_id = 2", c.getId()));
            falla(Codigo.DUPLICADO, "Ya existe una comunidad", () -> comunidades.crear(solicitante(ADMIN),
                    new ServicioComunidades.DatosComunidad("comunidad villa colmenar", "BUENA VISTA", null, null)));
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR o PERSONAL_FEDER", () -> comunidades.crear(solicitante(MARIA),
                    new ServicioComunidades.DatosComunidad("Otra", "Montero", null, null)));
        });
    }

    @Test
    @DisplayName("R066: modificar la comunidad no altera la instantánea de origen de las producciones")
    void modificarNoTocaInstantaneas() {
        enTransaccionRevertida(ctx, () -> {
            comunidades.modificar(solicitante(CARLA), 1, new ServicioComunidades.DatosComunidad(
                    "Comunidad Totaizal Renombrada", null, null, null));
            assertEquals("Comunidad Totaizal Renombrada", texto("SELECT nombre FROM comunidad_agricola WHERE id = 1"));
            assertEquals(0, numero("SELECT count(*) FROM produccion WHERE comunidad_origen_id = 1 "
                    + "AND nombre_comunidad_origen <> 'Comunidad Totaizal de San Juan'"));
            assertEquals("Comunidad Totaizal de San Juan", texto("SELECT detalle ->> 'nombre_anterior' FROM bitacora "
                    + "WHERE entidad = 'COMUNIDAD_AGRICOLA' AND entidad_id = 1 AND accion = 'MODIFICAR' ORDER BY id DESC LIMIT 1"));
            falla(Codigo.REGLA_DE_NEGOCIO, "iguales", () -> comunidades.modificar(solicitante(CARLA), 1,
                    new ServicioComunidades.DatosComunidad("Comunidad Totaizal Renombrada", null, null, null)));
        });
    }

    @Test
    @DisplayName("Solo el ADMINISTRADOR desactiva; los productores afiliados conservan su afiliación")
    void desactivarComunidad() {
        enTransaccionRevertida(ctx, () -> {
            falla(Codigo.SIN_PERMISO, "ADMINISTRADOR", () -> comunidades.desactivar(solicitante(CARLA), 1, "Motivo"));
            assertFalse(comunidades.desactivar(solicitante(ADMIN), 1, "La comunidad se fusionó con otra").getActiva());
            assertEquals(1, numero("SELECT count(*) FROM productor WHERE comunidad_agricola_id = 1 AND activo"));
            falla(Codigo.ESTADO_INVALIDO, "ya está inactiva", () -> comunidades.desactivar(solicitante(ADMIN), 1, "Otra vez"));
            assertTrue(comunidades.reactivar(solicitante(ADMIN), 1, "Se revirtió la fusión").getActiva());
            falla(Codigo.NO_ENCONTRADO, "No existe la comunidad", () -> comunidades.desactivar(solicitante(ADMIN), 999, "Motivo"));
        });
    }
}
