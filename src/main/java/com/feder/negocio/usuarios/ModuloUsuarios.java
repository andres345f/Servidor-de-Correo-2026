package com.feder.negocio.usuarios;

import com.feder.datos.usuarios.ConcesionRepositorio.Concesion;
import com.feder.datos.usuarios.ConcesionRepositorio.ConcesionDeCuenta;
import com.feder.datos.usuarios.CuentaRepositorio.CuentaResumen;
import com.feder.modelo.Bitacora;
import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

import java.util.List;
import java.util.stream.Collectors;

/**
 * CU1 Gestión de Usuarios: cuentas, roles, invitaciones y bitácora.
 * Los handlers solo convierten parámetros, llaman al servicio y arman la respuesta.
 */
public final class ModuloUsuarios implements ModuloComandos {

    private static final String CU = "CU1";

    private final ServicioCuentas cuentas;
    private final ServicioRoles roles;
    private final ConsultaBitacora bitacora;

    public ModuloUsuarios(ContextoNegocio ctx) {
        this.cuentas = new ServicioCuentas(ctx);
        this.roles = new ServicioRoles(ctx);
        this.bitacora = new ConsultaBitacora(ctx);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        registrarCuentas(catalogo);
        registrarRoles(catalogo);
        catalogo.registrar(ComandoSpec.comando("LISBIT").casoDeUso(CU)
                .descripcion("Lista los últimos registros de la bitácora, de una entidad (USUARIO_ROL, PRODUCTOR...) o de todas")
                .parametros(ParametroSpec.texto("entidad", 60).conComodin(), ParametroSpec.entero("limite").conComodin())
                .roles(TipoRol.ADMINISTRADOR)
                .ejemplo("LISBIT[\"USUARIO_ROL\",\"20\"]")
                .handler(this::listarBitacora).construir());
    }

    private void registrarCuentas(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("REGCLI").casoDeUso(CU)
                .descripcion("Registro público como cliente con la dirección de correo del remitente; si los datos ya "
                        + "pertenecen a una cuenta, se avisa a esa cuenta en su dirección registrada")
                .parametros(ParametroSpec.texto("ci", 9), ParametroSpec.texto("ci_complemento", 2).opcional(),
                        ParametroSpec.texto("nombres", 80), ParametroSpec.texto("apellidos", 80),
                        ParametroSpec.texto("telefono", 8), ParametroSpec.booleano("acepto_terminos"))
                .publico().modificaDatos()
                .ejemplo("REGCLI[\"99000031\",\"\",\"Lucas\",\"Pérez Vaca\",\"71234567\",\"true\"]")
                .handler(this::registrarCliente).construir());
        catalogo.registrar(ComandoSpec.comando("ACTCLI").casoDeUso(CU)
                .descripcion("Activa voluntariamente el rol CLIENTE en su propia cuenta aceptando los términos")
                .parametros(ParametroSpec.booleano("acepto_terminos"))
                .registrado().modificaDatos()
                .ejemplo("ACTCLI[\"true\"]")
                .handler((p, s) -> detalleCuenta("Rol CLIENTE activado", cuentas.activarCliente(s, p.booleano("acepto_terminos"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("VERUSU").casoDeUso(CU)
                .descripcion("Muestra los datos de su cuenta (el ADMINISTRADOR puede consultar cualquiera)")
                .parametros(ParametroSpec.correo("correo"))
                .registrado()
                .ejemplo("VERUSU[\"maria.quispe@feder.example\"]")
                .handler(this::verCuenta).construir());
        catalogo.registrar(ComandoSpec.comando("MODUSU").casoDeUso(CU)
                .descripcion("Modifica sus nombres, apellidos o teléfono; deje en \"\" lo que no cambia")
                .parametros(ParametroSpec.texto("nombres", 80).opcional(), ParametroSpec.texto("apellidos", 80).opcional(),
                        ParametroSpec.texto("telefono", 8).opcional())
                .registrado().modificaDatos()
                .ejemplo("MODUSU[\"\",\"\",\"71112233\"]")
                .handler((p, s) -> detalleCuenta("Datos actualizados",
                        cuentas.actualizarPerfil(s, p.texto("nombres"), p.texto("apellidos"), p.texto("telefono"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("LISUSU").casoDeUso(CU)
                .descripcion("Lista las cuentas de usuario con sus roles vigentes; filtra por correo o nombre, o por rol "
                        + "(ADMINISTRADOR, PERSONAL_FEDER, CLIENTE o PRODUCTOR)")
                .parametros(ParametroSpec.texto("filtro", 60).conComodin())
                .roles(TipoRol.ADMINISTRADOR)
                .ejemplo("LISUSU[\"*\"]")
                .handler(this::listarCuentas).construir());
        catalogo.registrar(ComandoSpec.comando("DESUSU").casoDeUso(CU)
                .descripcion("Desactiva una cuenta (baja lógica); nunca la del último administrador")
                .parametros(ParametroSpec.correo("correo"), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("DESUSU[\"silvia.anez@feder.example\",\"Cuenta duplicada informada por la titular\"]")
                .handler((p, s) -> detalleCuenta("Cuenta desactivada", cuentas.desactivar(s, p.texto("correo"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ACTUSU").casoDeUso(CU)
                .descripcion("Reactiva una cuenta desactivada que ya estuvo activa")
                .parametros(ParametroSpec.correo("correo"), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("ACTUSU[\"silvia.anez@feder.example\",\"La titular aclaró la situación de su cuenta\"]")
                .handler((p, s) -> detalleCuenta("Cuenta reactivada", cuentas.reactivar(s, p.texto("correo"), p.texto("motivo"))))
                .construir());
    }

    private void registrarRoles(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("INVUSU").casoDeUso(CU)
                .descripcion("Invita a una persona como PERSONAL_FEDER o ADMINISTRADOR y le envía la invitación por correo; "
                        + "queda PENDIENTE hasta que acepte")
                .parametros(ParametroSpec.correo("correo"), ParametroSpec.texto("ci", 9),
                        ParametroSpec.texto("ci_complemento", 2).opcional(), ParametroSpec.texto("nombres", 80),
                        ParametroSpec.texto("apellidos", 80), ParametroSpec.texto("telefono", 8),
                        ParametroSpec.enumeracion("rol", TipoRol.class), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("INVUSU[\"ventas.ana.rivero@feder.example\",\"99000020\",\"\",\"Ana\",\"Rivero Paz\",\"70000120\","
                        + "\"PERSONAL_FEDER\",\"Incorporación como auxiliar de ventas\"]")
                .handler(this::invitar).construir());
        catalogo.registrar(ComandoSpec.comando("ACEINV").casoDeUso(CU)
                .descripcion("La persona invitada acepta su invitación, por su número, enviando el comando desde el correo invitado")
                .parametros(ParametroSpec.entero("invitacion"))
                .publico().modificaDatos()
                .ejemplo("ACEINV[\"6\"]")
                .handler(this::aceptarInvitacion).construir());
        catalogo.registrar(ComandoSpec.comando("RECINV").casoDeUso(CU)
                .descripcion("La persona invitada rechaza su invitación, por su número, desde el correo invitado")
                .parametros(ParametroSpec.entero("invitacion"), ParametroSpec.texto("motivo", 255))
                .publico().modificaDatos()
                .ejemplo("RECINV[\"6\",\"No continuaré el proceso de incorporación\"]")
                .handler((p, s) -> anulacion("Invitación rechazada",
                        roles.rechazarInvitacion(s, p.entero("invitacion"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("CANINV").casoDeUso(CU)
                .descripcion("Cancela una invitación PENDIENTE y avisa por correo a la persona invitada")
                .parametros(ParametroSpec.correo("correo"), ParametroSpec.enumeracion("rol", TipoRol.class),
                        ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("CANINV[\"logistica.lucia.fernandez@feder.example\",\"PERSONAL_FEDER\",\"El puesto ya fue cubierto\"]")
                .handler((p, s) -> anulacion("Invitación cancelada",
                        roles.cancelarInvitacion(s, p.texto("correo"), p.enumeracion("rol", TipoRol.class), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("REVROL").casoDeUso(CU)
                .descripcion("Revoca una concesión VIGENTE y avisa por correo al titular; al revocar PRODUCTOR el perfil queda inactivo")
                .parametros(ParametroSpec.correo("correo"), ParametroSpec.enumeracion("rol", TipoRol.class),
                        ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("REVROL[\"ventas.jorge.suarez@feder.example\",\"PERSONAL_FEDER\",\"Finalización de su vínculo laboral\"]")
                .handler((p, s) -> detalleConcesion("Concesión revocada",
                        roles.revocar(s, p.texto("correo"), p.enumeracion("rol", TipoRol.class), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("LISROL").casoDeUso(CU)
                .descripcion("Lista el historial de concesiones de roles de su cuenta (el ADMINISTRADOR, de cualquiera; "
                        + "\"*\" = de todas las cuentas)")
                .parametros(ParametroSpec.correo("correo").conComodin())
                .registrado()
                .ejemplo("LISROL[\"ventas.jorge.suarez@feder.example\"]")
                .handler(this::listarConcesiones).construir());
    }

    private ResultadoOperacion registrarCliente(Parametros p, Solicitante s) {
        return cuentas.registrarCliente(s, new ServicioCuentas.DatosRegistro(p.texto("ci"), p.texto("ci_complemento"),
                        p.texto("nombres"), p.texto("apellidos"), p.texto("telefono"), p.booleano("acepto_terminos")))
                .map(ModuloUsuarios::registroCompletado)
                // R095: la misma respuesta que cualquier otra regla incumplida, exista o no la cuenta.
                .orElseGet(() -> ResultadoOperacion.error("Regla de negocio", ServicioCuentas.MENSAJE_REGISTRO_GENERICO));
    }

    private static ResultadoOperacion registroCompletado(Usuario cuenta) {
        return ResultadoOperacion.exito("Registro completado",
                        "Su cuenta de cliente quedó activa. Envíe HELP[\"*\"] para ver los comandos disponibles.")
                .detalle("Correo", cuenta.getCorreo())
                .detalle("Nombre", cuenta.getNombres() + " " + cuenta.getApellidos())
                .detalle("Rol", TipoRol.CLIENTE)
                .detalle("Términos aceptados", FormatoNegocio.fechaHora(cuenta.getFechaAceptacionTerminos()));
    }

    private ResultadoOperacion verCuenta(Parametros p, Solicitante s) {
        ServicioCuentas.CuentaConRoles cuenta = cuentas.consultar(s, p.texto("correo"));
        String vigentes = cuenta.rolesVigentes().isEmpty() ? "Ninguno"
                : cuenta.rolesVigentes().stream().map(Enum::name).collect(Collectors.joining(", "));
        return detalleCuenta("Cuenta " + cuenta.usuario().getCorreo(), cuenta.usuario()).detalle("Roles vigentes", vigentes);
    }

    private ResultadoOperacion listarCuentas(Parametros p, Solicitante s) {
        List<CuentaResumen> obtenidas = cuentas.listar(s, p.texto("filtro"));
        List<CuentaResumen> lista = Listados.recortar(obtenidas);
        TipoRol rol = ServicioCuentas.rolDelFiltro(p.texto("filtro"));
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Cuentas",
                List.of("N.º", "Correo", "Nombre", "CI", "Teléfono", "Activa", "Roles vigentes", "Registro"));
        for (CuentaResumen c : lista) {
            Usuario u = c.usuario();
            tabla.fila(u.getId(), u.getCorreo(), u.getNombres() + " " + u.getApellidos(), ci(u), u.getTelefono(),
                    FormatoNegocio.siNo(u.getActivo()), c.rolesVigentes() == null ? "Ninguno" : c.rolesVigentes(),
                    FormatoNegocio.fechaHora(u.getFechaRegistro()));
        }
        return ResultadoOperacion.exito("Cuentas de usuario", lista.size() + " cuenta(s) encontrada(s)"
                + (rol == null ? "." : " con el rol " + rol + " vigente.") + Listados.aviso(obtenidas)).tabla(tabla);
    }

    private ResultadoOperacion invitar(Parametros p, Solicitante s) {
        ServicioRoles.Invitacion invitacion = roles.invitar(s, new ServicioRoles.DatosInvitacion(p.texto("correo"),
                p.texto("ci"), p.texto("ci_complemento"), p.texto("nombres"), p.texto("apellidos"), p.texto("telefono"),
                p.enumeracion("rol", TipoRol.class), p.texto("motivo")));
        Concesion c = invitacion.concesion();
        String mensaje = "Se envió la invitación a " + invitacion.cuenta().getCorreo() + " con los asuntos ACEINV[\"" + c.id()
                + "\"] para aceptar y RECINV[\"" + c.id() + "\",\"<motivo>\"] para rechazar. La persona debe responder desde "
                + "ese correo antes del vencimiento y confirmar con el código de un solo uso que recibirá allí."
                + (invitacion.cuentaNueva() ? " Se creó una cuenta inactiva que se activará al aceptar."
                : " La invitación se dirigió a la cuenta existente con sus datos registrados"
                + (invitacion.cuenta().getActivo() ? "." : "; la cuenta está inactiva y se activará al aceptar."));
        return ResultadoOperacion.exito("Invitación registrada", mensaje)
                .detalle("Concesión", c.id())
                .detalle("Correo", invitacion.cuenta().getCorreo())
                .detalle("Rol", c.rol())
                .detalle("Estado", c.estado())
                .detalle("Vence", FormatoNegocio.fechaHora(c.datos().getFechaExpiracion()))
                .detalle("Para aceptar", "ACEINV[\"" + c.id() + "\"]");
    }

    private ResultadoOperacion aceptarInvitacion(Parametros p, Solicitante s) {
        ServicioRoles.Aceptacion aceptacion = roles.aceptarInvitacion(s, p.entero("invitacion"));
        if (aceptacion.vencida()) {
            return invitacionVencida();
        }
        return detalleConcesion("Invitación aceptada", aceptacion.concesion())
                .detalle("Cuenta activada ahora", FormatoNegocio.siNo(aceptacion.cuentaActivada()));
    }

    /**
     * Una invitación vencida se responde como error, pero sin lanzar excepción: así se confirma su anulación
     * por vencimiento en lugar de revertirla.
     */
    private static ResultadoOperacion anulacion(String titulo, ServicioRoles.Anulacion anulacion) {
        return anulacion.vencida() ? invitacionVencida() : detalleConcesion(titulo, anulacion.concesion());
    }

    private static ResultadoOperacion invitacionVencida() {
        return ResultadoOperacion.error("Invitación vencida", "La invitación venció sin respuesta y quedó ANULADA por "
                + "vencimiento. Si corresponde, el ADMINISTRADOR puede emitir una invitación nueva.");
    }

    private ResultadoOperacion listarConcesiones(Parametros p, Solicitante s) {
        if (p.esComodin("correo")) {
            return listarTodasLasConcesiones(s);
        }
        List<Concesion> lista = roles.concesionesDe(s, p.texto("correo"));
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Concesiones de " + p.texto("correo"),
                List.of("N.º", "Rol", "Estado", "Asignación", "Activación", "Vence", "Revocación", "Motivo", "Motivo de revocación"));
        for (Concesion c : lista) {
            tabla.fila(c.id(), c.rol(), c.estado(), FormatoNegocio.fechaHora(c.datos().getFechaAsignacion()),
                    FormatoNegocio.fechaHora(c.datos().getFechaActivacion()), FormatoNegocio.fechaHora(c.datos().getFechaExpiracion()),
                    FormatoNegocio.fechaHora(c.datos().getFechaRevocacion()), c.datos().getMotivo(), c.datos().getMotivoRevocacion());
        }
        return ResultadoOperacion.exito("Concesiones de roles", lista.size() + " concesión(es).").tabla(tabla);
    }

    private ResultadoOperacion listarTodasLasConcesiones(Solicitante s) {
        List<ConcesionDeCuenta> obtenidas = roles.todasLasConcesiones(s, Listados.A_PEDIR);
        List<ConcesionDeCuenta> lista = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Concesiones de todas las cuentas",
                List.of("N.º", "Cuenta", "Rol", "Estado", "Asignación", "Activación", "Vence", "Revocación", "Motivo",
                        "Motivo de revocación"));
        for (ConcesionDeCuenta x : lista) {
            Concesion c = x.concesion();
            tabla.fila(c.id(), x.correo(), c.rol(), c.estado(), FormatoNegocio.fechaHora(c.datos().getFechaAsignacion()),
                    FormatoNegocio.fechaHora(c.datos().getFechaActivacion()), FormatoNegocio.fechaHora(c.datos().getFechaExpiracion()),
                    FormatoNegocio.fechaHora(c.datos().getFechaRevocacion()), c.datos().getMotivo(), c.datos().getMotivoRevocacion());
        }
        return ResultadoOperacion.exito("Concesiones de roles",
                lista.size() + " concesión(es), de la más reciente a la más antigua." + Listados.aviso(obtenidas)).tabla(tabla);
    }

    private ResultadoOperacion listarBitacora(Parametros p, Solicitante s) {
        // "*" en el límite = el tope común de los listados.
        long limite = p.esComodin("limite") ? Listados.TOPE : p.entero("limite");
        List<Bitacora> lista = bitacora.ultimos(s, p.texto("entidad"), limite);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Bitácora",
                List.of("N.º", "Fecha", "Autor", "Acción", "Entidad", "Registro", "Detalle"));
        for (Bitacora b : lista) {
            tabla.fila(b.getId(), FormatoNegocio.fechaHora(b.getFecha()), b.getUsuarioId() == null ? "Sistema" : b.getUsuarioId(),
                    b.getAccion(), b.getEntidad(), b.getEntidadId(), b.getDetalle());
        }
        return ResultadoOperacion.exito("Bitácora de auditoría", lista.size() + " registro(s), del más reciente al más antiguo.")
                .tabla(tabla);
    }

    private static ResultadoOperacion detalleCuenta(String titulo, Usuario u) {
        return ResultadoOperacion.exito(titulo, "")
                .detalle("Correo", u.getCorreo())
                .detalle("Nombres", u.getNombres())
                .detalle("Apellidos", u.getApellidos())
                .detalle("CI", ci(u))
                .detalle("Teléfono", u.getTelefono())
                .detalle("Activa", FormatoNegocio.siNo(u.getActivo()))
                .detalle("Registro", FormatoNegocio.fechaHora(u.getFechaRegistro()))
                .detalle("Aceptación de términos", FormatoNegocio.fechaHora(u.getFechaAceptacionTerminos()));
    }

    private static ResultadoOperacion detalleConcesion(String titulo, Concesion c) {
        return ResultadoOperacion.exito(titulo, "")
                .detalle("Concesión", c.id())
                .detalle("Rol", c.rol())
                .detalle("Estado", c.estado())
                .detalle("Activación", FormatoNegocio.fechaHora(c.datos().getFechaActivacion()))
                .detalle("Revocación o anulación", FormatoNegocio.fechaHora(c.datos().getFechaRevocacion()))
                .detalle("Motivo de revocación", c.datos().getMotivoRevocacion());
    }

    private static String ci(Usuario u) {
        return u.getCiComplemento() == null ? String.valueOf(u.getCi()) : u.getCi() + "-" + u.getCiComplemento();
    }
}
