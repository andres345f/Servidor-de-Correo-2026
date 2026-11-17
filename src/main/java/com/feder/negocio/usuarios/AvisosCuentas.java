package com.feder.negocio.usuarios;

import com.feder.datos.usuarios.ConcesionRepositorio.Concesion;
import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.ResultadoOperacion;

/**
 * Avisos por correo de CU1 a la persona afectada por una operación que ella no envió: la invitación (R100),
 * su cancelación, la revocación de un rol (R101) y el intento de registro con datos de una cuenta existente (R095).
 *
 * Se preparan dentro de la transacción de la operación: si esta se revierte, el aviso no se envía.
 * El destino es siempre la dirección registrada de la cuenta, nunca una elegida por el remitente.
 */
final class AvisosCuentas {

    private static final String ZONA = " (hora de Bolivia)";

    private final ContextoNegocio ctx;

    AvisosCuentas(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    /** R100: la invitación llega al correo invitado con los asuntos exactos para aceptarla o rechazarla. */
    void invitacion(Usuario cuenta, Concesion invitacion) {
        long n = invitacion.id();
        String mensaje = "FEDER le invita a incorporarse con el rol " + invitacion.rol() + ". Para aceptar, envíe un correo "
                + "nuevo desde esta dirección (" + cuenta.getCorreo() + ") a la dirección de FEDER de la que recibió este "
                + "mensaje, con el asunto exacto indicado en «Para aceptar», antes del vencimiento. El sistema le responderá "
                + "con un código de confirmación de un solo uso: envíelo con CONFIRMAR[\"<código>\"] para completar la "
                + "aceptación. Si no desea incorporarse, use el asunto de «Para rechazar» con su motivo. Si no reconoce "
                + "esta invitación, ignore este mensaje: vencerá sin efecto.";
        ResultadoOperacion aviso = ResultadoOperacion.exito("Invitación de FEDER", mensaje)
                .detalle("Invitación", n)
                .detalle("Invita", "FEDER (administración)")
                .detalle("Rol", invitacion.rol())
                .detalle("Motivo", invitacion.datos().getMotivo())
                .detalle("Vence", FormatoNegocio.fechaHora(invitacion.datos().getFechaExpiracion()) + ZONA)
                .detalle("Para aceptar", "ACEINV[\"" + n + "\"]")
                .detalle("Para rechazar", "RECINV[\"" + n + "\",\"<motivo>\"]");
        if (!cuenta.getActivo()) {
            aviso.detalle("Su cuenta", "Inactiva; se activará al aceptar la invitación");
        }
        ctx.avisos().avisarDireccion(cuenta.getCorreo(), aviso);
    }

    /** La persona invitada ya tenía en su buzón el asunto para aceptar: se le informa que dejó de valer. */
    void invitacionCancelada(Usuario cuenta, Concesion invitacion) {
        ResultadoOperacion aviso = ResultadoOperacion.exito("Invitación cancelada",
                        "La administración de FEDER canceló la invitación número " + invitacion.id() + " al rol "
                                + invitacion.rol() + " que se le había enviado. Ya no puede aceptarla y no necesita hacer nada.")
                .detalle("Invitación", invitacion.id())
                .detalle("Rol", invitacion.rol())
                .detalle("Motivo", invitacion.datos().getMotivoRevocacion())
                .detalle("Fecha", FormatoNegocio.fechaHora(invitacion.datos().getFechaRevocacion()) + ZONA);
        ctx.avisos().avisarCuenta(cuenta.getId(), aviso);
    }

    /** R101: el titular sabe desde cuándo y por qué deja de ejercer ese rol; sus demás roles no cambian. */
    void revocacion(Usuario cuenta, Concesion revocada) {
        String mensaje = "La administración de FEDER revocó su rol " + revocada.rol() + ". Desde ahora su cuenta ya no "
                + "puede usar las funciones de ese rol; conserva sus demás roles y sus datos."
                + (revocada.rol() == TipoRol.PRODUCTOR
                ? " Su perfil de productor quedó inactivo; sus producciones, lotes y ventas registradas se conservan."
                : "")
                + comoConsultarRoles(cuenta);
        ResultadoOperacion aviso = ResultadoOperacion.exito("Rol revocado", mensaje)
                .detalle("Rol", revocada.rol())
                .detalle("Motivo", revocada.datos().getMotivoRevocacion())
                .detalle("Fecha", FormatoNegocio.fechaHora(revocada.datos().getFechaRevocacion()) + ZONA);
        ctx.avisos().avisarCuenta(cuenta.getId(), aviso);
    }

    // Una cuenta inactiva no puede enviar comandos: indicarle LISROL sería una instrucción que el sistema rechazaría.
    private static String comoConsultarRoles(Usuario cuenta) {
        return cuenta.getActivo()
                ? " Puede consultar el historial de sus roles con LISROL[\"" + cuenta.getCorreo() + "\"]."
                : " Su cuenta está inactiva, por lo que no puede enviar comandos; si tiene dudas, comuníquese con la "
                + "administración de FEDER.";
    }

    /**
     * R095: a la cuenta existente se le explica lo ocurrido y cómo seguir. No hay enlace de recuperación en este
     * sistema por correo: la cuenta se usa escribiendo desde su dirección registrada.
     */
    void registroConDatosExistentes(Usuario existente, boolean coincideCorreo, boolean coincideCi) {
        String datos = coincideCorreo && coincideCi ? "su correo y su cédula de identidad"
                : coincideCorreo ? "su correo" : "su cédula de identidad";
        String comoSeguir = existente.getActivo()
                ? " Si fue usted: su cuenta ya existe y puede operar escribiendo desde esta dirección registrada (envíe "
                + "HELP[\"*\"] para ver los comandos; si no tiene el rol CLIENTE, actívelo con ACTCLI[\"true\"])."
                : " Si fue usted: su cuenta existe pero está inactiva; comuníquese con la administración de FEDER para "
                + "revisarla (si recibió una invitación, acéptela con el asunto indicado en ella).";
        String mensaje = "Se recibió una solicitud de registro como cliente de FEDER con " + datos + ", que ya pertenecen "
                + "a su cuenta. No se creó ninguna cuenta nueva y su cuenta no cambió." + comoSeguir
                + " Si no fue usted, ignore este mensaje: las operaciones de su cuenta solo se confirman con códigos "
                + "enviados a esta dirección.";
        ResultadoOperacion aviso = ResultadoOperacion.exito("Intento de registro con sus datos", mensaje)
                .detalle("Fecha", FormatoNegocio.fechaHora(ctx.ahora()) + ZONA);
        ctx.avisos().avisarCuenta(existente.getId(), aviso);
    }
}
