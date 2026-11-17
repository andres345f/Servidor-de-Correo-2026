package com.feder.datos.pagos;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoReembolso;
import com.feder.modelo.Reembolso;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a REEMBOLSO. Pago, devolución, monto y clave no cambian tras crearse (rg_reembolso_datos_fijos);
 * solo cambian estado, fecha_ejecucion y referencia_externa, siempre desde PENDIENTE.
 */
public class ReembolsoRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT r.id, r.pago_id, r.devolucion_id, r.monto, r.estado, r.motivo, "
            + "r.fecha_solicitud, r.fecha_ejecucion, r.referencia_externa, r.clave_idempotencia FROM reembolso r ";

    /** Reembolso con los datos que identifican su origen ante el usuario. */
    public record ReembolsoVista(Reembolso reembolso, String codigoPedido, long pedidoId, long clienteId,
                                 String clavePago, String codigoDevolucion) {
    }

    public long insertar(Reembolso r) {
        return insertar("INSERT INTO reembolso (pago_id, devolucion_id, monto, estado, motivo, fecha_solicitud, clave_idempotencia) "
                        + "VALUES (?, ?, ?, 'PENDIENTE', ?, ?, ?)",
                r.getPagoId(), r.getDevolucionId(), r.getMonto(), r.getMotivo(), r.getFechaSolicitud(), r.getClaveIdempotencia());
    }

    public Optional<ReembolsoVista> buscarPorClave(String clave) {
        return consultarUno(VISTA + "WHERE r.clave_idempotencia = ?", ReembolsoRepositorio::mapearVista, clave);
    }

    public Optional<Reembolso> bloquear(long id) {
        return consultarUno(COLUMNAS + "WHERE r.id = ? FOR UPDATE", ReembolsoRepositorio::mapear, id);
    }

    public boolean existeClave(String clave) {
        return consultarUno("SELECT 1 AS x FROM reembolso WHERE clave_idempotencia = ?", fila -> 1, clave).isPresent();
    }

    public boolean existeReferencia(String referenciaExterna) {
        return consultarUno("SELECT 1 AS x FROM reembolso WHERE referencia_externa = ?", fila -> 1, referenciaExterna).isPresent();
    }

    /** R078: lo comprometido de un pago (reembolsos PENDIENTE o EJECUTADO); los FALLIDO no cuentan. */
    public BigDecimal comprometidoDePago(long pagoId) {
        return suma("SELECT COALESCE(sum(monto), 0) AS total FROM reembolso WHERE pago_id = ? "
                + "AND estado IN ('PENDIENTE', 'EJECUTADO')", pagoId);
    }

    /** R077 y R026: lo comprometido de una devolución (PENDIENTE o EJECUTADO). */
    public BigDecimal comprometidoDeDevolucion(long devolucionId) {
        return suma("SELECT COALESCE(sum(monto), 0) AS total FROM reembolso WHERE devolucion_id = ? "
                + "AND estado IN ('PENDIENTE', 'EJECUTADO')", devolucionId);
    }

    /** R026: para completar una devolución con REEMBOLSO, lo EJECUTADO iguala su importe reembolsable. */
    public BigDecimal ejecutadoDeDevolucion(long devolucionId) {
        return suma("SELECT COALESCE(sum(monto), 0) AS total FROM reembolso WHERE devolucion_id = ? AND estado = 'EJECUTADO'",
                devolucionId);
    }

    /** Reembolsos de una pareja (pago, devolución): sirve para numerar la clave del sustituto de uno FALLIDO. */
    public int contarDePareja(long pagoId, Long devolucionId) {
        return consultarUno("SELECT count(*) AS n FROM reembolso WHERE pago_id = ? AND devolucion_id IS NOT DISTINCT FROM ?",
                fila -> fila.getInt("n"), pagoId, devolucionId).orElse(0);
    }

    /** R082: clave del reembolso PENDIENTE de una pareja (pago, devolución), si lo hay (como máximo uno). */
    public Optional<String> pendienteDePareja(long pagoId, Long devolucionId) {
        return consultarUno("SELECT clave_idempotencia FROM reembolso WHERE pago_id = ? AND devolucion_id IS NOT DISTINCT FROM ? "
                + "AND estado = 'PENDIENTE'", fila -> fila.getString("clave_idempotencia"), pagoId, devolucionId);
    }

    /** R076: PENDIENTE -> EJECUTADO con la fecha y el comprobante del reintegro. */
    public boolean ejecutar(long id, OffsetDateTime fecha, String referenciaExterna) {
        return actualizar("UPDATE reembolso SET estado = 'EJECUTADO', fecha_ejecucion = ?, referencia_externa = ? "
                + "WHERE id = ? AND estado = 'PENDIENTE'", fecha, referenciaExterna, id) == 1;
    }

    public boolean marcarFallido(long id) {
        return actualizar("UPDATE reembolso SET estado = 'FALLIDO' WHERE id = ? AND estado = 'PENDIENTE'", id) == 1;
    }

    /** @param clienteId nulo para todos; @param estado nulo para todos los estados. */
    public List<ReembolsoVista> listar(Long clienteId, EstadoReembolso estado, int limite) {
        return consultar(VISTA + "WHERE (?::bigint IS NULL OR p.usuario_id = ?) AND (?::text IS NULL OR r.estado = ?) "
                        + "ORDER BY r.fecha_solicitud DESC, r.id DESC LIMIT ?",
                ReembolsoRepositorio::mapearVista, clienteId, clienteId, estado, estado, limite);
    }

    private static final String VISTA = "SELECT r.id, r.pago_id, r.devolucion_id, r.monto, r.estado, r.motivo, r.fecha_solicitud, "
            + "r.fecha_ejecucion, r.referencia_externa, r.clave_idempotencia, p.codigo, p.id AS pedido_id, p.usuario_id, "
            + "pg.clave_idempotencia AS clave_pago, d.codigo AS codigo_devolucion FROM reembolso r "
            + "JOIN pago pg ON pg.id = r.pago_id JOIN pedido p ON p.id = pg.pedido_id "
            + "LEFT JOIN devolucion d ON d.id = r.devolucion_id ";

    private BigDecimal suma(String sql, long id) {
        return consultarUno(sql, fila -> fila.getBigDecimal("total"), id).orElse(BigDecimal.ZERO);
    }

    private static ReembolsoVista mapearVista(ResultSet fila) throws SQLException {
        return new ReembolsoVista(mapear(fila), fila.getString("codigo"), fila.getLong("pedido_id"), fila.getLong("usuario_id"),
                fila.getString("clave_pago"), fila.getString("codigo_devolucion"));
    }

    private static Reembolso mapear(ResultSet fila) throws SQLException {
        Reembolso r = new Reembolso();
        r.setId(fila.getLong("id"));
        r.setPagoId(fila.getLong("pago_id"));
        r.setDevolucionId(largoONulo(fila, "devolucion_id"));
        r.setMonto(fila.getBigDecimal("monto"));
        r.setEstado(enumeracion(fila, "estado", EstadoReembolso.class));
        r.setMotivo(fila.getString("motivo"));
        r.setFechaSolicitud(fechaHora(fila, "fecha_solicitud"));
        r.setFechaEjecucion(fechaHora(fila, "fecha_ejecucion"));
        r.setReferenciaExterna(fila.getString("referencia_externa"));
        r.setClaveIdempotencia(fila.getString("clave_idempotencia"));
        return r;
    }
}
