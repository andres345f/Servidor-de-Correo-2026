package com.feder.negocio.usuarios;

import static com.feder.pruebas.BaseDatosPrueba.solicitante;

import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;

/** Incorpora, dentro de la transacción de la prueba, una segunda cuenta con ADMINISTRADOR VIGENTE (invitación y aceptación). */
final class SegundoAdministrador {

    static final String CORREO = "segunda.admin@feder.example";

    private SegundoAdministrador() {
    }

    static String incorporar(ContextoNegocio ctx, ServicioRoles roles) {
        long invitacion = roles.invitar(solicitante(ApoyoPruebas.ADMIN), new ServicioRoles.DatosInvitacion(CORREO, "99000041", null,
                "Ana", "Rivero Paz", "70000141", TipoRol.ADMINISTRADOR, "Incorporación de una segunda administradora")).concesion().id();
        roles.aceptarInvitacion(solicitante(CORREO), invitacion);
        return CORREO;
    }
}
