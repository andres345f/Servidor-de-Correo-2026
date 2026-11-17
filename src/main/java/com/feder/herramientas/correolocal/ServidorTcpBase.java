package com.feder.herramientas.correolocal;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Base de los servidores locales: escucha solo en 127.0.0.1, atiende cada conexión en un hilo daemon
 * y al detenerse cierra el socket de escucha y todas las conexiones abiertas.
 */
public abstract class ServidorTcpBase implements AutoCloseable {

    private static final int INACTIVIDAD_MS = 60_000;

    private final String nombre;
    private final int puertoSolicitado;
    protected final Consumer<String> bitacora;
    private final Set<Socket> conexiones = ConcurrentHashMap.newKeySet();
    private ServerSocket servidor;
    private ExecutorService hilos;
    private volatile boolean activo;

    protected ServidorTcpBase(String nombre, int puerto, Consumer<String> bitacora) {
        this.nombre = nombre;
        this.puertoSolicitado = puerto;
        this.bitacora = bitacora;
    }

    public synchronized void iniciar() throws IOException {
        if (servidor != null) {
            throw new IllegalStateException(nombre + " ya está iniciado");
        }
        ServerSocket escucha = new ServerSocket();
        try {
            // Solo la interfaz de bucle local: este servidor no debe quedar expuesto a la red.
            escucha.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), puertoSolicitado));
        } catch (IOException e) {
            escucha.close();
            throw e;
        }
        servidor = escucha;
        hilos = Executors.newCachedThreadPool(tarea -> {
            Thread hilo = new Thread(tarea, nombre);
            hilo.setDaemon(true);
            return hilo;
        });
        activo = true;
        hilos.execute(this::aceptarConexiones);
        bitacora.accept(nombre + " escuchando en 127.0.0.1:" + escucha.getLocalPort());
    }

    public synchronized int puerto() {
        if (servidor == null) {
            throw new IllegalStateException(nombre + " no está iniciado");
        }
        return servidor.getLocalPort();
    }

    public synchronized void detener() {
        if (servidor == null) {
            return;
        }
        activo = false;
        cerrar(servidor);
        for (Socket conexion : conexiones) {
            cerrar(conexion);
        }
        hilos.shutdownNow();
        try {
            if (!hilos.awaitTermination(5, TimeUnit.SECONDS)) {
                bitacora.accept(nombre + ": algunos hilos no terminaron a tiempo");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        servidor = null;
        bitacora.accept(nombre + " detenido");
    }

    @Override
    public void close() {
        detener();
    }

    /** Atiende una conexión completa. Los sockets que la sesión cree (TLS) deben cerrarse en ella. */
    protected abstract void atender(Socket cliente) throws IOException;

    /** Eleva una conexión aceptada a TLS en modo servidor. */
    protected static SSLSocket elevarTls(Socket socket, SSLContext contexto) throws IOException {
        SSLSocket seguro = (SSLSocket) contexto.getSocketFactory()
                .createSocket(socket, socket.getInetAddress().getHostAddress(), socket.getPort(), true);
        seguro.setUseClientMode(false);
        seguro.startHandshake();
        return seguro;
    }

    private void aceptarConexiones() {
        ServerSocket escucha = servidor;
        while (activo) {
            Socket cliente;
            try {
                cliente = escucha.accept();
            } catch (IOException e) {
                if (activo) {
                    bitacora.accept(nombre + ": error al aceptar una conexión: " + e.getMessage());
                }
                if (escucha.isClosed()) {
                    return;
                }
                continue;
            }
            conexiones.add(cliente);
            try {
                hilos.execute(() -> atenderYCerrar(cliente));
            } catch (RejectedExecutionException e) {
                bitacora.accept(nombre + ": conexión rechazada porque el servidor se está deteniendo");
                conexiones.remove(cliente);
                cerrar(cliente);
            }
        }
    }

    private void atenderYCerrar(Socket cliente) {
        try {
            cliente.setSoTimeout(INACTIVIDAD_MS);
            atender(cliente);
        } catch (EOFException e) {
            bitacora.accept(nombre + ": el cliente cerró la conexión sin despedirse");
        } catch (SocketTimeoutException e) {
            bitacora.accept(nombre + ": conexión cerrada por inactividad");
        } catch (IOException | RuntimeException e) {
            if (activo) {
                bitacora.accept(nombre + ": conexión terminada con error: " + e.getMessage());
            }
        } finally {
            conexiones.remove(cliente);
            cerrar(cliente);
        }
    }

    private void cerrar(AutoCloseable recurso) {
        try {
            recurso.close();
        } catch (Exception e) {
            bitacora.accept(nombre + ": no se pudo cerrar un socket: " + e.getMessage());
        }
    }
}
