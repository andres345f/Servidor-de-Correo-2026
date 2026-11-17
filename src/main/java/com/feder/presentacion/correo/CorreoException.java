package com.feder.presentacion.correo;

/**
 * Error de red o de protocolo al hablar con un servidor POP3 o SMTP.
 *
 * Conserva la línea de respuesta del servidor (saneada y recortada) para diagnosticar el problema.
 * Nunca debe construirse con el comando enviado, porque podría contener credenciales.
 */
public class CorreoException extends Exception {

    private static final long serialVersionUID = 1L;
    private static final int MAXIMO_RESPUESTA = 300;

    private final String respuestaServidor;

    public CorreoException(String mensaje) {
        this(mensaje, null, null);
    }

    public CorreoException(String mensaje, String respuestaServidor) {
        this(mensaje, respuestaServidor, null);
    }

    public CorreoException(String mensaje, Throwable causa) {
        this(mensaje, null, causa);
    }

    public CorreoException(String mensaje, String respuestaServidor, Throwable causa) {
        super(componer(mensaje, sanear(respuestaServidor)), causa);
        this.respuestaServidor = sanear(respuestaServidor);
    }

    /** Línea que devolvió el servidor, o null si el error no proviene de una respuesta. */
    public String respuestaServidor() {
        return respuestaServidor;
    }

    private static String componer(String mensaje, String respuesta) {
        return respuesta == null ? mensaje : mensaje + " (respuesta del servidor: " + respuesta + ")";
    }

    // Quitamos caracteres de control para que una respuesta maliciosa no altere los registros.
    private static String sanear(String linea) {
        if (linea == null) {
            return null;
        }
        StringBuilder limpio = new StringBuilder(Math.min(linea.length(), MAXIMO_RESPUESTA));
        for (int i = 0; i < linea.length() && limpio.length() < MAXIMO_RESPUESTA; i++) {
            char c = linea.charAt(i);
            limpio.append(Character.isISOControl(c) ? ' ' : c);
        }
        return linea.length() > MAXIMO_RESPUESTA ? limpio + "..." : limpio.toString();
    }
}
