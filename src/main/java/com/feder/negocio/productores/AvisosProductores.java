package com.feder.negocio.productores;

import com.feder.datos.productores.ProductorRepositorio.PerfilProductor;
import com.feder.modelo.Productor;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.usuarios.FormatoNegocio;

/**
 * Avisos por correo al titular de un perfil de productor cuando un ADMINISTRADOR decide sobre su solicitud
 * (R075) o rehabilita su perfil (R073). Van a la dirección registrada de la cuenta titular y solo se envían
 * si la decisión se confirma.
 */
final class AvisosProductores {

    private final ContextoNegocio ctx;

    AvisosProductores(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    /** Situación de la cuenta titular al decidir, de la que depende qué puede hacer después con su solicitud. */
    enum SituacionTitular {
        PUEDE_SOLICITAR, SIN_CLIENTE, CUENTA_INACTIVA
    }

    void solicitudAprobada(PerfilProductor perfil) {
        Productor p = perfil.productor();
        ResultadoOperacion aviso = ResultadoOperacion.exito("Solicitud de productor aprobada",
                        "Su solicitud para ser productor de FEDER fue aprobada. Su cuenta tiene ahora el rol PRODUCTOR; "
                                + "la aprobación no modifica sus demás roles. Envíe HELP[\"*\"] para ver los comandos "
                                + "disponibles y VERPRD[\"*\"] para consultar su perfil.")
                .detalle("Código de productor", p.getCodigoProductor())
                .detalle("Comunidad", perfil.comunidad())
                .detalle("Afiliación", FormatoNegocio.fecha(p.getFechaAfiliacion()))
                .detalle("Revisada", FormatoNegocio.fechaHora(p.getFechaRevision()) + " (hora de Bolivia)");
        ctx.avisos().avisarCuenta(p.getUsuarioId(), aviso);
    }

    void solicitudRechazada(PerfilProductor perfil, SituacionTitular situacion) {
        Productor p = perfil.productor();
        ResultadoOperacion aviso = ResultadoOperacion.exito("Solicitud de productor rechazada",
                        "Su solicitud para ser productor de FEDER fue rechazada por el motivo indicado abajo. La decisión "
                                + "no modifica sus roles." + comoVolverASolicitar(situacion))
                .detalle("Motivo del rechazo", p.getMotivoRechazo())
                .detalle("Revisada", FormatoNegocio.fechaHora(p.getFechaRevision()) + " (hora de Bolivia)");
        ctx.avisos().avisarCuenta(p.getUsuarioId(), aviso);
    }

    // SOLPRD exige una cuenta activa con CLIENTE vigente: solo se indica cuando el titular podrá usarlo de verdad.
    private static String comoVolverASolicitar(SituacionTitular situacion) {
        switch (situacion) {
            case PUEDE_SOLICITAR:
                return " Si corrige lo señalado, puede volver a solicitarlo con SOLPRD[\"<n.º de comunidad>\","
                        + "\"<ubicación de la actividad>\"] (vea las comunidades con LISCOM[\"*\"]); consulte su "
                        + "solicitud con VERPRD[\"*\"].";
            case SIN_CLIENTE:
                return " Para volver a solicitarlo su cuenta necesita el rol CLIENTE vigente, que ahora no tiene; "
                        + "comuníquese con la administración de FEDER. Puede consultar su solicitud con VERPRD[\"*\"].";
            default:
                return " Su cuenta está inactiva, por lo que no puede enviar comandos; si desea volver a solicitarlo, "
                        + "comuníquese con la administración de FEDER.";
        }
    }

    void perfilRehabilitado(PerfilProductor perfil, String motivo) {
        Productor p = perfil.productor();
        ResultadoOperacion aviso = ResultadoOperacion.exito("Perfil de productor rehabilitado",
                        "La administración de FEDER rehabilitó su perfil de productor: su cuenta vuelve a tener el rol "
                                + "PRODUCTOR y su perfil está activo, con el mismo código y fecha de afiliación.")
                .detalle("Código de productor", p.getCodigoProductor())
                .detalle("Motivo", motivo);
        ctx.avisos().avisarCuenta(p.getUsuarioId(), aviso);
    }
}
