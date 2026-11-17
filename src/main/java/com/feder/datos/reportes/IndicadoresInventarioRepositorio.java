package com.feder.datos.reportes;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Consultas a costo del inventario de FEDER (CU7, apartados 6 y 7, y datos de CU8): valor del inventario
 * reconstruido con los movimientos, compras aceptadas, mercancía despachada pendiente de entrega y
 * entregas NO_RECUPERADA pendientes de cancelación. Solo cuenta LOTE_FEDER, nunca LOTE_PRODUCCION.
 */
public class IndicadoresInventarioRepositorio extends RepositorioJdbc {

    /** Saldo y valor a costo de un producto a la fecha de corte. */
    public record ValorProducto(String codigo, String nombre, String unidad, BigDecimal cantidad, BigDecimal valor) {
    }

    /** Lo aceptado en un período por productor, producto y comunidad de origen histórica (A36). */
    public record AceptadoPorOrigen(String productor, String comunidad, String municipio, String departamento,
                                    String productoCodigo, String productoNombre, String unidad,
                                    BigDecimal cantidadEntrada, BigDecimal cantidadAnulada,
                                    BigDecimal costoEntrada, BigDecimal costoAnulado) {
    }

    /** Unidades despachadas que no están en el inventario ni entregadas, por pedido y producto. */
    public record MercanciaDespachada(String pedido, String estadoEntrega, boolean noRecuperada,
                                      String productoCodigo, String productoNombre, String unidad,
                                      BigDecimal unidades, BigDecimal costo) {
    }

    /** Costo de las entradas y de las salidas de inventario de un período (conciliación de inventario). */
    public record MovimientoACosto(BigDecimal entradas, BigDecimal salidas) {
    }

    private static final String SQL_VALOR = "WITH " + FragmentosSql.CORTE + ", " + FragmentosSql.FILTRO_LOTES + ", "
            + FragmentosSql.COSTO_MOVIMIENTO + " "
            + "SELECT pr.codigo, pr.nombre, pr.unidad_medida, SUM(cm.signo * cm.cantidad) AS cantidad, "
            + "       SUM(cm.signo * cm.costo) AS valor "
            + "  FROM costo_movimiento cm JOIN producto pr ON pr.id = cm.producto_id, corte c "
            + " WHERE cm.fecha < c.fin AND cm.lote_feder_id IN (SELECT id FROM lote_filtrado) "
            + " GROUP BY pr.codigo, pr.nombre, pr.unidad_medida ORDER BY pr.codigo";

    private static final String SQL_ACEPTADO = "WITH " + FragmentosSql.PERIODO + ", " + FragmentosSql.FILTRO_COMPRAS + ", "
            + FragmentosSql.COSTO_MOVIMIENTO + " "
            + "SELECT " + FragmentosSql.ETIQUETA_PRODUCTOR + " AS productor, "
            + "       pd.nombre_comunidad_origen, pd.municipio_origen, pd.departamento_origen, "
            + "       pr.codigo, pr.nombre, pr.unidad_medida, "
            + "       COALESCE(SUM(cm.cantidad) FILTER (WHERE cm.tipo = 'ENTRADA_COMPRA'), 0) AS cantidad_entrada, "
            + "       COALESCE(SUM(cm.cantidad) FILTER (WHERE cm.tipo = 'ANULACION_RECEPCION'), 0) AS cantidad_anulada, "
            + "       COALESCE(SUM(cm.costo) FILTER (WHERE cm.tipo = 'ENTRADA_COMPRA'), 0) AS costo_entrada, "
            + "       COALESCE(SUM(cm.costo) FILTER (WHERE cm.tipo = 'ANULACION_RECEPCION'), 0) AS costo_anulado "
            + "  FROM costo_movimiento cm "
            + "  JOIN produccion pd ON pd.id = cm.produccion_id "
            + "  JOIN producto pr ON pr.id = cm.producto_id "
            + "  JOIN compra_feder cf ON cf.id = cm.compra_id "
            + "  JOIN productor po ON po.id = cf.productor_id, periodo p "
            + " WHERE cm.tipo IN ('ENTRADA_COMPRA', 'ANULACION_RECEPCION') AND cm.fecha >= p.ini AND cm.fecha < p.fin "
            + "   AND cm.detalle_compra_id IN (SELECT id FROM linea_filtrada) "
            + " GROUP BY po.id, po.codigo_productor, pd.nombre_comunidad_origen, pd.municipio_origen, pd.departamento_origen, "
            + "          pr.codigo, pr.nombre, pr.unidad_medida "
            + " ORDER BY pr.codigo, productor, pd.nombre_comunidad_origen";

    // Por cada SALIDA_VENTA, lo despachado menos lo retornado hasta el corte. Se clasifica según el estado de la
    // entrega al corte: pendiente de entrega (EN_CAMINO, FALLIDA, LISTA_PARA_RECOJO) o NO_RECUPERADA cuyo pedido
    // todavía no tiene el paso a CANCELADO hasta el corte (desde esa cancelación el costo pasa a la merma).
    private static final String SQL_MERCANCIA = "WITH " + FragmentosSql.CORTE + ", " + FragmentosSql.FILTRO_LOTES + ", "
            + FragmentosSql.COSTO_MOVIMIENTO + ", " + FragmentosSql.TRANSICION_ENTREGA + ", "
            + FragmentosSql.ESTADO_ENTREGA + ", " + FragmentosSql.CANCELACION + ", "
            + "pendiente AS ("
            + " SELECT pe.codigo AS pedido, ee.estado, ee.estado = 'NO_RECUPERADA' AS no_recuperada, sv.producto_id, "
            + "        sv.cantidad - COALESCE((SELECT SUM(rt.cantidad) FROM movimiento_inventario rt "
            + "            WHERE rt.reserva_id = sv.reserva_id AND rt.tipo = 'ENTRADA_RETORNO_ENTREGA' AND rt.fecha < c.fin), 0) AS unidades, "
            + "        sv.precio "
            + "   FROM costo_movimiento sv JOIN reserva_stock rs ON rs.id = sv.reserva_id "
            + "   JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
            + "   JOIN pedido pe ON pe.id = dp.pedido_id "
            + "   JOIN entrega e ON e.pedido_id = pe.id "
            + "   JOIN estado_entrega ee ON ee.entrega_id = e.id, corte c "
            + "  WHERE sv.tipo = 'SALIDA_VENTA' AND sv.fecha < c.fin AND sv.lote_feder_id IN (SELECT id FROM lote_filtrado) "
            + "    AND (ee.estado IN ('EN_CAMINO', 'FALLIDA', 'LISTA_PARA_RECOJO') "
            + "         OR (ee.estado = 'NO_RECUPERADA' AND NOT EXISTS (SELECT 1 FROM cancelacion ca "
            + "              WHERE ca.pedido_id = pe.id AND ca.fecha < c.fin)))) "
            + "SELECT pn.pedido, pn.estado, pn.no_recuperada, pr.codigo, pr.nombre, pr.unidad_medida, "
            + "       SUM(pn.unidades) AS unidades, SUM(pn.unidades * pn.precio) AS costo "
            + "  FROM pendiente pn JOIN producto pr ON pr.id = pn.producto_id "
            + " GROUP BY pn.pedido, pn.estado, pn.no_recuperada, pr.codigo, pr.nombre, pr.unidad_medida "
            + "HAVING SUM(pn.unidades) > 0 "
            + " ORDER BY pn.no_recuperada, pn.pedido, pr.codigo";

    // Solo importan los pedidos que al corte siguen DESPACHADO: los ENTREGADO o CANCELADO ya no dependen del estado
    // de su entrega. Para ellos, el estado de la entrega a esa fecha no es determinable si (1) no hay ninguna
    // transición hasta el corte, (2) alguna transición no parte del estado en que dejó la anterior (o de PENDIENTE,
    // si es la primera), o (3) después del corte no hay más transiciones y la última registrada no coincide con el
    // estado actual de la entrega, lo que indica transiciones sin registrar.
    private static final String SQL_NO_DETERMINABLES = "WITH " + FragmentosSql.CORTE + ", " + FragmentosSql.ESTADO_PEDIDO + ", "
            + FragmentosSql.TRANSICION_ENTREGA + " "
            + "SELECT pe.codigo FROM pedido pe JOIN estado_pedido ep ON ep.pedido_id = pe.id AND ep.estado = 'DESPACHADO' "
            + "  JOIN entrega e ON e.pedido_id = pe.id, corte c "
            + " WHERE NOT EXISTS (SELECT 1 FROM transicion_entrega t WHERE t.entrega_id = e.id AND t.fecha < c.fin) "
            + "    OR EXISTS (SELECT 1 FROM transicion_entrega t WHERE t.entrega_id = e.id AND t.fecha < c.fin "
            + "                 AND t.anterior IS DISTINCT FROM COALESCE(t.previo, 'PENDIENTE')) "
            + "    OR (NOT EXISTS (SELECT 1 FROM transicion_entrega t WHERE t.entrega_id = e.id AND t.fecha >= c.fin) "
            + "        AND e.estado IS DISTINCT FROM (SELECT t.nuevo FROM transicion_entrega t WHERE t.entrega_id = e.id "
            + "                                        ORDER BY t.fecha DESC, t.id DESC LIMIT 1)) "
            + " ORDER BY pe.codigo";

    private static final String SQL_MOVIMIENTOS_A_COSTO = "WITH " + FragmentosSql.PERIODO + ", " + FragmentosSql.COSTO_MOVIMIENTO + " "
            + "SELECT COALESCE(SUM(cm.costo) FILTER (WHERE cm.signo > 0), 0) AS entradas, "
            + "       COALESCE(SUM(cm.costo) FILTER (WHERE cm.signo < 0), 0) AS salidas "
            + "  FROM costo_movimiento cm, periodo p WHERE cm.fecha >= p.ini AND cm.fecha < p.fin";

    public List<ValorProducto> valorPorProducto(LocalDate corte, FiltrosConsulta.Lotes filtro) {
        return consultar(SQL_VALOR, fila -> new ValorProducto(fila.getString("codigo"), fila.getString("nombre"),
                fila.getString("unidad_medida"), fila.getBigDecimal("cantidad"), fila.getBigDecimal("valor")),
                FiltrosConsulta.parametros(filtro.valores(), corte));
    }

    public List<AceptadoPorOrigen> aceptadoPorOrigen(LocalDate desde, LocalDate hasta, FiltrosConsulta.Compras filtro) {
        return consultar(SQL_ACEPTADO, fila -> new AceptadoPorOrigen(fila.getString("productor"),
                fila.getString("nombre_comunidad_origen"), fila.getString("municipio_origen"),
                fila.getString("departamento_origen"), fila.getString("codigo"), fila.getString("nombre"),
                fila.getString("unidad_medida"), fila.getBigDecimal("cantidad_entrada"),
                fila.getBigDecimal("cantidad_anulada"), fila.getBigDecimal("costo_entrada"),
                fila.getBigDecimal("costo_anulado")), FiltrosConsulta.parametros(filtro.valores(), desde, hasta));
    }

    public List<MercanciaDespachada> mercanciaDespachada(LocalDate corte, FiltrosConsulta.Lotes filtro) {
        return consultar(SQL_MERCANCIA, fila -> new MercanciaDespachada(fila.getString("pedido"), fila.getString("estado"),
                fila.getBoolean("no_recuperada"), fila.getString("codigo"), fila.getString("nombre"),
                fila.getString("unidad_medida"), fila.getBigDecimal("unidades"), fila.getBigDecimal("costo")),
                FiltrosConsulta.parametros(filtro.valores(), corte));
    }

    /** Pedidos DESPACHADO al corte cuyo estado de entrega a esa fecha no se puede reconstruir con la bitácora. */
    public List<String> pedidosNoDeterminables(LocalDate corte) {
        return consultar(SQL_NO_DETERMINABLES, fila -> fila.getString("codigo"), corte);
    }

    public MovimientoACosto movimientosACosto(LocalDate desde, LocalDate hasta) {
        return consultarUno(SQL_MOVIMIENTOS_A_COSTO, fila -> new MovimientoACosto(fila.getBigDecimal("entradas"),
                fila.getBigDecimal("salidas")), desde, hasta).orElseThrow();
    }
}
