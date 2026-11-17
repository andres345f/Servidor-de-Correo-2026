package com.feder.negocio.catalogo;

import com.feder.modelo.TipoRol;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;

/**
 * Comprobaciones de rol que repiten los servicios de CU4 y CU5 (R088).
 *
 * El despachador ya filtra por los roles declarados en cada comando; los servicios vuelven a
 * comprobarlos porque también se invocan desde otros servicios (por ejemplo, CU6) y desde pruebas.
 */
public final class Permisos {

    private Permisos() {
    }

    /** ADMINISTRADOR o PERSONAL_FEDER con concesión vigente. */
    public static void exigirPersonal(Solicitante solicitante, String accion) {
        if (!solicitante.estaRegistrado() || !solicitante.esPersonalInterno()) {
            throw NegocioException.sinPermiso("Solo PERSONAL_FEDER o un ADMINISTRADOR vigente puede " + accion + ".");
        }
    }

    public static void exigirAdministrador(Solicitante solicitante, String accion) {
        if (!solicitante.estaRegistrado() || !solicitante.tieneRol(TipoRol.ADMINISTRADOR)) {
            throw NegocioException.sinPermiso("Solo un ADMINISTRADOR vigente puede " + accion + ".");
        }
    }

    /** R034 y R054: solo una cuenta activa con CLIENTE vigente usa el carrito y confirma pedidos. */
    public static void exigirCliente(Solicitante solicitante, String accion) {
        if (!solicitante.estaRegistrado() || !solicitante.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Solo una cuenta con el rol CLIENTE vigente puede " + accion + ".");
        }
    }
}
