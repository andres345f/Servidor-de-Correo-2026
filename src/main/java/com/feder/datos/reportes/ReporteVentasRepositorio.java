package com.feder.datos.reportes;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Consultas del reporte de ventas y pedidos (CU8). Cada dato usa la fecha de su propio evento;
 * los estados «a la fecha hasta» se reconstruyen con HISTORIAL_PEDIDO, con las transiciones de
 * ENTREGA registradas en BITACORA y con las fechas de DEVOLUCION y REEMBOLSO, nunca con el estado actual.
 *
 * Todas las consultas reciben el período y los filtros opcionales del reporte (FragmentosSql.FILTRO_VENTAS).
 */
public class ReporteVentasRepositorio extends RepositorioJdbc {

    /** Cantidad bruta entregada de un producto en el período y su posición entre productos de la misma unidad. */
    public record CantidadVendida(String codigo, String nombre, String unidad, BigDecimal cantidad,
                                  BigDecimal subtotalEntregado, int posicionEnUnidad, int posicionPorImporte) {
    }

    /** Pedidos de un tipo en un estado a la fecha hasta. */
    public record PedidosPorEstado(String tipo, String estado, long cantidad) {
    }

    /** Eventos del período por tipo de pedido. */
    public record EventosPorTipo(String tipo, long creados, long entregados, long canceladosAntesDelDespacho,
                                 long canceladosTrasElDespacho) {
    }

    /** Línea de un encargo en espera de abastecimiento con su pendiente (DETALLE_PEDIDO «Pendiente de abastecer»). */
    public record EncargoPendiente(String pedido, String producto, String unidad, BigDecimal cantidad,
                                   BigDecimal cubierta, String produccion, String estadoProduccion,
                                   LocalDate disponibilidadProduccion, LocalDate fechaEstimada) {
    }

    /** Pedido con su entrega pendiente o despachada sin entregar a la fecha hasta. */
    public record EntregaAbierta(String pedido, String tipo, String modalidad, String estadoPedido,
                                 String estadoEntrega, OffsetDateTime fechaProgramada) {
    }

    /** Estado de entrega que se informa cuando un pedido despachado no tiene transiciones registradas. */
    public static final String ENTREGA_NO_DETERMINABLE = "NO_DETERMINABLE";

    /** Fallos (entradas en FALLIDA) y reintentos (FALLIDA -> EN_CAMINO) del período. */
    public record FallosEntrega(long fallos, long reintentos) {
    }

    /**
     * Devolución con algún hecho dentro del período. El estado y la resolución son los de la fecha hasta, y los
     * hechos posteriores a esa fecha llegan vacíos.
     */
    public record DevolucionPeriodo(String codigo, String pedido, String producto, String estado, String resolucion,
                                    String rectificaA, OffsetDateTime fechaSolicitud, BigDecimal cantidad,
                                    OffsetDateTime fechaResolucion, BigDecimal cantidadAprobada,
                                    OffsetDateTime fechaRecepcion, BigDecimal cantidadRecibida,
                                    BigDecimal cantidadApta, OffsetDateTime fechaAnulacion) {
    }

    /** Devoluciones solicitadas hasta la fecha hasta, por su estado y resolución a esa fecha. */
    public record DevolucionesPorEstado(String estado, String resolucion, long cantidad) {
    }

    /** Cantidades de posventa de un producto en el saldo vigente, cada una por la fecha de su hecho. */
    public record PosventaProducto(String producto, String unidad, BigDecimal solicitada, BigDecimal aprobada,
                                   BigDecimal recibida, BigDecimal reincorporada, BigDecimal repuesta) {
    }

    /** Reembolsos solicitados en el período, por su estado a la fecha hasta. */
    public record ReembolsosPorEstado(String estado, long cantidad) {
    }

    /** Pedido vigente a la fecha hasta con su saldo pendiente de cobro. */
    public record SaldoPedido(String pedido, String tipo, String estado, BigDecimal total, BigDecimal pagado,
                              boolean efectivoContraEntrega) {
    }

    /** Hitos de preparación y entrega de un pedido (HISTORIAL_PEDIDO) ocurridos hasta la fecha hasta. */
    public record TiemposPedido(String pedido, OffsetDateTime enPreparacion, OffsetDateTime despachado,
                                OffsetDateTime entregado) {
    }

    /** Período, estado de los pedidos a la fecha hasta y filtros. Consume desde, hasta y los cinco filtros. */
    private static final String BASE = "WITH " + FragmentosSql.PERIODO + ", " + FragmentosSql.CORTE_AL_FIN_DEL_PERIODO + ", "
            + FragmentosSql.ESTADO_PEDIDO + ", " + FragmentosSql.FILTRO_VENTAS;

    private static final String EN_PEDIDO_FILTRADO = " IN (SELECT id FROM pedido_filtrado)";
    private static final String EN_PRODUCTO_FILTRADO = " IN (SELECT id FROM producto_filtrado)";

    // Estado de una devolución (alias d) a la fecha hasta, reconstruido con sus fechas. Solo se anula una APROBADA
    // y fecha_cierre es el paso a COMPLETADA. Requiere la CTE corte.
    private static final String ESTADO_DEVOLUCION = "CASE WHEN d.fecha_anulacion < c.fin THEN 'ANULADA' "
            + "WHEN d.fecha_cierre < c.fin THEN 'COMPLETADA' "
            + "WHEN d.fecha_resolucion < c.fin THEN CASE WHEN d.estado = 'RECHAZADA' THEN 'RECHAZADA' ELSE 'APROBADA' END "
            + "ELSE 'SOLICITADA' END";

    private static final String SQL_CANTIDADES = BASE + " "
            + "SELECT pr.codigo, pr.nombre, pr.unidad_medida, SUM(dp.cantidad) AS cantidad, SUM(dp.subtotal) AS subtotal, "
            + "       RANK() OVER (PARTITION BY pr.unidad_medida ORDER BY SUM(dp.cantidad) DESC) AS posicion_unidad, "
            + "       RANK() OVER (ORDER BY SUM(dp.subtotal) DESC) AS posicion_importe "
            + "  FROM detalle_pedido dp JOIN entrega e ON e.pedido_id = dp.pedido_id "
            + "  JOIN producto pr ON pr.id = dp.producto_id, periodo p "
            + " WHERE e.fecha_entrega >= p.ini AND e.fecha_entrega < p.fin "
            + "   AND dp.pedido_id" + EN_PEDIDO_FILTRADO + " AND dp.producto_id" + EN_PRODUCTO_FILTRADO
            + " GROUP BY pr.codigo, pr.nombre, pr.unidad_medida "
            + " ORDER BY pr.unidad_medida, posicion_unidad, pr.codigo";

    private static final String SQL_ESTADOS = BASE + " "
            + "SELECT pe.tipo, ep.estado, COUNT(*) AS cantidad FROM pedido pe JOIN estado_pedido ep ON ep.pedido_id = pe.id "
            + " WHERE pe.id" + EN_PEDIDO_FILTRADO
            + " GROUP BY pe.tipo, ep.estado ORDER BY pe.tipo, ep.estado";

    private static final String SQL_EVENTOS = BASE + ", " + FragmentosSql.CANCELACION + " "
            + "SELECT pe.tipo, "
            + "  COUNT(*) FILTER (WHERE pe.fecha_creacion >= p.ini AND pe.fecha_creacion < p.fin) AS creados, "
            + "  COUNT(*) FILTER (WHERE e.fecha_entrega >= p.ini AND e.fecha_entrega < p.fin) AS entregados, "
            + "  COUNT(*) FILTER (WHERE ca.fecha >= p.ini AND ca.fecha < p.fin AND NOT ca.tras_despacho) AS cancelados_antes, "
            + "  COUNT(*) FILTER (WHERE ca.fecha >= p.ini AND ca.fecha < p.fin AND ca.tras_despacho) AS cancelados_despues "
            + "  FROM pedido pe JOIN entrega e ON e.pedido_id = pe.id "
            + "  LEFT JOIN cancelacion ca ON ca.pedido_id = pe.id, periodo p "
            + " WHERE pe.id" + EN_PEDIDO_FILTRADO
            + " GROUP BY pe.tipo ORDER BY pe.tipo";

    // Una reserva cubre la línea a la fecha hasta si ya existía y no estaba liberada ni expirada a esa fecha.
    private static final String SQL_ENCARGOS = BASE + " "
            + "SELECT pe.codigo AS pedido, pr.codigo || ' ' || pr.nombre AS producto, pr.unidad_medida, dp.cantidad, "
            + "       COALESCE((SELECT SUM(rs.cantidad) FROM reserva_stock rs WHERE rs.detalle_pedido_id = dp.id "
            + "          AND rs.fecha_creacion < c.fin "
            + "          AND (rs.estado IN ('ACTIVA', 'CONSUMIDA') OR rs.fecha_cierre >= c.fin)), 0) AS cubierta, "
            + "       pd.codigo AS produccion, pd.estado AS estado_produccion, pd.fecha_disponibilidad_estimada, "
            + "       pe.fecha_estimada_disponibilidad "
            + "  FROM pedido pe JOIN estado_pedido ep ON ep.pedido_id = pe.id "
            + "  JOIN detalle_pedido dp ON dp.pedido_id = pe.id JOIN producto pr ON pr.id = dp.producto_id "
            + "  LEFT JOIN produccion pd ON pd.id = dp.produccion_id, corte c "
            + " WHERE ep.estado = 'ESPERANDO_ABASTECIMIENTO' "
            + "   AND pe.id" + EN_PEDIDO_FILTRADO + " AND dp.producto_id" + EN_PRODUCTO_FILTRADO
            + " ORDER BY pe.codigo, pr.codigo";

    // Un pedido DESPACHADO cuya entrega no tiene transiciones hasta la fecha hasta no se omite: se informa como no
    // determinable, sin suponer su estado.
    private static final String SQL_ENTREGAS = BASE + ", " + FragmentosSql.TRANSICION_ENTREGA + ", "
            + FragmentosSql.ESTADO_ENTREGA + " "
            + "SELECT pe.codigo, pe.tipo, e.modalidad, ep.estado AS estado_pedido, "
            + "       CASE WHEN ee.estado IS NULL AND ep.estado = 'DESPACHADO' THEN '" + ENTREGA_NO_DETERMINABLE + "' "
            + "            ELSE COALESCE(ee.estado, 'PENDIENTE') END AS estado_entrega, e.fecha_programada "
            + "  FROM pedido pe JOIN entrega e ON e.pedido_id = pe.id JOIN estado_pedido ep ON ep.pedido_id = pe.id "
            + "  LEFT JOIN estado_entrega ee ON ee.entrega_id = e.id "
            + " WHERE ((ep.estado IN ('CONFIRMADO', 'EN_PREPARACION') AND COALESCE(ee.estado, 'PENDIENTE') = 'PENDIENTE') "
            + "    OR (ep.estado = 'DESPACHADO' AND (ee.estado IS NULL "
            + "        OR ee.estado IN ('EN_CAMINO', 'LISTA_PARA_RECOJO', 'FALLIDA', 'NO_RECUPERADA')))) "
            + "   AND pe.id" + EN_PEDIDO_FILTRADO
            + " ORDER BY ep.estado, pe.codigo";

    private static final String SQL_FALLOS = BASE + " "
            + "SELECT COUNT(*) FILTER (WHERE b.detalle ->> 'estado_nuevo' = 'FALLIDA') AS fallos, "
            + "       COUNT(*) FILTER (WHERE b.detalle ->> 'estado_anterior' = 'FALLIDA' "
            + "                          AND b.detalle ->> 'estado_nuevo' = 'EN_CAMINO') AS reintentos "
            + "  FROM bitacora b, periodo p "
            + " WHERE b.entidad = 'ENTREGA' AND b.fecha >= p.ini AND b.fecha < p.fin "
            + "   AND b.entidad_id IN (SELECT e.id FROM entrega e WHERE e.pedido_id" + EN_PEDIDO_FILTRADO + ")";

    private static final String SQL_DEVOLUCIONES = BASE + " "
            + "SELECT d.codigo, pe.codigo AS pedido, pr.codigo AS producto, " + ESTADO_DEVOLUCION + " AS estado, "
            + "       CASE WHEN d.fecha_resolucion < c.fin THEN d.resolucion END AS resolucion, "
            + "       an.codigo AS rectifica_a, d.fecha_solicitud, d.cantidad, "
            + "       CASE WHEN d.fecha_resolucion < c.fin THEN d.fecha_resolucion END AS fecha_resolucion, "
            + "       CASE WHEN d.fecha_resolucion < c.fin THEN d.cantidad_aprobada END AS cantidad_aprobada, "
            + "       CASE WHEN d.fecha_recepcion < c.fin THEN d.fecha_recepcion END AS fecha_recepcion, "
            + "       CASE WHEN d.fecha_recepcion < c.fin THEN d.cantidad_recibida END AS cantidad_recibida, "
            + "       CASE WHEN d.fecha_inspeccion < c.fin THEN d.cantidad_apta END AS cantidad_apta, "
            + "       CASE WHEN d.fecha_anulacion < c.fin THEN d.fecha_anulacion END AS fecha_anulacion "
            + "  FROM devolucion d "
            + "  JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id "
            + "  JOIN producto pr ON pr.id = feder_producto_de_lote_feder(desp.lote_feder_id) "
            + "  LEFT JOIN pedido pe ON pe.id = feder_pedido_de_movimiento(desp.id) "
            + "  LEFT JOIN devolucion an ON an.id = d.devolucion_anulada_id, periodo p, corte c "
            + " WHERE ((d.fecha_solicitud >= p.ini AND d.fecha_solicitud < p.fin) "
            + "    OR (d.fecha_resolucion >= p.ini AND d.fecha_resolucion < p.fin) "
            + "    OR (d.fecha_recepcion >= p.ini AND d.fecha_recepcion < p.fin) "
            + "    OR (d.fecha_cierre >= p.ini AND d.fecha_cierre < p.fin) "
            + "    OR (d.fecha_anulacion >= p.ini AND d.fecha_anulacion < p.fin)) "
            + "   AND pe.id" + EN_PEDIDO_FILTRADO + " AND pr.id" + EN_PRODUCTO_FILTRADO
            + " ORDER BY d.fecha_solicitud, d.codigo";

    private static final String SQL_DEVOLUCIONES_POR_ESTADO = BASE + " "
            + "SELECT " + ESTADO_DEVOLUCION + " AS estado, "
            + "       COALESCE(CASE WHEN d.fecha_resolucion < c.fin THEN d.resolucion END, 'Sin resolución') AS resolucion, "
            + "       COUNT(*) AS cantidad "
            + "  FROM devolucion d JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id, corte c "
            + " WHERE d.fecha_solicitud < c.fin "
            + "   AND feder_pedido_de_movimiento(desp.id)" + EN_PEDIDO_FILTRADO
            + "   AND feder_producto_de_lote_feder(desp.lote_feder_id)" + EN_PRODUCTO_FILTRADO
            + " GROUP BY 1, 2 ORDER BY 1, 2";

    // Cada cantidad por la fecha de su hecho, en el saldo vigente: una DEVOLUCION ANULADA deja de contar lo aprobado
    // desde su fecha_anulacion, y sus reincorporaciones quedan compensadas por los AJUSTE_NEGATIVO de la misma
    // devolución (R025). La rectificación (A39) no vuelve a sumar lo recibido que hereda de la devolución anulada.
    private static final String SQL_POSVENTA = BASE + ", "
            + "hecho AS ("
            + " SELECT desp.id AS despacho_id, desp.lote_feder_id, 'SOLICITADA' AS clave, d.cantidad "
            + "   FROM devolucion d JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id, periodo p "
            + "  WHERE d.fecha_solicitud >= p.ini AND d.fecha_solicitud < p.fin "
            + " UNION ALL "
            + " SELECT desp.id, desp.lote_feder_id, 'APROBADA', d.cantidad_aprobada "
            + "   FROM devolucion d JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id, periodo p "
            + "  WHERE d.cantidad_aprobada IS NOT NULL AND d.fecha_resolucion >= p.ini AND d.fecha_resolucion < p.fin "
            + " UNION ALL "
            + " SELECT desp.id, desp.lote_feder_id, 'APROBADA', -d.cantidad_aprobada "
            + "   FROM devolucion d JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id, periodo p "
            + "  WHERE d.estado = 'ANULADA' AND d.fecha_anulacion >= p.ini AND d.fecha_anulacion < p.fin "
            + " UNION ALL "
            + " SELECT desp.id, desp.lote_feder_id, 'RECIBIDA', "
            + "        GREATEST(d.cantidad_recibida - COALESCE(an.cantidad_recibida, 0), 0) "
            + "   FROM devolucion d JOIN movimiento_inventario desp ON desp.id = d.movimiento_inventario_id "
            + "   LEFT JOIN devolucion an ON an.id = d.devolucion_anulada_id, periodo p "
            + "  WHERE d.cantidad_recibida IS NOT NULL AND d.fecha_recepcion >= p.ini AND d.fecha_recepcion < p.fin "
            + " UNION ALL "
            + " SELECT m.id, m.lote_feder_id, "
            + "        CASE WHEN m.tipo = 'SALIDA_REPOSICION' THEN 'REPUESTA' ELSE 'REINCORPORADA' END, "
            + "        CASE WHEN m.tipo = 'AJUSTE_NEGATIVO' THEN -m.cantidad ELSE m.cantidad END "
            + "   FROM movimiento_inventario m, periodo p "
            + "  WHERE (m.tipo IN ('ENTRADA_DEVOLUCION', 'SALIDA_REPOSICION') "
            + "         OR (m.tipo = 'AJUSTE_NEGATIVO' AND m.devolucion_id IS NOT NULL)) "
            + "    AND m.fecha >= p.ini AND m.fecha < p.fin), "
            + "hecho_filtrado AS (SELECT feder_producto_de_lote_feder(h.lote_feder_id) AS producto_id, h.clave, h.cantidad "
            + "   FROM hecho h WHERE feder_pedido_de_movimiento(h.despacho_id)" + EN_PEDIDO_FILTRADO + ") "
            + "SELECT pr.codigo || ' ' || pr.nombre AS producto, pr.unidad_medida, "
            + "       COALESCE(SUM(h.cantidad) FILTER (WHERE h.clave = 'SOLICITADA'), 0) AS solicitada, "
            + "       COALESCE(SUM(h.cantidad) FILTER (WHERE h.clave = 'APROBADA'), 0) AS aprobada, "
            + "       COALESCE(SUM(h.cantidad) FILTER (WHERE h.clave = 'RECIBIDA'), 0) AS recibida, "
            + "       COALESCE(SUM(h.cantidad) FILTER (WHERE h.clave = 'REINCORPORADA'), 0) AS reincorporada, "
            + "       COALESCE(SUM(h.cantidad) FILTER (WHERE h.clave = 'REPUESTA'), 0) AS repuesta "
            + "  FROM hecho_filtrado h JOIN producto pr ON pr.id = h.producto_id "
            + " WHERE pr.id" + EN_PRODUCTO_FILTRADO
            + " GROUP BY pr.codigo, pr.nombre, pr.unidad_medida ORDER BY pr.codigo";

    // El fallo de un reembolso no tiene fecha propia en el modelo: un FALLIDO se informa con su estado actual.
    private static final String SQL_REEMBOLSOS = BASE + " "
            + "SELECT CASE WHEN r.estado = 'EJECUTADO' AND r.fecha_ejecucion >= c.fin THEN 'PENDIENTE' ELSE r.estado END AS estado, "
            + "       COUNT(*) AS cantidad "
            + "  FROM reembolso r JOIN pago pg ON pg.id = r.pago_id, periodo p, corte c "
            + " WHERE r.fecha_solicitud >= p.ini AND r.fecha_solicitud < p.fin AND pg.pedido_id" + EN_PEDIDO_FILTRADO
            + " GROUP BY 1 ORDER BY 1";

    // «Efectivo contra entrega» es un acuerdo en efectivo todavía abierto al corte: registrado antes y PENDIENTE, o
    // PAGADO después del corte. Un efectivo RECHAZADO (por ejemplo, antes de pagar por QR) no lo es; como el rechazo
    // no tiene fecha propia en el modelo, se informa con su estado actual.
    private static final String SQL_SALDOS = BASE + " "
            + "SELECT pe.codigo, pe.tipo, ep.estado, pe.total, "
            + "       COALESCE((SELECT SUM(pg.monto) FROM pago pg WHERE pg.pedido_id = pe.id "
            + "                  AND pg.estado = 'PAGADO' AND pg.fecha_pago < c.fin), 0) AS pagado, "
            + "       EXISTS (SELECT 1 FROM pago pg WHERE pg.pedido_id = pe.id AND pg.metodo = 'EFECTIVO' "
            + "                  AND pg.fecha_registro < c.fin "
            + "                  AND (pg.estado = 'PENDIENTE' OR (pg.estado = 'PAGADO' AND pg.fecha_pago >= c.fin))) AS efectivo "
            + "  FROM pedido pe JOIN estado_pedido ep ON ep.pedido_id = pe.id, corte c "
            + " WHERE ep.estado NOT IN ('ENTREGADO', 'CANCELADO') AND pe.id" + EN_PEDIDO_FILTRADO
            + " ORDER BY pe.codigo";

    // Solo cuentan los hitos ocurridos hasta la fecha hasta: un tramo que se cierra después sigue abierto en el
    // reporte, y así un reporte histórico no cambia según el día en que se genera.
    private static final String SQL_TIEMPOS = BASE + " "
            + "SELECT pe.codigo, "
            + "       MIN(h.fecha) FILTER (WHERE h.estado_nuevo = 'EN_PREPARACION' AND h.fecha < p.fin) AS en_preparacion, "
            + "       MIN(h.fecha) FILTER (WHERE h.estado_nuevo = 'DESPACHADO' AND h.fecha < p.fin) AS despachado, "
            + "       MIN(h.fecha) FILTER (WHERE h.estado_nuevo = 'ENTREGADO' AND h.fecha < p.fin) AS entregado "
            + "  FROM pedido pe JOIN historial_pedido h ON h.pedido_id = pe.id, periodo p "
            + " WHERE pe.id" + EN_PEDIDO_FILTRADO
            + " GROUP BY pe.codigo, p.ini, p.fin "
            + "HAVING bool_or(h.estado_nuevo IN ('EN_PREPARACION', 'DESPACHADO', 'ENTREGADO') "
            + "               AND h.fecha >= p.ini AND h.fecha < p.fin) "
            + " ORDER BY pe.codigo";

    public List<CantidadVendida> cantidadesVendidas(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_CANTIDADES, fila -> new CantidadVendida(fila.getString("codigo"), fila.getString("nombre"),
                fila.getString("unidad_medida"), fila.getBigDecimal("cantidad"), fila.getBigDecimal("subtotal"),
                fila.getInt("posicion_unidad"), fila.getInt("posicion_importe")), parametros(desde, hasta, filtro));
    }

    public List<PedidosPorEstado> pedidosPorEstado(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_ESTADOS, fila -> new PedidosPorEstado(fila.getString("tipo"), fila.getString("estado"),
                fila.getLong("cantidad")), parametros(desde, hasta, filtro));
    }

    public List<EventosPorTipo> eventosPorTipo(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_EVENTOS, fila -> new EventosPorTipo(fila.getString("tipo"), fila.getLong("creados"),
                fila.getLong("entregados"), fila.getLong("cancelados_antes"), fila.getLong("cancelados_despues")),
                parametros(desde, hasta, filtro));
    }

    public List<EncargoPendiente> encargosEnEspera(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_ENCARGOS, fila -> new EncargoPendiente(fila.getString("pedido"), fila.getString("producto"),
                fila.getString("unidad_medida"), fila.getBigDecimal("cantidad"), fila.getBigDecimal("cubierta"),
                fila.getString("produccion"), fila.getString("estado_produccion"),
                fecha(fila, "fecha_disponibilidad_estimada"), fecha(fila, "fecha_estimada_disponibilidad")),
                parametros(desde, hasta, filtro));
    }

    public List<EntregaAbierta> entregasAbiertas(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_ENTREGAS, fila -> new EntregaAbierta(fila.getString("codigo"), fila.getString("tipo"),
                fila.getString("modalidad"), fila.getString("estado_pedido"), fila.getString("estado_entrega"),
                fechaHora(fila, "fecha_programada")), parametros(desde, hasta, filtro));
    }

    public FallosEntrega fallosEntrega(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultarUno(SQL_FALLOS, fila -> new FallosEntrega(fila.getLong("fallos"), fila.getLong("reintentos")),
                parametros(desde, hasta, filtro)).orElseThrow();
    }

    public List<DevolucionPeriodo> devoluciones(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_DEVOLUCIONES, fila -> new DevolucionPeriodo(fila.getString("codigo"), fila.getString("pedido"),
                fila.getString("producto"), fila.getString("estado"), fila.getString("resolucion"),
                fila.getString("rectifica_a"), fechaHora(fila, "fecha_solicitud"), fila.getBigDecimal("cantidad"),
                fechaHora(fila, "fecha_resolucion"), fila.getBigDecimal("cantidad_aprobada"),
                fechaHora(fila, "fecha_recepcion"), fila.getBigDecimal("cantidad_recibida"),
                fila.getBigDecimal("cantidad_apta"), fechaHora(fila, "fecha_anulacion")), parametros(desde, hasta, filtro));
    }

    public List<DevolucionesPorEstado> devolucionesPorEstado(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_DEVOLUCIONES_POR_ESTADO, fila -> new DevolucionesPorEstado(fila.getString("estado"),
                fila.getString("resolucion"), fila.getLong("cantidad")), parametros(desde, hasta, filtro));
    }

    public List<PosventaProducto> posventaPorProducto(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_POSVENTA, fila -> new PosventaProducto(fila.getString("producto"), fila.getString("unidad_medida"),
                fila.getBigDecimal("solicitada"), fila.getBigDecimal("aprobada"), fila.getBigDecimal("recibida"),
                fila.getBigDecimal("reincorporada"), fila.getBigDecimal("repuesta")), parametros(desde, hasta, filtro));
    }

    public List<ReembolsosPorEstado> reembolsosPorEstado(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_REEMBOLSOS, fila -> new ReembolsosPorEstado(fila.getString("estado"), fila.getLong("cantidad")),
                parametros(desde, hasta, filtro));
    }

    public List<SaldoPedido> saldosPendientes(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_SALDOS, fila -> new SaldoPedido(fila.getString("codigo"), fila.getString("tipo"),
                fila.getString("estado"), fila.getBigDecimal("total"), fila.getBigDecimal("pagado"),
                fila.getBoolean("efectivo")), parametros(desde, hasta, filtro));
    }

    public List<TiemposPedido> tiempos(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return consultar(SQL_TIEMPOS, fila -> new TiemposPedido(fila.getString("codigo"), fechaHora(fila, "en_preparacion"),
                fechaHora(fila, "despachado"), fechaHora(fila, "entregado")), parametros(desde, hasta, filtro));
    }

    private static Object[] parametros(LocalDate desde, LocalDate hasta, FiltrosConsulta.Ventas filtro) {
        return FiltrosConsulta.parametros(filtro.valores(), desde, hasta);
    }
}
