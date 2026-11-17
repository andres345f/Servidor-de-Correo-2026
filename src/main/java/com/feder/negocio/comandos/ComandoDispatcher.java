package com.feder.negocio.comandos;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.GestorTransacciones;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.mensajeria.AvisosPendientes;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;

import java.util.List;
import java.util.Optional;

/**
 * Punto de entrada de la capa de negocio para un comando ya analizado sintácticamente.
 *
 * Orden de las comprobaciones (ninguna toca datos de negocio hasta el final):
 *   1. El comando existe en la lista blanca.
 *   2. La cantidad y el tipo de los parámetros son correctos.
 *   3. El remitente tiene acceso (público, registrado o rol vigente).
 *   4. Si el comando modifica datos, se emite un código de confirmación en lugar de ejecutarlo.
 *   5. Se ejecuta el handler dentro de un punto de guardado: si falla, solo se deshacen sus cambios.
 */
public class ComandoDispatcher {

    public static final String COMANDO_CONFIRMAR = "CONFIRMAR";

    private final CatalogoComandos catalogo;
    private final ServicioIdentidad identidad;
    private final ServicioConfirmacion confirmaciones;
    private final GestorTransacciones transacciones;
    private final TraductorErrores traductor = new TraductorErrores();

    public ComandoDispatcher(CatalogoComandos catalogo, ServicioIdentidad identidad,
                             ServicioConfirmacion confirmaciones, GestorTransacciones transacciones) {
        this.catalogo = catalogo;
        this.identidad = identidad;
        this.confirmaciones = confirmaciones;
        this.transacciones = transacciones;
    }

    /** Resultado del despacho más la dirección a la que debe ir la respuesta. */
    public static final class Despacho {
        public final ResultadoOperacion resultado;
        public final String destinatario;
        public final String comando;

        Despacho(ResultadoOperacion resultado, String destinatario, String comando) {
            this.resultado = resultado;
            this.destinatario = destinatario;
            this.comando = comando;
        }
    }

    /** Debe llamarse dentro de una transacción abierta por la capa que procesa el correo. */
    public Despacho despachar(String nombreComando, List<String> valores, String remitente) {
        Solicitante solicitante = identidad.resolver(remitente);
        // La respuesta siempre va a la dirección de la cuenta resuelta (o al remitente si no tiene cuenta).
        String destino = solicitante.correo();
        ComandoSpec comando = null;
        try {
            if (COMANDO_CONFIRMAR.equalsIgnoreCase(nombreComando)) {
                return new Despacho(confirmar(valores, solicitante), destino, COMANDO_CONFIRMAR);
            }
            comando = buscar(nombreComando);
            Parametros parametros = ValidadorParametros.validar(comando, valores);
            autorizar(comando, solicitante);
            if (comando.modificaDatos()) {
                return new Despacho(pedirConfirmacion(comando, valores, solicitante), destino, comando.nombre());
            }
            return new Despacho(ejecutar(comando, parametros, solicitante), destino, comando.nombre());
        } catch (NegocioException e) {
            ResultadoOperacion error = traductor.desdeNegocio(e);
            if (comando != null && e.codigo() == NegocioException.Codigo.PARAMETRO_INVALIDO) {
                // Un dato mal escrito: se muestra cómo se escribe el comando (B2).
                conFormaCorrecta(error, comando);
            }
            return new Despacho(error, destino, nombreComando);
        }
    }

    /** El comando que se quiso escribir, si existe: para mostrar su forma correcta ante un error de sintaxis. */
    public Optional<ComandoSpec> comandoLlamado(String nombre) {
        return nombre == null ? Optional.empty() : catalogo.buscar(nombre);
    }

    /** Agrega a un error de forma la «Forma correcta» y el «Ejemplo para copiar en el asunto» del comando. */
    public static ResultadoOperacion conFormaCorrecta(ResultadoOperacion error, ComandoSpec comando) {
        error.detalle("Forma correcta", comando.sintaxis());
        if (comando.ejemplo() != null && !comando.ejemplo().isBlank()) {
            error.detalle("Ejemplo para copiar en el asunto", comando.ejemplo());
        }
        return error;
    }

    /**
     * El nombre escrito antes del primer «[», en mayúsculas («lisusu["*"]» → «LISUSU»), o null si no hay uno
     * reconocible (solo letras A-Z, de 1 a 12). Sirve para nombrar el comando aunque el resto esté mal escrito.
     */
    public static String nombreEscrito(String asunto) {
        if (asunto == null) {
            return null;
        }
        java.util.regex.Matcher m = NOMBRE_ESCRITO.matcher(asunto);
        return m.find() ? m.group(1).toUpperCase(java.util.Locale.ROOT) : null;
    }

    private static final java.util.regex.Pattern NOMBRE_ESCRITO = java.util.regex.Pattern.compile("^\\s*([A-Za-z]{1,12})\\s*\\[");

    private ResultadoOperacion confirmar(List<String> valores, Solicitante remitente) {
        if (valores.size() != 1) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "Use CONFIRMAR[\"CODIGO\"] con el código recibido por correo.");
        }
        ServicioConfirmacion.OperacionConfirmada operacion = confirmaciones.consumir(valores.get(0), remitente);
        // Volvemos a validar y autorizar con los roles actuales del remitente (resueltos en esta misma
        // petición): si un rol se revocó entre la solicitud y la confirmación, ya no se permite (R088).
        ComandoSpec comando = buscar(operacion.comando);
        Parametros parametros = ValidadorParametros.validar(comando, operacion.parametros);
        autorizar(comando, remitente);
        return ejecutar(comando, parametros, remitente);
    }

    private ResultadoOperacion pedirConfirmacion(ComandoSpec comando, List<String> valores, Solicitante solicitante) {
        String codigo = confirmaciones.solicitar(comando.nombre(), valores, solicitante);
        long minutos = confirmaciones.vigencia().toMinutes();
        return ResultadoOperacion.confirmacionPendiente("Confirmación requerida",
                        "La operación " + comando.nombre() + " modifica datos. Para ejecutarla, envíe un correo "
                                + "desde esta misma cuenta con el asunto indicado abajo antes de " + minutos
                                + " minutos. Si usted no solicitó esta operación, ignore este mensaje.")
                .detalle("Comando solicitado", comando.sintaxisConValores(valores))
                .detalle("Asunto para confirmar", COMANDO_CONFIRMAR + "[\"" + codigo + "\"]")
                .detalle("Vigencia", minutos + " minutos, un solo uso");
    }

    private ResultadoOperacion ejecutar(ComandoSpec comando, Parametros parametros, Solicitante solicitante) {
        int marcaAvisos = AvisosPendientes.marca();
        try {
            return transacciones.conPuntoDeGuardado(() -> comando.handler().ejecutar(parametros, solicitante));
        } catch (RuntimeException e) {
            // La operación se revirtió: los avisos que había preparado tampoco deben enviarse.
            AvisosPendientes.revertirA(marcaAvisos);
            return traducirFallo(e);
        }
    }

    /**
     * Los errores de negocio se traducen en despachar(); los de datos que no son transitorios, aquí.
     * Un fallo transitorio (bloqueo mutuo, cancelación, conexión perdida) no es un error del usuario:
     * se propaga para revertir TODA la transacción del mensaje (incluido el uso del código de
     * confirmación) y el correo se reintenta en el ciclo siguiente.
     */
    private ResultadoOperacion traducirFallo(RuntimeException fallo) {
        if (fallo instanceof AccesoDatosException && !((AccesoDatosException) fallo).esTransitoria()) {
            return traductor.desdeDatos((AccesoDatosException) fallo);
        }
        throw fallo;
    }

    /** Une frases con un solo espacio, omitiendo las vacías (por ejemplo, cuando no hay sugerencias). */
    static String unirFrases(String... frases) {
        StringBuilder texto = new StringBuilder();
        for (String frase : frases) {
            if (frase != null && !frase.isBlank()) {
                texto.append(texto.length() == 0 ? "" : " ").append(frase.strip());
            }
        }
        return texto.toString();
    }

    private ComandoSpec buscar(String nombre) {
        Optional<ComandoSpec> comando = catalogo.buscar(nombre);
        if (comando.isEmpty()) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    unirFrases("El comando " + nombre + " no existe.", SugerenciaComandos.sugerir(nombre, catalogo),
                            "Envíe HELP[\"*\"] para ver la lista completa."));
        }
        return comando.get();
    }

    static void autorizar(ComandoSpec comando, Solicitante solicitante) {
        switch (comando.acceso()) {
            case PUBLICO:
                return;
            case REGISTRADO:
                if (!solicitante.estaRegistrado()) {
                    throw new NegocioException(NegocioException.Codigo.NO_IDENTIFICADO,
                            "Su dirección no corresponde a una cuenta activa de FEDER. Puede registrarse como cliente con REGCLI.");
                }
                return;
            case ROLES:
                if (!solicitante.estaRegistrado()) {
                    throw new NegocioException(NegocioException.Codigo.NO_IDENTIFICADO,
                            "Su dirección no corresponde a una cuenta activa de FEDER.");
                }
                if (!solicitante.tieneAlgunRol(comando.roles())) {
                    throw NegocioException.sinPermiso("Su cuenta no tiene un rol vigente que permita " + comando.nombre()
                            + ". Roles autorizados: " + comando.roles() + ".");
                }
                return;
            default:
                throw new IllegalStateException("Acceso no contemplado: " + comando.acceso());
        }
    }
}
