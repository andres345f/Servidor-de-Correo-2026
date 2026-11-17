package com.feder.presentacion.correo;

/**
 * Validación conservadora de direcciones de correo usadas en comandos y cabeceras.
 *
 * No pretende cubrir toda la gramática de RFC 5321; rechaza todo lo que permitiría
 * inyectar comandos o cabeceras (CR, LF, espacios, '&lt;', '&gt;', comas, comillas).
 */
public final class ValidadorDireccion {

    public static final int LONGITUD_MAXIMA = 254;
    private static final String PROHIBIDOS = "<>()[],;:\"\\";

    private ValidadorDireccion() {
    }

    public static boolean esValida(String direccion) {
        if (direccion == null || direccion.length() < 3 || direccion.length() > LONGITUD_MAXIMA) {
            return false;
        }
        for (int i = 0; i < direccion.length(); i++) {
            char c = direccion.charAt(i);
            if (c < 0x21 || c > 0x7E || PROHIBIDOS.indexOf(c) >= 0) {
                return false;
            }
        }
        int arroba = direccion.indexOf('@');
        return arroba > 0 && arroba == direccion.lastIndexOf('@') && arroba < direccion.length() - 1
                && !direccion.endsWith(".") && direccion.indexOf("..", arroba) < 0;
    }

    /**
     * Devuelve la dirección si es válida. El mensaje de error no repite el valor recibido
     * para no propagar texto malicioso a los registros.
     */
    public static String exigirValida(String direccion, String campo) {
        if (!esValida(direccion)) {
            throw new IllegalArgumentException("La dirección de " + campo
                    + " no es válida: debe ser usuario@dominio sin espacios, CR, LF ni '<' '>'");
        }
        return direccion;
    }
}
