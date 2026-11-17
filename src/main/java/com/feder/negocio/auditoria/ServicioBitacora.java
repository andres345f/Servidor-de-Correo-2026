package com.feder.negocio.auditoria;

import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Bitacora;
import com.feder.negocio.comun.DetalleJson;

import java.util.List;

/**
 * Registra en BITACORA las operaciones sensibles (concesiones, decisiones sobre productores,
 * cambios de precio, ajustes de inventario, anulaciones). Se llama dentro de la misma transacción
 * que la operación auditada: si la operación se revierte, su registro también.
 */
public class ServicioBitacora {

    private final BitacoraRepositorio repositorio;

    public ServicioBitacora(BitacoraRepositorio repositorio) {
        this.repositorio = repositorio;
    }

    /**
     * @param autorId usuario que ejecuta la operación; nulo solo en procesos de instalación (R099).
     * @param entidad nombre de la clase del modelo en mayúsculas (p. ej. "PRODUCTO", "USUARIO_ROL").
     */
    public void registrar(Long autorId, AccionBitacora accion, String entidad, Long entidadId, DetalleJson detalle) {
        repositorio.registrar(autorId, accion, entidad, entidadId, detalle == null ? null : detalle.aJson());
    }

    public List<Bitacora> ultimos(String entidad, int limite) {
        return repositorio.ultimos(entidad, limite);
    }
}
