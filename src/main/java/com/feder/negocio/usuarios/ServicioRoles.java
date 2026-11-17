package com.feder.negocio.usuarios;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.productores.ProductorRepositorio;
import com.feder.datos.usuarios.ConcesionRepositorio;
import com.feder.datos.usuarios.ConcesionRepositorio.Concesion;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoConcesion;
import com.feder.modelo.Productor;
import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Concesiones de roles privilegiados (CU1): invitación de personal y administradores, aceptación,
 * rechazo o cancelación de la invitación, revocación y consulta de las concesiones de una cuenta.
 */
public class ServicioRoles {

    /** Plazo para aceptar una invitación (fecha_expiracion). */
    public static final Duration PLAZO_INVITACION = Duration.ofDays(7);

    private static final Set<TipoRol> ROLES_POR_INVITACION = EnumSet.of(TipoRol.PERSONAL_FEDER, TipoRol.ADMINISTRADOR);

    /** Datos de la persona invitada, tal como llegan en el comando. */
    public record DatosInvitacion(String correo, String ci, String ciComplemento, String nombres, String apellidos,
                                  String telefono, TipoRol rol, String motivo) {
    }

    public record Invitacion(Usuario cuenta, Concesion concesion, boolean cuentaNueva) {
    }

    /** Resultado de aceptar: si la invitación ya había vencido, se anula y no se concede nada. */
    public record Aceptacion(Concesion concesion, boolean vencida, boolean cuentaActivada) {
    }

    /** Resultado de rechazar o cancelar: si ya había vencido, la anulación es la del vencimiento, sin autor. */
    public record Anulacion(Concesion concesion, boolean vencida) {
    }

    private final ContextoNegocio ctx;
    private final CuentaRepositorio cuentas = new CuentaRepositorio();
    private final ConcesionRepositorio concesiones = new ConcesionRepositorio();
    private final ProductorRepositorio productores = new ProductorRepositorio();
    private final RegistroConcesiones registro;
    private final GuardiaAdministradores guardia = new GuardiaAdministradores();
    private final AvisosCuentas avisos;

    public ServicioRoles(ContextoNegocio ctx) {
        this.ctx = ctx;
        this.registro = new RegistroConcesiones(ctx);
        this.avisos = new AvisosCuentas(ctx);
    }

    /**
     * R100: la invitación se dirige a una persona identificada por correo y cédula. Si no tiene cuenta se crea
     * una inactiva y sin contraseña; si correo y cédula pertenecen a cuentas distintas, se detiene (R095).
     * La invitación se envía por correo a la dirección de la cuenta invitada, solo si la operación se confirma.
     */
    public Invitacion invitar(Solicitante admin, DatosInvitacion datos) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "invitar personal o administradores");
        if (!ROLES_POR_INVITACION.contains(datos.rol())) {
            throw NegocioException.regla("Por invitación solo se incorporan PERSONAL_FEDER o ADMINISTRADOR. CLIENTE lo "
                    + "activa el propio titular y PRODUCTOR se obtiene con la aprobación de su solicitud.");
        }
        Usuario persona = DatosPersona.validar(datos.correo(), datos.ci(), datos.ciComplemento(), datos.nombres(),
                datos.apellidos(), datos.telefono());
        String motivo = ValidadorTexto.obligatorio(datos.motivo(), "El motivo de la invitación", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Optional<Usuario> porCorreo = cuentas.buscarPorCorreo(persona.getCorreo());
            Optional<Usuario> porCi = cuentas.buscarPorCi(persona.getCi(), persona.getCiComplemento());
            Usuario existente = identificarCuenta(porCorreo, porCi);
            boolean nueva = existente == null;
            // La cuenta existente se bloquea para que una baja simultánea (DESUSU) no deje viva esta invitación.
            Usuario cuenta = nueva ? crearCuentaInvitada(persona)
                    : cuentas.bloquearPorId(existente.getId()).orElseThrow(() -> ServicioCuentas.cuentaNoEncontrada(persona.getCorreo()));
            Permisos.exigirDistintoDelTitular(admin, cuenta.getId(), "Nadie puede invitarse ni concederse un rol a sí mismo.");
            concesiones.bloquearAbierta(cuenta.getId(), datos.rol()).ifPresent(registro::anularSiVencida);
            long concesionId = registro.invitar(cuenta.getId(), datos.rol(), admin.usuarioId(), PLAZO_INVITACION, motivo);
            Concesion invitacion = concesion(concesionId);
            avisos.invitacion(cuenta, invitacion);
            return new Invitacion(cuenta, invitacion, nueva);
        });
    }

    /**
     * La persona invitada acepta desde el correo invitado indicando el número de su invitación. El código de
     * confirmación que el despachador envió a ese buzón queda así ligado a esa concesión y a esa cuenta: es el
     * enlace de un solo uso de R100. Si la cuenta estaba inactiva, se activa en la misma transacción en que la
     * concesión pasa a VIGENTE.
     */
    public Aceptacion aceptarInvitacion(Solicitante remitente, long concesionId) {
        return ctx.transacciones().enTransaccion(() -> {
            Usuario cuenta = cuentaDelRemitente(remitente, concesionId);
            Concesion pendiente = invitacionDe(cuenta, concesionId);
            if (registro.anularSiVencida(pendiente)) {
                return new Aceptacion(concesion(concesionId), true, false);
            }
            boolean activar = !cuenta.getActivo();
            if (activar) {
                cuentas.activarConCredencial(cuenta.getId(), CredencialInerte.generar());
                auditarActivacionCuenta(cuenta.getId());
            }
            registro.activar(pendiente, cuenta.getId(), "Invitación aceptada por la persona invitada desde el correo invitado");
            return new Aceptacion(concesion(concesionId), false, activar);
        });
    }

    /** PENDIENTE -> ANULADA a pedido de la persona invitada: ella queda como revocado_por (R101). */
    public Anulacion rechazarInvitacion(Solicitante remitente, long concesionId, String motivo) {
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Usuario cuenta = cuentaDelRemitente(remitente, concesionId);
            Concesion pendiente = invitacionDe(cuenta, concesionId);
            if (registro.anularSiVencida(pendiente)) {
                return new Anulacion(concesion(concesionId), true);
            }
            registro.anular(pendiente, cuenta.getId(), justificacion, "Invitación rechazada por la persona invitada: " + justificacion);
            return new Anulacion(concesion(concesionId), false);
        });
    }

    /**
     * PENDIENTE -> ANULADA por decisión de un ADMINISTRADOR. La persona invitada recibió la invitación por correo,
     * así que también se le avisa de la cancelación; una invitación que ya había vencido no genera aviso.
     */
    public Anulacion cancelarInvitacion(Solicitante admin, String correo, TipoRol rol, String motivo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "cancelar invitaciones");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Usuario cuenta = cuentas.bloquearPorCorreo(correo).orElseThrow(() -> ServicioCuentas.cuentaNoEncontrada(correo));
            Concesion pendiente = concesiones.bloquearAbierta(cuenta.getId(), rol)
                    .filter(c -> c.estado() == EstadoConcesion.PENDIENTE)
                    .orElseThrow(() -> NegocioException.noEncontrado("No hay una invitación PENDIENTE al rol " + rol
                            + " para la cuenta " + cuenta.getCorreo() + "."));
            if (registro.anularSiVencida(pendiente)) {
                return new Anulacion(concesion(pendiente.id()), true);
            }
            registro.anular(pendiente, admin.usuarioId(), justificacion, justificacion);
            Concesion anulada = concesion(pendiente.id());
            avisos.invitacionCancelada(cuenta, anulada);
            return new Anulacion(anulada, false);
        });
    }

    /**
     * R101: solo un ADMINISTRADOR vigente revoca, y solo cambia esa concesión. Al revocar PRODUCTOR el perfil queda
     * inactivo en la misma transacción (R073). Revocar ADMINISTRADOR respeta «Último administrador» (R102).
     * El titular recibe un aviso con el rol y el motivo, que de todos modos puede consultar con LISROL.
     */
    public Concesion revocar(Solicitante admin, String correo, TipoRol rol, String motivo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "revocar roles");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo de la revocación", 255);
        return ctx.transacciones().enTransaccion(() -> {
            if (rol == TipoRol.ADMINISTRADOR) {
                guardia.bloquear();
            }
            Usuario cuenta = cuentas.buscarPorCorreo(correo).orElseThrow(() -> ServicioCuentas.cuentaNoEncontrada(correo));
            Permisos.exigirDistintoDelTitular(admin, cuenta.getId(),
                    "Nadie decide sobre su propio acceso: otro ADMINISTRADOR debe revocar sus concesiones.");
            Concesion vigente = concesiones.bloquearAbierta(cuenta.getId(), rol)
                    .filter(c -> c.estado() == EstadoConcesion.VIGENTE)
                    .orElseThrow(() -> NegocioException.noEncontrado("La cuenta " + cuenta.getCorreo()
                            + " no tiene una concesión VIGENTE del rol " + rol + "."));
            if (rol == TipoRol.ADMINISTRADOR && cuenta.getActivo()) {
                guardia.exigirOtroAdministrador(cuenta.getId(), "revocar el rol ADMINISTRADOR de " + cuenta.getCorreo());
            }
            registro.revocar(vigente, admin.usuarioId(), justificacion);
            if (rol == TipoRol.PRODUCTOR) {
                productores.bloquearPorUsuario(cuenta.getId()).ifPresent(p -> desactivarPerfil(p, admin.usuarioId(), justificacion));
            }
            Concesion revocada = concesion(vigente.id());
            avisos.revocacion(cuenta, revocada);
            return revocada;
        });
    }

    /** Historial de concesiones: el titular ve las suyas; el ADMINISTRADOR, las de cualquier cuenta. */
    public List<Concesion> concesionesDe(Solicitante solicitante, String correo) {
        Permisos.exigirRegistrado(solicitante);
        if (!correo.equalsIgnoreCase(solicitante.correo()) && !solicitante.tieneRol(TipoRol.ADMINISTRADOR)) {
            throw NegocioException.sinPermiso("Solo puede consultar las concesiones de su propia cuenta.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            Usuario cuenta = cuentas.buscarPorCorreo(correo).orElseThrow(() -> ServicioCuentas.cuentaNoEncontrada(correo));
            return concesiones.deUsuario(cuenta.getId());
        });
    }

    /** Concesiones de todas las cuentas (solo ADMINISTRADOR), de la más reciente a la más antigua. */
    public List<ConcesionRepositorio.ConcesionDeCuenta> todasLasConcesiones(Solicitante admin, int limite) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "listar las concesiones de todas las cuentas");
        return ctx.transacciones().enTransaccion(() -> concesiones.todas(limite));
    }

    private Usuario identificarCuenta(Optional<Usuario> porCorreo, Optional<Usuario> porCi) {
        if (porCorreo.isPresent() && porCi.isPresent()) {
            if (!porCorreo.get().getId().equals(porCi.get().getId())) {
                throw NegocioException.regla("El correo y la cédula corresponden a cuentas distintas. La invitación se "
                        + "detiene para que el ADMINISTRADOR revise la identidad de la persona.");
            }
            return porCorreo.get();
        }
        if (porCorreo.isPresent()) {
            throw NegocioException.regla("El correo pertenece a una cuenta registrada con otra cédula. Verifique la identidad "
                    + "de la persona con su documento antes de invitarla.");
        }
        if (porCi.isPresent()) {
            throw NegocioException.regla("La cédula pertenece a una cuenta registrada con otro correo. Una persona tiene una "
                    + "sola cuenta: dirija la invitación al correo de esa cuenta.");
        }
        return null;
    }

    /** Cuenta inactiva, sin contraseña y sin términos aceptados: todavía no ejerce ningún permiso (R091). */
    private Usuario crearCuentaInvitada(Usuario persona) {
        persona.setActivo(false);
        persona.setFechaRegistro(ctx.ahora());
        try {
            persona.setId(cuentas.insertar(persona, null));
        } catch (AccesoDatosException e) {
            if (e.esViolacionUnica()) {
                throw NegocioException.duplicado("Otra operación registró al mismo tiempo una cuenta con ese correo o cédula. "
                        + "Repita la invitación para dirigirla a esa cuenta.");
            }
            throw e;
        }
        return persona;
    }

    /** La cuenta se bloquea antes que la concesión, el mismo orden que usa la baja de cuentas. */
    private Usuario cuentaDelRemitente(Solicitante remitente, long concesionId) {
        return cuentas.bloquearPorCorreo(remitente.correo()).orElseThrow(() -> sinInvitacion(concesionId));
    }

    /**
     * La invitación debe ser de la cuenta del remitente y seguir PENDIENTE. El mensaje es el mismo si el número
     * no existe o es de otra persona, para no revelar invitaciones ajenas.
     */
    private Concesion invitacionDe(Usuario cuenta, long concesionId) {
        return concesiones.bloquearPorId(concesionId)
                .filter(c -> c.datos().getUsuarioId().equals(cuenta.getId()))
                .filter(c -> c.estado() == EstadoConcesion.PENDIENTE)
                .orElseThrow(() -> sinInvitacion(concesionId));
    }

    private void desactivarPerfil(Productor perfil, long autorId, String motivo) {
        if (!perfil.getActivo()) {
            return;
        }
        productores.cambiarActivo(perfil.getId(), false);
        ctx.bitacora().registrar(autorId, AccionBitacora.MODIFICAR, "PRODUCTOR", perfil.getId(), DetalleJson.nuevo()
                .con("activo_anterior", true)
                .con("activo_nuevo", false)
                .con("motivo", "Revocación de la concesión PRODUCTOR: " + motivo));
    }

    private void auditarActivacionCuenta(long cuentaId) {
        ctx.bitacora().registrar(cuentaId, AccionBitacora.MODIFICAR, "USUARIO", cuentaId, DetalleJson.nuevo()
                .con("activo_anterior", false)
                .con("activo_nuevo", true)
                .con("motivo", "Cuenta activada al aceptar la invitación; correo verificado con el código de un solo uso"));
    }

    private Concesion concesion(long id) {
        return concesiones.buscarPorId(id).orElseThrow(() -> NegocioException.noEncontrado("La concesión " + id + " no existe."));
    }

    private static NegocioException sinInvitacion(long concesionId) {
        return NegocioException.noEncontrado("No hay una invitación PENDIENTE número " + concesionId
                + " para esta dirección de correo.");
    }
}
