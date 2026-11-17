package com.feder.negocio.catalogo;

import com.feder.datos.catalogo.ProductoCatalogo;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.StockRepositorio;
import com.feder.modelo.Producto;
import com.feder.negocio.ContextoNegocio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Stock vendible derivado y alertas de stock (PRODUCTO «Stock vendible derivado», R070).
 *
 * stock vendible = suma de (cantidad_disponible - cantidad_reservada) de los lotes DISPONIBLE y no vencidos.
 * Un producto activo está en alerta si su stock vendible es menor que stock_minimo o es 0. La alerta se
 * notifica solo cuando la condición pasa de falsa a verdadera, en la misma transacción del cambio.
 */
public class ServicioStock {

    private final ContextoNegocio ctx;
    private final StockRepositorio stock;
    private final ProductoRepositorio productos;

    public ServicioStock(ContextoNegocio ctx, StockRepositorio stock, ProductoRepositorio productos) {
        this.ctx = ctx;
        this.stock = stock;
        this.productos = productos;
    }

    public LocalDate hoy() {
        return FechasBolivia.hoy(ctx.reloj());
    }

    public BigDecimal vendible(long productoId) {
        return stock.stockVendible(productoId, hoy());
    }

    public Map<Long, BigDecimal> vendiblePorProducto() {
        return stock.stockVendiblePorProducto(hoy());
    }

    public List<StockRepositorio.ResumenStock> resumen(String filtro) {
        return stock.resumen(filtro, hoy());
    }

    /** Un producto inactivo no se vende, por eso nunca está en alerta. */
    public boolean enAlerta(long productoId) {
        Optional<ProductoCatalogo> producto = productos.buscarPorId(productoId);
        return producto.isPresent() && enAlerta(producto.get().producto(), vendible(productoId));
    }

    public static boolean enAlerta(Producto producto, BigDecimal vendible) {
        return Boolean.TRUE.equals(producto.getActivo())
                && (vendible.signum() == 0 || vendible.compareTo(producto.getStockMinimo()) < 0);
    }

    /**
     * Ejecuta un cambio que puede reducir el stock vendible y, al terminar, avisa al personal de los
     * productos que entraron en alerta con ese cambio. Debe llamarse dentro de la transacción del cambio,
     * después de bloquear las filas de inventario que el cambio va a modificar.
     *
     * Dos transacciones pueden cambiar lotes distintos del mismo producto sin esperarse en ningún bloqueo de fila:
     * si cada una comparara su estado anterior y posterior por su cuenta, una alerta se perdería (ninguna ve el
     * cruce del mínimo) o se duplicaría (ambas lo ven). Por eso la evaluación se serializa por producto con un
     * bloqueo consultivo de transacción, tomado en orden creciente de producto y antes de leer el estado anterior:
     * ese estado ya incluye lo confirmado por la transacción que evaluó antes (más lo que esta transacción haya
     * cambiado hasta aquí) y el posterior suma solo el cambio propio, así cada cruce avisa una sola vez. Los bloqueos
     * se mantienen hasta el final de la transacción.
     *
     * Antes del bloqueo consultivo se toma FOR KEY SHARE la fila de cada producto, el mismo bloqueo que tomará la
     * NOTIFICACION de la alerta al insertarse. Así todas las transacciones toman primero la fila del producto y
     * después el bloqueo consultivo: quien modifica el producto (FOR UPDATE, por ejemplo su stock mínimo) y después
     * vigila su stock no queda esperando el bloqueo consultivo de otra transacción que, a su vez, espera la fila del
     * producto para insertar su alerta.
     */
    public <T> T vigilando(Collection<Long> productoIds, Supplier<T> cambio) {
        TreeSet<Long> ordenados = new TreeSet<>(productoIds);
        ordenados.forEach(productos::bloquearReferencia);
        ordenados.forEach(stock::bloquearEvaluacionAlerta);
        Map<Long, Boolean> antes = new LinkedHashMap<>();
        ordenados.forEach(id -> antes.put(id, enAlerta(id)));
        T resultado = cambio.get();
        antes.forEach((id, estaba) -> {
            if (!estaba) {
                avisarSiEntroEnAlerta(id);
            }
        });
        return resultado;
    }

    private void avisarSiEntroEnAlerta(long productoId) {
        Producto producto = productos.buscarPorId(productoId).orElseThrow().producto();
        BigDecimal actual = vendible(productoId);
        if (!enAlerta(producto, actual)) {
            return;
        }
        boolean agotado = actual.signum() == 0;
        String titulo = agotado ? "Producto agotado" : "Stock bajo mínimo";
        String mensaje = producto.getNombre() + " (" + producto.getCodigo() + "): "
                + (agotado ? "el stock vendible está agotado."
                : "el stock vendible (" + Cantidades.formatear(actual) + ") quedó bajo el mínimo ("
                + Cantidades.formatear(producto.getStockMinimo()) + ").");
        for (Long destinatario : stock.destinatariosAlerta()) {
            ctx.notificaciones().alertaStock(destinatario, productoId, titulo, mensaje);
        }
    }
}
