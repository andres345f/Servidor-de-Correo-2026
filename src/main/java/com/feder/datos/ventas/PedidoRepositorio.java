package com.feder.datos.ventas;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.Pedido;
import com.feder.modelo.TipoPedido;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Acceso a PEDIDO y a sus líneas DETALLE_PEDIDO. Las líneas no se modifican después de crearse
 * (rg_detalle_pedido_datos_fijos); solo puede vincularse la producción que respalda un encargo.
 * Los cambios de estado son condicionales: solo se aplican si el pedido sigue en el estado esperado.
 */
public class PedidoRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT p.id, p.usuario_id, p.codigo, p.tipo, p.estado, p.fecha_creacion, p.subtotal, "
            + "p.costo_envio, p.total, p.fecha_estimada_disponibilidad, p.clave_idempotencia, p.observacion FROM pedido p ";

    private static final String COLUMNAS_LINEA = "SELECT id, pedido_id, producto_id, produccion_id, cantidad, precio_unitario, "
            + "subtotal, descripcion_venta FROM detalle_pedido ";

    private final EntregaRepositorio entregas = new EntregaRepositorio();

    /** Pedido con el nombre de su cliente, para los listados del personal. */
    public record PedidoResumen(Pedido pedido, String cliente) {
    }

    public long siguienteId() {
        return consultarUno("SELECT nextval(pg_get_serial_sequence('pedido', 'id')) AS id", fila -> fila.getLong("id"))
                .orElseThrow(() -> new AccesoDatosException("No se obtuvo el identificador del pedido"));
    }

    public void insertar(Pedido p) {
        actualizar("INSERT INTO pedido (id, usuario_id, codigo, tipo, estado, fecha_creacion, subtotal, costo_envio, total, "
                        + "fecha_estimada_disponibilidad, clave_idempotencia, observacion) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                p.getId(), p.getUsuarioId(), p.getCodigo(), p.getTipo(), p.getEstado(), p.getFechaCreacion(), p.getSubtotal(),
                p.getCostoEnvio(), p.getTotal(), p.getFechaEstimadaDisponibilidad(), p.getClaveIdempotencia(), p.getObservacion());
    }

    public long insertarLinea(DetallePedido d) {
        return insertar("INSERT INTO detalle_pedido (pedido_id, producto_id, produccion_id, cantidad, precio_unitario, subtotal, "
                        + "descripcion_venta) VALUES (?, ?, ?, ?, ?, ?, ?)",
                d.getPedidoId(), d.getProductoId(), d.getProduccionId(), d.getCantidad(), d.getPrecioUnitario(), d.getSubtotal(),
                d.getDescripcionVenta());
    }

    public Optional<Pedido> buscarPorCodigo(String codigo) {
        return consultarUno(COLUMNAS + "WHERE p.codigo = ?", PedidoRepositorio::mapear, codigo);
    }

    /**
     * Pedidos del cliente de ese tipo creados desde {@code desde}, del más reciente al más antiguo: los candidatos
     * de un reenvío (R054). La ventana de reenvío es corta, así que la lista también lo es.
     */
    public List<Pedido> recientesDelCliente(long usuarioId, TipoPedido tipo, OffsetDateTime desde) {
        return consultar(COLUMNAS + "WHERE p.usuario_id = ? AND p.tipo = ? AND p.fecha_creacion >= ? "
                + "ORDER BY p.fecha_creacion DESC, p.id DESC", PedidoRepositorio::mapear, usuarioId, tipo, desde);
    }

    /** Códigos (en mayúsculas) de los productos de las líneas del pedido. */
    public Set<String> codigosDeProductos(long pedidoId) {
        return new HashSet<>(consultar("SELECT upper(pr.codigo) AS codigo FROM detalle_pedido dp "
                + "JOIN producto pr ON pr.id = dp.producto_id WHERE dp.pedido_id = ?", fila -> fila.getString("codigo"), pedidoId));
    }

    /**
     * R053 (3): pedidos INMEDIATO PENDIENTE con alguna reserva ACTIVA cuyo plazo ya venció, sin bloquear.
     * El proceso de expiración bloquea después cada pedido y relee su estado.
     */
    public List<Long> inmediatosConReservaVencida(OffsetDateTime ahora) {
        return consultar("SELECT DISTINCT p.id FROM pedido p JOIN detalle_pedido dp ON dp.pedido_id = p.id "
                        + "JOIN reserva_stock rs ON rs.detalle_pedido_id = dp.id WHERE p.tipo = 'INMEDIATO' AND p.estado = 'PENDIENTE' "
                        + "AND rs.estado = 'ACTIVA' AND rs.fecha_expiracion < ? ORDER BY p.id",
                fila -> fila.getLong(1), ahora);
    }

    /**
     * True si alguna reserva ACTIVA del pedido venció y ninguna perdió su plazo: una reserva ACTIVA sin
     * fecha_expiracion indica que el pedido ya tiene confirmación comercial (R053).
     */
    public boolean reservasActivasVencidas(long pedidoId, OffsetDateTime ahora) {
        return consultarUno("SELECT bool_or(rs.fecha_expiracion < ?) AND bool_and(rs.fecha_expiracion IS NOT NULL) AS vencidas "
                        + "FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                        + "WHERE dp.pedido_id = ? AND rs.estado = 'ACTIVA'",
                fila -> fila.getBoolean("vencidas"), ahora, pedidoId).orElse(false);
    }

    /** True si el pedido tiene un PAGO en curso (PENDIENTE) o ya cobrado (PAGADO). */
    public boolean tienePagoPendienteOCobrado(long pedidoId) {
        // R053 (3): el efectivo contra entrega PENDIENTE no es un cobro en curso que deba conciliarse con una pasarela.
        return consultarUno("SELECT count(*) AS n FROM pago WHERE pedido_id = ? AND (estado = 'PAGADO' "
                        + "OR (estado = 'PENDIENTE' AND metodo <> 'EFECTIVO'))",
                fila -> fila.getLong("n"), pedidoId).orElse(0L) > 0;
    }

    /**
     * R053 (3): claves de los intentos QR o TARJETA del pedido que siguen PENDIENTE, sea cual sea su antigüedad.
     * Ninguno vence por el paso del tiempo (R048): se concilian con CONPAG o se rechazan con RECPAG.
     */
    public List<String> intentosPasarelaPendientes(long pedidoId) {
        return consultar("SELECT clave_idempotencia FROM pago WHERE pedido_id = ? AND metodo <> 'EFECTIVO' "
                + "AND estado = 'PENDIENTE' ORDER BY id", fila -> fila.getString("clave_idempotencia"), pedidoId);
    }

    /** Bloqueo del pedido (FOR UPDATE): es la primera fila del orden global de bloqueo (R037, R053). */
    public Optional<Pedido> bloquear(long id) {
        return consultarUno(COLUMNAS + "WHERE p.id = ? FOR UPDATE", PedidoRepositorio::mapear, id);
    }

    public Optional<Pedido> bloquearPorCodigo(String codigo) {
        return consultarUno(COLUMNAS + "WHERE p.codigo = ? FOR UPDATE", PedidoRepositorio::mapear, codigo);
    }

    /** Pedido con sus líneas y su entrega, sin bloquear. */
    public Optional<PedidoCompleto> buscarConLineas(String codigo) {
        return buscarPorCodigo(codigo).map(this::completar);
    }

    /** Pedido bloqueado con sus líneas y su entrega. */
    public Optional<PedidoCompleto> bloquearConLineas(String codigo) {
        return bloquearPorCodigo(codigo).map(this::completar);
    }

    public List<DetallePedido> lineas(long pedidoId) {
        return consultar(COLUMNAS_LINEA + "WHERE pedido_id = ? ORDER BY id", PedidoRepositorio::mapearLinea, pedidoId);
    }

    /** @param usuarioId nulo para todos los clientes; @param estado nulo para todos los estados. */
    public List<PedidoResumen> listar(Long usuarioId, EstadoPedido estado, int limite) {
        return consultar("SELECT p.id, p.usuario_id, p.codigo, p.tipo, p.estado, p.fecha_creacion, p.subtotal, p.costo_envio, "
                        + "p.total, p.fecha_estimada_disponibilidad, p.clave_idempotencia, p.observacion, "
                        + "u.nombres || ' ' || u.apellidos AS cliente FROM pedido p JOIN usuario u ON u.id = p.usuario_id "
                        + "WHERE (?::bigint IS NULL OR p.usuario_id = ?) AND (?::text IS NULL OR p.estado = ?) "
                        + "ORDER BY p.fecha_creacion DESC, p.id DESC LIMIT ?",
                fila -> new PedidoResumen(mapear(fila), fila.getString("cliente")),
                usuarioId, usuarioId, estado, estado, limite);
    }

    /** Cambio de estado condicional: devuelve false si el pedido ya no estaba en {@code desde}. */
    public boolean cambiarEstado(long id, EstadoPedido desde, EstadoPedido hacia) {
        return actualizar("UPDATE pedido SET estado = ? WHERE id = ? AND estado = ?", hacia, id, desde) == 1;
    }

    public void actualizarFechaEstimada(long id, LocalDate fecha) {
        actualizar("UPDATE pedido SET fecha_estimada_disponibilidad = ? WHERE id = ?", fecha, id);
    }

    /** A25: vincula la producción que respalda la estimación de una línea de encargo. */
    public void vincularRespaldo(long detallePedidoId, long produccionId) {
        actualizar("UPDATE detalle_pedido SET produccion_id = ? WHERE id = ?", produccionId, detallePedidoId);
    }

    private PedidoCompleto completar(Pedido pedido) {
        return new PedidoCompleto(pedido, lineas(pedido.getId()), entregas.dePedido(pedido.getId()).orElse(null));
    }

    private static Pedido mapear(ResultSet fila) throws SQLException {
        Pedido p = new Pedido();
        p.setId(fila.getLong("id"));
        p.setUsuarioId(fila.getLong("usuario_id"));
        p.setCodigo(fila.getString("codigo"));
        p.setTipo(enumeracion(fila, "tipo", TipoPedido.class));
        p.setEstado(enumeracion(fila, "estado", EstadoPedido.class));
        p.setFechaCreacion(fechaHora(fila, "fecha_creacion"));
        p.setSubtotal(fila.getBigDecimal("subtotal"));
        p.setCostoEnvio(fila.getBigDecimal("costo_envio"));
        p.setTotal(fila.getBigDecimal("total"));
        p.setFechaEstimadaDisponibilidad(fecha(fila, "fecha_estimada_disponibilidad"));
        p.setClaveIdempotencia(fila.getString("clave_idempotencia"));
        p.setObservacion(fila.getString("observacion"));
        return p;
    }

    private static DetallePedido mapearLinea(ResultSet fila) throws SQLException {
        DetallePedido d = new DetallePedido();
        d.setId(fila.getLong("id"));
        d.setPedidoId(fila.getLong("pedido_id"));
        d.setProductoId(fila.getLong("producto_id"));
        d.setProduccionId(largoONulo(fila, "produccion_id"));
        d.setCantidad(fila.getBigDecimal("cantidad"));
        d.setPrecioUnitario(fila.getBigDecimal("precio_unitario"));
        d.setSubtotal(fila.getBigDecimal("subtotal"));
        d.setDescripcionVenta(fila.getString("descripcion_venta"));
        return d;
    }
}
