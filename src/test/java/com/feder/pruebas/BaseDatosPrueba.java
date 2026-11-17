package com.feder.pruebas;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.ConexionBD;
import com.feder.datos.GestorTransacciones;
import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.negocio.seguridad.ServicioIdentidad;
import org.junit.jupiter.api.Assumptions;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.function.Supplier;

/**
 * Apoyo para las pruebas de integración contra el PostgreSQL local (scripts/pg-local.ps1).
 *
 * Cada prueba trabaja dentro de una transacción que SIEMPRE se revierte al final, así la base
 * con esquema y semilla queda intacta y las pruebas no dependen del orden de ejecución.
 * Si la base no está disponible, las pruebas se omiten (Assumptions), no fallan en silencio.
 */
public final class BaseDatosPrueba {

    private static final String URL = System.getProperty("feder.test.db.url",
            "jdbc:postgresql://127.0.0.1:55432/feder_test");
    private static final String USUARIO = System.getProperty("feder.test.db.user", "feder_admin");

    private BaseDatosPrueba() {
    }

    public static ConexionBD conexion() {
        // Algunas pruebas confirman datos y luego los borran: solo contra el clúster local.
        String base = DestinoPruebas.base(URL);
        // Cada conexión que se abra con esta fábrica (también las de limpieza) se verifica con el servidor.
        ConexionBD conexion = new ConexionBD(DestinoPruebas.exigirLocal(URL), USUARIO, "") {
            @Override
            public Connection abrir() {
                Connection c = super.abrir();
                try {
                    DestinoPruebas.exigirConexionLocal(c, base);
                    return c;
                } catch (SQLException e) {
                    // La consulta no llegó a responder (servidor caído o reiniciándose): se trata como «sin servidor».
                    // Un destino que responde pero no es el esperado, en cambio, lanza IllegalStateException y falla.
                    cerrar(c);
                    throw new AccesoDatosException("No se pudo verificar la conexión de pruebas", e);
                } catch (RuntimeException e) {
                    cerrar(c);
                    throw e;
                }
            }
        };
        Connection abierta;
        try {
            abierta = conexion.abrir();
        } catch (AccesoDatosException e) {
            // Sin servidor local la prueba se omite; un destino que no es el esperado, en cambio, la hace fallar.
            Assumptions.abort("PostgreSQL de pruebas no disponible en " + URL + ": " + e.getMessage());
            return null;
        }
        try (Connection c = abierta) {
            Assumptions.assumeTrue(c.isValid(5), "La conexión de pruebas no responde");
            return conexion;
        } catch (SQLException e) {
            Assumptions.abort("PostgreSQL de pruebas no disponible en " + URL + ": " + e.getMessage());
            return null;
        }
    }

    private static void cerrar(Connection c) {
        try {
            c.close();
        } catch (SQLException e) {
            // Se descarta: la conexión ya no se usa.
        }
    }

    /** Contexto de negocio completo sobre la base de pruebas. */
    public static ContextoNegocio contexto() {
        GestorTransacciones transacciones = new GestorTransacciones(conexion());
        Clock reloj = Clock.systemUTC();
        return new ContextoNegocio(transacciones, reloj, new ServicioBitacora(new BitacoraRepositorio()),
                new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
    }

    /**
     * Ejecuta el bloque en una transacción y la revierte siempre al terminar.
     * Dentro del bloque los servicios se unen a esta transacción (enTransaccion es reentrante).
     */
    public static <T> T enTransaccionRevertida(ContextoNegocio contexto, Supplier<T> bloque) {
        RuntimeException[] marca = new RuntimeException[1];
        Object[] resultado = new Object[1];
        try {
            contexto.transacciones().enTransaccion(() -> {
                resultado[0] = bloque.get();
                marca[0] = new ReversionIntencional();
                throw marca[0];
            });
        } catch (ReversionIntencional e) {
            if (e != marca[0]) {
                throw e;
            }
        }
        @SuppressWarnings("unchecked")
        T valor = (T) resultado[0];
        return valor;
    }

    public static void enTransaccionRevertida(ContextoNegocio contexto, Runnable bloque) {
        enTransaccionRevertida(contexto, () -> {
            bloque.run();
            return null;
        });
    }

    /** Solicitante real según la semilla (roles vigentes leídos de la base). Usar dentro de una transacción. */
    public static Solicitante solicitante(String correo) {
        return new ServicioIdentidad(new IdentidadRepositorio()).resolver(correo);
    }

    /** Excepción usada solo para forzar el ROLLBACK al final de cada prueba. */
    private static final class ReversionIntencional extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ReversionIntencional() {
            super("Reversión intencional de la prueba", null, false, false);
        }
    }
}
