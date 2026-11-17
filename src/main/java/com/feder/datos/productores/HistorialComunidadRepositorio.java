package com.feder.datos.productores;

import com.feder.datos.RepositorioJdbc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Consultas sobre la bitácora de un productor que sirven de evidencia de su comunidad en el pasado
 * (R066): los cambios de comunidad registrados por un ADMINISTRADOR y las validaciones de origen.
 * Solo lee: las inserciones se hacen con ServicioBitacora.
 *
 * Los valores del JSON se comparan como texto para no convertir tipos en filas de otras operaciones.
 */
public class HistorialComunidadRepositorio extends RepositorioJdbc {

    public static final String OPERACION_CAMBIO = "CAMBIO_COMUNIDAD";
    public static final String OPERACION_VALIDACION = "VALIDACION_ORIGEN";

    /** Un cambio de comunidad registrado: cuándo y desde qué comunidad. */
    public record CambioComunidad(long bitacoraId, OffsetDateTime fecha, long comunidadAnteriorId, long comunidadNuevaId) {
    }

    /** Validación de origen hecha por un ADMINISTRADOR. */
    public record ValidacionOrigen(long bitacoraId, long administradorId, OffsetDateTime fecha) {
    }

    public List<CambioComunidad> cambiosDe(long productorId) {
        return consultar("SELECT id, fecha, detalle ->> 'comunidad_anterior_id' AS anterior, "
                        + "detalle ->> 'comunidad_nueva_id' AS nueva FROM bitacora "
                        + "WHERE entidad = 'PRODUCTOR' AND entidad_id = ? AND accion = 'MODIFICAR' "
                        + "AND detalle ->> 'operacion' = ? ORDER BY fecha, id",
                fila -> new CambioComunidad(fila.getLong("id"), fechaHora(fila, "fecha"),
                        Long.parseLong(fila.getString("anterior")), Long.parseLong(fila.getString("nueva"))),
                productorId, OPERACION_CAMBIO);
    }

    public Optional<ValidacionOrigen> validacion(long productorId, long comunidadId, LocalDate fechaElaboracion) {
        return consultarUno("SELECT id, usuario_id, fecha FROM bitacora "
                        + "WHERE entidad = 'PRODUCTOR' AND entidad_id = ? AND accion = 'MODIFICAR' "
                        + "AND detalle ->> 'operacion' = ? AND detalle ->> 'comunidad_id' = ? "
                        + "AND detalle ->> 'fecha_elaboracion' = ? ORDER BY id DESC LIMIT 1",
                fila -> new ValidacionOrigen(fila.getLong("id"), fila.getLong("usuario_id"), fechaHora(fila, "fecha")),
                productorId, OPERACION_VALIDACION, String.valueOf(comunidadId), fechaElaboracion.toString());
    }
}
