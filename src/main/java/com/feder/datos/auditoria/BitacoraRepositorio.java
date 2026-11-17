package com.feder.datos.auditoria;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.Bitacora;

import java.util.List;

/**
 * Acceso a BITACORA. Solo inserta y consulta: la base de datos rechaza UPDATE y DELETE
 * (disparador tg_bitacora_solo_insercion), así el historial de auditoría no se altera.
 */
public class BitacoraRepositorio extends RepositorioJdbc {

    public long registrar(Long usuarioId, AccionBitacora accion, String entidad, Long entidadId, String detalleJson) {
        return insertar("INSERT INTO bitacora (usuario_id, accion, entidad, entidad_id, detalle) VALUES (?, ?, ?, ?, ?::jsonb)",
                usuarioId, accion, entidad, entidadId, detalleJson);
    }

    /** Últimos registros, opcionalmente filtrados por entidad (nulo = todas). */
    public List<Bitacora> ultimos(String entidad, int limite) {
        return consultar("SELECT id, usuario_id, fecha, accion, entidad, entidad_id, detalle::text AS detalle, "
                        + "host(direccion_ip) AS direccion_ip FROM bitacora "
                        + "WHERE (?::text IS NULL OR entidad = ?) ORDER BY fecha DESC, id DESC LIMIT ?",
                fila -> {
                    Bitacora b = new Bitacora();
                    b.setId(fila.getLong("id"));
                    b.setUsuarioId(largoONulo(fila, "usuario_id"));
                    b.setFecha(fechaHora(fila, "fecha"));
                    b.setAccion(enumeracion(fila, "accion", AccionBitacora.class));
                    b.setEntidad(fila.getString("entidad"));
                    b.setEntidadId(largoONulo(fila, "entidad_id"));
                    b.setDetalle(fila.getString("detalle"));
                    b.setDireccionIp(fila.getString("direccion_ip"));
                    return b;
                },
                entidad, entidad, limite);
    }
}
