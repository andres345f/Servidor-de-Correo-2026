package com.feder.negocio.usuarios;

import com.feder.datos.usuarios.ConcesionRepositorio;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.modelo.TipoRol;
import com.feder.modelo.Usuario;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;

import java.util.Optional;

/**
 * Procedimiento de instalación (R099): crea la primera concesión ADMINISTRADOR.
 *
 * No se expone como comando por correo; lo invoca el instalador en el servidor. Solo actúa si no existe
 * ninguna concesión ADMINISTRADOR, en cualquier estado. Se serializa con el bloqueo de la fila ROL de
 * ADMINISTRADOR, de modo que dos instalaciones simultáneas no crean dos concesiones iniciales.
 */
public class ServicioInicializacion {

    /** Cuenta del primer administrador y la concesión creada. */
    public record Inicializacion(Usuario cuenta, long concesionId) {
    }

    private final ContextoNegocio ctx;
    private final CuentaRepositorio cuentas = new CuentaRepositorio();
    private final ConcesionRepositorio concesiones = new ConcesionRepositorio();
    private final RegistroConcesiones registro;

    public ServicioInicializacion(ContextoNegocio ctx) {
        this.ctx = ctx;
        this.registro = new RegistroConcesiones(ctx);
    }

    public Inicializacion inicializarAdministrador(String correo, String ci, String nombres, String apellidos, String telefono) {
        Usuario datos = validar(correo, ci, nombres, apellidos, telefono);
        return ctx.transacciones().enTransaccion(() -> {
            concesiones.bloquearRol(TipoRol.ADMINISTRADOR);
            // Se cuenta después de obtener el bloqueo para ver lo que confirmó una instalación concurrente.
            if (concesiones.existeAlgunaDeRol(TipoRol.ADMINISTRADOR)) {
                throw NegocioException.regla("El sistema ya está inicializado: existe una concesión ADMINISTRADOR. "
                        + "Los nuevos administradores se incorporan por invitación de otro administrador (INVUSU).");
            }
            Usuario cuenta = cuentaDelAdministrador(datos);
            // Única concesión de un rol privilegiado sin asignado_por, registrada en bitácora con autor nulo.
            long concesionId = registro.concederVigente(cuenta.getId(), TipoRol.ADMINISTRADOR, null, null,
                    "Concesión inicial creada por el procedimiento de instalación");
            return new Inicializacion(cuentas.buscarPorId(cuenta.getId()).orElseThrow(), concesionId);
        });
    }

    /** Reutiliza la cuenta si correo y cédula son de la misma persona; nunca crea una segunda cuenta (R095). */
    private Usuario cuentaDelAdministrador(Usuario datos) {
        Optional<Usuario> porCorreo = cuentas.buscarPorCorreo(datos.getCorreo());
        Optional<Usuario> porCi = cuentas.buscarPorCi(datos.getCi(), null);
        if (porCorreo.isEmpty() && porCi.isEmpty()) {
            datos.setId(cuentas.insertar(datos, CredencialInerte.generar()));
            return datos;
        }
        if (porCorreo.isEmpty() || porCi.isEmpty() || !porCorreo.get().getId().equals(porCi.get().getId())) {
            throw NegocioException.regla("El correo y la cédula indicados no corresponden a una misma cuenta existente; "
                    + "revise los datos antes de inicializar.");
        }
        Usuario existente = porCorreo.get();
        cuentas.activarConCredencial(existente.getId(), CredencialInerte.generar());
        return existente;
    }

    private Usuario validar(String correo, String ci, String nombres, String apellidos, String telefono) {
        Usuario u = DatosPersona.validar(correo, ci, null, nombres, apellidos, telefono);
        u.setActivo(true);
        u.setFechaRegistro(ctx.ahora());
        return u;
    }
}
