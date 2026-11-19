package com.feder.presentacion.procesamiento;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.util.Locale;

/**
 * Traduce la causa técnica de un fallo de conexión (la más profunda de la cadena de excepciones) a un motivo
 * que se entiende: tiempo agotado, puerto cerrado, nombre que no se resuelve, contraseña rechazada...
 *
 * @param tipo     clasificación del motivo (decide si tiene sentido diagnosticar la red)
 * @param corto    para la línea de reintento ("tiempo de espera agotado")
 * @param detalle  frase completa para el bloque del primer fallo
 */
record MotivoFalla(Tipo tipo, String corto, String detalle) {

    enum Tipo {
        TIEMPO_AGOTADO, RECHAZADA, NOMBRE, SIN_RUTA, CORTADA, CREDENCIALES, BASE_INEXISTENTE, SATURADO, REINICIO, OTRO;

        /** Solo los problemas de red se diagnostican con DNS, ping y puerto. */
        boolean esDeRed() {
            return this == TIEMPO_AGOTADO || this == RECHAZADA || this == NOMBRE || this == SIN_RUTA || this == CORTADA;
        }
    }

    static MotivoFalla de(Throwable error) {
        Throwable raiz = error;
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                MotivoFalla porEstado = porEstadoSql(sql.getSQLState());
                if (porEstado != null) {
                    return porEstado;
                }
            }
            String mensaje = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (mensaje.contains("rechazó la autenticación")) {
                return new MotivoFalla(Tipo.CREDENCIALES, "contraseña del buzón rechazada",
                        "el servidor de correo rechazó el usuario o la contraseña del buzón.");
            }
            raiz = t;
        }
        String mensaje = raiz.getMessage() == null ? "" : raiz.getMessage().toLowerCase(Locale.ROOT);
        if (raiz instanceof UnknownHostException || mensaje.contains("no se encontró el host")) {
            return new MotivoFalla(Tipo.NOMBRE, "no se resuelve el nombre del servidor",
                    "no se pudo resolver el nombre del servidor (sin internet o DNS con problemas).");
        }
        if (raiz instanceof SocketTimeoutException || mensaje.contains("timed out") || mensaje.contains("tiempo de espera")) {
            return new MotivoFalla(Tipo.TIEMPO_AGOTADO, "tiempo de espera agotado",
                    "el servidor no respondió a tiempo (tiempo de espera agotado).");
        }
        if (raiz instanceof NoRouteToHostException) {
            return new MotivoFalla(Tipo.SIN_RUTA, "sin ruta hasta el servidor",
                    "no hay ruta de red hasta el servidor.");
        }
        if (raiz instanceof ConnectException || mensaje.contains("refused") || mensaje.contains("rechaz")) {
            return new MotivoFalla(Tipo.RECHAZADA, "conexión rechazada (puerto cerrado)",
                    "el servidor respondió, pero rechazó la conexión: el puerto está cerrado o el servicio detenido.");
        }
        if (raiz instanceof SocketException) {
            return new MotivoFalla(Tipo.CORTADA, "conexión cortada",
                    "el servidor cortó la conexión a mitad de la operación.");
        }
        String texto = raiz.getMessage() == null ? raiz.getClass().getSimpleName() : raiz.getMessage();
        return new MotivoFalla(Tipo.OTRO, texto, texto + ".");
    }

    private static MotivoFalla porEstadoSql(String estado) {
        if (estado.equals("28P01") || estado.equals("28000")) {
            return new MotivoFalla(Tipo.CREDENCIALES, "contraseña rechazada (" + estado + ")",
                    "PostgreSQL rechazó el usuario o la contraseña (" + estado + ").");
        }
        if (estado.equals("3D000")) {
            return new MotivoFalla(Tipo.BASE_INEXISTENTE, "la base no existe (3D000)",
                    "la base de datos configurada no existe en el servidor (3D000).");
        }
        if (estado.equals("53300")) {
            return new MotivoFalla(Tipo.SATURADO, "demasiadas conexiones (53300)",
                    "el servidor rechazó la conexión porque tiene demasiadas conexiones abiertas (53300).");
        }
        if (estado.startsWith("57P0")) {
            return new MotivoFalla(Tipo.REINICIO, "servidor reiniciándose (" + estado + ")",
                    "el servidor de base de datos se está apagando o reiniciando (" + estado + ").");
        }
        return null; // 08xxx y otros: se busca la causa de red más profunda.
    }
}
