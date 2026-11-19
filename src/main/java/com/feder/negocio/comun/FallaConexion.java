package com.feder.negocio.comun;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;

/**
 * Reconoce un fallo por falta de conexión con la base de datos (servidor inaccesible, credenciales rechazadas,
 * base inexistente, conexión cortada), a diferencia de un error de una operación concreta. Cuando la base no
 * responde, el resto de pasos del ciclo que la necesitan no se intentan: se informa una sola vez.
 */
public final class FallaConexion {

    private FallaConexion() {
    }

    public static boolean esDeBaseDeDatos(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                String estado = sql.getSQLState();
                // 08 = conexión, 28 = autorización, 3D000 = base inexistente, 53300 = demasiadas conexiones,
                // 57P0x = servidor apagándose o reiniciándose.
                if (estado.startsWith("08") || estado.startsWith("28") || estado.equals("3D000")
                        || estado.equals("53300") || estado.startsWith("57P0")) {
                    return true;
                }
            }
            if (t instanceof SQLException && (t.getCause() instanceof ConnectException
                    || t.getCause() instanceof SocketTimeoutException || t.getCause() instanceof UnknownHostException
                    || t.getCause() instanceof NoRouteToHostException)) {
                return true;
            }
        }
        return false;
    }
}
