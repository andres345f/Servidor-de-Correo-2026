package com.feder.presentacion.procesamiento;

import com.feder.negocio.mantenimiento.ServicioMantenimiento;
import com.feder.negocio.mensajeria.GeneradorRespuesta;
import com.feder.negocio.mensajeria.ServicioMensajeria;
import com.feder.negocio.mensajeria.SolicitudCorreo;
import com.feder.presentacion.correo.CorreoException;
import com.feder.presentacion.correo.mime.EmailResponseBuilder;
import com.feder.presentacion.correo.pop3.MensajeDemasiadoGrandeException;
import com.feder.presentacion.correo.pop3.Pop3Client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Un ciclo completo de procesamiento del buzón:
 *   1. Envía las respuestas pendientes de ciclos anteriores (por si el SMTP había fallado).
 *   2. Lee el buzón por POP3 y procesa solo los mensajes cuyo UIDL no está registrado.
 *   3. Envía las respuestas generadas en este ciclo.
 * Los mensajes no se borran del servidor salvo que se configure mail.pop3.eliminar.procesados,
 * y en ese caso solo cuando su resultado ya quedó guardado en la base de datos.
 */
public class CicloCorreo {

    private static final Logger ACCESO = Logger.getLogger("feder.access");
    private static final Logger ERROR = Logger.getLogger("feder.error");
    private static final Logger CONSOLA = Logger.getLogger("feder.consola");

    /** Resumen de un ciclo, para el log y las pruebas. */
    public static final class Resumen {
        public int recibidosNuevos;
        public int procesados;
        public int duplicados;
        public int fallidos;
        public int respuestasEnviadas;
        public int fallosEnvio;
        public String errorRecepcion;

        @Override
        public String toString() {
            return "nuevos=" + recibidosNuevos + " procesados=" + procesados + " duplicados=" + duplicados
                    + " fallidos=" + fallidos
                    + " enviados=" + respuestasEnviadas + " fallos_envio=" + fallosEnvio
                    + (errorRecepcion == null ? "" : " error_recepcion=" + errorRecepcion);
        }
    }

    private final ConfiguracionCorreo configuracion;
    private final ServicioMensajeria mensajeria;
    private final ServicioMantenimiento mantenimiento;
    private final InterpreteCorreo interprete;
    private final EmailResponseBuilder constructorCorreos;
    private final GeneradorRespuesta generador;

    public CicloCorreo(ConfiguracionCorreo configuracion, ServicioMensajeria mensajeria, GeneradorAdjuntos adjuntos) {
        this(configuracion, mensajeria, adjuntos, null);
    }

    /** @param mantenimiento tareas periódicas del negocio que se ejecutan al inicio de cada ciclo (puede ser null). */
    public CicloCorreo(ConfiguracionCorreo configuracion, ServicioMensajeria mensajeria, GeneradorAdjuntos adjuntos,
                       ServicioMantenimiento mantenimiento) {
        this.mantenimiento = mantenimiento;
        this.configuracion = configuracion;
        this.mensajeria = mensajeria;
        this.interprete = new InterpreteCorreo(configuracion.identificadorBuzon(), configuracion.direccionSistema());
        this.constructorCorreos = new EmailResponseBuilder(configuracion.direccionSistema(), "Sistema FEDER",
                configuracion.dominio(), java.time.Clock.systemUTC());
        this.generador = new GeneradorRespuestaCorreo(constructorCorreos, adjuntos);
    }

    public Resumen ejecutar() {
        Resumen resumen = new Resumen();
        if (mantenimiento != null) {
            mantenimiento.ejecutar();
        }
        enviarPendientes(resumen);
        recibir(resumen);
        enviarPendientes(resumen);
        ACCESO.info(() -> "CICLO " + resumen);
        // Un ciclo sin novedades ya mostró su línea "[POP3] Conectado ..." y uno con error, su aviso.
        boolean huboActividad = resumen.recibidosNuevos > 0 || resumen.respuestasEnviadas > 0
                || resumen.fallidos > 0 || resumen.fallosEnvio > 0;
        if (resumen.errorRecepcion == null && huboActividad) {
            CONSOLA.info(() -> textoRevision(resumen));
        }
        return resumen;
    }

    /** Resumen en pantalla de un ciclo en el que llegaron correos o se enviaron respuestas. */
    static String textoRevision(Resumen r) {
        StringBuilder texto = new StringBuilder("Buzón revisado: ")
                .append(r.recibidosNuevos).append(" correo(s) nuevo(s), ")
                .append(r.respuestasEnviadas).append(" respuesta(s) enviada(s)");
        if (r.fallidos > 0) {
            texto.append(", ").append(r.fallidos).append(" sin procesar (se reintentará)");
        }
        if (r.fallosEnvio > 0) {
            texto.append(", ").append(r.fallosEnvio).append(" envío(s) fallido(s)");
        }
        return texto.append('.').toString();
    }

    private void recibir(Resumen resumen) {
        String servidor = "[POP3] " + configuracion.pop3().host() + ":" + configuracion.pop3().puerto();
        // La traza C/S se guarda y solo se muestra si llegaron correos nuevos o si algo falla.
        List<String> traza = new ArrayList<>();
        boolean trazaMostrada = false;
        try (Pop3Client pop3 = new Pop3Client(configuracion.pop3());
             VigilantePlazo plazo = new VigilantePlazo("plazo-pop3", configuracion.plazoPorOperacionPop3())) {
            pop3.trazar(traza::add);
            // Cada operación de red tiene un plazo absoluto: si el servidor se vuelve lento, se cierra la conexión.
            Runnable cortar = () -> cerrarSilenciosamente(pop3);
            plazo.ejecutar(pop3::conectar, cortar);
            plazo.ejecutar(pop3::autenticar, cortar);
            // UIDL identifica cada mensaje de forma estable entre sesiones; los números no.
            Map<Integer, String> uidls = new TreeMap<>(plazo.ejecutar(pop3::uidl, cortar));
            Set<String> registrados = mensajeria.uidlsRegistrados(configuracion.identificadorBuzon());
            long nuevos = uidls.values().stream().filter(u -> !registrados.contains(u)).count();
            if (nuevos > 0) {
                CONSOLA.info(servidor + " · " + uidls.size() + " correo(s) en el buzón, " + nuevos + " nuevo(s)\n"
                        + String.join("\n", traza));
                traza.clear();
                trazaMostrada = true;
                // Desde aquí cada RETR se muestra en el momento, antes del bloque del correo que descarga.
                pop3.trazar(linea -> {
                    if (linea.startsWith("C : RETR")) {
                        CONSOLA.info("[POP3] " + linea);
                    }
                });
            }
            List<Integer> conResultadoGuardado = new ArrayList<>();
            for (Map.Entry<Integer, String> entrada : uidls.entrySet()) {
                if (registrados.contains(entrada.getValue())) {
                    conResultadoGuardado.add(entrada.getKey());
                    continue;
                }
                if (resumen.recibidosNuevos >= configuracion.maximoPorCiclo()) {
                    break; // El resto queda para el siguiente ciclo.
                }
                resumen.recibidosNuevos++;
                byte[] crudo;
                try {
                    int numero = entrada.getKey();
                    crudo = plazo.ejecutar(() -> pop3.retr(numero), cortar);
                } catch (MensajeDemasiadoGrandeException e) {
                    // Cuarentena: se registra como ignorado para no volver a descargarlo en cada ciclo.
                    // La sesión POP3 quedó desincronizada, así que este ciclo termina aquí.
                    mensajeria.procesar(SolicitudCorreo.automatica(configuracion.identificadorBuzon(),
                            entrada.getValue(), null, "desconocido", "(mensaje demasiado grande)"), generador);
                    throw e;
                }
                if (procesarAislado(entrada.getValue(), crudo, resumen)) {
                    conResultadoGuardado.add(entrada.getKey());
                }
            }
            if (configuracion.eliminarProcesados()) {
                // DELE solo marca; el borrado real ocurre con QUIT (estado UPDATE de RFC 1939).
                for (int numero : conResultadoGuardado) {
                    plazo.ejecutar(() -> pop3.dele(numero), cortar);
                }
            }
            plazo.ejecutar(pop3::quit, cortar);
            if (!trazaMostrada) {
                CONSOLA.info(servidor + " · Conectado · " + uidls.size() + " correo(s) en el buzón, 0 nuevos");
            }
            ultimoError = null;
        } catch (CorreoException e) {
            resumen.errorRecepcion = e.getMessage();
            mostrarTrazaDelFallo(servidor, traza, trazaMostrada, e.getMessage());
            String pista = e.getMessage().contains("autenticación")
                    ? " Revise mail.password en config/application-tecnoweb.properties." : "";
            ERROR.warning(() -> "[POP3] No se pudo revisar el buzón: " + e.getMessage() + "." + pista);
        } catch (RuntimeException e) {
            // Por ejemplo, PostgreSQL caído: el mensaje no quedó registrado y se reintentará en otro ciclo.
            resumen.errorRecepcion = e.getClass().getSimpleName() + ": " + e.getMessage();
            mostrarTrazaDelFallo(servidor, traza, trazaMostrada, resumen.errorRecepcion);
            ERROR.log(Level.SEVERE, "Revisión interrumpida: los correos nuevos quedan en el buzón y se procesarán"
                    + " cuando el servicio responda", e);
        }
    }

    /** Motivo del último fallo mostrado: si el siguiente es igual, no se repite la traza. */
    private String ultimoError;

    /** Muestra la traza C/S hasta el punto donde falló, salvo que ya se haya mostrado o el fallo se repita igual. */
    private void mostrarTrazaDelFallo(String servidor, List<String> traza, boolean trazaMostrada, String motivo) {
        boolean repetido = motivo != null && motivo.equals(ultimoError);
        ultimoError = motivo;
        if (trazaMostrada || repetido) {
            return;
        }
        CONSOLA.info(servidor + " · Conectando ..." + (traza.isEmpty() ? "" : "\n" + String.join("\n", traza)));
    }

    /**
     * Procesa un mensaje sin que su fallo detenga a los demás. Si falla (por ejemplo, un bloqueo
     * mutuo en PostgreSQL), su transacción ya se revirtió: no queda registrado y se reintentará
     * en el siguiente ciclo. Devuelve true si el resultado quedó guardado.
     */
    private boolean procesarAislado(String uidl, byte[] crudo, Resumen resumen) {
        try {
            SolicitudCorreo solicitud = interprete.interpretar(uidl, crudo);
            if (mensajeria.procesar(solicitud, generador)) {
                resumen.procesados++;
            } else {
                resumen.duplicados++;
            }
            return true;
        } catch (RuntimeException e) {
            resumen.fallidos++;
            ERROR.log(Level.WARNING, "No se pudo procesar uidl=" + uidl + "; se reintentará en el próximo ciclo", e);
            return false;
        }
    }

    private static void cerrarSilenciosamente(Pop3Client pop3) {
        try {
            pop3.close();
        } catch (CorreoException | RuntimeException e) {
            ERROR.fine(() -> "No se pudo cerrar la conexión POP3: " + e.getMessage());
        }
    }

    private void enviarPendientes(Resumen resumen) {
        try (EnviadorSmtp enviador = new EnviadorSmtp(configuracion, constructorCorreos, configuracion.plazoPorEnvio())) {
            while (true) {
                ServicioMensajeria.ResultadoEnvio resultado = mensajeria.enviarSiguientePendiente(enviador);
                if (resultado == ServicioMensajeria.ResultadoEnvio.SIN_PENDIENTES) {
                    return;
                }
                if (resultado == ServicioMensajeria.ResultadoEnvio.ENVIADO) {
                    resumen.respuestasEnviadas++;
                    continue;
                }
                resumen.fallosEnvio++;
                if (resultado == ServicioMensajeria.ResultadoEnvio.FALLO_TRANSITORIO) {
                    return; // Servidor no disponible: se reintentará en el siguiente ciclo.
                }
            }
        } catch (RuntimeException e) {
            ERROR.log(Level.SEVERE, "Error al recorrer la bandeja de salida", e);
        }
    }
}
