package com.feder.datos.pagos;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.EstadoPago;
import com.feder.modelo.MetodoPago;
import com.feder.modelo.Pago;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a PAGO. Un pago sale de PENDIENTE una sola vez (UPDATE condicional, R048) y su pedido no
 * cambia (rg_pago_pedido_fijo). La base impide un segundo PENDIENTE por pedido y reutilizar claves
 * o referencias externas (R051).
 */
public class PagoRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT pg.id, pg.pedido_id, pg.metodo, pg.monto, pg.estado, pg.fecha_registro, "
            + "pg.fecha_pago, pg.referencia_externa, pg.clave_idempotencia FROM pago pg ";

    /** Pago con el código de su pedido, para los listados. */
    public record PagoVista(Pago pago, String codigoPedido) {
    }

    public long insertar(Pago p) {
        return insertar("INSERT INTO pago (pedido_id, metodo, monto, estado, fecha_registro, clave_idempotencia) "
                        + "VALUES (?, ?, ?, 'PENDIENTE', ?, ?)",
                p.getPedidoId(), p.getMetodo(), p.getMonto(), p.getFechaRegistro(), p.getClaveIdempotencia());
    }

    public Optional<Pago> buscarPorClave(String clave) {
        return consultarUno(COLUMNAS + "WHERE pg.clave_idempotencia = ?", PagoRepositorio::mapear, clave);
    }

    public Optional<Pago> buscarPorReferencia(String referenciaExterna) {
        return consultarUno(COLUMNAS + "WHERE pg.referencia_externa = ?", PagoRepositorio::mapear, referenciaExterna);
    }

    /** Bloquea el pago (después del pedido, según el orden de bloqueo de CU6). */
    public Optional<Pago> bloquear(long id) {
        return consultarUno(COLUMNAS + "WHERE pg.id = ? FOR UPDATE", PagoRepositorio::mapear, id);
    }

    public List<Pago> dePedido(long pedidoId) {
        return consultar(COLUMNAS + "WHERE pg.pedido_id = ? ORDER BY pg.id", PagoRepositorio::mapear, pedidoId);
    }

    /** Pagos PAGADO del pedido bloqueados por identificador, para repartir un reintegro (R078, R080). */
    public List<Pago> bloquearPagadosDePedido(long pedidoId) {
        return consultar(COLUMNAS + "WHERE pg.pedido_id = ? AND pg.estado = 'PAGADO' ORDER BY pg.id FOR UPDATE",
                PagoRepositorio::mapear, pedidoId);
    }

    public Optional<Pago> pendienteDePedido(long pedidoId) {
        return consultarUno(COLUMNAS + "WHERE pg.pedido_id = ? AND pg.estado = 'PENDIENTE'", PagoRepositorio::mapear, pedidoId);
    }

    /** Suma de pagos PAGADO del pedido (R047, R051, R057). */
    public BigDecimal sumaPagada(long pedidoId) {
        return consultarUno("SELECT COALESCE(sum(monto), 0) AS total FROM pago WHERE pedido_id = ? AND estado = 'PAGADO'",
                fila -> fila.getBigDecimal("total"), pedidoId).orElse(BigDecimal.ZERO);
    }

    public int contarDePedido(long pedidoId) {
        return consultarUno("SELECT count(*) AS n FROM pago WHERE pedido_id = ?", fila -> fila.getInt("n"), pedidoId).orElse(0);
    }

    /** R048: PENDIENTE -> PAGADO con su fecha y, si la hay, la referencia de la transacción externa. */
    public boolean confirmar(long id, OffsetDateTime fechaPago, String referenciaExterna) {
        return actualizar("UPDATE pago SET estado = 'PAGADO', fecha_pago = ?, "
                        + "referencia_externa = COALESCE(?, referencia_externa) WHERE id = ? AND estado = 'PENDIENTE'",
                fechaPago, referenciaExterna, id) == 1;
    }

    /** R048 y R049: PENDIENTE -> RECHAZADO (intento terminado sin cobro). */
    public boolean rechazar(long id) {
        return actualizar("UPDATE pago SET estado = 'RECHAZADO' WHERE id = ? AND estado = 'PENDIENTE'", id) == 1;
    }

    /**
     * @param clienteId nulo para todos los clientes; @param pedidoId nulo para todos los pedidos.
     */
    public List<PagoVista> listar(Long clienteId, Long pedidoId, int limite) {
        return consultar("SELECT pg.id, pg.pedido_id, pg.metodo, pg.monto, pg.estado, pg.fecha_registro, pg.fecha_pago, "
                        + "pg.referencia_externa, pg.clave_idempotencia, p.codigo FROM pago pg JOIN pedido p ON p.id = pg.pedido_id "
                        + "WHERE (?::bigint IS NULL OR p.usuario_id = ?) AND (?::bigint IS NULL OR pg.pedido_id = ?) "
                        + "ORDER BY pg.fecha_registro DESC, pg.id DESC LIMIT ?",
                fila -> new PagoVista(mapear(fila), fila.getString("codigo")),
                clienteId, clienteId, pedidoId, pedidoId, limite);
    }

    static Pago mapear(ResultSet fila) throws SQLException {
        Pago p = new Pago();
        p.setId(fila.getLong("id"));
        p.setPedidoId(fila.getLong("pedido_id"));
        p.setMetodo(enumeracion(fila, "metodo", MetodoPago.class));
        p.setMonto(fila.getBigDecimal("monto"));
        p.setEstado(enumeracion(fila, "estado", EstadoPago.class));
        p.setFechaRegistro(fechaHora(fila, "fecha_registro"));
        p.setFechaPago(fechaHora(fila, "fecha_pago"));
        p.setReferenciaExterna(fila.getString("referencia_externa"));
        p.setClaveIdempotencia(fila.getString("clave_idempotencia"));
        return p;
    }
}
