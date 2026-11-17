package com.feder.negocio.productores;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.ELMER;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.usuarios.BandejaPruebas;
import com.feder.negocio.usuarios.BandejaPruebas.Procesado;
import com.feder.negocio.usuarios.BandejaPruebas.Saliente;
import com.feder.negocio.usuarios.ServicioCuentas;
import com.feder.negocio.usuarios.ServicioRoles;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R075 y R073: la decisión sobre la solicitud de productor y la rehabilitación del perfil se comunican por correo
 * a la dirección registrada del titular, solo si la operación se confirma.
 */
class AvisosProductoresTest {

    /** Perfil 4 de la semilla: solicitud PENDIENTE de Elmer. */
    private static final String PENDIENTE = "4";

    private ContextoNegocio ctx;
    private BandejaPruebas bandeja;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        bandeja = new BandejaPruebas(ctx);
    }

    @Test
    @DisplayName("APRPRD avisa al titular con su código de productor")
    void aprobacionAvisaAlTitular() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "APRPRD", PENDIENTE, "Identidad verificada con el documento");
            assertEquals(ADMIN, p.respuesta().destinatario());
            assertTrue(p.respuesta().asunto().contains("Solicitud aprobada"), p.respuesta()::cuerpo);
            Saliente aviso = p.unicoAvisoA(ELMER);
            assertEquals(1, p.avisos().size());
            assertEquals("FEDER - Solicitud de productor aprobada", aviso.asunto());
            assertTrue(aviso.cuerpo().contains("Código de productor: PROD-0004"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("RECPRD avisa al titular con el motivo del rechazo")
    void rechazoAvisaConMotivo() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "RECPRD", PENDIENTE, "No se pudo verificar la actividad declarada");
            Saliente aviso = p.unicoAvisoA(ELMER);
            assertEquals("FEDER - Solicitud de productor rechazada", aviso.asunto());
            assertTrue(aviso.cuerpo().contains("Motivo del rechazo: No se pudo verificar la actividad declarada"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("no modifica sus roles"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("SOLPRD["), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("RECPRD a un titular sin CLIENTE vigente no le indica SOLPRD, que el sistema le negaría")
    void rechazoSinClienteNoIndicaSolprd() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioRoles(ctx).revocar(solicitante(ADMIN), ELMER, TipoRol.CLIENTE, "Revisión de la cuenta");
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "RECPRD", PENDIENTE, "No se pudo verificar la actividad declarada");
            Saliente aviso = p.unicoAvisoA(ELMER);
            assertTrue(!aviso.cuerpo().contains("SOLPRD") && !aviso.cuerpo().contains("conserva el rol CLIENTE"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("necesita el rol CLIENTE vigente"), aviso::cuerpo);
            // Lo que sí indica funciona: VERPRD desde la cuenta activa sin CLIENTE; SOLPRD se le niega.
            Procesado verprd = bandeja.procesarDirecto(ELMER, "VERPRD", "*");
            assertTrue(verprd.respuesta().cuerpo().contains("RECHAZADA"), verprd.respuesta()::cuerpo);
            Procesado solprd = bandeja.procesarDirecto(ELMER, "SOLPRD", "1", "Comunidad de origen");
            assertTrue(solprd.respuesta().asunto().contains("Acceso denegado"),
                    () -> solprd.respuesta().asunto() + "\n" + solprd.respuesta().cuerpo());
        });
    }

    @Test
    @DisplayName("RECPRD a un titular con la cuenta inactiva le indica que no puede enviar comandos")
    void rechazoACuentaInactiva() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioCuentas(ctx).desactivar(solicitante(ADMIN), ELMER, "Baja solicitada por el titular");
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "RECPRD", PENDIENTE, "No se pudo verificar la actividad declarada");
            Saliente aviso = p.unicoAvisoA(ELMER);
            assertTrue(!aviso.cuerpo().contains("SOLPRD") && !aviso.cuerpo().contains("VERPRD"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Su cuenta está inactiva"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("APRPRD no afirma que el titular conserva CLIENTE si ya no lo tiene")
    void aprobacionSinClienteNoLoAfirma() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioRoles(ctx).revocar(solicitante(ADMIN), ELMER, TipoRol.CLIENTE, "Revisión de la cuenta");
            Saliente aviso = bandeja.confirmarPorCorreo(ADMIN, "APRPRD", PENDIENTE, "Identidad verificada").unicoAvisoA(ELMER);
            assertTrue(!aviso.cuerpo().contains("conserva el rol CLIENTE"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("no modifica sus demás roles"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("REHPRD avisa al titular que vuelve a tener el rol PRODUCTOR")
    void rehabilitacionAvisaAlTitular() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioRoles(ctx).revocar(solicitante(ADMIN), MARIA, TipoRol.PRODUCTOR, "Revisión de calidad");
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "REHPRD", "1", "Se resolvió la causa de la revocación");
            Saliente aviso = p.unicoAvisoA(MARIA);
            assertEquals("FEDER - Perfil de productor rehabilitado", aviso.asunto());
            assertTrue(aviso.cuerpo().contains("Código de productor: PROD-0001"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Motivo: Se resolvió la causa de la revocación"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("Una decisión rechazada (la solicitud ya no está PENDIENTE) no avisa a nadie")
    void decisionFallidaSinAviso() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "APRPRD", "1", "Segunda aprobación indebida");
            assertTrue(p.respuesta().asunto().contains("Estado no permitido"), p.respuesta()::asunto);
            assertTrue(p.avisos().isEmpty(), () -> "No debía haber avisos: " + p.avisos());
            Procesado rehabilitarActivo = bandeja.confirmarPorCorreo(ADMIN, "REHPRD", "1", "Perfil todavía activo");
            assertTrue(rehabilitarActivo.avisos().isEmpty());
        });
    }
}
