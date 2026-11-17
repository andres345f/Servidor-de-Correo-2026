package com.feder.datos.produccion;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.LoteProduccion;
import com.feder.modelo.Produccion;

import java.math.BigDecimal;
import java.util.Optional;

/** Recorrido de trazabilidad de origen de un lote: lote, producción, producto, productor y comunidad de origen. */
public class TrazabilidadRepositorio extends RepositorioJdbc {

    public record TrazaLote(LoteProduccion lote, Produccion produccion, String codigoProducto, String nombreProducto,
                            long usuarioProductorId, String codigoProductor, String nombreProductor,
                            boolean comunidadOrigenActiva, BigDecimal compradoPorFeder, BigDecimal recibidoPorFeder) {
    }

    public Optional<TrazaLote> porCodigoLote(String codigoLote) {
        Optional<LoteProduccion> lote = consultarUno("SELECT lp.id, lp.produccion_id, lp.codigo_lote, lp.fecha_elaboracion, "
                        + "lp.fecha_vencimiento, lp.cantidad_producida, lp.cantidad_ofrecida, lp.precio_ofrecido, lp.estado "
                        + "FROM lote_produccion lp WHERE lp.codigo_lote = ?",
                LoteProduccionRepositorio::mapear, codigoLote);
        return lote.flatMap(l -> consultarUno("SELECT pr.id, pr.productor_id, pr.producto_id, pr.comunidad_origen_id, pr.codigo, "
                        + "pr.fecha_inicio, pr.fecha_disponibilidad_estimada, pr.cantidad_estimada, pr.ubicacion_produccion, "
                        + "pr.estado, pr.observacion, pr.nombre_comunidad_origen, pr.municipio_origen, pr.departamento_origen, "
                        + "p.codigo AS codigo_producto, p.nombre AS nombre_producto, o.usuario_id, o.codigo_productor, "
                        + "u.nombres || ' ' || u.apellidos AS nombre_productor, c.activa AS comunidad_activa, "
                        + "(SELECT COALESCE(sum(dc.cantidad_comprada - dc.cantidad_cancelada), 0) FROM detalle_compra dc "
                        + " WHERE dc.lote_origen_id = ?) AS comprado, "
                        + "(SELECT COALESCE(sum(dc.cantidad_recibida), 0) FROM detalle_compra dc WHERE dc.lote_origen_id = ?) AS recibido "
                        + "FROM produccion pr JOIN producto p ON p.id = pr.producto_id JOIN productor o ON o.id = pr.productor_id "
                        + "JOIN usuario u ON u.id = o.usuario_id JOIN comunidad_agricola c ON c.id = pr.comunidad_origen_id "
                        + "WHERE pr.id = ?",
                fila -> new TrazaLote(l, ProduccionRepositorio.mapear(fila), fila.getString("codigo_producto"),
                        fila.getString("nombre_producto"), fila.getLong("usuario_id"), fila.getString("codigo_productor"),
                        fila.getString("nombre_productor"), fila.getBoolean("comunidad_activa"), fila.getBigDecimal("comprado"),
                        fila.getBigDecimal("recibido")),
                l.getId(), l.getId(), l.getProduccionId()));
    }

    /** Base con que se declaró la procedencia al registrar la producción (detalle del registro CREAR de BITACORA). */
    public Optional<String> origenDeclarado(long produccionId) {
        return consultarUno("SELECT detalle ->> 'origen' AS origen FROM bitacora WHERE entidad = 'PRODUCCION' "
                        + "AND entidad_id = ? AND accion = 'CREAR' ORDER BY id LIMIT 1",
                fila -> fila.getString("origen"), produccionId).filter(java.util.Objects::nonNull);
    }
}
