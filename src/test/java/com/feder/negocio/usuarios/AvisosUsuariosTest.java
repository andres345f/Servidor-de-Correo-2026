package com.feder.negocio.usuarios;

import static com.feder.negocio.usuarios.ApoyoPruebas.ADMIN;
import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.ELMER;
import static com.feder.negocio.usuarios.ApoyoPruebas.LUCIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static com.feder.pruebas.BaseDatosPrueba.solicitante;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.usuarios.BandejaPruebas.Procesado;
import com.feder.negocio.usuarios.BandejaPruebas.Saliente;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Avisos por correo de CU1 a la persona afectada (R100, R095, R101), comprobados en correo_saliente tras procesar
 * el correo completo: destino = dirección invitada o registrada, nunca una elegida por el remitente.
 */
class AvisosUsuariosTest {

    private static final String ANA = "ventas.ana.rivero@feder.example";
    private static final String DESCONOCIDA = "otra.persona@feder.example";

    private ContextoNegocio ctx;
    private BandejaPruebas bandeja;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        bandeja = new BandejaPruebas(ctx);
    }

    private Procesado invitarAna() {
        return bandeja.confirmarPorCorreo(ADMIN, "INVUSU", ANA, "99000020", "", "Ana", "Rivero Paz", "70000120",
                "PERSONAL_FEDER", "Incorporación como auxiliar de ventas");
    }

    private static String concesionDe(String correo, TipoRol rol) {
        return String.valueOf(numero("SELECT ur.id FROM usuario_rol ur JOIN usuario u ON u.id = ur.usuario_id "
                + "JOIN rol r ON r.id = ur.rol_id WHERE u.correo = ? AND r.codigo = ? AND ur.estado = 'PENDIENTE'", correo, rol.name()));
    }

    @Test
    @DisplayName("R100: INVUSU envía la invitación a la dirección invitada con los asuntos exactos y el vencimiento")
    void invitacionLlegaAlCorreoInvitado() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = invitarAna();
            assertEquals(ADMIN, p.respuesta().destinatario());
            assertTrue(p.respuesta().asunto().contains("Invitación registrada"), p.respuesta()::asunto);
            String n = concesionDe(ANA, TipoRol.PERSONAL_FEDER);
            Saliente aviso = p.unicoAvisoA(ANA);
            assertEquals(1, p.avisos().size(), "Solo se avisa a la persona invitada");
            assertEquals("FEDER - Invitación de FEDER", aviso.asunto());
            assertTrue(aviso.cuerpo().contains("Para aceptar: ACEINV[\"" + n + "\"]"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Para rechazar: RECINV[\"" + n + "\",\"<motivo>\"]"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Rol: PERSONAL_FEDER"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Vence: ") && aviso.cuerpo().contains("hora de Bolivia"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("código de confirmación"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Invita: FEDER"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("R100: la invitación a una cuenta existente va a su dirección registrada")
    void invitacionACuentaExistente() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "INVUSU", MARIA, "99000006", "", "María", "Quispe Mamani",
                    "70000106", "PERSONAL_FEDER", "Apoyo en la recepción de compras");
            assertTrue(p.respuesta().asunto().contains("Invitación registrada"), p.respuesta()::cuerpo);
            Saliente aviso = p.unicoAvisoA(MARIA);
            assertTrue(aviso.cuerpo().contains("ACEINV[\"" + concesionDe(MARIA, TipoRol.PERSONAL_FEDER) + "\"]"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("Una invitación rechazada por una regla no envía ningún aviso")
    void invitacionFallidaSinAviso() {
        enTransaccionRevertida(ctx, () -> {
            // Correo nuevo con la cédula de María: la invitación se detiene (R095) y nadie recibe un aviso.
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "INVUSU", ANA, "99000006", "", "Ana", "Rivero Paz", "70000120",
                    "PERSONAL_FEDER", "Incorporación como auxiliar de ventas");
            assertTrue(p.respuesta().asunto().contains("Regla de negocio"), p.respuesta()::asunto);
            assertTrue(p.avisos().isEmpty(), () -> "No debía haber avisos: " + p.avisos());
        });
    }

    @Test
    @DisplayName("Si la operación falla después de preparar el aviso, el aviso se descarta con ella")
    void avisoDescartadoSiLaOperacionFalla() {
        enTransaccionRevertida(ctx, () -> {
            CatalogoComandos catalogo = ApoyoPruebas.catalogo(ctx);
            ServicioRoles roles = new ServicioRoles(ctx);
            // Comando de prueba: invita (lo que prepara el aviso) y luego falla una validación posterior.
            catalogo.registrar(ComandoSpec.comando("PRUINV").casoDeUso("CU1").descripcion("Invita y luego falla")
                    .parametros(ParametroSpec.texto("x", 1).conComodin()).roles(TipoRol.ADMINISTRADOR).ejemplo("PRUINV[\"*\"]")
                    .handler((p, s) -> {
                        roles.invitar(s, new ServicioRoles.DatosInvitacion(ANA, "99000020", null, "Ana", "Rivero Paz",
                                "70000120", TipoRol.PERSONAL_FEDER, "Incorporación como auxiliar de ventas"));
                        throw NegocioException.regla("Validación posterior fallida");
                    })
                    .construir());
            BandejaPruebas conFallo = new BandejaPruebas(ctx, ApoyoPruebas.despachador(ctx, catalogo));
            Procesado p = conFallo.procesarDirecto(ADMIN, "PRUINV", "*");
            assertTrue(p.respuesta().cuerpo().contains("Validación posterior fallida"), p.respuesta()::cuerpo);
            assertTrue(p.avisos().isEmpty(), () -> "El aviso debía descartarse: " + p.avisos());
            assertEquals(0, numero("SELECT count(*) FROM usuario WHERE correo = ?", ANA), "La invitación también se revirtió");
        });
    }

    @Test
    @DisplayName("CANINV avisa a la persona invitada que su invitación ya no vale")
    void cancelacionAvisaALaInvitada() {
        enTransaccionRevertida(ctx, () -> {
            new ServicioRoles(ctx).invitar(solicitante(ADMIN), new ServicioRoles.DatosInvitacion(ANA, "99000020", null, "Ana",
                    "Rivero Paz", "70000120", TipoRol.PERSONAL_FEDER, "Incorporación como auxiliar de ventas"));
            String n = concesionDe(ANA, TipoRol.PERSONAL_FEDER);
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "CANINV", ANA, "PERSONAL_FEDER", "El puesto ya fue cubierto");
            assertEquals(ADMIN, p.respuesta().destinatario());
            Saliente aviso = p.unicoAvisoA(ANA);
            assertEquals("FEDER - Invitación cancelada", aviso.asunto());
            assertTrue(aviso.cuerpo().contains("invitación número " + n), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Motivo: El puesto ya fue cubierto"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("REVROL avisa al titular con el rol y el motivo; al revocar PRODUCTOR menciona el perfil inactivo")
    void revocacionAvisaAlTitular() {
        enTransaccionRevertida(ctx, () -> {
            Procesado carla = bandeja.confirmarPorCorreo(ADMIN, "REVROL", CARLA, "PERSONAL_FEDER", "Finalización de su contrato");
            Saliente aviso = carla.unicoAvisoA(CARLA);
            assertEquals("FEDER - Rol revocado", aviso.asunto());
            assertTrue(aviso.cuerpo().contains("Rol: PERSONAL_FEDER") && aviso.cuerpo().contains("Motivo: Finalización de su contrato"),
                    aviso::cuerpo);
            Procesado maria = bandeja.confirmarPorCorreo(ADMIN, "REVROL", MARIA, "PRODUCTOR", "Revisión de calidad");
            assertTrue(maria.unicoAvisoA(MARIA).cuerpo().contains("perfil de productor quedó inactivo"));
        });
    }

    @Test
    @DisplayName("REVROL a una cuenta inactiva no le indica LISROL, que el sistema le rechazaría")
    void revocacionACuentaInactivaSinLisrol() {
        enTransaccionRevertida(ctx, () -> {
            String silvia = "silvia.anez@feder.example";
            new ServicioCuentas(ctx).desactivar(solicitante(ADMIN), silvia, "Baja solicitada por la titular");
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "REVROL", silvia, "CLIENTE", "Depuración de cuentas dadas de baja");
            Saliente aviso = p.unicoAvisoA(silvia);
            assertEquals("FEDER - Rol revocado", aviso.asunto());
            assertTrue(!aviso.cuerpo().contains("LISROL"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Su cuenta está inactiva"), aviso::cuerpo);
            // Y es cierto: desde la cuenta inactiva LISROL no se atiende.
            Procesado lisrol = bandeja.procesarDirecto(silvia, "LISROL", silvia);
            assertTrue(lisrol.respuesta().cuerpo().contains("no corresponde a una cuenta activa"), lisrol.respuesta()::cuerpo);
            Saliente activa = bandeja.confirmarPorCorreo(ADMIN, "REVROL", CARLA, "PERSONAL_FEDER", "Fin de contrato").unicoAvisoA(CARLA);
            assertTrue(activa.cuerpo().contains("LISROL[\"" + CARLA + "\"]"), activa::cuerpo);
        });
    }

    @Test
    @DisplayName("REVROL rechazado (sin concesión vigente) no avisa a nadie")
    void revocacionFallidaSinAviso() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo(ADMIN, "REVROL", ELMER, "PERSONAL_FEDER", "No corresponde");
            assertTrue(p.respuesta().asunto().contains("No encontrado"), p.respuesta()::asunto);
            assertTrue(p.avisos().isEmpty());
        });
    }

    @Test
    @DisplayName("R095: registro con una cédula existente: respuesta genérica al remitente y aviso a la cuenta registrada")
    void registroConCedulaExistente() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo(DESCONOCIDA, "REGCLI", "99000006", "", "Lucas", "Pérez Vaca", "71234567", "true");
            assertEquals(DESCONOCIDA, p.respuesta().destinatario());
            assertTrue(p.respuesta().cuerpo().startsWith(ServicioCuentas.MENSAJE_REGISTRO_GENERICO), p.respuesta()::cuerpo);
            assertTrue(p.avisosA(DESCONOCIDA).isEmpty(), "Quien se registra nunca recibe el aviso");
            Saliente aviso = p.unicoAvisoA(MARIA);
            assertEquals("FEDER - Intento de registro con sus datos", aviso.asunto());
            assertTrue(aviso.cuerpo().contains("con su cédula de identidad"), aviso::cuerpo);
            assertTrue(aviso.cuerpo().contains("Si no fue usted, ignore este mensaje"), aviso::cuerpo);
            assertEquals(0, numero("SELECT count(*) FROM usuario WHERE correo = ?", DESCONOCIDA));
        });
    }

    @Test
    @DisplayName("R095: la respuesta al remitente es idéntica exista el correo, la cédula o una cuenta inactiva")
    void respuestaGenericaNoRevelaQueExiste() {
        enTransaccionRevertida(ctx, () -> {
            Procesado porCi = bandeja.confirmarPorCorreo(DESCONOCIDA, "REGCLI", "99000006", "", "Lucas", "Pérez Vaca", "71234567", "true");
            Procesado porCorreoInactiva = bandeja.confirmarPorCorreo(LUCIA, "REGCLI", "99000032", "", "Lucía", "Fernández Ortiz",
                    "71234567", "true");
            assertEquals(porCi.respuesta().asunto(), porCorreoInactiva.respuesta().asunto());
            assertEquals(porCi.respuesta().cuerpo(), porCorreoInactiva.respuesta().cuerpo());
            // La cuenta invitada inactiva recibe el aviso en su dirección, que indica que está inactiva.
            Saliente aviso = porCorreoInactiva.unicoAvisoA(LUCIA);
            assertTrue(aviso.cuerpo().contains("con su correo,") && aviso.cuerpo().contains("está inactiva"), aviso::cuerpo);
        });
    }

    @Test
    @DisplayName("R095: con el correo de una cuenta y la cédula de otra, se avisa a cada cuenta una sola vez")
    void registroCoincideConDosCuentas() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo(LUCIA, "REGCLI", "99000009", "", "Lucía", "Fernández Ortiz", "71234567", "true");
            assertTrue(p.unicoAvisoA(LUCIA).cuerpo().contains("con su correo,"));
            assertTrue(p.unicoAvisoA(ELMER).cuerpo().contains("con su cédula de identidad"));
            assertEquals(2, p.avisos().size());
        });
    }

    @Test
    @DisplayName("R095: desde su propia cuenta activa no hay aviso a sí mismo; sí a la dueña de la cédula escrita")
    void registroDesdeCuentaPropia() {
        enTransaccionRevertida(ctx, () -> {
            Procesado propio = bandeja.confirmarPorCorreo(MARIA, "REGCLI", "99000006", "", "María", "Quispe", "71234567", "true");
            assertTrue(propio.respuesta().cuerpo().startsWith(ServicioCuentas.MENSAJE_REGISTRO_GENERICO));
            assertTrue(propio.avisos().isEmpty(), () -> "No debía avisarse a sí misma: " + propio.avisos());
            Procesado ajena = bandeja.confirmarPorCorreo(MARIA, "REGCLI", "99000009", "", "María", "Quispe", "71234567", "true");
            assertEquals(1, ajena.avisos().size());
            ajena.unicoAvisoA(ELMER);
        });
    }

    @Test
    @DisplayName("Un registro nuevo correcto no genera avisos a terceros")
    void registroCorrectoSinAvisos() {
        enTransaccionRevertida(ctx, () -> {
            Procesado p = bandeja.confirmarPorCorreo("lucas.perez@feder.example", "REGCLI", "99000031", "", "Lucas", "Pérez Vaca",
                    "71234567", "true");
            assertEquals("FEDER - Registro completado", p.respuesta().asunto());
            assertTrue(p.avisos().isEmpty());
        });
    }
}
