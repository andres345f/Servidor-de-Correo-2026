package com.feder.herramientas.correolocal;

import java.io.IOException;
import java.net.Socket;
import java.util.Objects;

/**
 * Servidor POP3 local para pruebas y demostraciones, sobre los mismos {@link BuzonesEnMemoria} que el SMTP local.
 */
public final class ServidorPop3Local extends ServidorTcpBase {

    private final ConfiguracionCorreoLocal configuracion;
    private final BuzonesEnMemoria buzones;

    public ServidorPop3Local(ConfiguracionCorreoLocal configuracion, BuzonesEnMemoria buzones) {
        super("POP3 local", configuracion.puertoPop3(), configuracion.bitacora());
        if (configuracion.pop3TlsImplicito() && configuracion.contextoTls() == null) {
            throw new IllegalArgumentException("El TLS implícito de POP3 requiere un contexto TLS");
        }
        this.configuracion = configuracion;
        this.buzones = Objects.requireNonNull(buzones, "buzones");
    }

    @Override
    protected void atender(Socket cliente) throws IOException {
        new SesionPop3Local(configuracion, buzones).atender(cliente);
    }
}
