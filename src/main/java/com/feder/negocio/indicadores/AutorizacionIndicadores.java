package com.feder.negocio.indicadores;

import com.feder.modelo.TipoRol;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

/**
 * Permisos de CU7 y CU8 (ROL «Autorización por rol», R088), evaluados en el servicio con los roles
 * vigentes de la petición además de la declaración del comando.
 */
public final class AutorizacionIndicadores {

    private AutorizacionIndicadores() {
    }

    /** Los indicadores financieros de CU7 solo los consulta un ADMINISTRADOR vigente. */
    public static void exigirAdministrador(Solicitante solicitante) {
        if (!solicitante.estaRegistrado() || !solicitante.tieneRol(TipoRol.ADMINISTRADOR)) {
            throw NegocioException.sinPermiso(
                    "Los indicadores financieros solo los consulta un ADMINISTRADOR con su rol vigente.");
        }
    }

    /** Los reportes de CU8 los consultan ADMINISTRADOR y PERSONAL_FEDER; PRODUCTOR y CLIENTE no acceden. */
    public static void exigirPersonalInterno(Solicitante solicitante) {
        if (!solicitante.estaRegistrado() || !solicitante.esPersonalInterno()) {
            throw NegocioException.sinPermiso(
                    "Los reportes de FEDER solo los consultan ADMINISTRADOR y PERSONAL_FEDER con su rol vigente.");
        }
    }

    /** Solo el ADMINISTRADOR ve importes agregados, costos, márgenes y el valor del inventario en los reportes. */
    public static boolean veImportes(Solicitante solicitante) {
        return solicitante.tieneRol(TipoRol.ADMINISTRADOR);
    }
}
