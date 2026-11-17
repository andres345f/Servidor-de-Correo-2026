package com.feder.datos.inventario;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Consultas del stock derivado (R070): ningún saldo de producto se guarda, todo se calcula de los lotes. */
public class StockRepositorio extends RepositorioJdbc {

    /** Existencias de un producto: físico total, reservado y vendible. */
    public record ResumenStock(long productoId, String codigo, String nombre, UnidadMedida unidad, boolean activo,
                               BigDecimal stockMinimo, BigDecimal existencia, BigDecimal reservado, BigDecimal vendible) {
    }

    public BigDecimal stockVendible(long productoId, LocalDate hoy) {
        return consultarUno("SELECT COALESCE(sum(lf.cantidad_disponible - lf.cantidad_reservada), 0) AS vendible"
                        + ConsultasLote.LOTES_CON_PRODUCTO + "WHERE p.id = ? AND" + ConsultasLote.CONDICION_VENDIBLE,
                fila -> fila.getBigDecimal("vendible"), productoId, hoy).orElse(BigDecimal.ZERO);
    }

    /** Stock vendible de todos los productos que tienen al menos un lote vendible. */
    public Map<Long, BigDecimal> stockVendiblePorProducto(LocalDate hoy) {
        List<Map.Entry<Long, BigDecimal>> filas = consultar(
                "SELECT p.id, sum(lf.cantidad_disponible - lf.cantidad_reservada) AS vendible"
                        + ConsultasLote.LOTES_CON_PRODUCTO + "WHERE" + ConsultasLote.CONDICION_VENDIBLE + "GROUP BY p.id",
                fila -> Map.entry(fila.getLong("id"), fila.getBigDecimal("vendible")), hoy);
        Map<Long, BigDecimal> resultado = new HashMap<>();
        filas.forEach(e -> resultado.put(e.getKey(), e.getValue()));
        return resultado;
    }

    /** @param filtro texto del código o nombre del producto; nulo para todos. */
    public List<ResumenStock> resumen(String filtro, LocalDate hoy) {
        return consultar("SELECT p.id, p.codigo, p.nombre, p.unidad_medida, p.activo, p.stock_minimo, "
                        + "COALESCE(sum(lf.cantidad_disponible), 0) AS existencia, "
                        + "COALESCE(sum(lf.cantidad_reservada), 0) AS reservado, "
                        + "COALESCE(sum(lf.cantidad_disponible - lf.cantidad_reservada) FILTER (WHERE"
                        + ConsultasLote.CONDICION_VENDIBLE + "), 0) AS vendible "
                        + "FROM producto p "
                        + "LEFT JOIN produccion pr ON pr.producto_id = p.id "
                        + "LEFT JOIN lote_produccion lp ON lp.produccion_id = pr.id "
                        + "LEFT JOIN detalle_compra dc ON dc.lote_origen_id = lp.id "
                        + "LEFT JOIN lote_feder lf ON lf.detalle_compra_id = dc.id "
                        + "WHERE (?::text IS NULL OR p.codigo ILIKE '%' || ? || '%' OR p.nombre ILIKE '%' || ? || '%') "
                        + "GROUP BY p.id, p.codigo, p.nombre, p.unidad_medida, p.activo, p.stock_minimo ORDER BY p.nombre",
                fila -> new ResumenStock(fila.getLong("id"), fila.getString("codigo"), fila.getString("nombre"),
                        enumeracion(fila, "unidad_medida", UnidadMedida.class), fila.getBoolean("activo"),
                        fila.getBigDecimal("stock_minimo"), fila.getBigDecimal("existencia"),
                        fila.getBigDecimal("reservado"), fila.getBigDecimal("vendible")),
                hoy, filtro, filtro, filtro);
    }

    /** R046 y R070: las alertas van a cada cuenta activa con PERSONAL_FEDER o ADMINISTRADOR vigente. */
    public List<Long> destinatariosAlerta() {
        return consultar("SELECT DISTINCT u.id FROM usuario u JOIN usuario_rol ur ON ur.usuario_id = u.id "
                        + "JOIN rol r ON r.id = ur.rol_id WHERE u.activo AND ur.estado = 'VIGENTE' "
                        + "AND r.codigo IN ('ADMINISTRADOR', 'PERSONAL_FEDER') ORDER BY u.id",
                fila -> fila.getLong("id"));
    }

    /**
     * Primera clave del bloqueo consultivo que serializa la evaluación de la alerta de stock (R070). Se usa la forma
     * de dos claves enteras, que no se mezcla con los bloqueos de una sola clave (por ejemplo, el del remitente).
     */
    static final int CLASE_BLOQUEO_ALERTA_STOCK = 0x46455237;

    /**
     * Bloqueo consultivo de transacción por producto: dos transacciones que cambian el stock del mismo producto
     * (aunque sea en lotes distintos) evalúan su alerta una después de la otra. Se libera solo al terminar la
     * transacción; la segunda, al obtenerlo, ya ve confirmado lo que hizo la primera (READ COMMITTED). Un producto
     * con identificador mayor que un entero comparte clave con otro: solo se serializa de más, nunca de menos.
     */
    public void bloquearEvaluacionAlerta(long productoId) {
        consultar("SELECT pg_advisory_xact_lock(?, ?) IS NULL AS bloqueado", fila -> 1,
                CLASE_BLOQUEO_ALERTA_STOCK, Long.hashCode(productoId));
    }
}
