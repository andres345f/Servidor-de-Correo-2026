package com.feder.negocio.usuarios;

import com.feder.negocio.comun.NegocioException;

import java.util.regex.Pattern;

/**
 * Validación de textos libres que se guardan en la base (motivos, ubicaciones, nombres).
 * Replica en negocio las condiciones de los CHECK para responder con un mensaje claro
 * en lugar del error genérico de la base.
 */
public final class ValidadorTexto {

    private static final Pattern ALFANUMERICO = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Pattern LETRA = Pattern.compile("\\p{L}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private ValidadorTexto() {
    }

    /** Texto obligatorio con al menos una letra o dígito y sin caracteres de control. */
    public static String obligatorio(String valor, String campo, int maximo) {
        String limpio = valor == null ? "" : valor.strip();
        if (limpio.isEmpty() || !ALFANUMERICO.matcher(limpio).find()) {
            throw invalido(campo + " es obligatorio y debe contener al menos una letra o un dígito.");
        }
        return comprobar(limpio, campo, maximo);
    }

    /** Igual que {@link #obligatorio} pero admite ausencia (nulo o vacío). */
    public static String opcional(String valor, String campo, int maximo) {
        if (valor == null || valor.isBlank()) {
            return null;
        }
        return obligatorio(valor, campo, maximo);
    }

    /** Nombres y apellidos: al menos una letra y ningún carácter de control (ck_usuario_nombres). */
    public static String nombrePersona(String valor, String campo) {
        String limpio = valor == null ? "" : valor.strip();
        if (!LETRA.matcher(limpio).find()) {
            throw invalido(campo + " debe contener al menos una letra.");
        }
        return comprobar(limpio, campo, 80);
    }

    private static String comprobar(String limpio, String campo, int maximo) {
        if (CONTROL.matcher(limpio).find()) {
            throw invalido(campo + " no puede contener caracteres de control.");
        }
        if (limpio.length() > maximo) {
            throw invalido(campo + " admite como máximo " + maximo + " caracteres.");
        }
        return limpio;
    }

    private static NegocioException invalido(String mensaje) {
        return new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO, mensaje);
    }
}
