package com.feder.negocio.seguridad;

import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Confirmación de operaciones por correo (desafío y respuesta).
 *
 * Cuando un comando modifica datos no se ejecuta de inmediato: se genera un código aleatorio
 * de un solo uso que se envía a la dirección registrada de la cuenta. Solo quien controla ese
 * buzón puede leerlo y devolverlo con CONFIRMAR["código"]. Así un remitente falsificado no
 * logra ejecutar escrituras, el código no se puede reutilizar (repetición) y su vigencia
 * (30 minutos por defecto) tolera las demoras normales del correo.
 */
public class ServicioConfirmacion {

    /** Sin 0/O ni 1/I/L para evitar confusiones al copiar el código. */
    private static final char[] ALFABETO = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int LONGITUD_CODIGO = 8;

    private final ConfirmacionRepositorio repositorio;
    private final Clock reloj;
    private final Duration vigencia;
    private final int maximoPorHora;
    private final SecureRandom aleatorio = new SecureRandom();

    public ServicioConfirmacion(ConfirmacionRepositorio repositorio, Clock reloj, Duration vigencia, int maximoPorHora) {
        this.repositorio = repositorio;
        this.reloj = reloj;
        this.vigencia = vigencia;
        this.maximoPorHora = maximoPorHora;
    }

    /** Operación recuperada al confirmar: qué comando era, con qué valores y para qué cuenta. */
    public static final class OperacionConfirmada {
        public final String comando;
        public final List<String> parametros;
        public final String correo;
        public final Long usuarioId;

        OperacionConfirmada(String comando, List<String> parametros, String correo, Long usuarioId) {
            this.comando = comando;
            this.parametros = parametros;
            this.correo = correo;
            this.usuarioId = usuarioId;
        }
    }

    /** Crea la confirmación pendiente y devuelve el código en claro (solo para el correo de respuesta). */
    public String solicitar(String comando, List<String> parametros, Solicitante solicitante) {
        OffsetDateTime ahora = OffsetDateTime.now(reloj);
        // Limitamos cuántos códigos se emiten por hora para que nadie use el sistema como generador de correos.
        if (repositorio.contarCreadasDesde(solicitante.correo(), ahora.minusHours(1)) >= maximoPorHora) {
            throw new NegocioException(NegocioException.Codigo.LIMITE_EXCEDIDO,
                    "Se alcanzó el máximo de confirmaciones por hora para esta cuenta. Intente más tarde.");
        }
        String codigo = generarCodigo();
        Long usuarioId = solicitante.estaRegistrado() ? solicitante.usuarioId() : null;
        repositorio.registrar(hash(codigo), solicitante.correo(), usuarioId, comando, parametros, ahora.plus(vigencia));
        return codigo;
    }

    /**
     * Valida y consume el código. El remitente debe ser la misma cuenta que solicitó la operación.
     * Todas las fallas responden con el mismo mensaje para no revelar si un código existe.
     */
    public OperacionConfirmada consumir(String codigo, Solicitante remitente) {
        String normalizado = codigo == null ? "" : codigo.trim().toUpperCase(Locale.ROOT);
        ConfirmacionRepositorio.Pendiente pendiente = repositorio.buscarParaUsar(hash(normalizado))
                .orElseThrow(ServicioConfirmacion::codigoInvalido);
        boolean vigente = pendiente.usadaEn == null && pendiente.expiraEn.isAfter(OffsetDateTime.now(reloj));
        if (!esMismaCuenta(pendiente, remitente) || !vigente || !repositorio.marcarUsada(pendiente.id)) {
            throw codigoInvalido();
        }
        return new OperacionConfirmada(pendiente.comando, repositorio.parametros(pendiente.id), pendiente.correo,
                pendiente.usuarioId);
    }

    /**
     * La confirmación pertenece a una CUENTA, no solo a una dirección: si la dirección cambió de dueño
     * mientras el código seguía vigente, el código ya no sirve. Las solicitudes sin cuenta
     * (registro público, aceptación de invitación) solo se confirman desde un remitente sin cuenta activa.
     */
    private static boolean esMismaCuenta(ConfirmacionRepositorio.Pendiente pendiente, Solicitante remitente) {
        if (!pendiente.correo.equalsIgnoreCase(remitente.correo())) {
            return false;
        }
        if (pendiente.usuarioId == null) {
            return !remitente.estaRegistrado();
        }
        return remitente.estaRegistrado() && remitente.usuarioId() == pendiente.usuarioId;
    }

    public Duration vigencia() {
        return vigencia;
    }

    private String generarCodigo() {
        char[] codigo = new char[LONGITUD_CODIGO];
        for (int i = 0; i < codigo.length; i++) {
            codigo[i] = ALFABETO[aleatorio.nextInt(ALFABETO.length)];
        }
        return new String(codigo);
    }

    static String hash(String codigo) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(codigo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    private static NegocioException codigoInvalido() {
        return new NegocioException(NegocioException.Codigo.CONFIRMACION_INVALIDA,
                "El código de confirmación no es válido, ya fue usado, venció o no corresponde a esta cuenta.");
    }
}
