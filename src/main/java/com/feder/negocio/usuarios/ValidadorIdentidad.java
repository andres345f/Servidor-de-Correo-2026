package com.feder.negocio.usuarios;

import com.feder.negocio.comun.NegocioException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cédula, complemento y teléfono se validan como texto antes de convertirlos a entero, tal como
 * indican las notas de USUARIO: una entrada con otros caracteres se rechaza, no se corrige.
 */
public final class ValidadorIdentidad {

    private static final Pattern CI = Pattern.compile("[1-9][0-9]{4,8}");
    private static final Pattern COMPLEMENTO = Pattern.compile("[0-9A-Z]{2}");
    private static final Pattern TELEFONO = Pattern.compile("[2-7][0-9]{7}");

    private ValidadorIdentidad() {
    }

    public static int ci(String texto) {
        String valor = texto == null ? "" : texto.strip();
        if (!CI.matcher(valor).matches()) {
            throw invalido("La cédula (ci) debe tener de 5 a 9 dígitos, sin cero inicial, signos, espacios ni separadores.");
        }
        return Integer.parseInt(valor);
    }

    /** El complemento es opcional; se admite en minúsculas y se guarda en mayúsculas como lo emite el SEGIP. */
    public static String complemento(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String valor = texto.strip().toUpperCase(Locale.ROOT);
        if (!COMPLEMENTO.matcher(valor).matches()) {
            throw invalido("El complemento de la cédula debe tener exactamente 2 letras o dígitos (por ejemplo 1A).");
        }
        return valor;
    }

    public static int telefono(String texto) {
        String valor = texto == null ? "" : texto.strip();
        if (!TELEFONO.matcher(valor).matches()) {
            throw invalido("El teléfono debe tener exactamente 8 dígitos y empezar por una cifra de 2 a 7, sin +591, "
                    + "espacios ni separadores.");
        }
        return Integer.parseInt(valor);
    }

    private static NegocioException invalido(String mensaje) {
        return new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO, mensaje);
    }
}
