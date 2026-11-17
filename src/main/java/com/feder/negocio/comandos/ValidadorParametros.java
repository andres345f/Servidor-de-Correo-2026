package com.feder.negocio.comandos;

import com.feder.negocio.comun.NegocioException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Valida la cantidad y el tipo de cada parámetro ANTES de ejecutar cualquier operación.
 * Los mensajes de error indican la posición y el nombre del parámetro para que el usuario
 * pueda corregir el comando.
 */
public final class ValidadorParametros {

    public static final ZoneId ZONA_BOLIVIA = ZoneId.of("America/La_Paz");

    private static final Pattern PATRON_CODIGO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern PATRON_ENTERO = Pattern.compile("-?\\d{1,18}");
    private static final Pattern PATRON_DECIMAL = Pattern.compile("-?\\d{1,12}(\\.\\d{1,3})?");
    private static final Pattern PATRON_CORREO = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final DateTimeFormatter FORMATO_FECHA = DateTimeFormatter.ofPattern("uuuu-MM-dd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter FORMATO_FECHA_HORA = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    private ValidadorParametros() {
    }

    public static Parametros validar(ComandoSpec comando, List<String> valores) {
        List<ParametroSpec> especificacion = comando.parametros();
        if (valores.size() != especificacion.size()) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "El comando " + comando.nombre() + " espera " + especificacion.size() + " parámetro(s) y recibió "
                            + valores.size() + ". Formato: " + comando.sintaxis());
        }
        Map<String, Object> convertidos = new LinkedHashMap<>();
        for (int i = 0; i < especificacion.size(); i++) {
            ParametroSpec parametro = especificacion.get(i);
            convertidos.put(parametro.nombre(), convertir(parametro, valores.get(i), i + 1));
        }
        return new Parametros(convertidos);
    }

    private static Object convertir(ParametroSpec p, String bruto, int posicion) {
        String valor = bruto.strip();
        if (valor.isEmpty()) {
            if (p.esObligatorio()) {
                throw invalido(p, posicion, "es obligatorio y llegó vacío");
            }
            return null;
        }
        if (p.admiteComodin() && valor.equals("*")) {
            return Parametros.COMODIN;
        }
        if (valor.length() > p.longitudMaxima()) {
            throw invalido(p, posicion, "supera la longitud máxima de " + p.longitudMaxima() + " caracteres");
        }
        switch (p.tipo()) {
            case TEXTO:
                return valor;
            case CODIGO:
                exigir(PATRON_CODIGO.matcher(valor).matches(), p, posicion, "solo admite letras, dígitos, '.', '-' y '_'");
                return valor.toUpperCase(Locale.ROOT);
            case ENTERO:
                exigir(PATRON_ENTERO.matcher(valor).matches(), p, posicion, "debe ser un número entero");
                return Long.parseLong(valor);
            case DECIMAL:
                exigir(PATRON_DECIMAL.matcher(valor).matches(), p, posicion,
                        "debe ser un número con punto decimal (máximo 3 decimales), p. ej. 35.50");
                return new BigDecimal(valor);
            case FECHA:
                try {
                    return LocalDate.parse(valor, FORMATO_FECHA);
                } catch (DateTimeParseException e) {
                    throw invalido(p, posicion, "debe ser una fecha válida con formato AAAA-MM-DD");
                }
            case FECHA_HORA:
                try {
                    return LocalDateTime.parse(valor, FORMATO_FECHA_HORA).atZone(ZONA_BOLIVIA).toOffsetDateTime();
                } catch (DateTimeParseException e) {
                    throw invalido(p, posicion, "debe tener el formato AAAA-MM-DD HH:MM");
                }
            case BOOLEANO:
                String minus = valor.toLowerCase(Locale.ROOT);
                exigir(minus.equals("true") || minus.equals("false"), p, posicion, "debe ser true o false");
                return Boolean.valueOf(minus);
            case CORREO:
                exigir(PATRON_CORREO.matcher(valor).matches(), p, posicion, "debe ser un correo electrónico válido");
                return valor.toLowerCase(Locale.ROOT);
            case ENUMERACION:
                return convertirEnumeracion(p, valor, posicion);
            default:
                throw new IllegalStateException("Tipo no contemplado: " + p.tipo());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertirEnumeracion(ParametroSpec p, String valor, int posicion) {
        String buscado = valor.toUpperCase(Locale.ROOT);
        for (Enum<?> constante : p.enumeracion().getEnumConstants()) {
            if (constante.name().equals(buscado)) {
                return Enum.valueOf((Class) p.enumeracion(), buscado);
            }
        }
        throw invalido(p, posicion, "debe ser uno de: " + String.join(", ", p.valoresPermitidos()));
    }

    private static void exigir(boolean condicion, ParametroSpec p, int posicion, String motivo) {
        if (!condicion) {
            throw invalido(p, posicion, motivo);
        }
    }

    private static NegocioException invalido(ParametroSpec p, int posicion, String motivo) {
        return new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                "Parámetro " + posicion + " (" + p.nombre() + ") " + motivo + ".");
    }
}
