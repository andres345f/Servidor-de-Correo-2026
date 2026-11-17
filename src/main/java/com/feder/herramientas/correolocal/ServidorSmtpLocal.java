package com.feder.herramientas.correolocal;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/**
 * Servidor SMTP local para pruebas y demostraciones. Entrega cada mensaje en {@link BuzonesEnMemoria}.
 *
 * Permite inyectar fallos: las próximas N órdenes DATA se responden con el código indicado (4xx o 5xx).
 */
public final class ServidorSmtpLocal extends ServidorTcpBase {

    private final ConfiguracionCorreoLocal configuracion;
    private final BuzonesEnMemoria buzones;
    private int fallosPendientes;
    private int codigoFallo;

    public ServidorSmtpLocal(ConfiguracionCorreoLocal configuracion, BuzonesEnMemoria buzones) {
        super("SMTP local", configuracion.puertoSmtp(), configuracion.bitacora());
        this.configuracion = configuracion;
        this.buzones = Objects.requireNonNull(buzones, "buzones");
    }

    /** Las próximas {@code cantidad} órdenes DATA se responderán con {@code codigo} (por ejemplo 451 o 554). */
    public synchronized void fallarSiguientesData(int cantidad, int codigo) {
        if (codigo < 400 || codigo > 599) {
            throw new IllegalArgumentException("El código de fallo debe ser 4xx o 5xx");
        }
        if (cantidad < 0) {
            throw new IllegalArgumentException("La cantidad de fallos no puede ser negativa");
        }
        this.fallosPendientes = cantidad;
        this.codigoFallo = codigo;
    }

    /** Devuelve el código a simular para este DATA, o 0 si debe aceptarse normalmente. */
    synchronized int tomarFalloData() {
        if (fallosPendientes == 0) {
            return 0;
        }
        fallosPendientes--;
        return codigoFallo;
    }

    @Override
    protected void atender(Socket cliente) throws IOException {
        new SesionSmtpLocal(configuracion, buzones, this).atender(cliente);
    }
}
