package com.feder.negocio.productores;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.productores.ComunidadRepositorio;
import com.feder.datos.productores.HistorialComunidadRepositorio;
import com.feder.datos.productores.ProductorRepositorio;
import com.feder.datos.productores.ProductorRepositorio.PerfilProductor;
import com.feder.datos.usuarios.ConcesionRepositorio;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.ComunidadAgricola;
import com.feder.modelo.EstadoConcesion;
import com.feder.modelo.EstadoSolicitudProductor;
import com.feder.modelo.Productor;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.productores.AvisosProductores.SituacionTitular;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.negocio.usuarios.Permisos;
import com.feder.negocio.usuarios.RegistroConcesiones;
import com.feder.negocio.usuarios.ValidadorTexto;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Perfil de productor y su solicitud de alta (CU2): solicitud, aprobación, rechazo, rehabilitación,
 * cambio de comunidad y actualización de la ubicación de la actividad (R072 a R075).
 */
public class ServicioProductores {

    private static final String ENTIDAD = "PRODUCTOR";

    private final ContextoNegocio ctx;
    private final ProductorRepositorio productores = new ProductorRepositorio();
    private final ComunidadRepositorio comunidades = new ComunidadRepositorio();
    private final CuentaRepositorio cuentas = new CuentaRepositorio();
    private final ConcesionRepositorio concesiones = new ConcesionRepositorio();
    private final RegistroConcesiones registro;
    private final AvisosProductores avisos;

    public ServicioProductores(ContextoNegocio ctx) {
        this.ctx = ctx;
        this.registro = new RegistroConcesiones(ctx);
        this.avisos = new AvisosProductores(ctx);
    }

    /**
     * «Quiero ser productor de FEDER» (R075): la presenta un titular con CLIENTE vigente, hacia una comunidad activa.
     * Se crea PENDIENTE, inactiva y sin rol PRODUCTOR; tras un rechazo se reabre el mismo perfil.
     */
    public PerfilProductor solicitar(Solicitante titular, long comunidadId, String ubicacionActividad) {
        Permisos.exigirRol(titular, TipoRol.CLIENTE, "solicitar el alta como productor");
        String ubicacion = ValidadorTexto.obligatorio(ubicacionActividad, "La ubicación de la actividad", 200);
        return ctx.transacciones().enTransaccion(() -> {
            ComunidadAgricola comunidad = comunidadActiva(comunidadId, "no admite solicitudes de productor");
            Optional<Productor> existente = productores.bloquearPorUsuario(titular.usuarioId());
            EstadoSolicitudProductor anterior = existente.map(Productor::getEstadoSolicitud).orElse(null);
            long id;
            if (existente.isEmpty()) {
                id = insertarSolicitud(titular.usuarioId(), comunidad.getId(), ubicacion);
            } else {
                exigirReabrible(existente.get());
                id = existente.get().getId();
                productores.reabrirSolicitud(id, comunidad.getId(), ubicacion, ctx.ahora());
            }
            ctx.bitacora().registrar(titular.usuarioId(), AccionBitacora.CREAR, ENTIDAD, id, DetalleJson.nuevo()
                    .con("estado_anterior", anterior)
                    .con("estado_nuevo", EstadoSolicitudProductor.PENDIENTE)
                    .con("comunidad_id", comunidad.getId())
                    .con("ubicacion_actividad", ubicacion)
                    .con("justificacion", "Solicitud de alta como productor de FEDER"));
            return perfil(id);
        });
    }

    /**
     * Aprobación (R074, R075): solo desde PENDIENTE, por un ADMINISTRADOR distinto del titular. En una transacción:
     * APROBADA, código y afiliación en la primera aprobación, perfil activo y concesión PRODUCTOR VIGENTE.
     * El resultado se comunica por correo al titular (R075).
     */
    public PerfilProductor aprobar(Solicitante admin, long productorId, String justificacion) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "aprobar solicitudes de productor");
        String motivo = ValidadorTexto.obligatorio(justificacion, "La justificación", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Productor perfil = bloquearParaDecidir(admin, productorId);
            exigirCuentaActiva(perfil);
            registro.concederVigente(perfil.getUsuarioId(), TipoRol.PRODUCTOR, admin.usuarioId(), admin.usuarioId(),
                    "Aprobación de la solicitud de productor");
            productores.aprobar(perfil.getId(), ctx.ahora(), String.format("PROD-%04d", perfil.getId()),
                    FormatoNegocio.hoy(ctx.reloj()));
            PerfilProductor aprobado = perfil(perfil.getId());
            ctx.bitacora().registrar(admin.usuarioId(), AccionBitacora.APROBAR_SOLICITUD, ENTIDAD, perfil.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", EstadoSolicitudProductor.PENDIENTE)
                    .con("estado_nuevo", EstadoSolicitudProductor.APROBADA)
                    .con("codigo_productor", aprobado.productor().getCodigoProductor())
                    .con("justificacion", motivo));
            avisos.solicitudAprobada(aprobado);
            return aprobado;
        });
    }

    /**
     * Rechazo (R075): solo desde PENDIENTE, con motivo; no se concede PRODUCTOR y los roles de la cuenta no cambian.
     * El titular recibe por correo la decisión con su motivo.
     */
    public PerfilProductor rechazar(Solicitante admin, long productorId, String motivoRechazo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "rechazar solicitudes de productor");
        String motivo = ValidadorTexto.obligatorio(motivoRechazo, "El motivo del rechazo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Productor perfil = bloquearParaDecidir(admin, productorId);
            productores.rechazar(perfil.getId(), ctx.ahora(), motivo);
            ctx.bitacora().registrar(admin.usuarioId(), AccionBitacora.RECHAZAR_SOLICITUD, ENTIDAD, perfil.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", EstadoSolicitudProductor.PENDIENTE)
                    .con("estado_nuevo", EstadoSolicitudProductor.RECHAZADA)
                    .con("justificacion", motivo));
            PerfilProductor rechazado = perfil(perfil.getId());
            avisos.solicitudRechazada(rechazado, situacionTitular(perfil.getUsuarioId()));
            return rechazado;
        });
    }

    /**
     * Rehabilitación (R073): un perfil APROBADA e inactivo cuyo rol se revocó recibe una concesión PRODUCTOR nueva
     * y vuelve a estar activo. Se conservan la concesión revocada, el código y la fecha de afiliación.
     * Es una decisión sobre el acceso del titular, como la aprobación: también se le comunica por correo.
     */
    public PerfilProductor rehabilitar(Solicitante admin, long productorId, String motivo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "rehabilitar productores");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Productor perfil = bloquearPerfil(productorId);
            Permisos.exigirDistintoDelTitular(admin, perfil.getUsuarioId(), "Nadie rehabilita su propio acceso de productor.");
            if (perfil.getEstadoSolicitud() != EstadoSolicitudProductor.APROBADA) {
                throw NegocioException.estadoInvalido("Un perfil " + perfil.getEstadoSolicitud()
                        + " no se rehabilita: la solicitud debe aprobarse.");
            }
            if (perfil.getActivo()) {
                throw NegocioException.estadoInvalido("El perfil de productor ya está activo.");
            }
            exigirCuentaActiva(perfil);
            registro.concederVigente(perfil.getUsuarioId(), TipoRol.PRODUCTOR, admin.usuarioId(), admin.usuarioId(),
                    "Rehabilitación del perfil de productor: " + justificacion);
            productores.cambiarActivo(perfil.getId(), true);
            ctx.bitacora().registrar(admin.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, perfil.getId(), DetalleJson.nuevo()
                    .con("activo_anterior", false)
                    .con("activo_nuevo", true)
                    .con("motivo", justificacion));
            PerfilProductor rehabilitado = perfil(perfil.getId());
            avisos.perfilRehabilitado(rehabilitado, justificacion);
            return rehabilitado;
        });
    }

    /**
     * Cambio de comunidad (R072): solo un ADMINISTRADOR distinto del titular, con motivo y hacia una comunidad activa.
     * No altera la comunidad de origen de las producciones ya registradas (R066).
     */
    public PerfilProductor cambiarComunidad(Solicitante admin, long productorId, long comunidadId, String motivo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "cambiar la comunidad de un productor");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Productor perfil = bloquearPerfil(productorId);
            Permisos.exigirDistintoDelTitular(admin, perfil.getUsuarioId(), "Nadie cambia la comunidad de su propio perfil.");
            if (perfil.getEstadoSolicitud() != EstadoSolicitudProductor.APROBADA) {
                throw NegocioException.estadoInvalido("Solo se cambia la comunidad de un productor con la solicitud APROBADA.");
            }
            ComunidadAgricola destino = comunidadActiva(comunidadId, "no admite cambios de comunidad hacia ella");
            if (destino.getId().equals(perfil.getComunidadAgricolaId())) {
                throw NegocioException.regla("El productor ya pertenece a la comunidad " + destino.getNombre() + ".");
            }
            productores.cambiarComunidad(perfil.getId(), destino.getId());
            ctx.bitacora().registrar(admin.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, perfil.getId(), DetalleJson.nuevo()
                    .con("operacion", HistorialComunidadRepositorio.OPERACION_CAMBIO)
                    .con("comunidad_anterior_id", perfil.getComunidadAgricolaId())
                    .con("comunidad_nueva_id", destino.getId())
                    .con("motivo", justificacion));
            return perfil(perfil.getId());
        });
    }

    /** R072: el PRODUCTOR actualiza por sí mismo la ubicación de su actividad; las producciones no cambian. */
    public PerfilProductor actualizarUbicacion(Solicitante productor, String ubicacionActividad) {
        Permisos.exigirRol(productor, TipoRol.PRODUCTOR, "actualizar su perfil de productor");
        String ubicacion = ValidadorTexto.obligatorio(ubicacionActividad, "La ubicación de la actividad", 200);
        return ctx.transacciones().enTransaccion(() -> {
            Productor perfil = productores.bloquearPorUsuario(productor.usuarioId())
                    .orElseThrow(() -> NegocioException.noEncontrado("Su cuenta no tiene un perfil de productor."));
            if (ubicacion.equals(perfil.getUbicacionActividad())) {
                throw NegocioException.regla("La ubicación indicada es igual a la registrada; no se modificó nada.");
            }
            productores.actualizarUbicacion(perfil.getId(), ubicacion);
            ctx.bitacora().registrar(productor.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, perfil.getId(), DetalleJson.nuevo()
                    .con("ubicacion_actividad_anterior", perfil.getUbicacionActividad())
                    .con("ubicacion_actividad_nuevo", ubicacion));
            return perfil(perfil.getId());
        });
    }

    /** Panel de solicitudes y productores: ADMINISTRADOR y PERSONAL_FEDER (R088). */
    public List<PerfilProductor> listar(Solicitante solicitante, EstadoSolicitudProductor estado) {
        Permisos.exigirPersonalInterno(solicitante, "consultar productores");
        return ctx.transacciones().enTransaccion(() -> productores.listar(estado));
    }

    /**
     * Perfil por número o código de productor; sin referencia, el propio. El titular ve su perfil (estado y motivo
     * de rechazo, R075); el personal interno, cualquiera.
     */
    public PerfilProductor consultar(Solicitante solicitante, String referencia) {
        Permisos.exigirRegistrado(solicitante);
        return ctx.transacciones().enTransaccion(() -> {
            PerfilProductor perfil = buscar(solicitante, referencia);
            if (!solicitante.esPersonalInterno() && perfil.productor().getUsuarioId() != solicitante.usuarioId()) {
                throw NegocioException.sinPermiso("Solo puede consultar su propio perfil de productor.");
            }
            return perfil;
        });
    }

    private PerfilProductor buscar(Solicitante solicitante, String referencia) {
        if (referencia == null) {
            return productores.perfilPorUsuario(solicitante.usuarioId())
                    .orElseThrow(() -> NegocioException.noEncontrado("Su cuenta no tiene un perfil ni una solicitud de productor."));
        }
        String ref = referencia.strip().toUpperCase(Locale.ROOT);
        Optional<PerfilProductor> perfil = ref.chars().allMatch(Character::isDigit) && ref.length() <= 18
                ? productores.perfilPorId(Long.parseLong(ref))
                : productores.perfilPorCodigo(ref);
        return perfil.orElseThrow(() -> NegocioException.noEncontrado("No existe el productor " + referencia + "."));
    }

    private long insertarSolicitud(long usuarioId, long comunidadId, String ubicacion) {
        try {
            return productores.insertarSolicitud(usuarioId, comunidadId, ubicacion, ctx.ahora());
        } catch (AccesoDatosException e) {
            if (e.esViolacionUnica()) {
                throw NegocioException.duplicado("Su cuenta ya tiene un perfil de productor (una cuenta tiene como máximo uno).");
            }
            throw e;
        }
    }

    private static void exigirReabrible(Productor perfil) {
        switch (perfil.getEstadoSolicitud()) {
            case PENDIENTE:
                throw NegocioException.estadoInvalido("Ya tiene una solicitud PENDIENTE presentada el "
                        + FormatoNegocio.fechaHora(perfil.getFechaSolicitud()) + "; espere la decisión del ADMINISTRADOR.");
            case APROBADA:
                throw NegocioException.estadoInvalido("Su solicitud de productor ya fue aprobada."
                        + (perfil.getActivo() ? "" : " Si su rol fue revocado, un ADMINISTRADOR puede rehabilitar su perfil."));
            default:
                return;
        }
    }

    /** Bloquea el perfil y comprueba que la decisión es posible: PENDIENTE y decidida por otra persona (R074). */
    private Productor bloquearParaDecidir(Solicitante admin, long productorId) {
        Productor perfil = bloquearPerfil(productorId);
        Permisos.exigirDistintoDelTitular(admin, perfil.getUsuarioId(), "Nadie decide sobre su propia solicitud de productor.");
        if (perfil.getEstadoSolicitud() != EstadoSolicitudProductor.PENDIENTE) {
            throw NegocioException.estadoInvalido("Solo se decide una solicitud PENDIENTE; esta está "
                    + perfil.getEstadoSolicitud() + ".");
        }
        return perfil;
    }

    /**
     * El rechazo no exige cuenta activa ni CLIENTE vigente (pudo cambiar mientras la solicitud esperaba), así que el
     * aviso solo indica SOLPRD si el titular realmente podrá usarlo.
     */
    private SituacionTitular situacionTitular(long usuarioId) {
        if (!cuentaActiva(usuarioId)) {
            return SituacionTitular.CUENTA_INACTIVA;
        }
        boolean clienteVigente = concesiones.buscarAbierta(usuarioId, TipoRol.CLIENTE)
                .filter(c -> c.estado() == EstadoConcesion.VIGENTE).isPresent();
        return clienteVigente ? SituacionTitular.PUEDE_SOLICITAR : SituacionTitular.SIN_CLIENTE;
    }

    private boolean cuentaActiva(long usuarioId) {
        return cuentas.buscarPorId(usuarioId).map(u -> u.getActivo()).orElse(false);
    }

    private void exigirCuentaActiva(Productor perfil) {
        boolean activa = cuentaActiva(perfil.getUsuarioId());
        if (!activa) {
            throw NegocioException.regla("La cuenta del titular está inactiva; no se le puede conceder el rol PRODUCTOR.");
        }
    }

    /** FOR SHARE: una desactivación simultánea (DESCOM) termina antes y aquí se ve la comunidad ya inactiva (R008). */
    private ComunidadAgricola comunidadActiva(long comunidadId, String consecuencia) {
        ComunidadAgricola comunidad = comunidades.compartirPorId(comunidadId)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la comunidad agrícola " + comunidadId + "."));
        if (!comunidad.getActiva()) {
            throw NegocioException.regla("La comunidad " + comunidad.getNombre() + " está inactiva y " + consecuencia + ".");
        }
        return comunidad;
    }

    private Productor bloquearPerfil(long productorId) {
        return productores.bloquearPorId(productorId)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el perfil de productor " + productorId + "."));
    }

    private PerfilProductor perfil(long id) {
        return productores.perfilPorId(id).orElseThrow(() -> NegocioException.noEncontrado("No existe el productor " + id + "."));
    }
}
