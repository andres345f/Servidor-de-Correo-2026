package com.feder.datos.inventario;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoLoteFeder;
import com.feder.modelo.LoteFeder;
import com.feder.modelo.UnidadMedida;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a LOTE_FEDER. Cada cambio de saldo es una sola sentencia condicional que ajusta también
 * el estado AGOTADO/DISPONIBLE, como exige ck_lote_feder_agotado_sin_saldo (condición 1 del informe).
 */
public class LoteFederRepositorio extends RepositorioJdbc {

    private static final String SELECT_VISTA =
            "SELECT lf.id, lf.codigo, lf.detalle_compra_id, lp.id AS lote_origen_id, lp.codigo_lote, p.id AS producto_id, "
                    + "p.codigo AS codigo_producto, p.nombre AS nombre_producto, p.unidad_medida, lf.fecha_ingreso, "
                    + "lf.cantidad_disponible, lf.cantidad_reservada, lf.ubicacion_almacen, lf.estado, lp.fecha_vencimiento"
                    + ConsultasLote.LOTES_CON_PRODUCTO;

    // Al llegar a saldo 0 un lote DISPONIBLE pasa a AGOTADO; una entrada devuelve a DISPONIBLE un lote AGOTADO.
    // BLOQUEADO y VENCIDO no cambian por movimientos (R038).
    private static final String ESTADO_TRAS_SALIDA =
            "estado = CASE WHEN estado = 'DISPONIBLE' AND cantidad_disponible - ? = 0 THEN 'AGOTADO' ELSE estado END";

    /** Reserva el identificador antes de insertar, para formar el código visible del lote con él. */
    public long siguienteId() {
        return consultarUno("SELECT nextval(pg_get_serial_sequence('lote_feder', 'id')) AS id", fila -> fila.getLong("id"))
                .orElseThrow(() -> new AccesoDatosException("No se obtuvo el identificador del lote"));
    }

    public void insertar(LoteFeder lote) {
        actualizar("INSERT INTO lote_feder (id, detalle_compra_id, codigo, fecha_ingreso, cantidad_disponible, "
                        + "cantidad_reservada, ubicacion_almacen, estado) VALUES (?, ?, ?, ?, ?, 0, ?, ?)",
                lote.getId(), lote.getDetalleCompraId(), lote.getCodigo(), lote.getFechaIngreso(),
                lote.getCantidadDisponible(), lote.getUbicacionAlmacen(), lote.getEstado());
    }

    public Optional<LoteFederVista> buscarPorCodigo(String codigo) {
        return consultarUno(SELECT_VISTA + "WHERE lf.codigo = ?", LoteFederRepositorio::mapear, codigo);
    }

    public Optional<LoteFederVista> buscarPorId(long id) {
        return consultarUno(SELECT_VISTA + "WHERE lf.id = ?", LoteFederRepositorio::mapear, id);
    }

    public Optional<LoteFederVista> buscarPorDetalle(long detalleCompraId) {
        return consultarUno(SELECT_VISTA + "WHERE lf.detalle_compra_id = ?", LoteFederRepositorio::mapear, detalleCompraId);
    }

    /** Bloquea la fila del lote (FOR UPDATE) y devuelve sus valores ya bloqueados. */
    public Optional<LoteFederVista> bloquear(long id) {
        return consultarUno(SELECT_VISTA + "WHERE lf.id = ? FOR UPDATE OF lf", LoteFederRepositorio::mapear, id);
    }

    /** Bloqueo compartido del lote de origen: su fecha de vencimiento no cambia mientras se reserva o despacha (R035). */
    public void bloquearOrigenCompartido(long loteOrigenId) {
        consultar("SELECT id FROM lote_produccion WHERE id = ? FOR SHARE", fila -> fila.getLong("id"), loteOrigenId);
    }

    /** @param filtro texto del código del lote o del producto; nulo para todos. @param estado nulo para todos. */
    public List<LoteFederVista> listar(String filtro, EstadoLoteFeder estado) {
        return consultar(SELECT_VISTA
                        + "WHERE (?::text IS NULL OR lf.codigo ILIKE '%' || ? || '%' OR p.codigo ILIKE '%' || ? || '%' "
                        + "       OR p.nombre ILIKE '%' || ? || '%') AND (?::text IS NULL OR lf.estado = ?) "
                        + "ORDER BY p.nombre, lf.fecha_ingreso, lf.id",
                LoteFederRepositorio::mapear, filtro, filtro, filtro, filtro, estado, estado);
    }

    /** Lotes vendibles con stock libre de un producto, sin bloquear (primer paso de la reserva FEFO, R037). */
    public List<LoteFederVista> candidatosVendibles(long productoId, LocalDate hoy) {
        return consultar(SELECT_VISTA + "WHERE p.id = ? AND" + ConsultasLote.CONDICION_VENDIBLE
                        + "AND lf.cantidad_disponible - lf.cantidad_reservada > 0 ORDER BY lf.id",
                LoteFederRepositorio::mapear, productoId, hoy);
    }

    /** R037: UPDATE condicional; false si el lote ya no tiene stock libre suficiente o dejó de estar DISPONIBLE. */
    public boolean reservar(long id, BigDecimal cantidad) {
        return actualizar("UPDATE lote_feder SET cantidad_reservada = cantidad_reservada + ? "
                + "WHERE id = ? AND estado = 'DISPONIBLE' AND cantidad_disponible - cantidad_reservada >= ?",
                cantidad, id, cantidad) == 1;
    }

    /** R084: descuenta lo reservado de una reserva que acaba de salir de ACTIVA. */
    public void liberarReservado(long id, BigDecimal cantidad) {
        exigirUnaFila(actualizar("UPDATE lote_feder SET cantidad_reservada = cantidad_reservada - ? "
                + "WHERE id = ? AND cantidad_reservada >= ?", cantidad, id, cantidad), id);
    }

    /** R083: el despacho descuenta a la vez el saldo físico y lo reservado. */
    public void consumirReservado(long id, BigDecimal cantidad) {
        exigirUnaFila(actualizar("UPDATE lote_feder SET cantidad_disponible = cantidad_disponible - ?, "
                + "cantidad_reservada = cantidad_reservada - ?, " + ESTADO_TRAS_SALIDA
                + " WHERE id = ? AND cantidad_reservada >= ?", cantidad, cantidad, cantidad, id, cantidad), id);
    }

    /** Salida de stock libre (ajuste negativo, baja o reposición); false si no alcanza lo libre. */
    public boolean restarLibre(long id, BigDecimal cantidad) {
        return actualizar("UPDATE lote_feder SET cantidad_disponible = cantidad_disponible - ?, " + ESTADO_TRAS_SALIDA
                + " WHERE id = ? AND cantidad_disponible - cantidad_reservada >= ?", cantidad, cantidad, id, cantidad) == 1;
    }

    /** Entrada física (recepción, retorno, reingreso o ajuste positivo). */
    public void sumarEntrada(long id, BigDecimal cantidad) {
        exigirUnaFila(actualizar("UPDATE lote_feder SET cantidad_disponible = cantidad_disponible + ?, "
                + "estado = CASE WHEN estado = 'AGOTADO' THEN 'DISPONIBLE' ELSE estado END WHERE id = ?", cantidad, id), id);
    }

    /**
     * R035: pasa el lote a BLOQUEADO o VENCIDO. Solo desde DISPONIBLE o AGOTADO; devuelve false si ya
     * estaba inhabilitado. Con esos estados el saldo ya no exige AGOTADO (ck_lote_feder_agotado_sin_saldo).
     */
    public boolean inhabilitar(long id, EstadoLoteFeder destino) {
        return actualizar("UPDATE lote_feder SET estado = ? WHERE id = ? AND estado IN ('DISPONIBLE', 'AGOTADO')",
                destino, id) == 1;
    }

    /** Lotes DISPONIBLE o AGOTADO cuyo lote de origen ya venció: los que el proceso diario pasa a VENCIDO. */
    public List<LoteFederVista> porMarcarVencidos(LocalDate hoy) {
        return consultar(SELECT_VISTA + "WHERE lf.estado IN ('DISPONIBLE', 'AGOTADO') AND lp.fecha_vencimiento < ? ORDER BY lf.id",
                LoteFederRepositorio::mapear, hoy);
    }

    /**
     * Consulta del panel de R070: lotes con saldo cuyo origen vence hasta {@code limite}, en cualquier
     * estado. Incluye los ya vencidos (pendientes de baja) y los que vencen entre hoy y el límite.
     */
    public List<LoteFederVista> conSaldoQueVencenHasta(LocalDate limite) {
        return consultar(SELECT_VISTA + "WHERE lf.cantidad_disponible > 0 AND lp.fecha_vencimiento <= ? "
                + "ORDER BY lp.fecha_vencimiento, lf.id", LoteFederRepositorio::mapear, limite);
    }

    /**
     * Todos los lotes con saldo, del vencimiento más cercano al más lejano y al final los que no vencen
     * (como máximo {@code maximo} filas).
     */
    public List<LoteFederVista> conSaldoPorVencimiento(int maximo) {
        return consultar(SELECT_VISTA + "WHERE lf.cantidad_disponible > 0 ORDER BY lp.fecha_vencimiento NULLS LAST, lf.id LIMIT ?",
                LoteFederRepositorio::mapear, maximo);
    }

    public void actualizarUbicacion(long id, String ubicacion) {
        actualizar("UPDATE lote_feder SET ubicacion_almacen = ? WHERE id = ?", ubicacion, id);
    }

    private static void exigirUnaFila(int filas, long id) {
        if (filas != 1) {
            throw new AccesoDatosException("El saldo del lote " + id + " no admite la operación solicitada");
        }
    }

    private static LoteFederVista mapear(ResultSet fila) throws SQLException {
        return new LoteFederVista(fila.getLong("id"), fila.getString("codigo"), fila.getLong("detalle_compra_id"),
                fila.getLong("lote_origen_id"), fila.getString("codigo_lote"), fila.getLong("producto_id"),
                fila.getString("codigo_producto"), fila.getString("nombre_producto"),
                enumeracion(fila, "unidad_medida", UnidadMedida.class), fechaHora(fila, "fecha_ingreso"),
                fila.getBigDecimal("cantidad_disponible"), fila.getBigDecimal("cantidad_reservada"),
                fila.getString("ubicacion_almacen"), enumeracion(fila, "estado", EstadoLoteFeder.class),
                fecha(fila, "fecha_vencimiento"));
    }
}
