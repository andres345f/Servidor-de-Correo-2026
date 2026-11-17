package com.feder.datos.consultas;

import com.feder.datos.RepositorioJdbc;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Consultas de solo lectura para los listados generales por tabla (plan, fase C, C0): las tablas que no tenían
 * un listado propio. Cada fila se devuelve como una lista de valores en el orden de las columnas del SELECT:
 * fechas con zona como {@link OffsetDateTime}, fechas como {@link LocalDate}, montos y cantidades como BigDecimal
 * (con la escala de su columna) y el resto tal como lo entrega JDBC. El formato lo pone la capa de negocio.
 */
public class ListadosGeneralesRepositorio extends RepositorioJdbc {

    /** Roles con la cantidad de cuentas que lo tienen VIGENTE; filtro en código o nombre. */
    public List<List<Object>> roles(String filtro, int limite) {
        return filas("SELECT r.codigo, r.nombre, r.descripcion, "
                        + "(SELECT count(DISTINCT ur.usuario_id) FROM usuario_rol ur WHERE ur.rol_id = r.id AND ur.estado = 'VIGENTE') "
                        + "FROM rol r WHERE (?::text IS NULL OR strpos(lower(r.codigo || ' ' || r.nombre), lower(?)) > 0) "
                        + "ORDER BY r.id LIMIT ?",
                filtro, filtro, limite);
    }

    /** Líneas de compra a productores, de la compra más reciente a la más antigua; de todas o de una compra. */
    public List<List<Object>> lineasDeCompra(String codigoCompra, int limite) {
        return filas("SELECT c.codigo, dc.id, pr.codigo_productor, lp.codigo_lote, p.codigo || ' ' || p.nombre, "
                        + "dc.cantidad_comprada, dc.precio_unitario, dc.cantidad_recibida, dc.cantidad_rechazada, dc.cantidad_cancelada, "
                        + "dc.cantidad_comprada - dc.cantidad_recibida - dc.cantidad_rechazada - dc.cantidad_cancelada "
                        + "FROM detalle_compra dc JOIN compra_feder c ON c.id = dc.compra_id "
                        + "JOIN productor pr ON pr.id = c.productor_id JOIN lote_produccion lp ON lp.id = dc.lote_origen_id "
                        + "JOIN produccion pn ON pn.id = lp.produccion_id JOIN producto p ON p.id = pn.producto_id "
                        + "WHERE (?::text IS NULL OR c.codigo = ?) ORDER BY c.fecha_compra DESC, c.id DESC, dc.id LIMIT ?",
                codigoCompra, codigoCompra, limite);
    }

    /** Productos en los carritos de los clientes (de todos o de uno), con el precio vigente. */
    public List<List<Object>> carritos(String correoCliente, int limite) {
        return filas("SELECT u.correo, p.codigo || ' ' || p.nombre, ic.cantidad, p.precio_venta, ic.fecha_agregado "
                        + "FROM item_carrito ic JOIN usuario u ON u.id = ic.cliente_id JOIN producto p ON p.id = ic.producto_id "
                        + "WHERE (?::text IS NULL OR lower(u.correo) = lower(?)) ORDER BY u.correo, ic.fecha_agregado, ic.id LIMIT ?",
                correoCliente, correoCliente, limite);
    }

    /** Líneas de pedido, del pedido más reciente al más antiguo; de todos o de un pedido. */
    public List<List<Object>> lineasDePedido(String codigoPedido, int limite) {
        return filas("SELECT pe.codigo, dp.id, p.codigo || ' ' || p.nombre, dp.cantidad, dp.precio_unitario, dp.subtotal, "
                        + "dp.descripcion_venta, pn.codigo "
                        + "FROM detalle_pedido dp JOIN pedido pe ON pe.id = dp.pedido_id JOIN producto p ON p.id = dp.producto_id "
                        + "LEFT JOIN produccion pn ON pn.id = dp.produccion_id "
                        + "WHERE (?::text IS NULL OR pe.codigo = ?) ORDER BY pe.fecha_creacion DESC, pe.id DESC, dp.id LIMIT ?",
                codigoPedido, codigoPedido, limite);
    }

    /** Reservas de stock, de la más reciente a la más antigua; de todos o de un pedido. */
    public List<List<Object>> reservas(String codigoPedido, int limite) {
        return filas("SELECT rs.id, pe.codigo, p.codigo, lf.codigo, rs.cantidad, rs.estado, rs.fecha_creacion, "
                        + "rs.fecha_expiracion, rs.fecha_cierre "
                        + "FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id "
                        + "JOIN pedido pe ON pe.id = dp.pedido_id JOIN producto p ON p.id = dp.producto_id "
                        + "JOIN lote_feder lf ON lf.id = rs.lote_feder_id "
                        + "WHERE (?::text IS NULL OR pe.codigo = ?) ORDER BY rs.fecha_creacion DESC, rs.id DESC LIMIT ?",
                codigoPedido, codigoPedido, limite);
    }

    /** Cambios de estado de los pedidos, del más reciente al más antiguo; de todos o de un pedido. */
    public List<List<Object>> historialDePedidos(String codigoPedido, int limite) {
        return filas("SELECT pe.codigo, h.fecha, h.estado_anterior, h.estado_nuevo, COALESCE(u.correo, 'Sistema'), h.comentario "
                        + "FROM historial_pedido h JOIN pedido pe ON pe.id = h.pedido_id LEFT JOIN usuario u ON u.id = h.usuario_id "
                        + "WHERE (?::text IS NULL OR pe.codigo = ?) ORDER BY h.fecha DESC, h.id DESC LIMIT ?",
                codigoPedido, codigoPedido, limite);
    }

    /** Facturas, de la más reciente a la más antigua; de todas o de un estado (VIGENTE o ANULADA). */
    public List<List<Object>> facturas(String estado, int limite) {
        return filas("SELECT f.numero, pe.codigo, f.fecha_emision, f.nit_ci, f.razon_social, f.total, f.estado, f.fecha_anulacion "
                        + "FROM factura f JOIN pedido pe ON pe.id = f.pedido_id "
                        + "WHERE (?::text IS NULL OR f.estado = ?) ORDER BY f.fecha_emision DESC, f.id DESC LIMIT ?",
                estado, estado, limite);
    }

    /** Notificaciones de todos los usuarios, de la más reciente a la más antigua, con su destinatario. */
    public List<List<Object>> notificaciones(boolean soloNoLeidas, int limite) {
        return filas("SELECT n.id, u.correo, n.fecha_creacion, n.tipo, n.canal, n.titulo, n.mensaje, n.estado, n.fecha_lectura "
                        + "FROM notificacion n JOIN usuario u ON u.id = n.usuario_id "
                        + "WHERE (? = FALSE OR n.fecha_lectura IS NULL) ORDER BY n.fecha_creacion DESC, n.id DESC LIMIT ?",
                soloNoLeidas, limite);
    }

    // ---------------- Tablas de detalle para VERCOM y VERDEV (plan, fase C, C2 y C4) ----------------

    /** Productores afiliados a una comunidad (cualquier estado de solicitud). */
    public List<List<Object>> productoresDeComunidad(long comunidadId, int limite) {
        return filas("SELECT pr.codigo_productor, u.nombres || ' ' || u.apellidos, u.correo, pr.estado_solicitud, "
                        + "CASE WHEN pr.activo THEN 'Sí' ELSE 'No' END, pr.fecha_afiliacion "
                        + "FROM productor pr JOIN usuario u ON u.id = pr.usuario_id WHERE pr.comunidad_agricola_id = ? "
                        + "ORDER BY pr.id LIMIT ?",
                comunidadId, limite);
    }

    /** Reembolsos ordenados por una devolución. */
    public List<List<Object>> reembolsosDeDevolucion(long devolucionId) {
        return filas("SELECT r.id, r.monto, r.estado, r.motivo, r.fecha_solicitud, r.fecha_ejecucion, r.referencia_externa "
                + "FROM reembolso r WHERE r.devolucion_id = ? ORDER BY r.fecha_solicitud, r.id", devolucionId);
    }

    /** Movimientos de inventario de una devolución (entrada de lo devuelto y salida de la reposición). */
    public List<List<Object>> movimientosDeDevolucion(long devolucionId) {
        return filas("SELECT m.fecha, lf.codigo, m.tipo, m.cantidad, m.motivo FROM movimiento_inventario m "
                + "JOIN lote_feder lf ON lf.id = m.lote_feder_id WHERE m.devolucion_id = ? ORDER BY m.fecha, m.id", devolucionId);
    }

    public boolean existeCompra(String codigo) {
        return existe("SELECT EXISTS (SELECT 1 FROM compra_feder WHERE codigo = ?) AS hay", codigo);
    }

    public boolean existePedido(String codigo) {
        return existe("SELECT EXISTS (SELECT 1 FROM pedido WHERE codigo = ?) AS hay", codigo);
    }

    public boolean existeCuenta(String correo) {
        return existe("SELECT EXISTS (SELECT 1 FROM usuario WHERE lower(correo) = lower(?)) AS hay", correo);
    }

    private boolean existe(String sql, Object valor) {
        return consultarUno(sql, fila -> fila.getBoolean("hay"), valor).orElse(false);
    }

    private List<List<Object>> filas(String sql, Object... parametros) {
        return consultar(sql, ListadosGeneralesRepositorio::fila, parametros);
    }

    private static List<Object> fila(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        List<Object> valores = new ArrayList<>(meta.getColumnCount());
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            String tipo = meta.getColumnTypeName(i);
            if ("timestamptz".equals(tipo)) {
                valores.add(rs.getObject(i, OffsetDateTime.class));
            } else if ("date".equals(tipo)) {
                valores.add(rs.getObject(i, LocalDate.class));
            } else {
                valores.add(rs.getObject(i));
            }
        }
        return valores;
    }
}
