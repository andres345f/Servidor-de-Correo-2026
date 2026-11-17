package com.feder.negocio.usuarios;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.usuarios.ConcesionRepositorio;
import com.feder.datos.usuarios.ConcesionRepositorio.Concesion;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.datos.usuarios.CuentaRepositorio.CuentaResumen;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoConcesion;
import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Cuentas de usuario (CU1): registro público de clientes, activación voluntaria de CLIENTE,
 * datos propios, consulta y baja o reactivación lógica de cuentas.
 */
public class ServicioCuentas {

    /** Datos del registro público tal como llegan en el comando (se validan como texto). */
    public record DatosRegistro(String ci, String ciComplemento, String nombres, String apellidos, String telefono,
                                boolean aceptoTerminos) {
    }

    /** Cuenta con sus roles vigentes. */
    public record CuentaConRoles(Usuario usuario, List<TipoRol> rolesVigentes) {
    }

    // R095: el mismo mensaje para correo o cédula ya registrados, sin revelar cuál existe ni si se avisó a alguien.
    static final String MENSAJE_REGISTRO_GENERICO = "No se pudo completar el registro con los datos enviados. Si usted ya "
            + "tiene una cuenta en FEDER, escriba desde la dirección de esa cuenta (si no tiene el rol CLIENTE, actívelo con "
            + "ACTCLI); si cree que se trata de un error, comuníquese con la administración de FEDER.";

    private static final String ENTIDAD = "USUARIO";

    private final ContextoNegocio ctx;
    private final CuentaRepositorio cuentas = new CuentaRepositorio();
    private final ConcesionRepositorio concesiones = new ConcesionRepositorio();
    private final RegistroConcesiones registro;
    private final GuardiaAdministradores guardia = new GuardiaAdministradores();
    private final AvisosCuentas avisos;

    public ServicioCuentas(ContextoNegocio ctx) {
        this.ctx = ctx;
        this.registro = new RegistroConcesiones(ctx);
        this.avisos = new AvisosCuentas(ctx);
    }

    /**
     * R093: cuenta activa, términos aceptados y CLIENTE VIGENTE en una sola transacción. Siempre y solo CLIENTE.
     * El correo es el del remitente; el código de confirmación que exige el despachador ya llegó a ese buzón.
     *
     * R095: si el correo o la cédula ya pertenecen a una cuenta, no se crea nada y se devuelve vacío para que el
     * remitente reciba el mensaje genérico, y la cuenta existente recibe un aviso en su dirección registrada.
     * No se lanza una excepción a propósito: el despachador descartaría el aviso junto con la operación.
     */
    public Optional<Usuario> registrarCliente(Solicitante remitente, DatosRegistro datos) {
        if (!datos.aceptoTerminos()) {
            throw NegocioException.regla("Para registrarse debe aceptar los términos y condiciones de la tienda (acepto_terminos = true).");
        }
        Usuario nueva = nuevaCuenta(remitente.correo(), datos);
        return ctx.transacciones().enTransaccion(() -> {
            if (avisarSiYaExiste(remitente, nueva)) {
                return Optional.<Usuario>empty();
            }
            Optional<Long> id = insertarSiNoExiste(nueva);
            if (id.isEmpty()) {
                // Otra petición registró el mismo correo o cédula a la vez; esa cuenta ya es visible y se le avisa.
                avisarSiYaExiste(remitente, nueva);
                return Optional.<Usuario>empty();
            }
            nueva.setId(id.get());
            registro.concederVigente(id.get(), TipoRol.CLIENTE, null, id.get(),
                    "Registro público por correo con aceptación de los términos");
            return Optional.of(nueva);
        });
    }

    /** Avisa a cada cuenta que ya tiene ese correo o esa cédula. Devuelve true si el registro no puede crearse. */
    private boolean avisarSiYaExiste(Solicitante remitente, Usuario nueva) {
        Optional<Usuario> porCorreo = cuentas.buscarPorCorreo(nueva.getCorreo());
        Optional<Usuario> porCi = cuentas.buscarPorCi(nueva.getCi(), nueva.getCiComplemento());
        boolean mismaCuenta = porCorreo.isPresent() && porCi.isPresent() && porCorreo.get().getId().equals(porCi.get().getId());
        porCorreo.ifPresent(cuenta -> avisarCuentaExistente(remitente, cuenta, true, mismaCuenta));
        if (!mismaCuenta) {
            porCi.ifPresent(cuenta -> avisarCuentaExistente(remitente, cuenta, false, true));
        }
        return remitente.estaRegistrado() || porCorreo.isPresent() || porCi.isPresent();
    }

    private void avisarCuentaExistente(Solicitante remitente, Usuario cuenta, boolean coincideCorreo, boolean coincideCi) {
        // Quien escribe desde su propia cuenta activa ya recibe la respuesta genérica en esa misma dirección.
        if (remitente.estaRegistrado() && remitente.usuarioId() == cuenta.getId()) {
            return;
        }
        avisos.registroConDatosExistentes(cuenta, coincideCorreo, coincideCi);
    }

    /** R097: CLIENTE es el único rol que el titular obtiene por sí mismo, aceptando los términos. */
    public Usuario activarCliente(Solicitante titular, boolean aceptoTerminos) {
        Permisos.exigirRegistrado(titular);
        if (!aceptoTerminos) {
            throw NegocioException.regla("Para activar el rol CLIENTE debe aceptar los términos y condiciones (acepto_terminos = true).");
        }
        return ctx.transacciones().enTransaccion(() -> {
            Usuario cuenta = cuentas.bloquearPorId(titular.usuarioId()).orElseThrow(ServicioCuentas::cuentaInexistente);
            registro.concederVigente(cuenta.getId(), TipoRol.CLIENTE, null, cuenta.getId(),
                    "Activación voluntaria del rol de cliente con aceptación de los términos");
            cuentas.fijarAceptacionTerminos(cuenta.getId(), ctx.ahora());
            return cuentas.buscarPorId(cuenta.getId()).orElseThrow(ServicioCuentas::cuentaInexistente);
        });
    }

    /** Datos propios: nombres, apellidos y teléfono. La cédula y el correo no se cambian por este medio. */
    public Usuario actualizarPerfil(Solicitante titular, String nombres, String apellidos, String telefono) {
        Permisos.exigirRegistrado(titular);
        if (nombres == null && apellidos == null && telefono == null) {
            throw NegocioException.regla("Indique al menos un dato para modificar; deje en \"\" los que no cambian.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            Usuario actual = cuentas.bloquearPorId(titular.usuarioId()).orElseThrow(ServicioCuentas::cuentaInexistente);
            String nuevosNombres = nombres == null ? actual.getNombres() : ValidadorTexto.nombrePersona(nombres, "Nombres");
            String nuevosApellidos = apellidos == null ? actual.getApellidos() : ValidadorTexto.nombrePersona(apellidos, "Apellidos");
            int nuevoTelefono = telefono == null ? actual.getTelefono() : ValidadorIdentidad.telefono(telefono);
            DetalleJson cambios = DetalleJson.nuevo();
            boolean hayCambios = CambiosBitacora.anotar(cambios, "nombres", actual.getNombres(), nuevosNombres)
                    | CambiosBitacora.anotar(cambios, "apellidos", actual.getApellidos(), nuevosApellidos)
                    | CambiosBitacora.anotar(cambios, "telefono", actual.getTelefono(), nuevoTelefono);
            if (!hayCambios) {
                throw NegocioException.regla("Los datos indicados son iguales a los registrados; no se modificó nada.");
            }
            cuentas.actualizarContacto(actual.getId(), nuevosNombres, nuevosApellidos, nuevoTelefono);
            ctx.bitacora().registrar(actual.getId(), AccionBitacora.MODIFICAR, ENTIDAD, actual.getId(), cambios);
            return cuentas.buscarPorId(actual.getId()).orElseThrow(ServicioCuentas::cuentaInexistente);
        });
    }

    /** Baja lógica de una cuenta por un ADMINISTRADOR, respetando «Último administrador» (R102). */
    public Usuario desactivar(Solicitante admin, String correo, String motivo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "desactivar cuentas");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            // El bloqueo de la fila ROL va primero: así todas las operaciones de R102 se ordenan igual.
            guardia.bloquear();
            Usuario cuenta = cuentas.bloquearPorCorreo(correo).orElseThrow(() -> cuentaNoEncontrada(correo));
            Permisos.exigirDistintoDelTitular(admin, cuenta.getId(),
                    "No puede desactivar su propia cuenta: otro ADMINISTRADOR debe hacerlo.");
            if (!cuenta.getActivo()) {
                throw NegocioException.estadoInvalido("La cuenta " + cuenta.getCorreo() + " ya está inactiva.");
            }
            if (tieneVigente(cuenta.getId(), TipoRol.ADMINISTRADOR)) {
                guardia.exigirOtroAdministrador(cuenta.getId(), "desactivar la cuenta " + cuenta.getCorreo());
            }
            cuentas.cambiarActivo(cuenta.getId(), false);
            auditarActivo(admin.usuarioId(), cuenta.getId(), true, false, justificacion);
            anularInvitacionesPendientes(admin.usuarioId(), cuenta.getId(), justificacion);
            return cuentas.buscarPorId(cuenta.getId()).orElseThrow(ServicioCuentas::cuentaInexistente);
        });
    }

    /**
     * Una invitación pendiente no sobrevive a la baja de la cuenta: si siguiera viva, la persona podría reactivarse
     * sola con ACEINV y recibir un rol privilegiado contra la decisión del ADMINISTRADOR (R088, R100, R101).
     */
    private void anularInvitacionesPendientes(long adminId, long cuentaId, String motivo) {
        for (Concesion pendiente : concesiones.bloquearPendientesDe(cuentaId)) {
            if (!registro.anularSiVencida(pendiente)) {
                registro.anular(pendiente, adminId, motivo, "Invitación anulada por la baja de la cuenta: " + motivo);
            }
        }
    }

    /** Reactivación de una cuenta que ya estuvo activa; una cuenta invitada nunca activada usa ACEINV (R091). */
    public Usuario reactivar(Solicitante admin, String correo, String motivo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "reactivar cuentas");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Usuario cuenta = cuentas.bloquearPorCorreo(correo).orElseThrow(() -> cuentaNoEncontrada(correo));
            if (cuenta.getActivo()) {
                throw NegocioException.estadoInvalido("La cuenta " + cuenta.getCorreo() + " ya está activa.");
            }
            if (!cuentas.tieneCredencial(cuenta.getId())) {
                throw NegocioException.regla("La cuenta " + cuenta.getCorreo() + " nunca se activó: la persona debe aceptar "
                        + "su invitación enviando ACEINV desde ese correo.");
            }
            cuentas.cambiarActivo(cuenta.getId(), true);
            auditarActivo(admin.usuarioId(), cuenta.getId(), false, true, justificacion);
            return cuentas.buscarPorId(cuenta.getId()).orElseThrow(ServicioCuentas::cuentaInexistente);
        });
    }

    /** Un usuario consulta su propia cuenta; el ADMINISTRADOR, cualquiera. */
    public CuentaConRoles consultar(Solicitante solicitante, String correo) {
        Permisos.exigirRegistrado(solicitante);
        if (!correo.equalsIgnoreCase(solicitante.correo()) && !solicitante.tieneRol(TipoRol.ADMINISTRADOR)) {
            throw NegocioException.sinPermiso("Solo puede consultar su propia cuenta.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            Usuario cuenta = cuentas.buscarPorCorreo(correo).orElseThrow(() -> cuentaNoEncontrada(correo));
            List<TipoRol> roles = concesiones.deUsuario(cuenta.getId()).stream()
                    .filter(c -> c.estado() == EstadoConcesion.VIGENTE)
                    .map(Concesion::rol)
                    .collect(Collectors.toList());
            return new CuentaConRoles(cuenta, roles);
        });
    }

    /**
     * Listado de cuentas (hasta {@link Listados#A_PEDIR} filas). Si el filtro es exactamente un rol (sin distinguir
     * mayúsculas; "personal feder" vale como PERSONAL_FEDER), lista las cuentas con ese rol VIGENTE; si no, busca el
     * texto en correo, nombres y apellidos.
     */
    public List<CuentaResumen> listar(Solicitante admin, String filtro) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "listar las cuentas");
        TipoRol rol = rolDelFiltro(filtro);
        String texto = rol == null ? filtro : null;
        return ctx.transacciones().enTransaccion(() -> cuentas.listar(texto, rol, Listados.A_PEDIR));
    }

    /** El rol que nombra el filtro, o null si el filtro no es exactamente un rol. */
    public static TipoRol rolDelFiltro(String filtro) {
        if (filtro == null) {
            return null;
        }
        String buscado = filtro.strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        for (TipoRol rol : TipoRol.values()) {
            if (rol.name().equals(buscado)) {
                return rol;
            }
        }
        return null;
    }

    private Usuario nuevaCuenta(String correo, DatosRegistro datos) {
        Usuario u = DatosPersona.validar(correo, datos.ci(), datos.ciComplemento(), datos.nombres(), datos.apellidos(),
                datos.telefono());
        u.setActivo(true);
        // Registro y aceptación de términos ocurren en el mismo momento.
        OffsetDateTime ahora = ctx.ahora();
        u.setFechaRegistro(ahora);
        u.setFechaAceptacionTerminos(ahora);
        return u;
    }

    /**
     * Si otra petición registró el mismo correo o cédula a la vez, la unicidad de la base lo rechaza. El punto de
     * guardado deja la transacción utilizable para avisar a esa cuenta y responder con el mismo mensaje genérico.
     */
    private Optional<Long> insertarSiNoExiste(Usuario nueva) {
        try {
            return Optional.of(ctx.transacciones().conPuntoDeGuardado(() -> cuentas.insertar(nueva, CredencialInerte.generar())));
        } catch (AccesoDatosException e) {
            if (e.esViolacionUnica()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private boolean tieneVigente(long usuarioId, TipoRol rol) {
        return concesiones.buscarAbierta(usuarioId, rol).filter(c -> c.estado() == EstadoConcesion.VIGENTE).isPresent();
    }

    private void auditarActivo(long autorId, long cuentaId, boolean anterior, boolean nuevo, String motivo) {
        ctx.bitacora().registrar(autorId, AccionBitacora.MODIFICAR, ENTIDAD, cuentaId, DetalleJson.nuevo()
                .con("activo_anterior", anterior)
                .con("activo_nuevo", nuevo)
                .con("motivo", motivo));
    }

    static NegocioException cuentaNoEncontrada(String correo) {
        return NegocioException.noEncontrado("No existe una cuenta con el correo " + correo + ".");
    }

    private static NegocioException cuentaInexistente() {
        return NegocioException.noEncontrado("La cuenta ya no existe.");
    }
}
