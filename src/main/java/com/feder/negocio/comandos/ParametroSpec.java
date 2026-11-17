package com.feder.negocio.comandos;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Definición de un parámetro posicional de un comando.
 *
 * Un parámetro opcional acepta "" (vacío), que se interpreta como "sin valor".
 * Un parámetro con comodín acepta "*" con el significado "todos" (solo en consultas).
 */
public final class ParametroSpec {

    private final String nombre;
    private final TipoParametro tipo;
    private final boolean obligatorio;
    private final int longitudMaxima;
    private final Class<? extends Enum<?>> enumeracion;
    private final boolean admiteComodin;

    private ParametroSpec(String nombre, TipoParametro tipo, boolean obligatorio, int longitudMaxima,
                          Class<? extends Enum<?>> enumeracion, boolean admiteComodin) {
        this.nombre = nombre;
        this.tipo = tipo;
        this.obligatorio = obligatorio;
        this.longitudMaxima = longitudMaxima;
        this.enumeracion = enumeracion;
        this.admiteComodin = admiteComodin;
    }

    public static ParametroSpec texto(String nombre, int longitudMaxima) {
        return new ParametroSpec(nombre, TipoParametro.TEXTO, true, longitudMaxima, null, false);
    }

    public static ParametroSpec codigo(String nombre, int longitudMaxima) {
        return new ParametroSpec(nombre, TipoParametro.CODIGO, true, longitudMaxima, null, false);
    }

    public static ParametroSpec entero(String nombre) {
        return new ParametroSpec(nombre, TipoParametro.ENTERO, true, 18, null, false);
    }

    public static ParametroSpec decimal(String nombre) {
        return new ParametroSpec(nombre, TipoParametro.DECIMAL, true, 20, null, false);
    }

    public static ParametroSpec fecha(String nombre) {
        return new ParametroSpec(nombre, TipoParametro.FECHA, true, 10, null, false);
    }

    public static ParametroSpec fechaHora(String nombre) {
        return new ParametroSpec(nombre, TipoParametro.FECHA_HORA, true, 16, null, false);
    }

    public static ParametroSpec booleano(String nombre) {
        return new ParametroSpec(nombre, TipoParametro.BOOLEANO, true, 5, null, false);
    }

    public static ParametroSpec correo(String nombre) {
        return new ParametroSpec(nombre, TipoParametro.CORREO, true, 120, null, false);
    }

    public static ParametroSpec enumeracion(String nombre, Class<? extends Enum<?>> tipoEnum) {
        return new ParametroSpec(nombre, TipoParametro.ENUMERACION, true, 40, tipoEnum, false);
    }

    /** Devuelve una copia que acepta "" como valor ausente. */
    public ParametroSpec opcional() {
        return new ParametroSpec(nombre, tipo, false, longitudMaxima, enumeracion, admiteComodin);
    }

    /** Devuelve una copia que acepta "*" como "todos". */
    public ParametroSpec conComodin() {
        return new ParametroSpec(nombre, tipo, obligatorio, longitudMaxima, enumeracion, true);
    }

    public String nombre() {
        return nombre;
    }

    public TipoParametro tipo() {
        return tipo;
    }

    public boolean esObligatorio() {
        return obligatorio;
    }

    public int longitudMaxima() {
        return longitudMaxima;
    }

    public Class<? extends Enum<?>> enumeracion() {
        return enumeracion;
    }

    public boolean admiteComodin() {
        return admiteComodin;
    }

    /** Valores permitidos de la enumeración, para los mensajes de ayuda y error. */
    public List<String> valoresPermitidos() {
        if (enumeracion == null) {
            return List.of();
        }
        return Arrays.stream(enumeracion.getEnumConstants()).map(Enum::name).collect(Collectors.toList());
    }

    /** Descripción breve para la ayuda: nombre:tipo, con marcas de opcional o comodín. */
    public String describir() {
        StringBuilder sb = new StringBuilder(nombre).append(" (").append(tipo.descripcion());
        if (tipo == TipoParametro.ENUMERACION) {
            sb.append(": ").append(String.join("|", valoresPermitidos()));
        }
        if (!obligatorio) {
            sb.append(", opcional");
        }
        if (admiteComodin) {
            sb.append(", \"*\" = todos");
        }
        return sb.append(')').toString();
    }
}
