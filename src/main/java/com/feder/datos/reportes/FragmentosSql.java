package com.feder.datos.reportes;

/**
 * Expresiones de tabla comunes (CTE) que comparten las consultas de CU7 y CU8.
 *
 * Son texto SQL fijo: los únicos valores variables son las fechas y los filtros, que siempre llegan por "?".
 * Todas las fechas de corte y de período se interpretan en hora de Bolivia (America/La_Paz):
 * un período [desde, hasta] va desde las 00:00 de desde hasta antes de las 00:00 del día siguiente
 * a hasta, y una fecha de corte comprende todo ese día.
 */
final class FragmentosSql {

    /** Período [ini, fin). Consume dos parámetros: desde y hasta. */
    static final String PERIODO = "periodo AS (SELECT (CAST(? AS date))::timestamp AT TIME ZONE 'America/La_Paz' AS ini, "
            + "(CAST(? AS date) + 1)::timestamp AT TIME ZONE 'America/La_Paz' AS fin)";

    /** Fin exclusivo de una fecha de corte. Consume un parámetro: la fecha de corte. */
    static final String CORTE = "corte AS (SELECT (CAST(? AS date) + 1)::timestamp AT TIME ZONE 'America/La_Paz' AS fin)";

    /** Corte igual al final del período (estado «a la fecha hasta»). Requiere la CTE periodo. */
    static final String CORTE_AL_FIN_DEL_PERIODO = "corte AS (SELECT fin FROM periodo)";

    /** Tipos de movimiento que suman al saldo del lote; el resto lo resta. */
    static final String TIPOS_ENTRADA = "('ENTRADA_COMPRA', 'ENTRADA_RETORNO_ENTREGA', 'ENTRADA_DEVOLUCION', 'AJUSTE_POSITIVO')";

    /** Etiqueta pública de un productor (alias po): su código, o un texto con su id si aún no lo tiene. */
    static final String ETIQUETA_PRODUCTOR = "COALESCE(po.codigo_productor, 'Productor ' || po.id)";

    /**
     * Cada movimiento con su costo: cantidad x precio_unitario del detalle de compra de su lote (A15, A14),
     * a precisión completa. Incluye el producto, la producción de origen y la compra para los desgloses.
     */
    static final String COSTO_MOVIMIENTO = "costo_movimiento AS ("
            + "SELECT m.id, m.tipo, m.fecha, m.cantidad, m.reserva_id, m.devolucion_id, m.lote_feder_id, "
            + "       CASE WHEN m.tipo IN " + TIPOS_ENTRADA + " THEN 1 ELSE -1 END AS signo, "
            + "       dc.precio_unitario AS precio, m.cantidad * dc.precio_unitario AS costo, "
            + "       pd.producto_id, pd.id AS produccion_id, dc.compra_id, lf.detalle_compra_id "
            + "  FROM movimiento_inventario m "
            + "  JOIN lote_feder lf ON lf.id = m.lote_feder_id "
            + "  JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
            + "  JOIN lote_produccion lp ON lp.id = dc.lote_origen_id "
            + "  JOIN produccion pd ON pd.id = lp.produccion_id)";

    /**
     * Transiciones de cada entrega registradas en BITACORA (ENTREGA «Coherencia con el pedido»), con el estado
     * nuevo de la transición anterior de la misma entrega para comprobar que la cadena no tenga huecos.
     */
    static final String TRANSICION_ENTREGA = "transicion_entrega AS ("
            + "SELECT b.id, b.entidad_id AS entrega_id, b.fecha, b.detalle ->> 'estado_anterior' AS anterior, "
            + "       b.detalle ->> 'estado_nuevo' AS nuevo, "
            + "       LAG(b.detalle ->> 'estado_nuevo') OVER (PARTITION BY b.entidad_id ORDER BY b.fecha, b.id) AS previo "
            + "  FROM bitacora b "
            + " WHERE b.entidad = 'ENTREGA' "
            + "   AND b.detalle ->> 'estado_anterior' IS NOT NULL AND b.detalle ->> 'estado_nuevo' IS NOT NULL)";

    /**
     * Estado de cada entrega al corte, según su última transición registrada en BITACORA.
     * Una entrega sin transiciones sigue en PENDIENTE. Requiere las CTE corte y transicion_entrega.
     */
    static final String ESTADO_ENTREGA = "estado_entrega AS ("
            + "SELECT DISTINCT ON (t.entrega_id) t.entrega_id, t.nuevo AS estado "
            + "  FROM transicion_entrega t, corte c WHERE t.fecha < c.fin "
            + " ORDER BY t.entrega_id, t.fecha DESC, t.id DESC)";

    /** Estado de cada pedido al corte, según su HISTORIAL_PEDIDO. Requiere la CTE corte. */
    static final String ESTADO_PEDIDO = "estado_pedido AS ("
            + "SELECT DISTINCT ON (h.pedido_id) h.pedido_id, h.estado_nuevo AS estado "
            + "  FROM historial_pedido h, corte c "
            + " WHERE h.fecha < c.fin "
            + " ORDER BY h.pedido_id, h.fecha DESC, h.id DESC)";

    /** Paso de cada pedido a CANCELADO, indicando si ocurrió después del despacho. */
    static final String CANCELACION = "cancelacion AS ("
            + "SELECT h.pedido_id, h.fecha, h.estado_anterior = 'DESPACHADO' AS tras_despacho "
            + "  FROM historial_pedido h WHERE h.estado_nuevo = 'CANCELADO')";

    /**
     * Filtros del reporte de ventas. Consume cinco parámetros (producto, categoría, tipo, modalidad y estado del
     * pedido a la fecha hasta); un nulo significa «sin filtro». Un pedido pasa el filtro de producto o categoría
     * si tiene alguna línea de ese producto o categoría. Requiere la CTE estado_pedido.
     */
    static final String FILTRO_VENTAS = "filtro AS (SELECT CAST(? AS text) AS producto, CAST(? AS text) AS categoria, "
            + "       CAST(? AS text) AS tipo, CAST(? AS text) AS modalidad, CAST(? AS text) AS estado), "
            + "producto_filtrado AS (SELECT pr.id FROM producto pr JOIN categoria ca ON ca.id = pr.categoria_id, filtro f "
            + " WHERE (f.producto IS NULL OR pr.codigo = f.producto) "
            + "   AND (f.categoria IS NULL OR lower(ca.nombre) = lower(f.categoria))), "
            + "pedido_filtrado AS (SELECT pe.id FROM pedido pe LEFT JOIN entrega e ON e.pedido_id = pe.id "
            + "   LEFT JOIN estado_pedido ep ON ep.pedido_id = pe.id, filtro f "
            + " WHERE (f.tipo IS NULL OR pe.tipo = f.tipo) AND (f.modalidad IS NULL OR e.modalidad = f.modalidad) "
            + "   AND (f.estado IS NULL OR ep.estado = f.estado) "
            + "   AND ((f.producto IS NULL AND f.categoria IS NULL) OR EXISTS (SELECT 1 FROM detalle_pedido dp "
            + "        WHERE dp.pedido_id = pe.id AND dp.producto_id IN (SELECT id FROM producto_filtrado))))";

    /**
     * Filtros de lotes de FEDER del reporte de inventario. Consume cinco parámetros (producto, categoría, productor,
     * comunidad de origen histórica y estado actual del lote); un nulo significa «sin filtro».
     */
    static final String FILTRO_LOTES = "filtro AS (SELECT CAST(? AS text) AS producto, CAST(? AS text) AS categoria, "
            + "       CAST(? AS text) AS productor, CAST(? AS text) AS comunidad, CAST(? AS text) AS estado), "
            + "producto_filtrado AS (SELECT pr.id FROM producto pr JOIN categoria ca ON ca.id = pr.categoria_id, filtro f "
            + " WHERE (f.producto IS NULL OR pr.codigo = f.producto) "
            + "   AND (f.categoria IS NULL OR lower(ca.nombre) = lower(f.categoria))), "
            + "lote_filtrado AS (SELECT lf.id FROM lote_feder lf JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
            + "   JOIN compra_feder cf ON cf.id = dc.compra_id JOIN productor po ON po.id = cf.productor_id "
            + "   JOIN lote_produccion lp ON lp.id = dc.lote_origen_id JOIN produccion pd ON pd.id = lp.produccion_id, filtro f "
            + " WHERE pd.producto_id IN (SELECT id FROM producto_filtrado) "
            + "   AND (f.productor IS NULL OR po.codigo_productor = f.productor) "
            + "   AND (f.comunidad IS NULL OR lower(pd.nombre_comunidad_origen) = lower(f.comunidad)) "
            + "   AND (f.estado IS NULL OR lf.estado = f.estado))";

    /**
     * Filtros del reporte de abastecimiento. Consume cinco parámetros (producto, categoría, productor, comunidad de
     * origen histórica y estado de la compra). Una línea pasa si su compra cumple productor y estado y la línea
     * cumple producto, categoría y comunidad.
     */
    static final String FILTRO_COMPRAS = "filtro AS (SELECT CAST(? AS text) AS producto, CAST(? AS text) AS categoria, "
            + "       CAST(? AS text) AS productor, CAST(? AS text) AS comunidad, CAST(? AS text) AS estado), "
            + "linea_filtrada AS (SELECT dc.id, dc.compra_id FROM detalle_compra dc "
            + "   JOIN compra_feder cf ON cf.id = dc.compra_id JOIN productor po ON po.id = cf.productor_id "
            + "   JOIN lote_produccion lp ON lp.id = dc.lote_origen_id JOIN produccion pd ON pd.id = lp.produccion_id "
            + "   JOIN producto pr ON pr.id = pd.producto_id JOIN categoria ca ON ca.id = pr.categoria_id, filtro f "
            + " WHERE (f.producto IS NULL OR pr.codigo = f.producto) "
            + "   AND (f.categoria IS NULL OR lower(ca.nombre) = lower(f.categoria)) "
            + "   AND (f.productor IS NULL OR po.codigo_productor = f.productor) "
            + "   AND (f.comunidad IS NULL OR lower(pd.nombre_comunidad_origen) = lower(f.comunidad)) "
            + "   AND (f.estado IS NULL OR cf.estado = f.estado))";

    private FragmentosSql() {
    }
}
