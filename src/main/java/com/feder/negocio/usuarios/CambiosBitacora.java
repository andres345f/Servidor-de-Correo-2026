package com.feder.negocio.usuarios;

import com.feder.negocio.comun.DetalleJson;

import java.util.Objects;

/**
 * Anota en el detalle de BITACORA el valor anterior y el nuevo de un campo modificado,
 * con claves planas «campo_anterior» y «campo_nuevo» (DetalleJson no anida objetos).
 */
public final class CambiosBitacora {

    private CambiosBitacora() {
    }

    /** Devuelve true si el campo cambió (y lo anota); false si el valor es el mismo. */
    public static boolean anotar(DetalleJson detalle, String campo, Object anterior, Object nuevo) {
        if (Objects.equals(anterior, nuevo)) {
            return false;
        }
        detalle.con(campo + "_anterior", anterior).con(campo + "_nuevo", nuevo);
        return true;
    }
}
