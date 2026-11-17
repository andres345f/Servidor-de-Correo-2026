package com.feder.datos.reportes;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Consultas del reporte de inventario de FEDER (CU8) a una fecha de corte. Las cantidades físicas se
 * reconstruyen con los movimientos y las reservadas con las fechas de RESERVA_STOCK, de modo que un corte
 * pasado es exacto. Solo se consulta LOTE_FEDER: las existencias del productor nunca se suman.
 *
 * Todas las consultas reciben los filtros opcionales de lotes (FragmentosSql.FILTRO_LOTES).
 */
public class ReporteInventarioRepositorio extends RepositorioJdbc {

    /** Producto del catálogo que se informa (activo o con lotes). */
    public record ProductoCatalogo(long id, String codigo, String nombre, String unidad, String categoria,
                                   BigDecimal stockMinimo, boolean activo) {
    }

    /** Lote de FEDER con su saldo y reservado al corte y los datos de origen (A14, A13). */
    public record LoteAlCorte(long productoId, String codigo, String productoCodigo, String productor,
                              String comunidadOrigen, BigDecimal saldo, BigDecimal reservado, String estadoActual,
                              String ubicacion, OffsetDateTime fechaIngreso, LocalDate vencimiento) {
    }

    /** Lote con saldo actual que vence hasta una fecha límite (avisos de PRODUCTO «Stock vendible derivado»). */
    public record LoteConVencimiento(String codigo, String productoCodigo, BigDecimal saldoActual, String estadoActual,
                                     LocalDate vencimiento) {
    }

    /** Movimientos de un tipo en un lote dentro de un período. */
    public record MovimientosAgrupados(String tipo, String productoCodigo, String lote, BigDecimal cantidad,
                                       long movimientos) {
    }

    // Con filtros de productor, comunidad o estado del lote, el catálogo se limita a los productos que tienen
    // algún lote que los cumple; si no, un producto sin esos lotes aparecería como agotado.
    private static final String SQL_PRODUCTOS = "WITH " + FragmentosSql.FILTRO_LOTES + " "
            + "SELECT p.id, p.codigo, p.nombre, p.unidad_medida, c.nombre AS categoria, p.stock_minimo, p.activo "
            + "  FROM producto p JOIN categoria c ON c.id = p.categoria_id, filtro f "
            + " WHERE p.id IN (SELECT id FROM producto_filtrado) "
            + "   AND (p.activo OR EXISTS (SELECT 1 FROM lote_feder lf JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
            + "        JOIN lote_produccion lp ON lp.id = dc.lote_origen_id JOIN produccion pd ON pd.id = lp.produccion_id "
            + "       WHERE pd.producto_id = p.id)) "
            + "   AND ((f.productor IS NULL AND f.comunidad IS NULL AND f.estado IS NULL) "
            + "        OR p.id IN (SELECT feder_producto_de_lote_feder(id) FROM lote_filtrado)) "
            + " ORDER BY p.codigo";

    // Una reserva aparta unidades al corte si ya existía y todavía no se había cerrado a esa fecha.
    private static final String SQL_LOTES = "WITH " + FragmentosSql.CORTE + ", " + FragmentosSql.FILTRO_LOTES + ", "
            + "saldo AS (SELECT m.lote_feder_id, "
            + "          SUM(CASE WHEN m.tipo IN " + FragmentosSql.TIPOS_ENTRADA + " THEN m.cantidad ELSE -m.cantidad END) AS saldo "
            + "     FROM movimiento_inventario m, corte c WHERE m.fecha < c.fin GROUP BY m.lote_feder_id), "
            + "reservado AS (SELECT rs.lote_feder_id, SUM(rs.cantidad) AS reservado "
            + "     FROM reserva_stock rs, corte c "
            + "    WHERE rs.fecha_creacion < c.fin AND (rs.fecha_cierre IS NULL OR rs.fecha_cierre >= c.fin) "
            + "    GROUP BY rs.lote_feder_id) "
            + "SELECT pd.producto_id, lf.codigo, pr.codigo AS producto_codigo, "
            + "       " + FragmentosSql.ETIQUETA_PRODUCTOR + " AS productor, "
            + "       pd.nombre_comunidad_origen || ' (' || pd.municipio_origen || ')' AS comunidad, "
            + "       COALESCE(s.saldo, 0) AS saldo, COALESCE(r.reservado, 0) AS reservado, lf.estado, "
            + "       lf.ubicacion_almacen, lf.fecha_ingreso, lp.fecha_vencimiento "
            + "  FROM lote_feder lf JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
            + "  JOIN compra_feder cf ON cf.id = dc.compra_id JOIN productor po ON po.id = cf.productor_id "
            + "  JOIN lote_produccion lp ON lp.id = dc.lote_origen_id JOIN produccion pd ON pd.id = lp.produccion_id "
            + "  JOIN producto pr ON pr.id = pd.producto_id "
            + "  LEFT JOIN saldo s ON s.lote_feder_id = lf.id LEFT JOIN reservado r ON r.lote_feder_id = lf.id, corte c "
            + " WHERE lf.fecha_ingreso < c.fin AND lf.id IN (SELECT id FROM lote_filtrado) ORDER BY pr.codigo, lf.codigo";

    // Los avisos son de hoy: usan el saldo actual del lote (cantidad_disponible), no el saldo al corte.
    private static final String SQL_VENCIMIENTOS = "WITH " + FragmentosSql.FILTRO_LOTES + " "
            + "SELECT lf.codigo, pr.codigo AS producto_codigo, lf.cantidad_disponible, lf.estado, lp.fecha_vencimiento "
            + "  FROM lote_feder lf JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id "
            + "  JOIN lote_produccion lp ON lp.id = dc.lote_origen_id JOIN produccion pd ON pd.id = lp.produccion_id "
            + "  JOIN producto pr ON pr.id = pd.producto_id "
            + " WHERE lf.cantidad_disponible > 0 AND lp.fecha_vencimiento <= ? AND lf.id IN (SELECT id FROM lote_filtrado) "
            + " ORDER BY lp.fecha_vencimiento, lf.codigo";

    private static final String SQL_MOVIMIENTOS = "WITH " + FragmentosSql.PERIODO + ", " + FragmentosSql.FILTRO_LOTES + " "
            + "SELECT m.tipo, pr.codigo AS producto_codigo, lf.codigo AS lote, SUM(m.cantidad) AS cantidad, COUNT(*) AS movimientos "
            + "  FROM movimiento_inventario m JOIN lote_feder lf ON lf.id = m.lote_feder_id "
            + "  JOIN producto pr ON pr.id = feder_producto_de_lote_feder(lf.id), periodo p "
            + " WHERE m.fecha >= p.ini AND m.fecha < p.fin AND lf.id IN (SELECT id FROM lote_filtrado) "
            + " GROUP BY m.tipo, pr.codigo, lf.codigo ORDER BY pr.codigo, lf.codigo, m.tipo";

    public List<ProductoCatalogo> productos(FiltrosConsulta.Lotes filtro) {
        return consultar(SQL_PRODUCTOS, fila -> new ProductoCatalogo(fila.getLong("id"), fila.getString("codigo"),
                fila.getString("nombre"), fila.getString("unidad_medida"), fila.getString("categoria"),
                fila.getBigDecimal("stock_minimo"), fila.getBoolean("activo")), filtro.valores());
    }

    public List<LoteAlCorte> lotes(LocalDate corte, FiltrosConsulta.Lotes filtro) {
        return consultar(SQL_LOTES, fila -> new LoteAlCorte(fila.getLong("producto_id"), fila.getString("codigo"),
                fila.getString("producto_codigo"), fila.getString("productor"), fila.getString("comunidad"),
                fila.getBigDecimal("saldo"), fila.getBigDecimal("reservado"), fila.getString("estado"),
                fila.getString("ubicacion_almacen"), fechaHora(fila, "fecha_ingreso"), fecha(fila, "fecha_vencimiento")),
                FiltrosConsulta.parametros(filtro.valores(), corte));
    }

    /** Lotes con saldo actual cuyo vencimiento de origen es anterior o igual a la fecha límite. */
    public List<LoteConVencimiento> lotesQueVencenHasta(LocalDate limite, FiltrosConsulta.Lotes filtro) {
        Object[] valores = filtro.valores();
        Object[] parametros = Arrays.copyOf(valores, valores.length + 1);
        parametros[valores.length] = limite;
        return consultar(SQL_VENCIMIENTOS, fila -> new LoteConVencimiento(fila.getString("codigo"),
                fila.getString("producto_codigo"), fila.getBigDecimal("cantidad_disponible"), fila.getString("estado"),
                fecha(fila, "fecha_vencimiento")), parametros);
    }

    public List<MovimientosAgrupados> movimientos(LocalDate desde, LocalDate hasta, FiltrosConsulta.Lotes filtro) {
        return consultar(SQL_MOVIMIENTOS, fila -> new MovimientosAgrupados(fila.getString("tipo"),
                fila.getString("producto_codigo"), fila.getString("lote"), fila.getBigDecimal("cantidad"),
                fila.getLong("movimientos")), FiltrosConsulta.parametros(filtro.valores(), desde, hasta));
    }
}
