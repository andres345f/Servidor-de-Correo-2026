package com.feder.presentacion.correo.pop3;

import static com.feder.presentacion.correo.pop3.RespuestasPop3.interpretarEstado;
import static com.feder.presentacion.correo.pop3.RespuestasPop3.leerListado;

import com.feder.presentacion.correo.CanalLineas;
import com.feder.presentacion.correo.ConectorSocket;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.LimiteExcedidoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.RellenoPuntos;
import com.feder.presentacion.correo.Secretos;
import com.feder.presentacion.correo.TransporteSeguro;
import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cliente POP3 (RFC 1939, CAPA de RFC 2449 y STLS de RFC 2595) escrito sobre sockets.
 *
 * Uso típico: conectar(), autenticar(), uidl(), retr(n), dele(n) y quit().
 * Antes de leer el cuerpo de una respuesta multilínea siempre se verifica el "+OK" inicial.
 */
public final class Pop3Client implements AutoCloseable {

    /** Tamaño máximo aceptado para un mensaje descargado con RETR. */
    public static final int MAXIMO_BYTES_MENSAJE = 20 * 1024 * 1024;
    private static final Pattern UIDL_VALIDO = Pattern.compile("[\\x21-\\x7E]{1,70}");

    private final ParametrosConexion parametros;
    // volatile: el vigilante del plazo puede cerrar la conexión desde otro hilo.
    private volatile Socket socket;
    /** Socket que se está conectando (antes de completar TCP y TLS); close() también lo cierra. */
    private volatile Socket abriendo;
    private volatile CanalLineas canal;
    /** true desde que se llama a close(): la conexión ya no puede abrirse ni usarse (también si aún no empezaba). */
    private volatile boolean cerrado;
    private boolean cifrada;
    private Set<String> capacidades = Set.of();

    public Pop3Client(ParametrosConexion parametros) {
        this.parametros = Objects.requireNonNull(parametros, "parametros");
    }

    /** Abre la conexión, valida el saludo, consulta CAPA y, si se configuró, eleva a TLS con STLS. */
    public void conectar() throws CorreoException {
        if (socket != null) {
            throw new IllegalStateException("El cliente POP3 ya está conectado");
        }
        if (cerrado) {
            throw new CorreoException("La conexión POP3 se canceló antes de abrirse (se cumplió el plazo)");
        }
        // Si el plazo vence mientras se crea el socket, close() lo verá en abriendo o el socket se cierra aquí.
        Socket abierto = ConectorSocket.abrir(parametros, nuevo -> {
            abriendo = nuevo;
            if (cerrado) {
                cerrarSinError(nuevo);
            }
        });
        socket = abierto;
        abriendo = null;
        cifrada = parametros.transporte() == TransporteSeguro.TLS;
        try {
            canal = CanalLineas.sobre(abierto);
            interpretarEstado(canal().leerLinea(), "el saludo inicial");
            capacidades = consultarCapacidades();
            if (parametros.transporte() == TransporteSeguro.STARTTLS) {
                elevarConStls();
            }
        } catch (IOException e) {
            throw cerrarPorError(new CorreoException("Error de comunicación con el servidor POP3 al conectar", e));
        } catch (CorreoException e) {
            throw cerrarPorError(e);
        }
    }

    /** Envía USER y PASS. Se niega a hacerlo sin cifrado salvo que se permita texto plano. */
    public void autenticar() throws CorreoException {
        exigirConexion();
        if (!parametros.tieneCredenciales()) {
            throw new IllegalStateException("No se configuraron credenciales POP3");
        }
        if (!cifrada && !(parametros.permitirTextoPlano() && ConectorSocket.esLoopback(socket))) {
            throw new CorreoException("Se rechaza enviar usuario y contraseña POP3 por una conexión sin cifrar;"
                    + " configure TLS o STARTTLS (el texto plano solo se admite hacia el servidor local 127.0.0.1)");
        }
        comando("USER " + parametros.usuario(), "USER");
        char[] clave = parametros.contrasena();
        byte[] secreto = Secretos.utf8(clave);
        byte[] linea = Secretos.concatenar("PASS ", secreto);
        try {
            canal().escribirLinea(linea);
            String respuesta = canal().leerLinea();
            if (!respuesta.startsWith("+OK")) {
                // No se conserva el texto libre del servidor (algunos repiten datos de la credencial);
                // solo el código de respuesta de RFC 2449, como [AUTH] o [IN-USE], útil para el diagnóstico.
                throw new CorreoException("El servidor POP3 rechazó la autenticación", soloCodigoDeRespuesta(respuesta));
            }
        } catch (IOException e) {
            throw new CorreoException("Error de comunicación con el servidor POP3 durante la autenticación", e);
        } finally {
            Arrays.fill(clave, '\0');
            Secretos.borrar(secreto, linea);
        }
    }

    private static final Pattern CODIGO_RESPUESTA = Pattern.compile("^(-ERR) (\\[[A-Z0-9/-]{1,40}\\]).*");

    static String soloCodigoDeRespuesta(String linea) {
        java.util.regex.Matcher m = CODIGO_RESPUESTA.matcher(linea);
        if (m.matches()) {
            return m.group(1) + " " + m.group(2);
        }
        return linea.startsWith("-ERR") ? "-ERR" : "(respuesta no reconocida)";
    }

    /** Vuelve a consultar CAPA y devuelve las palabras clave anunciadas (en mayúsculas). */
    public Set<String> capa() throws CorreoException {
        exigirConexion();
        try {
            capacidades = consultarCapacidades();
            return capacidades;
        } catch (IOException e) {
            throw new CorreoException("Error de comunicación con el servidor POP3 durante CAPA", e);
        }
    }

    /** Capacidades obtenidas en la última consulta CAPA (vacío si el servidor no la admite). */
    public Set<String> capacidades() {
        return capacidades;
    }

    public boolean esCifrada() {
        return cifrada;
    }

    public EstadoBuzon stat() throws CorreoException {
        String texto = comando("STAT", "STAT");
        String[] partes = texto.split(" +");
        try {
            int cantidad = Integer.parseInt(partes[0]);
            long octetos = Long.parseLong(partes[1]);
            if (cantidad < 0 || octetos < 0) {
                throw new CorreoException("Respuesta STAT con valores negativos", "+OK " + texto);
            }
            return new EstadoBuzon(cantidad, octetos);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            throw new CorreoException("Respuesta STAT mal formada", "+OK " + texto, e);
        }
    }

    /** Devuelve el mapa número de mensaje → identificador único (UIDL) en el orden del servidor. */
    public Map<Integer, String> uidl() throws CorreoException {
        exigirConexion();
        try {
            canal().escribirLinea("UIDL");
            String linea = canal().leerLinea();
            if (linea.startsWith("-ERR")) {
                throw new CorreoException("El servidor POP3 no admite UIDL; sin identificadores únicos no se puede"
                        + " garantizar que un mismo mensaje no se procese dos veces", linea);
            }
            interpretarEstado(linea, "UIDL");
            Map<Integer, String> mapa = new LinkedHashMap<>();
            for (String entrada : leerListado(canal(), RespuestasPop3.MAXIMO_BYTES_UIDL)) {
                String[] partes = entrada.trim().split(" +");
                if (partes.length != 2 || !UIDL_VALIDO.matcher(partes[1]).matches()) {
                    throw new CorreoException("Línea UIDL mal formada", entrada);
                }
                if (mapa.put(RespuestasPop3.numeroPositivo(partes[0], entrada), partes[1]) != null) {
                    throw new CorreoException("Número de mensaje repetido en UIDL", entrada);
                }
            }
            return Collections.unmodifiableMap(mapa);
        } catch (IOException e) {
            throw new CorreoException("Error de comunicación con el servidor POP3 durante UIDL", e);
        }
    }

    /**
     * Descarga el mensaje completo como bytes crudos (con CRLF y sin el relleno de puntos).
     * Si el mensaje excede el máximo, la sesión queda inutilizable y debe cerrarse.
     */
    public byte[] retr(int numero) throws CorreoException {
        exigirNumero(numero);
        comando("RETR " + numero, "RETR " + numero);
        try {
            return RellenoPuntos.leerBloque(canal(), MAXIMO_BYTES_MENSAJE);
        } catch (LimiteExcedidoException e) {
            throw new MensajeDemasiadoGrandeException("El mensaje " + numero + " supera el tamaño máximo permitido", e);
        } catch (IOException e) {
            throw new CorreoException("Error al descargar el mensaje " + numero + ": " + e.getMessage(), e);
        }
    }

    /** Marca el mensaje para borrado; el servidor lo elimina solo al terminar con QUIT. */
    public void dele(int numero) throws CorreoException {
        exigirNumero(numero);
        comando("DELE " + numero, "DELE " + numero);
    }

    public void noop() throws CorreoException {
        comando("NOOP", "NOOP");
    }

    /** Termina la sesión (el servidor aplica los borrados) y cierra el socket. */
    public void quit() throws CorreoException {
        CorreoException error = null;
        try {
            comando("QUIT", "QUIT");
        } catch (CorreoException e) {
            error = e;
        }
        try {
            close();
        } catch (CorreoException e) {
            if (error == null) {
                error = e;
            } else {
                error.addSuppressed(e);
            }
        }
        if (error != null) {
            throw error;
        }
    }

    /** Cierra el socket sin enviar QUIT: los mensajes marcados con DELE no se borran. */
    @Override
    public void close() throws CorreoException {
        cerrado = true;
        Socket enCurso = abriendo;
        abriendo = null;
        if (enCurso != null) {
            try {
                // Interrumpe una conexión o un saludo TLS todavía en curso.
                enCurso.close();
            } catch (IOException e) {
                // Se intenta cerrar también el socket ya establecido, si lo hay.
            }
        }
        Socket actual = socket;
        socket = null;
        canal = null;
        if (actual == null) {
            return;
        }
        try {
            actual.close();
        } catch (IOException e) {
            throw new CorreoException("No se pudo cerrar la conexión POP3", e);
        }
    }

    private void elevarConStls() throws IOException, CorreoException {
        // Verificamos que el servidor anuncie STLS. Si no lo anuncia, cancelamos en lugar de seguir sin cifrar.
        if (!capacidades.contains("STLS")) {
            throw new CorreoException("El servidor POP3 no anuncia STLS; se cancela la conexión para no enviar"
                    + " credenciales sin cifrar");
        }
        canal().escribirLinea("STLS");
        interpretarEstado(canal().leerLinea(), "STLS");
        if (canal().hayDatosPendientes()) {
            throw new CorreoException("El servidor POP3 envió datos antes de iniciar TLS; se cancela la conexión");
        }
        Socket plano = socket;
        if (plano == null) {
            throw new CorreoException("La conexión se cerró antes de iniciar TLS (plazo vencido)");
        }
        Socket seguro = ConectorSocket.elevarATls(plano, parametros);
        socket = seguro;
        canal = CanalLineas.sobre(seguro);
        cifrada = true;
        // Lo anunciado antes de TLS pudo ser alterado por un intermediario, así que se consulta otra vez.
        capacidades = consultarCapacidades();
    }

    private Set<String> consultarCapacidades() throws IOException, CorreoException {
        canal().escribirLinea("CAPA");
        String linea = canal().leerLinea();
        // Un servidor anterior a RFC 2449 responde -ERR: no es un fallo, solo no informa capacidades.
        if (linea.startsWith("-ERR")) {
            return Set.of();
        }
        interpretarEstado(linea, "CAPA");
        Set<String> resultado = new HashSet<>();
        for (String capacidad : leerListado(canal(), RespuestasPop3.MAXIMO_BYTES_CAPA)) {
            String palabra = capacidad.trim().split(" +", 2)[0];
            if (!palabra.isEmpty()) {
                resultado.add(palabra.toUpperCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(resultado);
    }

    private String comando(String linea, String descripcion) throws CorreoException {
        exigirConexion();
        try {
            canal().escribirLinea(linea);
            return interpretarEstado(canal().leerLinea(), descripcion);
        } catch (IOException e) {
            throw new CorreoException("Error de comunicación con el servidor POP3 durante " + descripcion, e);
        }
    }

    private void exigirConexion() throws CorreoException {
        if (canal == null) {
            if (cerrado) {
                // Otro hilo la cerró (plazo vencido): es un fallo de comunicación, no un error de programación.
                throw new CorreoException("La conexión POP3 se cerró (por ejemplo, porque se cumplió el plazo)");
            }
            throw new IllegalStateException("El cliente POP3 no está conectado; llame primero a conectar()");
        }
    }

    private static void exigirNumero(int numero) {
        if (numero < 1) {
            throw new IllegalArgumentException("El número de mensaje POP3 debe ser mayor o igual a 1");
        }
    }

    private CorreoException cerrarPorError(CorreoException error) {
        try {
            close();
        } catch (CorreoException e) {
            error.addSuppressed(e);
        }
        return error;
    }

    /** Canal de la conexión; si otro hilo la cerró (plazo vencido), falla con un error de comunicación. */
    private CanalLineas canal() throws IOException {
        CanalLineas actual = canal;
        if (actual == null) {
            throw new IOException("La conexión POP3 se cerró (por ejemplo, porque se cumplió el plazo)");
        }
        return actual;
    }

    private static void cerrarSinError(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Ya estaba cerrado: no hay nada más que hacer.
        }
    }
}
