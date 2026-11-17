package com.feder.negocio.usuarios;

import com.feder.modelo.Usuario;
import com.feder.negocio.comun.NegocioException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validación común de los datos de una persona en las tres vías por las que nace una cuenta: registro público,
 * invitación e instalación (R093, R095, R100). Así una regla corregida aquí vale igual para las tres.
 * Devuelve la cuenta sin estado ni fechas: cada servicio decide si nace activa y cuándo.
 */
final class DatosPersona {

    private static final Pattern CORREO = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final int MAXIMO_CORREO = 120;

    private DatosPersona() {
    }

    static Usuario validar(String correo, String ci, String complemento, String nombres, String apellidos, String telefono) {
        Usuario u = new Usuario();
        u.setCorreo(correo(correo));
        u.setCi(ValidadorIdentidad.ci(ci));
        u.setCiComplemento(ValidadorIdentidad.complemento(complemento));
        u.setNombres(ValidadorTexto.nombrePersona(nombres, "Nombres"));
        u.setApellidos(ValidadorTexto.nombrePersona(apellidos, "Apellidos"));
        u.setTelefono(ValidadorIdentidad.telefono(telefono));
        return u;
    }

    /** El correo se guarda en minúsculas porque su unicidad no distingue mayúsculas (R090). */
    private static String correo(String texto) {
        String direccion = texto == null ? "" : texto.strip().toLowerCase(Locale.ROOT);
        if (!CORREO.matcher(direccion).matches() || direccion.length() > MAXIMO_CORREO) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "La dirección de correo no es válida para registrar una cuenta (máximo " + MAXIMO_CORREO + " caracteres).");
        }
        return direccion;
    }
}
