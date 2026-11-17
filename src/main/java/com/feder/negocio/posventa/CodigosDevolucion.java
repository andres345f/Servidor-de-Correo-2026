package com.feder.negocio.posventa;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.comun.NegocioException;

import java.util.regex.Pattern;

/**
 * Códigos de DEVOLUCION. FEDER asigna los de la forma DEV-AAAA-NNNN a partir del identificador (solicitudes sin
 * código y rectificaciones); quien solicita puede elegir otro código, pero nunca uno de esa forma, para que no
 * ocupe de antemano el código de la devolución de otra persona.
 */
final class CodigosDevolucion {

    private static final Pattern RESERVADO = Pattern.compile("(?i)DEV-\\d{4}-\\d+");

    private CodigosDevolucion() {
    }

    static String generado(ContextoNegocio ctx, long id) {
        return String.format("DEV-%d-%04d", FechasBolivia.hoy(ctx.reloj()).getYear(), id);
    }

    /** @return el código indicado, o nulo si no se indicó; rechaza los de la forma que asigna FEDER. */
    static String validarIndicado(String codigo) {
        if (codigo != null && RESERVADO.matcher(codigo).matches()) {
            throw NegocioException.regla("Los códigos de la forma DEV-AAAA-NNNN los asigna FEDER: elija otro código o deje el "
                    + "campo vacío.");
        }
        return codigo;
    }
}
