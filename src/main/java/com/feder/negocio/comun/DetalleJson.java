package com.feder.negocio.comun;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Construye el objeto JSON del campo bitacora.detalle (valores anteriores y nuevos).
 * Es intencionalmente mínimo: claves de texto y valores de texto, número, booleano o nulo,
 * con el escape de cadenas que exige el estándar JSON. Así no hace falta una biblioteca externa.
 */
public final class DetalleJson {

    private final Map<String, Object> valores = new LinkedHashMap<>();

    public static DetalleJson nuevo() {
        return new DetalleJson();
    }

    public DetalleJson con(String clave, Object valor) {
        valores.put(clave, valor);
        return this;
    }

    public String aJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean primero = true;
        for (Map.Entry<String, Object> entrada : valores.entrySet()) {
            if (!primero) {
                sb.append(',');
            }
            primero = false;
            texto(sb, entrada.getKey());
            sb.append(':');
            valor(sb, entrada.getValue());
        }
        return sb.append('}').toString();
    }

    private static void valor(StringBuilder sb, Object valor) {
        if (valor == null) {
            sb.append("null");
        } else if (valor instanceof Boolean) {
            sb.append(valor);
        } else if (valor instanceof BigDecimal) {
            sb.append(((BigDecimal) valor).toPlainString());
        } else if (valor instanceof Long || valor instanceof Integer) {
            sb.append(valor);
        } else if (valor instanceof Enum<?>) {
            texto(sb, ((Enum<?>) valor).name());
        } else {
            texto(sb, String.valueOf(valor));
        }
    }

    private static void texto(StringBuilder sb, String texto) {
        sb.append('"');
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
