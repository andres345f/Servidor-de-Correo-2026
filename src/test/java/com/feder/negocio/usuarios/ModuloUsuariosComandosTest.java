package com.feder.negocio.usuarios;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.codigo;
import static com.feder.negocio.usuarios.ApoyoPruebas.confirmado;
import static com.feder.negocio.usuarios.ApoyoPruebas.detalle;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

/** Comandos de CU1 de extremo a extremo con el despachador real (identidad, autorización y confirmación). */
class ModuloUsuariosComandosTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher despachador;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        despachador = ApoyoPruebas.despachador(ctx);
    }

    @Test
    @DisplayName("REGCLI pide confirmación al correo que se registra y CONFIRMAR crea la cuenta de cliente")
    void registroDeClientePorCorreo() {
        enTransaccionRevertida(ctx, () -> {
            String correo = "lucas.perez@feder.example";
            ResultadoOperacion pedido = despachador.despachar("REGCLI",
                    List.of("99000031", "", "Lucas", "Pérez Vaca", "71234567", "true"), correo).resultado;
            assertEquals("Confirmación requerida", pedido.titulo());
            assertEquals(0, numero("SELECT count(*) FROM usuario WHERE correo = ?", correo), "Nada se crea antes de confirmar");
            ResultadoOperacion ajeno = despachador.despachar(ComandoDispatcher.COMANDO_CONFIRMAR, List.of(codigo(pedido)),
                    "otra.direccion@feder.example").resultado;
            assertFalse(ajeno.esExito(), "El código solo vale desde la dirección que lo solicitó");
            ResultadoOperacion r = despachador.despachar(ComandoDispatcher.COMANDO_CONFIRMAR, List.of(codigo(pedido)), correo).resultado;
            assertTrue(r.esExito(), r::mensaje);
            assertEquals("Registro completado", r.titulo());
            assertTrue(solicitante(correo).tieneRol(TipoRol.CLIENTE));
            ResultadoOperacion ver = despachador.despachar("VERUSU", List.of(correo), correo).resultado;
            assertEquals("CLIENTE", detalle(ver, "Roles vigentes"));
        });
    }

    @Test
    @DisplayName("REGCLI desde una dirección ya registrada responde con el mensaje genérico")
    void registroDuplicado() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = confirmado(despachador, MARIA, "REGCLI", "99000031", "", "María", "Quispe", "71234567", "true");
            assertFalse(r.esExito());
            assertTrue(r.mensaje().contains("No se pudo completar el registro"), r::mensaje);
        });
    }

    @Test
    @DisplayName("INVUSU y ACEINV: la persona invitada acepta desde su correo aunque su cuenta esté inactiva")
    void invitacionYAceptacionPorCorreo() {
        enTransaccionRevertida(ctx, () -> {
            String invitada = "ventas.ana.rivero@feder.example";
            ResultadoOperacion inv = confirmado(despachador, ADMIN, "INVUSU", invitada, "99000020", "", "Ana", "Rivero Paz",
                    "70000120", "PERSONAL_FEDER", "Incorporación como auxiliar de ventas");
            assertTrue(inv.esExito(), inv::mensaje);
            assertEquals("PENDIENTE", detalle(inv, "Estado"));
            assertEquals("ACEINV[\"" + detalle(inv, "Concesión") + "\"]", detalle(inv, "Para aceptar"));
            ResultadoOperacion acepto = confirmado(despachador, invitada, "ACEINV", detalle(inv, "Concesión"));
            assertTrue(acepto.esExito(), acepto::mensaje);
            assertEquals("VIGENTE", detalle(acepto, "Estado"));
            assertEquals("Sí", detalle(acepto, "Cuenta activada ahora"));
            assertTrue(solicitante(invitada).tieneRol(TipoRol.PERSONAL_FEDER));
        });
    }

    @Test
    @DisplayName("LISUSU (equivalente a LISPER) lista las cuentas sin pedir confirmación; solo ADMINISTRADOR")
    void listadoDeCuentas() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = despachador.despachar("LISUSU", List.of("*"), ADMIN).resultado;
            assertTrue(r.esExito(), r::mensaje);
            assertEquals(14, r.tablas().get(0).filas().size());
            ResultadoOperacion negado = despachador.despachar("LISUSU", List.of("*"), CARLA).resultado;
            assertEquals("Acceso denegado", negado.titulo());
            ResultadoOperacion sugerencia = despachador.despachar("LISPER", List.of("*"), ADMIN).resultado;
            assertTrue(sugerencia.mensaje().contains("LISUSU"));
        });
    }

    @Test
    @DisplayName("REVROL confirmado: la revocación surte efecto en la petición siguiente (R088)")
    void revocacionPorCorreo() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = confirmado(despachador, ADMIN, "REVROL", CARLA, "PERSONAL_FEDER", "Finalización de su contrato");
            assertTrue(r.esExito(), r::mensaje);
            ResultadoOperacion despues = despachador.despachar("LISPRD", List.of("*"), CARLA).resultado;
            assertFalse(despues.esExito(), "Carla ya no ejerce PERSONAL_FEDER");
            ResultadoOperacion propia = despachador.despachar("LISROL", List.of(CARLA), CARLA).resultado;
            assertTrue(propia.esExito(), "R092: la cuenta sin rol vigente aún consulta sus datos");
        });
    }

    @Test
    @DisplayName("LISBIT muestra la bitácora al ADMINISTRADOR y valida el límite")
    void bitacora() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = despachador.despachar("LISBIT", List.of("USUARIO_ROL", "5"), ADMIN).resultado;
            assertEquals(5, r.tablas().get(0).filas().size());
            ResultadoOperacion fuera = despachador.despachar("LISBIT", List.of("*", "500"), ADMIN).resultado;
            assertFalse(fuera.esExito());
        });
    }

    @Test
    @DisplayName("R100: el código pedido con ACEINV vale solo para esa invitación, no para la que la sustituye")
    void codigoLigadoALaInvitacion() {
        enTransaccionRevertida(ctx, () -> {
            String invitada = "ventas.ana.rivero@feder.example";
            String[] datos = {invitada, "99000020", "", "Ana", "Rivero Paz", "70000120", "PERSONAL_FEDER", "Incorporación en ventas"};
            String primera = detalle(confirmado(despachador, ADMIN, "INVUSU", datos), "Concesión");
            ResultadoOperacion pedido = despachador.despachar("ACEINV", List.of(primera), invitada).resultado;
            assertEquals("Confirmación requerida", pedido.titulo());
            assertTrue(confirmado(despachador, ADMIN, "CANINV", invitada, "PERSONAL_FEDER", "Datos de contratación incompletos").esExito());
            String segunda = detalle(confirmado(despachador, ADMIN, "INVUSU", datos), "Concesión");
            ResultadoOperacion viejo = despachador.despachar(ComandoDispatcher.COMANDO_CONFIRMAR, List.of(codigo(pedido)), invitada).resultado;
            assertFalse(viejo.esExito(), "El código de la primera invitación no acepta la segunda");
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol WHERE id = ? AND estado = 'PENDIENTE'", Long.parseLong(segunda)));
            assertTrue(confirmado(despachador, invitada, "ACEINV", segunda).esExito());
        });
    }

    @Test
    @DisplayName("CANINV de una invitación vencida responde con error y deja confirmada su anulación por vencimiento")
    void cancelarInvitacionVencidaPorCorreo() {
        ContextoNegocio tarde = ApoyoPruebas.contextoConReloj(OffsetDateTime.parse("2026-11-20T10:00:00-04:00"));
        ComandoDispatcher despachadorTarde = ApoyoPruebas.despachador(tarde);
        enTransaccionRevertida(tarde, () -> {
            ResultadoOperacion r = confirmado(despachadorTarde, ADMIN, "CANINV", ApoyoPruebas.LUCIA, "PERSONAL_FEDER",
                    "El puesto ya fue cubierto");
            assertFalse(r.esExito());
            assertEquals("Invitación vencida", r.titulo());
            assertEquals(1, numero("SELECT count(*) FROM usuario_rol WHERE id = 6 AND estado = 'ANULADA' AND revocado_por_id IS NULL"));
        });
    }
}