package com.feder.herramientas.correolocal;

import java.io.IOException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/**
 * Servidores SMTP y POP3 locales sobre los mismos buzones en memoria.
 *
 * Sirve para pruebas automatizadas (puertos efímeros) y para demostrar el sistema sin un servidor real.
 * main() inicia SMTP en 2525 y POP3 en 1110, en texto plano y solo en 127.0.0.1.
 */
public final class ServidorCorreoLocal implements AutoCloseable {

    public static final int PUERTO_SMTP_DEMO = 2525;
    public static final int PUERTO_POP3_DEMO = 1110;

    private final BuzonesEnMemoria buzones = new BuzonesEnMemoria();
    private final ServidorSmtpLocal smtp;
    private final ServidorPop3Local pop3;

    public ServidorCorreoLocal(ConfiguracionCorreoLocal configuracion) {
        Objects.requireNonNull(configuracion, "configuracion");
        this.smtp = new ServidorSmtpLocal(configuracion, buzones);
        this.pop3 = new ServidorPop3Local(configuracion, buzones);
    }

    public void iniciar() throws IOException {
        smtp.iniciar();
        try {
            pop3.iniciar();
        } catch (IOException e) {
            // Si POP3 no puede escuchar (puerto ocupado), no dejamos el SMTP abierto a medias.
            smtp.detener();
            throw e;
        }
    }

    public void detener() {
        pop3.detener();
        smtp.detener();
    }

    @Override
    public void close() {
        detener();
    }

    public int puertoSmtp() {
        return smtp.puerto();
    }

    public int puertoPop3() {
        return pop3.puerto();
    }

    public BuzonesEnMemoria buzones() {
        return buzones;
    }

    public ServidorSmtpLocal smtp() {
        return smtp;
    }

    public ServidorPop3Local pop3() {
        return pop3;
    }

    /**
     * Demo manual. Argumentos opcionales: --smtp=PUERTO --pop3=PUERTO --cuenta=direccion:contrasena (repetible).
     * Sin --cuenta se usan las variables FEDER_LOCAL_USUARIO y FEDER_LOCAL_CONTRASENA.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        DateTimeFormatter hora = DateTimeFormatter.ofPattern("HH:mm:ss");
        ConfiguracionCorreoLocal configuracion = new ConfiguracionCorreoLocal()
                .puertoSmtp(PUERTO_SMTP_DEMO)
                .puertoPop3(PUERTO_POP3_DEMO)
                .bitacora(mensaje -> System.out.println("[" + LocalTime.now().format(hora) + "] " + mensaje));
        for (String argumento : args) {
            if (argumento.startsWith("--smtp=")) {
                configuracion.puertoSmtp(Integer.parseInt(argumento.substring(7)));
            } else if (argumento.startsWith("--pop3=")) {
                configuracion.puertoPop3(Integer.parseInt(argumento.substring(7)));
            } else if (argumento.startsWith("--cuenta=") && argumento.indexOf(':') > 9) {
                int separador = argumento.indexOf(':');
                configuracion.cuenta(argumento.substring(9, separador), argumento.substring(separador + 1));
            } else {
                System.err.println("Argumento no reconocido: " + argumento.split(":", 2)[0]);
                System.exit(2);
            }
        }
        String usuario = System.getenv("FEDER_LOCAL_USUARIO");
        String contrasena = System.getenv("FEDER_LOCAL_CONTRASENA");
        if (configuracion.usuarios().isEmpty() && usuario != null && contrasena != null) {
            configuracion.cuenta(usuario, contrasena);
        }
        if (configuracion.usuarios().isEmpty()) {
            System.err.println("No hay cuentas configuradas. Use --cuenta=direccion:contrasena o defina"
                    + " FEDER_LOCAL_USUARIO y FEDER_LOCAL_CONTRASENA.");
            System.exit(2);
        }

        ServidorCorreoLocal servidor = new ServidorCorreoLocal(configuracion);
        servidor.iniciar();
        System.out.println("Servidor de correo local de FEDER (texto plano, solo 127.0.0.1)");
        System.out.println("  SMTP: 127.0.0.1:" + servidor.puertoSmtp());
        System.out.println("  POP3: 127.0.0.1:" + servidor.puertoPop3());
        for (String cuenta : configuracion.usuarios()) {
            System.out.println("  Cuenta: " + cuenta + " (la contraseña no se muestra)");
        }
        System.out.println("Pulse Ctrl+C para detenerlo.");

        CountDownLatch fin = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            servidor.detener();
            fin.countDown();
        }, "detener-correo-local"));
        fin.await();
    }
}
