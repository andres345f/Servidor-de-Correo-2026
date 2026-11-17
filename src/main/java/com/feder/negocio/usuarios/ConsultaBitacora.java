package com.feder.negocio.usuarios;

import com.feder.modelo.Bitacora;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

import java.util.List;
import java.util.Locale;

/** Consulta de la bitácora de auditoría, reservada al ADMINISTRADOR. */
public class ConsultaBitacora {

    /** El tope común de los listados (antes 200). */
    public static final int LIMITE_MAXIMO = Listados.TOPE;

    private final ContextoNegocio ctx;

    public ConsultaBitacora(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    /** Últimos registros, opcionalmente de una entidad (nulo = todas). */
    public List<Bitacora> ultimos(Solicitante admin, String entidad, long limite) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "consultar la bitácora");
        if (limite < 1 || limite > LIMITE_MAXIMO) {
            throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO,
                    "El límite debe estar entre 1 y " + LIMITE_MAXIMO + " registros.");
        }
        String filtro = entidad == null ? null : entidad.strip().toUpperCase(Locale.ROOT);
        return ctx.transacciones().enTransaccion(() -> ctx.bitacora().ultimos(filtro, (int) limite));
    }
}
