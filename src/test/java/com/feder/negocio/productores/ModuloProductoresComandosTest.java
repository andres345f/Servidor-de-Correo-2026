package com.feder.negocio.productores;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.DANIELA;
import static com.feder.negocio.usuarios.ApoyoPruebas.confirmado;
import static com.feder.negocio.usuarios.ApoyoPruebas.detalle;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.usuarios.ApoyoPruebas;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Comandos de CU2 de extremo a extremo con el despachador real. */
class ModuloProductoresComandosTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher despachador;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        despachador = ApoyoPruebas.despachador(ctx);
    }

    @Test
    @DisplayName("SOLPRD y APRPRD por correo: la solicitud de un cliente se aprueba y su cuenta ejerce PRODUCTOR")
    void solicitudYAprobacion() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion sol = confirmado(despachador, DANIELA, "SOLPRD", "3", "Cocina familiar de conservas en El Palmar");
            assertTrue(sol.esExito(), sol::mensaje);
            assertEquals("PENDIENTE", detalle(sol, "Estado de la solicitud"));
            String id = detalle(sol, "N.º de perfil");
            ResultadoOperacion apr = confirmado(despachador, ADMIN, "APRPRD", id, "Identidad verificada con el documento");
            assertTrue(apr.esExito(), apr::mensaje);
            assertEquals("APROBADA", detalle(apr, "Estado de la solicitud"));
            ResultadoOperacion lista = despachador.despachar("LISPCN", List.of("*"), DANIELA).resultado;
            assertTrue(lista.esExito(), "Con PRODUCTOR vigente ya consulta su producción: " + lista.mensaje());
        });
    }

    @Test
    @DisplayName("LISCOM y LISPRD son consultas: no piden confirmación y respetan el rol")
    void consultas() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion com = despachador.despachar("LISCOM", List.of("*"), DANIELA).resultado;
            assertEquals(3, com.tablas().get(0).filas().size());
            ResultadoOperacion prd = despachador.despachar("LISPRD", List.of("PENDIENTE"), CARLA).resultado;
            assertEquals(1, prd.tablas().get(0).filas().size());
            ResultadoOperacion negado = despachador.despachar("LISPRD", List.of("*"), DANIELA).resultado;
            assertEquals("Acceso denegado", negado.titulo());
            ResultadoOperacion anonimo = despachador.despachar("LISCOM", List.of("*"), "visitante@feder.example").resultado;
            assertFalse(anonimo.esExito());
        });
    }

    @Test
    @DisplayName("DESCOM exige ADMINISTRADOR antes de pedir confirmación")
    void desactivarComunidadSoloAdministrador() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion negado = despachador.despachar("DESCOM", List.of("3", "Fusión"), CARLA).resultado;
            assertEquals("Acceso denegado", negado.titulo());
            ResultadoOperacion r = confirmado(despachador, ADMIN, "DESCOM", "3", "La comunidad se fusionó con otra vecina");
            assertEquals("No", detalle(r, "Activa"));
        });
    }
}
