package com.feder.datos.reportes;

import com.feder.datos.RepositorioJdbc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Consultas del reporte de abastecimiento (CU8): compras a productores del período, productores
 * registrados e importes de compra. Nunca lee la cédula, el teléfono ni el correo del productor.
 *
 * Las consultas de compras reciben los filtros opcionales del reporte (FragmentosSql.FILTRO_COMPRAS).
 */
public class ReporteAbastecimientoRepositorio extends RepositorioJdbc {

    /** Línea de una compra con fecha_compra en el período y cómo se resolvió (DETALLE_COMPRA). */
    public record LineaCompra(String compra, OffsetDateTime fechaCompra, String productor, String estado,
                              String producto, String unidad, String loteOrigen, BigDecimal comprada,
                              BigDecimal aceptada, BigDecimal rechazada, BigDecimal cancelada,
                              BigDecimal precioUnitario) {
    }

    /** Productor registrado con su comunidad actual (A06) y si tiene compras o suministros. */
    public record ProductorRegistrado(String codigo, String comunidadActual, LocalDate fechaAfiliacion,
                                      String estadoSolicitud, boolean activo, boolean tieneCompras,
                                      boolean tieneSuministros) {
    }

    /** Importe acordado (COMPRA_FEDER.total) e importe pagado al productor (COMPRA_FEDER «Pago al productor»). */
    public record ImportesCompra(BigDecimal acordado, BigDecimal pagado) {
    }

    private static final String SQL_LINEAS = "WITH " + FragmentosSql.PERIODO + ", " + FragmentosSql.FILTRO_COMPRAS + " "
            + "SELECT cf.codigo, cf.fecha_compra, " + FragmentosSql.ETIQUETA_PRODUCTOR + " AS productor, cf.estado, "
            + "       pr.codigo || ' ' || pr.nombre AS producto, pr.unidad_medida, lp.codigo_lote, dc.cantidad_comprada, "
            + "       dc.cantidad_recibida, dc.cantidad_rechazada, dc.cantidad_cancelada, dc.precio_unitario "
            + "  FROM compra_feder cf JOIN productor po ON po.id = cf.productor_id "
            + "  JOIN detalle_compra dc ON dc.compra_id = cf.id "
            + "  JOIN lote_produccion lp ON lp.id = dc.lote_origen_id JOIN produccion pd ON pd.id = lp.produccion_id "
            + "  JOIN producto pr ON pr.id = pd.producto_id, periodo p "
            + " WHERE cf.fecha_compra >= p.ini AND cf.fecha_compra < p.fin AND dc.id IN (SELECT id FROM linea_filtrada) "
            + " ORDER BY cf.fecha_compra, cf.codigo, dc.id";

    private static final String SQL_PRODUCTORES = "WITH filtro AS (SELECT CAST(? AS text) AS productor) "
            + "SELECT COALESCE(po.codigo_productor, 'Solicitud ' || po.id) AS codigo, "
            + "       ca.nombre || ' (' || ca.municipio || ')' AS comunidad, po.fecha_afiliacion, po.estado_solicitud, po.activo, "
            + "       EXISTS (SELECT 1 FROM compra_feder cf WHERE cf.productor_id = po.id) AS tiene_compras, "
            + "       EXISTS (SELECT 1 FROM compra_feder cf JOIN detalle_compra dc ON dc.compra_id = cf.id "
            + "                 JOIN lote_feder lf ON lf.detalle_compra_id = dc.id "
            + "                 JOIN movimiento_inventario m ON m.lote_feder_id = lf.id AND m.tipo = 'ENTRADA_COMPRA' "
            + "                WHERE cf.productor_id = po.id) AS tiene_suministros "
            + "  FROM productor po JOIN comunidad_agricola ca ON ca.id = po.comunidad_agricola_id, filtro f "
            + " WHERE f.productor IS NULL OR po.codigo_productor = f.productor "
            + " ORDER BY po.codigo_productor NULLS LAST, po.id";

    // El importe acordado es el total de cada compra del período con alguna línea que cumple el filtro (el total
    // no se reparte entre líneas). El importe pagado redondea por línea (suma de round(cantidad_recibida x
    // precio_unitario, 2)), por eso puede diferir en centavos del costo de lo aceptado.
    private static final String SQL_IMPORTES = "WITH " + FragmentosSql.PERIODO + ", " + FragmentosSql.FILTRO_COMPRAS + " "
            + "SELECT (SELECT COALESCE(SUM(cf.total), 0) FROM compra_feder cf, periodo p "
            + "         WHERE cf.fecha_compra >= p.ini AND cf.fecha_compra < p.fin "
            + "           AND cf.id IN (SELECT compra_id FROM linea_filtrada)) AS acordado, "
            + "       (SELECT COALESCE(SUM(round(dc.cantidad_recibida * dc.precio_unitario, 2)), 0) "
            + "          FROM compra_feder cf JOIN detalle_compra dc ON dc.compra_id = cf.id, periodo p "
            + "         WHERE cf.fecha_pago_productor >= p.ini AND cf.fecha_pago_productor < p.fin "
            + "           AND dc.id IN (SELECT id FROM linea_filtrada)) AS pagado";

    public List<LineaCompra> lineasDeCompra(LocalDate desde, LocalDate hasta, FiltrosConsulta.Compras filtro) {
        return consultar(SQL_LINEAS, fila -> new LineaCompra(fila.getString("codigo"), fechaHora(fila, "fecha_compra"),
                fila.getString("productor"), fila.getString("estado"), fila.getString("producto"),
                fila.getString("unidad_medida"), fila.getString("codigo_lote"), fila.getBigDecimal("cantidad_comprada"),
                fila.getBigDecimal("cantidad_recibida"), fila.getBigDecimal("cantidad_rechazada"),
                fila.getBigDecimal("cantidad_cancelada"), fila.getBigDecimal("precio_unitario")),
                FiltrosConsulta.parametros(filtro.valores(), desde, hasta));
    }

    /** Productores registrados; con filtro de productor, solo ese productor. */
    public List<ProductorRegistrado> productores(FiltrosConsulta.Compras filtro) {
        return consultar(SQL_PRODUCTORES, fila -> new ProductorRegistrado(fila.getString("codigo"),
                fila.getString("comunidad"), fecha(fila, "fecha_afiliacion"), fila.getString("estado_solicitud"),
                fila.getBoolean("activo"), fila.getBoolean("tiene_compras"), fila.getBoolean("tiene_suministros")),
                filtro.productor());
    }

    public ImportesCompra importes(LocalDate desde, LocalDate hasta, FiltrosConsulta.Compras filtro) {
        return consultarUno(SQL_IMPORTES, fila -> new ImportesCompra(fila.getBigDecimal("acordado"),
                fila.getBigDecimal("pagado")), FiltrosConsulta.parametros(filtro.valores(), desde, hasta)).orElseThrow();
    }
}
