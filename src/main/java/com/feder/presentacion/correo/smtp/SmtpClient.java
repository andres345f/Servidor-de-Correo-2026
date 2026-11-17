package com.feder.presentacion.correo.smtp;

import com.feder.presentacion.correo.CanalLineas;
import com.feder.presentacion.correo.ConectorSocket;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.ParametrosConexion;
import com.feder.presentacion.correo.RellenoPuntos;
import com.feder.presentacion.correo.TransporteSeguro;
import com.feder.presentacion.correo.ValidadorDireccion;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Cliente SMTP (RFC 5321, STARTTLS de RFC 3207 y AUTH de RFC 4954) escrito sobre sockets.
 *
 * Uso típico: conectar(), enviar(...) una o varias veces y quit().
 * Los rechazos 4xx se informan como {@link SmtpTransitorioException} y los 5xx como
 * {@link SmtpPermanenteException}, para que quien llama decida si reintentar.
 */
public final class SmtpClient implements AutoCloseable {

    private static final Pattern DOMINIO_EHLO = Pattern.compile("[A-Za-z0-9.-]{1,253}|\\[[0-9.]{7,15}\\]");
    private static final Pattern PALABRA_CLAVE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]*");

    private final ParametrosConexion parametros;
    private final MecanismoAutenticacion mecanismo;
    private final String dominioEhlo;
    // volatile: el vigilante del plazo puede cerrar la conexión desde otro hilo.
    private volatile Socket socket;
    /** Socket que se está conectando (antes de completar TCP y TLS); close() también lo cierra. */
    private volatile Socket abriendo;
    private volatile CanalLineas canal;
    /** true desde que se llama a close(): la conexión ya no puede abrirse ni usarse (también si aún no empezaba). */
    private volatile boolean cerrado;
    private boolean cifrada;
    private Map<String, String> extensiones = Map.of();

    public SmtpClient(ParametrosConexion parametros, MecanismoAutenticacion mecanismo, String dominioEhlo) {
        this.parametros = Objects.requireNonNull(parametros, "parametros");
        this.mecanismo = Objects.requireNonNull(mecanismo, "mecanismo");
        if (dominioEhlo == null || !DOMINIO_EHLO.matcher(dominioEhlo).matches()) {
            throw new IllegalArgumentException("El dominio para EHLO debe ser un nombre de host o una IP entre corchetes");
        }
        this.dominioEhlo = dominioEhlo;
    }

    /** Conecta, valida el 220, saluda con EHLO, eleva a TLS si se configuró y se autentica. */
    public void conectar() throws CorreoException {
        if (socket != null) {
            throw new IllegalStateException("El cliente SMTP ya está conectado");
        }
        if (cerrado) {
            throw new CorreoException("La conexión SMTP se canceló antes de abrirse (se cumplió el plazo)");
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
            esperar(SmtpRespuesta.leer(canal()), 220, "el saludo inicial");
            saludar();
            if (parametros.transporte() == TransporteSeguro.STARTTLS) {
                elevarConStarttls();
            }
            if (mecanismo != MecanismoAutenticacion.NINGUNA) {
                autenticar();
            }
        } catch (IOException e) {
            throw cerrarPorError(new CorreoException("Error de comunicación con el servidor SMTP al conectar", e));
        } catch (CorreoException e) {
            throw cerrarPorError(e);
        }
    }

    /** Extensiones anunciadas en el último EHLO: palabra clave en mayúsculas → parámetros. */
    public Map<String, String> extensiones() {
        return extensiones;
    }

    public boolean esCifrada() {
        return cifrada;
    }

    /**
     * Envía un mensaje ya construido (RFC 5322) y devuelve la línea final de aceptación del servidor.
     * Un remitente vacío o null se envía como "MAIL FROM:&lt;&gt;" (remitente nulo).
     */
    public String enviar(String remitente, List<String> destinatarios, byte[] mensaje) throws CorreoException {
        exigirConexion();
        Objects.requireNonNull(mensaje, "mensaje");
        String desde = remitente == null || remitente.isEmpty() ? ""
                : ValidadorDireccion.exigirValida(remitente, "remitente");
        if (destinatarios == null || destinatarios.isEmpty()) {
            throw new IllegalArgumentException("Debe indicarse al menos un destinatario");
        }
        List<String> para = new ArrayList<>(destinatarios.size());
        for (String destinatario : destinatarios) {
            para.add(ValidadorDireccion.exigirValida(destinatario, "destinatario"));
        }
        try {
            esperar(intercambiar("MAIL FROM:<" + desde + ">"), 250, "MAIL FROM");
            for (String destinatario : para) {
                SmtpRespuesta respuesta = intercambiar("RCPT TO:<" + destinatario + ">");
                if (respuesta.codigo() != 250 && respuesta.codigo() != 251) {
                    throw respuesta.comoExcepcion("El servidor SMTP rechazó un destinatario");
                }
            }
            esperar(intercambiar("DATA"), 354, "DATA");
            RellenoPuntos.escribirBloque(canal(), mensaje);
            SmtpRespuesta aceptacion = SmtpRespuesta.leer(canal());
            esperar(aceptacion, 250, "el contenido del mensaje");
            return aceptacion.ultimaLinea();
        } catch (IOException e) {
            throw new CorreoException("Error de comunicación con el servidor SMTP durante el envío", e);
        } catch (CorreoException e) {
            reiniciarTransaccion(e);
            throw e;
        }
    }

    /** Envío a un único destinatario. */
    public String enviar(String remitente, String destinatario, byte[] mensaje) throws CorreoException {
        return enviar(remitente, List.of(destinatario), mensaje);
    }

    /** Termina la sesión con QUIT y cierra el socket. */
    public void quit() throws CorreoException {
        CorreoException error = null;
        try {
            exigirConexion();
            esperar(intercambiar("QUIT"), 221, "QUIT");
        } catch (IOException e) {
            error = new CorreoException("Error de comunicación con el servidor SMTP durante QUIT", e);
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
            throw new CorreoException("No se pudo cerrar la conexión SMTP", e);
        }
    }

    private void saludar() throws IOException, CorreoException {
        SmtpRespuesta respuesta = intercambiar("EHLO " + dominioEhlo);
        if (respuesta.codigo() == 250) {
            extensiones = interpretarExtensiones(respuesta);
            return;
        }
        // Un servidor antiguo sin ESMTP responde 500/502: se intenta HELO, pero entonces no habrá extensiones.
        if (respuesta.codigo() == 500 || respuesta.codigo() == 502) {
            esperar(intercambiar("HELO " + dominioEhlo), 250, "HELO");
            extensiones = Map.of();
            return;
        }
        throw respuesta.comoExcepcion("El servidor SMTP rechazó EHLO");
    }

    private static Map<String, String> interpretarExtensiones(SmtpRespuesta respuesta) {
        Map<String, String> resultado = new LinkedHashMap<>();
        List<String> lineas = respuesta.textos();
        // La primera línea es el saludo con el nombre del servidor; las extensiones empiezan en la segunda.
        for (int i = 1; i < lineas.size(); i++) {
            String[] partes = lineas.get(i).trim().split(" +", 2);
            if (PALABRA_CLAVE.matcher(partes[0]).matches()) {
                resultado.put(partes[0].toUpperCase(Locale.ROOT), partes.length > 1 ? partes[1].trim() : "");
            }
        }
        return Collections.unmodifiableMap(resultado);
    }

    private void elevarConStarttls() throws IOException, CorreoException {
        if (!extensiones.containsKey("STARTTLS")) {
            throw new CorreoException("El servidor SMTP no anuncia STARTTLS; se cancela la conexión para no"
                    + " continuar sin cifrar");
        }
        esperar(intercambiar("STARTTLS"), 220, "STARTTLS");
        if (canal().hayDatosPendientes()) {
            throw new CorreoException("El servidor SMTP envió datos antes de iniciar TLS; se cancela la conexión");
        }
        Socket plano = socket;
        if (plano == null) {
            throw new CorreoException("La conexión se cerró antes de iniciar TLS (plazo vencido)");
        }
        Socket seguro = ConectorSocket.elevarATls(plano, parametros);
        socket = seguro;
        canal = CanalLineas.sobre(seguro);
        cifrada = true;
        // RFC 3207: tras STARTTLS se descarta lo anunciado antes y se vuelve a saludar.
        extensiones = Map.of();
        saludar();
    }

    private void autenticar() throws IOException, CorreoException {
        if (!parametros.tieneCredenciales()) {
            throw new CorreoException("Se configuró AUTH " + mecanismo + " pero faltan el usuario o la contraseña");
        }
        if (!cifrada && !(parametros.permitirTextoPlano() && ConectorSocket.esLoopback(socket))) {
            throw new CorreoException("Se rechaza autenticarse en SMTP por una conexión sin cifrar;"
                    + " configure TLS o STARTTLS (el texto plano solo se admite hacia el servidor local 127.0.0.1)");
        }
        String anunciados = extensiones.get("AUTH");
        if (anunciados == null
                || !Arrays.asList(anunciados.toUpperCase(Locale.ROOT).split(" +")).contains(mecanismo.name())) {
            throw new CorreoException("El servidor SMTP no anuncia AUTH " + mecanismo.name());
        }
        char[] clave = parametros.contrasena();
        try {
            AutenticadorSmtp.autenticar(mecanismo, parametros.usuario(), clave, linea -> {
                canal().escribirLinea(linea);
                return SmtpRespuesta.leer(canal());
            });
        } finally {
            Arrays.fill(clave, '\0');
        }
    }

    private SmtpRespuesta intercambiar(String linea) throws IOException, CorreoException {
        canal().escribirLinea(linea);
        return SmtpRespuesta.leer(canal());
    }

    private static void esperar(SmtpRespuesta respuesta, int codigo, String contexto) throws CorreoException {
        if (respuesta.codigo() != codigo) {
            throw respuesta.comoExcepcion("El servidor SMTP no aceptó " + contexto);
        }
    }

    // Tras un rechazo se intenta RSET para que la misma conexión sirva para el siguiente mensaje.
    private void reiniciarTransaccion(CorreoException original) {
        if (canal == null) {
            return;
        }
        try {
            esperar(intercambiar("RSET"), 250, "RSET");
        } catch (IOException | CorreoException e) {
            original.addSuppressed(e);
        }
    }

    private void exigirConexion() throws CorreoException {
        if (canal == null) {
            if (cerrado) {
                // Otro hilo la cerró (plazo vencido): es un fallo de comunicación, no un error de programación.
                throw new CorreoException("La conexión SMTP se cerró (por ejemplo, porque se cumplió el plazo)");
            }
            throw new IllegalStateException("El cliente SMTP no está conectado; llame primero a conectar()");
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
            throw new IOException("La conexión SMTP se cerró (por ejemplo, porque se cumplió el plazo)");
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
