package com.feder.datos.inventario;

import com.feder.modelo.EstadoLoteFeder;
import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Lote de FEDER con su producto y su lote de origen (de donde sale la fecha de vencimiento, A14 y A13). */
public record LoteFederVista(long id, String codigo, long detalleCompraId, long loteOrigenId, String codigoLoteOrigen,
                             long productoId, String codigoProducto, String nombreProducto, UnidadMedida unidad,
                             OffsetDateTime fechaIngreso, BigDecimal disponible, BigDecimal reservada,
                             String ubicacion, EstadoLoteFeder estado, LocalDate fechaVencimiento) {

    /** Stock libre del lote: cantidad_disponible - cantidad_reservada. */
    public BigDecimal libre() {
        return disponible.subtract(reservada);
    }

    /** R035: vencido si la fecha de su lote de origen es anterior a hoy; sin fecha, nunca se considera vencido. */
    public boolean vencido(LocalDate hoy) {
        return fechaVencimiento != null && fechaVencimiento.isBefore(hoy);
    }

    /** Se puede reservar o despachar desde él: DISPONIBLE y no vencido. */
    public boolean vendible(LocalDate hoy) {
        return estado == EstadoLoteFeder.DISPONIBLE && !vencido(hoy);
    }
}
