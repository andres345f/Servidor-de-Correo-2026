package com.feder.presentacion.correo;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Apertura de sockets hacia servidores de correo, con TLS implícito o elevación posterior.
 *
 * TLS siempre valida el certificado y el nombre del host. Si la negociación falla,
 * la conexión se cierra: jamás se continúa en texto plano.
 */
public final class ConectorSocket {

    /** Consultas DNS que pueden estar en curso a la vez; si el sistema operativo deja colgadas más, se rechazan. */
    static final int MAXIMO_CONSULTAS_DNS = 4;

    /**
     * Hilos para resolver nombres (demonios: no impiden que la aplicación termine). El grupo tiene un tope: si
     * varias consultas quedan bloqueadas en el sistema operativo, las siguientes fallan de inmediato en lugar de
     * acumular hilos sin límite.
     */
    private static final ExecutorService RESOLUCION_DNS = new ThreadPoolExecutor(0, MAXIMO_CONSULTAS_DNS,
            60, TimeUnit.SECONDS, new SynchronousQueue<>(), tarea -> {
                Thread hilo = new Thread(tarea, "resolucion-dns");
                hilo.setDaemon(true);
                return hilo;
            });

    private ConectorSocket() {
    }

    /** Conecta con el servidor y, si el transporte es TLS, negocia TLS antes de devolver el socket. */
    public static Socket abrir(ParametrosConexion parametros) throws CorreoException {
        return abrir(parametros, socket -> { });
    }

    /**
     * Igual que {@link #abrir(ParametrosConexion)}, pero entrega el socket a {@code alCrear} antes de conectar.
     * Así otro hilo (el vigilante del plazo) puede cerrarlo mientras la conexión o el saludo TLS siguen en curso:
     * el tiempo de espera del socket solo limita cada lectura y no basta frente a un servidor que responde muy
     * lento o que acepta la conexión TCP y nunca contesta el saludo TLS.
     */
    public static Socket abrir(ParametrosConexion parametros, Consumer<Socket> alCrear) throws CorreoException {
        InetAddress direccion = resolver(parametros.host(), parametros.timeoutMs());
        Socket socket = new Socket();
        alCrear.accept(socket);
        try {
            socket.connect(new InetSocketAddress(direccion, parametros.puerto()), parametros.timeoutMs());
            socket.setSoTimeout(parametros.timeoutMs());
        } catch (UnknownHostException e) {
            throw cerrarTrasError(socket, new CorreoException("No se encontró el host " + parametros.host(), e));
        } catch (SocketTimeoutException e) {
            throw cerrarTrasError(socket, new CorreoException("Tiempo de espera agotado al conectar con "
                    + destino(parametros), e));
        } catch (IOException e) {
            throw cerrarTrasError(socket, new CorreoException("No se pudo conectar con " + destino(parametros)
                    + ": " + e.getMessage(), e));
        }
        if (parametros.transporte() == TransporteSeguro.TLS) {
            return elevarATls(socket, parametros);
        }
        return socket;
    }

    /**
     * Resuelve el nombre del servidor esperando como máximo timeoutMs. Java no puede interrumpir una consulta DNS
     * del sistema operativo; por eso se hace en un hilo aparte y, si tarda demasiado, se abandona con un error en
     * lugar de detener el ciclo. El nombre original se sigue usando para verificar el certificado TLS.
     */
    static InetAddress resolver(String host, int timeoutMs) throws CorreoException {
        Future<InetAddress> consulta;
        try {
            consulta = RESOLUCION_DNS.submit(() -> InetAddress.getByName(host));
        } catch (RejectedExecutionException e) {
            throw new CorreoException("Hay " + MAXIMO_CONSULTAS_DNS + " consultas DNS sin terminar; no se resolvió " + host
                    + " (se reintentará en el próximo ciclo)", e);
        }
        try {
            return consulta.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            consulta.cancel(true);
            throw new CorreoException("La resolución DNS de " + host + " superó " + timeoutMs + " ms", e);
        } catch (ExecutionException e) {
            Throwable causa = e.getCause();
            if (causa instanceof UnknownHostException) {
                throw new CorreoException("No se encontró el host " + host, causa);
            }
            throw new CorreoException("No se pudo resolver " + host + ": " + causa.getMessage(), causa);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CorreoException("Se interrumpió la resolución DNS de " + host, e);
        }
    }

    /** Envuelve un socket ya conectado en TLS y completa el handshake con verificación del host. */
    public static SSLSocket elevarATls(Socket plano, ParametrosConexion parametros) throws CorreoException {
        SSLSocketFactory fabrica = parametros.fabricaSsl() != null
                ? parametros.fabricaSsl()
                : (SSLSocketFactory) SSLSocketFactory.getDefault();
        try {
            SSLSocket seguro = (SSLSocket) fabrica.createSocket(plano, parametros.host(), parametros.puerto(), true);
            SSLParameters ssl = seguro.getSSLParameters();
            // Sin este algoritmo Java valida la cadena del certificado pero no que corresponda al host.
            ssl.setEndpointIdentificationAlgorithm("HTTPS");
            seguro.setSSLParameters(ssl);
            seguro.setUseClientMode(true);
            seguro.setSoTimeout(parametros.timeoutMs());
            seguro.startHandshake();
            return seguro;
        } catch (IOException e) {
            throw cerrarTrasError(plano, new CorreoException("No se pudo establecer TLS con " + destino(parametros)
                    + "; la conexión se cerró sin enviar credenciales: " + e.getMessage(), e));
        }
    }

    /**
     * Indica si el socket está conectado a la propia máquina (127.0.0.1 o ::1). Las credenciales sin cifrar
     * solo se aceptan hacia el servidor local de pruebas: comprobarlo sobre la conexión real impide que una
     * configuración local apuntada a otro host envíe la contraseña en claro por la red.
     */
    public static boolean esLoopback(Socket socket) {
        InetAddress remota = socket == null ? null : socket.getInetAddress();
        return remota != null && remota.isLoopbackAddress();
    }

    private static CorreoException cerrarTrasError(Socket socket, CorreoException error) {
        try {
            socket.close();
        } catch (IOException e) {
            error.addSuppressed(e);
        }
        return error;
    }

    private static String destino(ParametrosConexion parametros) {
        return parametros.host() + ":" + parametros.puerto();
    }
}
