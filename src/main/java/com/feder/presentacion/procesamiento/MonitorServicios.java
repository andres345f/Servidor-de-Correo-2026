package com.feder.presentacion.procesamiento;

import com.feder.configuracion.ConfiguradorLogs;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Informa en pantalla el estado de la base de datos y del correo POP3 entre ciclos:
 * <ul>
 *   <li>primera falla (o cambio de motivo): un bloque con el motivo, el diagnóstico de red, la conclusión y lo
 *       que queda en pausa;</li>
 *   <li>cada reintento con el mismo motivo: una línea con el tiempo que lleva caído y el próximo intento;</li>
 *   <li>al volver: una línea con la duración total sin servicio.</li>
 * </ul>
 * El detalle técnico de cada intento va a logs/error.log (CicloCorreo); aquí solo el resumen legible.
 */
public final class MonitorServicios {

    private static final Logger CONSOLA = Logger.getLogger("feder.consola");
    private static final Pattern DESTINO_JDBC = Pattern.compile("//([^/:?]+)(?::(\\d+))?");

    /** Un servicio vigilado: cómo se nombra, dónde está, qué queda en pausa y qué clave revisar si rechaza la contraseña. */
    record Destino(String etiqueta, String host, int puerto, String servicio, String enPausa, String claveContrasena) {
    }

    interface Diagnosticador {
        DiagnosticoRed.Resultado diagnosticar(String host, int puerto);
    }

    private static final class Estado {
        Instant desde;
        String motivo;
        MotivoFalla.Tipo tipo;

        boolean caido() {
            return desde != null;
        }
    }

    private final Destino bd;
    private final Destino correo;
    private final Diagnosticador diagnosticador;
    private final Clock reloj;
    private final Consumer<String> salida;
    private final String ok;
    private final String falla;
    private final Estado estadoBd = new Estado();
    private final Estado estadoCorreo = new Estado();
    private boolean avisoServidorCompleto;

    MonitorServicios(Destino bd, Destino correo, Diagnosticador diagnosticador, Clock reloj, Consumer<String> salida,
                     String ok, String falla) {
        this.bd = bd;
        this.correo = correo;
        this.diagnosticador = diagnosticador;
        this.reloj = reloj;
        this.salida = salida;
        this.ok = ok;
        this.falla = falla;
    }

    /** Monitor para el procesador real: con diagnóstico de red y salida por la consola. */
    public static MonitorServicios para(String urlBaseDatos, String hostCorreo, int puertoCorreo) {
        boolean simbolos = ConfiguradorLogs.consolaPuedeMostrar("✔✖");
        return new MonitorServicios(destinoDeUrl(urlBaseDatos), destinoCorreo(hostCorreo, puertoCorreo),
                DiagnosticoRed::ejecutar, Clock.systemDefaultZone(), CONSOLA::info, simbolos ? "✔" : "OK", simbolos ? "✖" : "X");
    }

    /**
     * Diagnóstico y conclusión para un fallo de la base al arrancar (antes de que exista el procesador):
     * "Diagnóstico: ...\nConclusión: ..." o, si no es un problema de red, solo la conclusión.
     */
    public static String diagnosticoArranque(String urlBaseDatos, Throwable error) {
        Destino d = destinoDeUrl(urlBaseDatos);
        MotivoFalla motivo = MotivoFalla.de(error);
        if (!motivo.tipo().esDeRed() || d.puerto() <= 0) {
            return "Conclusión:  " + conclusionSinRed(d, motivo.tipo());
        }
        boolean simbolos = ConfiguradorLogs.consolaPuedeMostrar("✔✖");
        DiagnosticoRed.Resultado r = DiagnosticoRed.ejecutar(d.host(), d.puerto());
        return "Diagnóstico: " + r.resumen(simbolos ? "✔" : "OK", simbolos ? "✖" : "X")
                + "\nConclusión:  " + r.conclusion(d.servicio());
    }

    private static Destino destinoDeUrl(String urlBaseDatos) {
        Matcher m = DESTINO_JDBC.matcher(urlBaseDatos == null ? "" : urlBaseDatos);
        if (m.find()) {
            return destinoBaseDatos(m.group(1), m.group(2) == null ? 5432 : Integer.parseInt(m.group(2)));
        }
        return destinoBaseDatos("base de datos", 0);
    }

    /** Motivo legible de un fallo de conexión (por ejemplo, para el arranque, antes de que exista el procesador). */
    public static String motivoLegible(Throwable error) {
        return MotivoFalla.de(error).detalle();
    }

    /** Monitor sin diagnóstico de red (cuando no se conocen los destinos). */
    static MonitorServicios basico() {
        return new MonitorServicios(destinoBaseDatos("base de datos", 0), destinoCorreo("servidor de correo", 0), null,
                Clock.systemDefaultZone(), CONSOLA::info, "OK", "X");
    }

    static Destino destinoBaseDatos(String host, int puerto) {
        return new Destino("[BD]", host, puerto, "PostgreSQL",
                "procesamiento de correos, envío de respuestas y consulta de pagos."
                        + "\n             Los correos nuevos esperan en el buzón; no se pierden.", "db.password");
    }

    static Destino destinoCorreo(String host, int puerto) {
        return new Destino("[POP3]", host, puerto, "el correo (POP3)",
                "lectura de correos nuevos (quedan en el buzón; no se pierden).", "mail.password");
    }

    /**
     * Se llama después de cada ciclo.
     *
     * @param fallaBd     fallo de conexión con la base en el ciclo, o null
     * @param fallaCorreo fallo de conexión con POP3 en el ciclo, o null
     * @param otroFallo   el ciclo falló por otra causa (ya informada en error.log)
     * @param intento     número de reintento (fallos seguidos)
     * @param espera      tiempo hasta el próximo ciclo
     */
    public void registrar(Throwable fallaBd, Throwable fallaCorreo, boolean otroFallo, int intento, Duration espera) {
        boolean bloque = revisar(estadoBd, bd, fallaBd);
        bloque |= revisar(estadoCorreo, correo, fallaCorreo);
        boolean ambosRed = estadoBd.caido() && estadoCorreo.caido() && estadoBd.tipo.esDeRed() && estadoCorreo.tipo.esDeRed();
        if (ambosRed && !avisoServidorCompleto) {
            avisoServidorCompleto = true;
            bloque = true;
            salida.accept("Ahora fallan la base Y el correo: el servidor " + (bd.host().equals(correo.host()) ? ""
                    : "de base de datos y el de correo ") + "no está accesible desde tu red. No es un problema del programa."
                    + "\nSugerencia: prueba desde otra red (por ejemplo, el hotspot del celular) para saber si es una caída"
                    + "\ndel servidor o un bloqueo de tu IP.");
        }
        if (!ambosRed) {
            avisoServidorCompleto = false;
        }
        if (fallaBd == null && fallaCorreo == null) {
            if (otroFallo) {
                salida.accept("Reintento " + intento + " en " + segundos(espera) + ".");
            }
            return;
        }
        if (bloque) {
            salida.accept("Reintento " + intento + " en " + segundos(espera) + ".");
            return;
        }
        salida.accept(lineaReintento(fallaBd != null, fallaCorreo != null, intento, espera));
    }

    /** Devuelve true si mostró un bloque nuevo (primera falla o cambio de motivo). */
    private boolean revisar(Estado estado, Destino destino, Throwable error) {
        if (error == null) {
            if (estado.caido()) {
                salida.accept(destino.etiqueta() + " " + ok + " Conexión recuperada tras "
                        + duracion(Duration.between(estado.desde, reloj.instant())) + " sin servicio.");
                estado.desde = null;
                estado.motivo = null;
                estado.tipo = null;
            }
            return false;
        }
        MotivoFalla motivo = MotivoFalla.de(error);
        boolean nueva = !estado.caido();
        if (!nueva && motivo.corto().equals(estado.motivo)) {
            return false;
        }
        if (nueva) {
            estado.desde = reloj.instant();
        }
        estado.motivo = motivo.corto();
        estado.tipo = motivo.tipo();
        salida.accept(bloque(destino, motivo, nueva));
        return true;
    }

    private String bloque(Destino d, MotivoFalla motivo, boolean nueva) {
        StringBuilder s = new StringBuilder(d.etiqueta()).append(' ').append(falla).append(' ')
                .append(nueva ? "Sin conexión con " : "Cambió el motivo de la falla con ").append(d.host());
        if (d.puerto() > 0) {
            s.append(':').append(d.puerto());
        }
        s.append("\nMotivo:      ").append(motivo.detalle());
        String conclusion;
        if (motivo.tipo().esDeRed() && diagnosticador != null && d.puerto() > 0) {
            DiagnosticoRed.Resultado r = diagnosticador.diagnosticar(d.host(), d.puerto());
            s.append("\nDiagnóstico: ").append(r.resumen(ok, falla));
            conclusion = r.conclusion(d.servicio());
        } else {
            conclusion = conclusionSinRed(d, motivo.tipo());
        }
        s.append("\nConclusión:  ").append(conclusion);
        s.append("\nEn pausa:    ").append(d.enPausa());
        return s.toString();
    }

    private static String conclusionSinRed(Destino d, MotivoFalla.Tipo tipo) {
        return switch (tipo) {
            case CREDENCIALES -> "el servidor funciona, pero la contraseña no es la correcta: revisa " + d.claveContrasena()
                    + "\n             en config/application-tecnoweb.properties y reinicia el procesador.";
            case BASE_INEXISTENTE -> "el servidor funciona, pero la base no existe: revisa db.url en la configuración.";
            case SATURADO -> "el servidor está saturado; se reintentará solo en unos minutos.";
            case REINICIO -> "el servidor se está reiniciando; se reintentará solo.";
            default -> "revisa la conexión con el servidor; el detalle técnico está en logs/error.log.";
        };
    }

    private String lineaReintento(boolean bdCaida, boolean correoCaido, int intento, Duration espera) {
        List<String> nombres = new ArrayList<>();
        List<String> motivos = new ArrayList<>();
        Instant desde = null;
        if (bdCaida) {
            nombres.add("BD");
            motivos.add(estadoBd.motivo);
            desde = estadoBd.desde;
        }
        if (correoCaido) {
            nombres.add("POP3");
            if (!motivos.contains(estadoCorreo.motivo)) {
                motivos.add(estadoCorreo.motivo);
            }
            desde = desde == null || estadoCorreo.desde.isBefore(desde) ? estadoCorreo.desde : desde;
        }
        boolean varios = nombres.size() > 1;
        return "[" + String.join(" + ", nombres) + "] " + falla + (varios ? " Siguen" : " Sigue") + " sin conexión (hace "
                + duracion(Duration.between(desde, reloj.instant())) + "): " + String.join("; ", motivos)
                + ". Reintento " + intento + " en " + segundos(espera) + ".";
    }

    static String duracion(Duration d) {
        long total = Math.max(0, d.toSeconds());
        if (total < 60) {
            return total + " s";
        }
        if (total < 3600) {
            return (total / 60) + " min " + (total % 60) + " s";
        }
        return (total / 3600) + " h " + ((total % 3600) / 60) + " min";
    }

    private static String segundos(Duration espera) {
        return espera.toSeconds() + " s";
    }
}
