package com.feder.configuracion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Configura los dos registros exigidos por el proyecto:
 *   logs/access.log  cada correo procesado y cada respuesta enviada (logger "feder.access").
 *   logs/error.log   excepciones y fallos de protocolo o de base de datos (logger "feder.error").
 * Y la salida por pantalla, pensada para leerse en vivo (por ejemplo, en una demostración):
 *   logger "feder.consola"  un bloque legible por cada correo recibido y cada respuesta enviada.
 *   Los errores ("feder.error") también se muestran en pantalla con el mismo formato.
 *   El detalle técnico de access.log (incluidos los ciclos sin correo) queda solo en el archivo.
 * Se usa java.util.logging del JDK; no hace falta ninguna dependencia adicional.
 * Ningún mensaje de log incluye contraseñas: las clases que registran nunca las reciben como texto.
 */
public final class ConfiguradorLogs {

    private static final DateTimeFormatter FORMATO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    /** Marca de un registro que va solo al archivo (la pantalla ya lo muestra resumido de otra forma). */
    private static final Object SOLO_ARCHIVO = new Object();

    private ConfiguradorLogs() {
    }

    /**
     * Registra el error completo (con su excepción) en el archivo del logger, sin mostrarlo en pantalla. Se usa
     * cuando la pantalla ya lo informa de forma resumida, para no repetir el mismo fallo varias veces por ciclo.
     */
    public static void soloArchivo(Logger logger, Level nivel, String mensaje, Throwable error) {
        LogRecord registro = new LogRecord(nivel, mensaje);
        registro.setThrown(error);
        registro.setParameters(new Object[] {SOLO_ARCHIVO});
        registro.setLoggerName(logger.getName());
        logger.log(registro);
    }

    /** Indica si la consola puede mostrar el texto (por ejemplo, los símbolos ✔ y ✖) sin convertirlo en "?". */
    public static boolean consolaPuedeMostrar(String texto) {
        return codificacionTerminal().newEncoder().canEncode(texto);
    }

    private static boolean esSoloArchivo(LogRecord registro) {
        Object[] parametros = registro.getParameters();
        return parametros != null && parametros.length > 0 && parametros[0] == SOLO_ARCHIVO;
    }

    public static void configurar(Path carpeta) {
        try {
            Files.createDirectories(carpeta);
            configurarLogger("feder.access", carpeta.resolve("access.log"), Level.INFO, false);
            configurarLogger("feder.error", carpeta.resolve("error.log"), Level.WARNING, true);
            configurarLogger("feder.consola", null, Level.INFO, true);
        } catch (IOException e) {
            throw new ConfiguracionException("No se pudo preparar la carpeta de logs " + carpeta + ": " + e.getMessage());
        }
    }

    /** {@code archivo} es null si el logger no escribe en archivo; {@code enPantalla} indica si se muestra en consola. */
    private static void configurarLogger(String nombre, Path archivo, Level nivel, boolean enPantalla) throws IOException {
        Logger logger = Logger.getLogger(nombre);
        for (Handler anterior : logger.getHandlers()) {
            logger.removeHandler(anterior);
            anterior.close();
        }
        if (archivo != null) {
            // Rotación: hasta 5 archivos de 5 MB, en modo agregar para conservar el historial.
            FileHandler archivoLog = new FileHandler(archivo.toString().replace("%", "%%"), 5 * 1024 * 1024, 5, true);
            archivoLog.setEncoding("UTF-8");
            archivoLog.setFormatter(new FormatoLinea());
            archivoLog.setLevel(nivel);
            logger.addHandler(archivoLog);
        }
        if (enPantalla) {
            ConsoleHandler consola = new ConsoleHandler();
            // Se escribe con la codificación de la terminal para que las tildes y la ñ se vean bien.
            consola.setEncoding(codificacionTerminal().name());
            consola.setFormatter(new FormatoConsola());
            consola.setLevel(nivel);
            consola.setFilter(registro -> !esSoloArchivo(registro));
            logger.addHandler(consola);
        }
        logger.setUseParentHandlers(false);
        logger.setLevel(nivel);
    }

    private static Charset codificacionTerminal() {
        java.io.Console terminal = System.console();
        return terminal != null ? terminal.charset() : Charset.defaultCharset();
    }

    /**
     * Formato para la pantalla: hora corta, sin el nivel INFO y con los mensajes de varias líneas
     * sangrados y separados por una línea en blanco, para que cada correo se lea como un bloque.
     */
    static final class FormatoConsola extends Formatter {
        private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm:ss");
        private static final String SANGRIA = " ".repeat(10);

        @Override
        public String format(LogRecord registro) {
            String fin = System.lineSeparator();
            String[] lineas = formatMessage(registro).split("\n", -1);
            StringBuilder texto = new StringBuilder();
            if (lineas.length > 1) {
                texto.append(fin);
            }
            texto.append(HORA.format(registro.getInstant().atZone(ZoneId.systemDefault()))).append("  ");
            if (registro.getLevel().intValue() >= Level.SEVERE.intValue()) {
                texto.append("ERROR: ");
            } else if (registro.getLevel().intValue() >= Level.WARNING.intValue()) {
                texto.append("AVISO: ");
            }
            for (int i = 0; i < lineas.length; i++) {
                if (i > 0) {
                    texto.append(fin).append(SANGRIA);
                }
                texto.append(FormatoLinea.limpiar(lineas[i]));
            }
            Throwable t = registro.getThrown();
            if (t != null) {
                texto.append(fin).append(SANGRIA).append("Detalle: ").append(t.getClass().getSimpleName())
                        .append(": ").append(FormatoLinea.limpiar(String.valueOf(t.getMessage())));
                if (t.getCause() != null) {
                    texto.append(fin).append(SANGRIA).append("Causa:   ").append(t.getCause().getClass().getSimpleName())
                            .append(": ").append(FormatoLinea.limpiar(String.valueOf(t.getCause().getMessage())));
                }
            }
            return texto.append(fin).toString();
        }
    }

    /** Una línea por evento: fecha | nivel | mensaje [| excepción]. */
    static final class FormatoLinea extends Formatter {
        @Override
        public String format(LogRecord registro) {
            StringBuilder linea = new StringBuilder()
                    .append(FORMATO.format(OffsetDateTime.now()))
                    .append(" | ").append(registro.getLevel().getName())
                    .append(" | ").append(limpiar(formatMessage(registro)));
            if (registro.getThrown() != null) {
                Throwable t = registro.getThrown();
                linea.append(" | ").append(t.getClass().getName()).append(": ").append(limpiar(String.valueOf(t.getMessage())));
                if (t.getCause() != null) {
                    linea.append(" | causa: ").append(t.getCause().getClass().getSimpleName())
                            .append(": ").append(limpiar(String.valueOf(t.getCause().getMessage())));
                    // La causa más profunda suele ser la que explica el porqué (p. ej. "connect timed out").
                    Throwable raiz = t.getCause();
                    for (int i = 0; raiz.getCause() != null && raiz.getCause() != raiz && i < 10; i++) {
                        raiz = raiz.getCause();
                    }
                    if (raiz != t.getCause()) {
                        linea.append(" | causa raíz: ").append(raiz.getClass().getSimpleName())
                                .append(": ").append(limpiar(String.valueOf(raiz.getMessage())));
                    }
                }
            }
            return linea.append(System.lineSeparator()).toString();
        }

        /** Evita que un asunto o remitente malicioso inserte líneas falsas en el log. */
        static String limpiar(String texto) {
            StringBuilder sb = new StringBuilder(texto.length());
            texto.codePoints().forEach(c -> {
                if (c == '\r' || c == '\n') {
                    sb.append("\\n");
                } else if (Character.isISOControl(c)) {
                    sb.append('?');
                } else {
                    sb.appendCodePoint(c);
                }
            });
            return sb.toString();
        }
    }
}
