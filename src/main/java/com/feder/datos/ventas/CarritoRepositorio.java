package com.feder.datos.ventas;

import com.feder.datos.RepositorioJdbc;
import com.feder.datos.catalogo.ProductoCatalogo;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.modelo.ItemCarrito;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Acceso a ITEM_CARRITO, el carrito persistente del cliente. El carrito no reserva stock. */
public class CarritoRepositorio extends RepositorioJdbc {

    private static final String SELECT_VISTA = "SELECT ic.id AS item_id, ic.cliente_id, ic.cantidad, ic.fecha_agregado, "
            + ProductoRepositorio.COLUMNAS_CATALOGO
            + "FROM item_carrito ic JOIN producto p ON p.id = ic.producto_id JOIN categoria c ON c.id = p.categoria_id ";

    /** Ítem del carrito con el producto en su estado actual (precio vigente, activo o no). */
    public record ItemCarritoVista(ItemCarrito item, ProductoCatalogo producto) {
    }

    public List<ItemCarritoVista> listar(long clienteId) {
        return consultar(SELECT_VISTA + "WHERE ic.cliente_id = ? ORDER BY ic.id", CarritoRepositorio::mapear, clienteId);
    }

    /**
     * R054 (1): bloquea los ítems del cliente antes que cualquier fila de inventario. Sus productos se leen
     * con FOR SHARE: un cambio de precio o una desactivación simultáneos esperan o se ven ya confirmados,
     * así la revalidación usa el precio y el estado con los que se crea el pedido (R034).
     */
    public List<ItemCarritoVista> bloquear(long clienteId) {
        return consultar(SELECT_VISTA + "WHERE ic.cliente_id = ? ORDER BY ic.id FOR UPDATE OF ic FOR SHARE OF p",
                CarritoRepositorio::mapear, clienteId);
    }

    public Optional<ItemCarritoVista> bloquearItem(long clienteId, long productoId) {
        return consultarUno(SELECT_VISTA + "WHERE ic.cliente_id = ? AND ic.producto_id = ? FOR UPDATE OF ic",
                CarritoRepositorio::mapear, clienteId, productoId);
    }

    /** R033: agregar de nuevo un producto suma a su línea; una sola sentencia atómica. */
    public void agregar(long clienteId, long productoId, BigDecimal cantidad, OffsetDateTime momento) {
        actualizar("INSERT INTO item_carrito (cliente_id, producto_id, cantidad, fecha_agregado) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (cliente_id, producto_id) DO UPDATE SET cantidad = item_carrito.cantidad + EXCLUDED.cantidad, "
                        + "fecha_agregado = EXCLUDED.fecha_agregado",
                clienteId, productoId, cantidad, momento);
    }

    public void actualizarCantidad(long itemId, BigDecimal cantidad, OffsetDateTime momento) {
        actualizar("UPDATE item_carrito SET cantidad = ?, fecha_agregado = ? WHERE id = ?", cantidad, momento, itemId);
    }

    public void eliminar(long itemId) {
        actualizar("DELETE FROM item_carrito WHERE id = ?", itemId);
    }

    public int vaciar(long clienteId) {
        return actualizar("DELETE FROM item_carrito WHERE cliente_id = ?", clienteId);
    }

    private static ItemCarritoVista mapear(ResultSet fila) throws SQLException {
        ProductoCatalogo producto = ProductoRepositorio.mapearCatalogo(fila);
        ItemCarrito item = new ItemCarrito();
        item.setId(fila.getLong("item_id"));
        item.setClienteId(fila.getLong("cliente_id"));
        item.setProductoId(producto.producto().getId());
        item.setCantidad(fila.getBigDecimal("cantidad"));
        item.setFechaAgregado(fechaHora(fila, "fecha_agregado"));
        return new ItemCarritoVista(item, producto);
    }
}
