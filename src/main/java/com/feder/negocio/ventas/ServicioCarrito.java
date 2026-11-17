package com.feder.negocio.ventas;

import com.feder.datos.catalogo.ProductoCatalogo;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.ventas.CarritoRepositorio;
import com.feder.datos.ventas.CarritoRepositorio.ItemCarritoVista;
import com.feder.modelo.Producto;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.catalogo.ServicioStock;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Carrito persistente del cliente (ITEM_CARRITO). No reserva stock: la disponibilidad se valida al
 * confirmar el pedido. Solo un CLIENTE vigente agrega o modifica ítems, y solo de productos activos (R034).
 */
public class ServicioCarrito {

    private final ContextoNegocio ctx;
    private final CarritoRepositorio carrito;
    private final ProductoRepositorio productos;
    private final ServicioStock stock;

    public ServicioCarrito(ContextoNegocio ctx, CarritoRepositorio carrito, ProductoRepositorio productos, ServicioStock stock) {
        this.ctx = ctx;
        this.carrito = carrito;
        this.productos = productos;
        this.stock = stock;
    }

    /** Línea del carrito con su situación actual para confirmar. */
    public record LineaCarrito(ItemCarritoVista item, BigDecimal subtotal, BigDecimal vendible, boolean confirmable,
                               String observacion) {
    }

    public record ResumenCarrito(List<LineaCarrito> lineas, BigDecimal subtotal) {
    }

    /** R033: agregar de nuevo un producto suma la cantidad a su línea. */
    public ResumenCarrito agregar(Solicitante solicitante, String codigoProducto, BigDecimal cantidad) {
        Permisos.exigirCliente(solicitante, "usar el carrito");
        return ctx.transacciones().enTransaccion(() -> {
            Producto p = productos.buscarPorCodigo(codigoProducto).filter(ProductoCatalogo::publicado)
                    .orElseThrow(() -> NegocioException.noEncontrado("El producto " + codigoProducto
                            + " no existe o no está a la venta. Consulte LISPRO[\"*\"].")).producto();
            Cantidades.validarPositiva(cantidad, p.getUnidadMedida(), "cantidad");
            // La suma con lo que ya hay en el carrito también debe caber en item_carrito.cantidad (NUMERIC(12,3)).
            BigDecimal enCarrito = carrito.bloquearItem(solicitante.usuarioId(), p.getId())
                    .map(i -> i.item().getCantidad()).orElse(BigDecimal.ZERO);
            Cantidades.exigirMaximo(enCarrito.add(cantidad), "cantidad del producto en el carrito");
            carrito.agregar(solicitante.usuarioId(), p.getId(), cantidad, ctx.ahora());
            return resumen(solicitante.usuarioId());
        });
    }

    /** Quita una cantidad de un producto del carrito; con {@code cantidad} nula quita el producto entero. */
    public ResumenCarrito quitar(Solicitante solicitante, String codigoProducto, BigDecimal cantidad) {
        Permisos.exigirCliente(solicitante, "usar el carrito");
        return ctx.transacciones().enTransaccion(() -> {
            ItemCarritoVista item = productos.buscarPorCodigo(codigoProducto)
                    .flatMap(p -> carrito.bloquearItem(solicitante.usuarioId(), p.producto().getId()))
                    .orElseThrow(() -> NegocioException.noEncontrado("Su carrito no tiene el producto " + codigoProducto + "."));
            BigDecimal restante = cantidad == null ? BigDecimal.ZERO
                    : item.item().getCantidad().subtract(
                            Cantidades.validarPositiva(cantidad, item.producto().producto().getUnidadMedida(), "cantidad a quitar"));
            if (restante.signum() <= 0) {
                carrito.eliminar(item.item().getId());
            } else {
                carrito.actualizarCantidad(item.item().getId(), restante, ctx.ahora());
            }
            return resumen(solicitante.usuarioId());
        });
    }

    public ResumenCarrito ver(Solicitante solicitante) {
        return ver(solicitante, Set.of());
    }

    /**
     * Muestra el carrito o solo los productos indicados, con su subtotal: es el que el cliente indica al
     * confirmar esa misma selección con PEDINM o PEDENC (R054).
     * @param productos códigos a mostrar; vacío para todo el carrito.
     */
    public ResumenCarrito ver(Solicitante solicitante, Set<String> productos) {
        Permisos.exigirCliente(solicitante, "consultar el carrito");
        return ctx.transacciones().enTransaccion(() -> {
            ResumenCarrito completo = resumen(solicitante.usuarioId());
            if (productos.isEmpty()) {
                return completo;
            }
            List<LineaCarrito> elegidas = completo.lineas().stream()
                    .filter(l -> productos.contains(l.item().producto().producto().getCodigo())).toList();
            BigDecimal subtotal = elegidas.stream().filter(LineaCarrito::confirmable).map(LineaCarrito::subtotal)
                    .reduce(BigDecimal.ZERO.setScale(Importes.ESCALA), BigDecimal::add);
            return new ResumenCarrito(elegidas, subtotal);
        });
    }

    public int vaciar(Solicitante solicitante) {
        Permisos.exigirCliente(solicitante, "usar el carrito");
        return ctx.transacciones().enTransaccion(() -> carrito.vaciar(solicitante.usuarioId()));
    }

    /**
     * Situación de cada ítem: un producto desactivado se conserva en el carrito, se muestra como no
     * disponible y no se puede confirmar (R034); si el stock vendible no alcanza se avisa la cantidad vendible.
     */
    private ResumenCarrito resumen(long clienteId) {
        Map<Long, BigDecimal> vendibles = stock.vendiblePorProducto();
        List<LineaCarrito> lineas = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO.setScale(Importes.ESCALA);
        for (ItemCarritoVista item : carrito.listar(clienteId)) {
            Producto p = item.producto().producto();
            BigDecimal vendible = vendibles.getOrDefault(p.getId(), BigDecimal.ZERO);
            BigDecimal importe = Importes.subtotal(item.item().getCantidad(), p.getPrecioVenta());
            boolean publicado = item.producto().publicado();
            String observacion = !publicado ? "No disponible: el producto ya no está a la venta"
                    : vendible.compareTo(item.item().getCantidad()) >= 0 ? "Disponible"
                    : "Stock vendible insuficiente (" + Cantidades.formatear(vendible) + "): ajuste la cantidad"
                    + (Boolean.TRUE.equals(p.getPermiteEncargo()) ? " o confírmelo como encargo con PEDENC" : "");
            if (publicado) {
                subtotal = subtotal.add(importe);
            }
            lineas.add(new LineaCarrito(item, importe, vendible, publicado, observacion));
        }
        return new ResumenCarrito(lineas, subtotal);
    }
}
