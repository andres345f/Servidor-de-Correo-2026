package com.feder.negocio.comun;

/**
 * Error de negocio con un mensaje seguro para el usuario final.
 *
 * El mensaje se envía tal cual por correo, por eso nunca debe contener SQL, trazas,
 * contraseñas ni datos internos. El código permite clasificar el error en los logs.
 */
public class NegocioException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Clasificación estable de los errores que se informan al remitente. */
    public enum Codigo {
        NO_ENCONTRADO,
        DUPLICADO,
        REGLA_DE_NEGOCIO,
        ESTADO_INVALIDO,
        SIN_PERMISO,
        NO_IDENTIFICADO,
        PARAMETRO_INVALIDO,
        STOCK_INSUFICIENTE,
        CONFIRMACION_INVALIDA,
        LIMITE_EXCEDIDO
    }

    private final Codigo codigo;

    public NegocioException(Codigo codigo, String mensajeSeguro) {
        super(mensajeSeguro);
        this.codigo = codigo;
    }

    public Codigo codigo() {
        return codigo;
    }

    public static NegocioException noEncontrado(String mensaje) {
        return new NegocioException(Codigo.NO_ENCONTRADO, mensaje);
    }

    public static NegocioException regla(String mensaje) {
        return new NegocioException(Codigo.REGLA_DE_NEGOCIO, mensaje);
    }

    public static NegocioException estadoInvalido(String mensaje) {
        return new NegocioException(Codigo.ESTADO_INVALIDO, mensaje);
    }

    public static NegocioException duplicado(String mensaje) {
        return new NegocioException(Codigo.DUPLICADO, mensaje);
    }

    public static NegocioException sinPermiso(String mensaje) {
        return new NegocioException(Codigo.SIN_PERMISO, mensaje);
    }
}
