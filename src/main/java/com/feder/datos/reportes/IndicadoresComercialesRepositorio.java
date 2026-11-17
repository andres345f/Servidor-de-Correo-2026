package com.feder.datos.reportes;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Consultas de los indicadores de ventas, costos y mermas (CU7, apartados 1 a 3).
 * Devuelve importes a precisión completa; el redondeo lo hace la capa de negocio al presentar.
 */
public class IndicadoresComercialesRepositorio extends RepositorioJdbc {

    /** Componentes del ingreso comercial reconocido de un período. */
    public record ImportesVenta(BigDecimal subtotalEntregado, BigDecimal envioCobrado,
                                BigDecimal reembolsosPorDevolucion) {
    }

    /** Componentes de ingreso, costo y merma de un producto en el período (desglose por producto, A24). */
    public record ComponentesProducto(String codigo, String nombre, String categoria,
                                      BigDecimal subtotalEntregado, BigDecimal reembolsos,
                                      BigDecimal salidasVenta, BigDecimal reingresosVenta, BigDecimal ajustesVenta,
                                      BigDecimal salidasReposicion, BigDecimal reingresosReposicion,
                                      BigDecimal ajustesReposicion, BigDecimal mermas, BigDecimal devueltoNoApto) {
    }

    private static final String SQL_INGRESOS = "WITH " + FragmentosSql.PERIODO + ", "
            + "entregados AS (SELECT pe.subtotal, pe.costo_envio FROM pedido pe "
            + "  JOIN entrega e ON e.pedido_id = pe.id, periodo p "
            + " WHERE e.fecha_entrega >= p.ini AND e.fecha_entrega < p.fin) "
            + "SELECT (SELECT COALESCE(SUM(subtotal), 0) FROM entregados) AS subtotal, "
            + "       (SELECT COALESCE(SUM(costo_envio), 0) FROM entregados) AS envio, "
            + "       (SELECT COALESCE(SUM(r.monto), 0) FROM reembolso r, periodo p "
            + "         WHERE r.estado = 'EJECUTADO' AND r.devolucion_id IS NOT NULL "
            + "           AND r.fecha_ejecucion >= p.ini AND r.fecha_ejecucion < p.fin) AS reembolsos";

    // Cada componente se calcula con su propia fecha (entrega, ejecución del reembolso, movimiento, cancelación o
    // inspección) y se pivota por producto. El reembolso se atribuye al producto del lote del despacho devuelto:
    // una reposición entrega el mismo producto, así que coincide con el de la línea original (A34, A35).
    private static final String SQL_DESGLOSE = "WITH " + FragmentosSql.PERIODO + ", " + FragmentosSql.COSTO_MOVIMIENTO + ", "
            + FragmentosSql.CANCELACION + ", "
            + "salida_cancelada AS ("
            + " SELECT sv.reserva_id, sv.producto_id, sv.precio, sv.cantidad, ca.fecha AS fecha_cancelacion "
            + "   FROM costo_movimiento sv JOIN reserva_stock rs ON rs.id = sv.reserva_id "
            + "   JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
            + "   JOIN cancelacion ca ON ca.pedido_id = dp.pedido_id AND ca.tras_despacho "
            + "  WHERE sv.tipo = 'SALIDA_VENTA'), "
            + "componente AS ("
            + " SELECT 'SUBTOTAL' AS clave, dp.producto_id, dp.subtotal AS importe "
            + "   FROM detalle_pedido dp JOIN entrega e ON e.pedido_id = dp.pedido_id, periodo p "
            + "  WHERE e.fecha_entrega >= p.ini AND e.fecha_entrega < p.fin "
            + " UNION ALL "
            + " SELECT 'REEMBOLSO', feder_producto_de_lote_feder(desp.lote_feder_id), r.monto "
            + "   FROM reembolso r JOIN devolucion d ON d.id = r.devolucion_id "
            + "   JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id, periodo p "
            + "  WHERE r.estado = 'EJECUTADO' AND r.fecha_ejecucion >= p.ini AND r.fecha_ejecucion < p.fin "
            + " UNION ALL "
            + " SELECT 'SALIDA_VENTA', cm.producto_id, cm.costo "
            + "   FROM costo_movimiento cm JOIN reserva_stock rs ON rs.id = cm.reserva_id "
            + "   JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
            + "   JOIN entrega e ON e.pedido_id = dp.pedido_id, periodo p "
            + "  WHERE cm.tipo = 'SALIDA_VENTA' AND e.fecha_entrega >= p.ini AND e.fecha_entrega < p.fin "
            + " UNION ALL "
            + " SELECT 'SALIDA_REPOSICION', cm.producto_id, cm.costo FROM costo_movimiento cm, periodo p "
            + "  WHERE cm.tipo = 'SALIDA_REPOSICION' AND cm.fecha >= p.ini AND cm.fecha < p.fin "
            + " UNION ALL "
            // Cada reingreso (y su ajuste compensatorio en una rectificación) se imputa según el tipo de
            // su despacho devuelto, no según la resolución de la devolución.
            + " SELECT CASE WHEN cm.tipo = 'ENTRADA_DEVOLUCION' THEN 'REINGRESO_' ELSE 'AJUSTE_' END "
            + "        || CASE WHEN desp.tipo = 'SALIDA_VENTA' THEN 'VENTA' ELSE 'REPOSICION' END, "
            + "        cm.producto_id, cm.costo "
            + "   FROM costo_movimiento cm JOIN devolucion d ON d.id = cm.devolucion_id "
            + "   JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id, periodo p "
            + "  WHERE cm.tipo IN ('ENTRADA_DEVOLUCION', 'AJUSTE_NEGATIVO') "
            + "    AND cm.fecha >= p.ini AND cm.fecha < p.fin "
            + " UNION ALL "
            // Mermas informativas: bajas y ajustes negativos sin devolución, menos ajustes positivos, por la fecha
            // del movimiento.
            + " SELECT 'MERMA', cm.producto_id, -cm.signo * cm.costo FROM costo_movimiento cm, periodo p "
            + "  WHERE cm.fecha >= p.ini AND cm.fecha < p.fin "
            + "    AND (cm.tipo IN ('BAJA_VENCIDO', 'BAJA_DANADO', 'BAJA_DEFECTUOSO', 'AJUSTE_POSITIVO') "
            + "         OR (cm.tipo = 'AJUSTE_NEGATIVO' AND cm.devolucion_id IS NULL)) "
            + " UNION ALL "
            // Lo no recuperado de pedidos cancelados tras el despacho, medido con los retornos hasta la cancelación,
            // por la fecha de la cancelación.
            + " SELECT 'MERMA', sc.producto_id, (sc.cantidad - COALESCE((SELECT SUM(rt.cantidad) FROM movimiento_inventario rt "
            + "        WHERE rt.reserva_id = sc.reserva_id AND rt.tipo = 'ENTRADA_RETORNO_ENTREGA' "
            + "          AND rt.fecha <= sc.fecha_cancelacion), 0)) * sc.precio "
            + "   FROM salida_cancelada sc, periodo p "
            + "  WHERE sc.fecha_cancelacion >= p.ini AND sc.fecha_cancelacion < p.fin "
            + " UNION ALL "
            // Una recuperación tardía resta de las mermas por su propia fecha.
            + " SELECT 'MERMA', rt.producto_id, -rt.costo FROM costo_movimiento rt "
            + "   JOIN salida_cancelada sc ON sc.reserva_id = rt.reserva_id, periodo p "
            + "  WHERE rt.tipo = 'ENTRADA_RETORNO_ENTREGA' AND rt.fecha > sc.fecha_cancelacion "
            + "    AND rt.fecha >= p.ini AND rt.fecha < p.fin "
            + " UNION ALL "
            // Unidades devueltas no aptas de devoluciones APROBADA o COMPLETADA, por fecha_inspeccion. Ya están en el
            // costo de ventas o de posventa: se informan aparte y nunca se restan otra vez.
            + " SELECT 'NO_APTA', desp.producto_id, (d.cantidad_recibida - d.cantidad_apta) * desp.precio "
            + "   FROM devolucion d JOIN costo_movimiento desp ON desp.id = d.movimiento_inventario_id, periodo p "
            + "  WHERE d.estado IN ('APROBADA', 'COMPLETADA') AND d.cantidad_recibida > d.cantidad_apta "
            + "    AND d.fecha_inspeccion >= p.ini AND d.fecha_inspeccion < p.fin) "
            + "SELECT pr.codigo, pr.nombre, ca.nombre AS categoria, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'SUBTOTAL'), 0) AS subtotal, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'REEMBOLSO'), 0) AS reembolsos, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'SALIDA_VENTA'), 0) AS salidas_venta, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'REINGRESO_VENTA'), 0) AS reingresos_venta, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'AJUSTE_VENTA'), 0) AS ajustes_venta, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'SALIDA_REPOSICION'), 0) AS salidas_reposicion, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'REINGRESO_REPOSICION'), 0) AS reingresos_reposicion, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'AJUSTE_REPOSICION'), 0) AS ajustes_reposicion, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'MERMA'), 0) AS mermas, "
            + "       COALESCE(SUM(c.importe) FILTER (WHERE c.clave = 'NO_APTA'), 0) AS no_apto "
            + "  FROM componente c JOIN producto pr ON pr.id = c.producto_id "
            + "  JOIN categoria ca ON ca.id = pr.categoria_id "
            + " GROUP BY pr.codigo, pr.nombre, ca.nombre ORDER BY ca.nombre, pr.codigo";

    public ImportesVenta ingresos(LocalDate desde, LocalDate hasta) {
        return consultarUno(SQL_INGRESOS, fila -> new ImportesVenta(fila.getBigDecimal("subtotal"),
                fila.getBigDecimal("envio"), fila.getBigDecimal("reembolsos")), desde, hasta).orElseThrow();
    }

    public List<ComponentesProducto> desglosePorProducto(LocalDate desde, LocalDate hasta) {
        return consultar(SQL_DESGLOSE, fila -> new ComponentesProducto(fila.getString("codigo"), fila.getString("nombre"),
                fila.getString("categoria"), fila.getBigDecimal("subtotal"), fila.getBigDecimal("reembolsos"),
                fila.getBigDecimal("salidas_venta"), fila.getBigDecimal("reingresos_venta"),
                fila.getBigDecimal("ajustes_venta"), fila.getBigDecimal("salidas_reposicion"),
                fila.getBigDecimal("reingresos_reposicion"), fila.getBigDecimal("ajustes_reposicion"),
                fila.getBigDecimal("mermas"), fila.getBigDecimal("no_apto")),
                desde, hasta);
    }
}
