package com.feder.datos.inventario;

/**
 * Fragmentos SQL compartidos por los repositorios de inventario, para que la definición de
 * «stock vendible» (R070) exista en un solo lugar.
 */
final class ConsultasLote {

    /** Recorrido LOTE_FEDER -> DETALLE_COMPRA -> LOTE_PRODUCCION -> PRODUCCION -> PRODUCTO (A14, A13, A09, A08). */
    static final String LOTES_CON_PRODUCTO =
            " FROM lote_feder lf"
                    + " JOIN detalle_compra dc ON dc.id = lf.detalle_compra_id"
                    + " JOIN lote_produccion lp ON lp.id = dc.lote_origen_id"
                    + " JOIN produccion pr ON pr.id = lp.produccion_id"
                    + " JOIN producto p ON p.id = pr.producto_id ";

    /**
     * Lote vendible (R035, R070): DISPONIBLE y con vencimiento de origen nulo o no anterior a hoy.
     * Recibe un parámetro: la fecha de hoy en hora de Bolivia.
     */
    static final String CONDICION_VENDIBLE =
            " lf.estado = 'DISPONIBLE' AND (lp.fecha_vencimiento IS NULL OR lp.fecha_vencimiento >= ?) ";

    private ConsultasLote() {
    }
}
