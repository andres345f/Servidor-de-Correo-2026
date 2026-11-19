package com.feder.presentacion.correo;

/**
 * Líneas de la traza de protocolo que se muestra en pantalla: "C : comando   S : respuesta".
 * C es lo que envía el sistema y S lo que responde el servidor. Las contraseñas nunca pasan por aquí:
 * PASS se traza como "PASS ********" y AUTH no se traza.
 */
public final class Traza {

    private static final int ANCHO_COMANDO = 26;
    private static final int MAXIMO_RESPUESTA = 70;

    private Traza() {
    }

    /** Un comando del sistema y la respuesta del servidor en la misma línea. */
    public static String par(String comando, String respuesta) {
        String c = "C : " + comando;
        String relleno = c.length() < ANCHO_COMANDO ? " ".repeat(ANCHO_COMANDO - c.length()) : "  ";
        return c + relleno + servidor(respuesta);
    }

    /** Una línea que el servidor envía sin que se le pida nada (el saludo inicial). */
    public static String servidor(String respuesta) {
        String texto = respuesta == null ? "" : respuesta.strip();
        return "S : " + (texto.length() > MAXIMO_RESPUESTA ? texto.substring(0, MAXIMO_RESPUESTA) + "..." : texto);
    }
}
