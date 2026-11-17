package com.feder.negocio.ventas;

import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.ventas.CarritoRepositorio;
import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.datos.ventas.ProduccionRespaldoRepositorio;
import com.feder.datos.ventas.ReintegroCancelacionRepositorio;
import com.feder.modelo.ModalidadEntrega;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.inventario.ServicioInventario;

import java.math.BigDecimal;
import java.util.Set;

/** Construye los servicios de CU5 como lo hace ModuloVentas, para las pruebas. */
final class FabricaVentas {

    final ServicioInventario inventario;
    final ServicioCarrito carrito;
    final ServicioPedidos pedidos;
    final ServicioEncargos encargos;

    FabricaVentas(ContextoNegocio ctx) {
        inventario = ServicioInventario.crear(ctx);
        PedidoRepositorio repoPedidos = new PedidoRepositorio();
        HistorialPedidoRepositorio historial = new HistorialPedidoRepositorio();
        ReservaRepositorio reservas = new ReservaRepositorio();
        CarritoRepositorio repoCarrito = new CarritoRepositorio();
        carrito = new ServicioCarrito(ctx, repoCarrito, new ProductoRepositorio(), inventario.stock());
        pedidos = new ServicioPedidos(ctx, repoCarrito, repoPedidos, new EntregaRepositorio(), historial, reservas, inventario,
                new ReintegroPendiente(ctx, new ReintegroCancelacionRepositorio()));
        encargos = new ServicioEncargos(ctx, repoPedidos, historial, new ProduccionRespaldoRepositorio(), reservas, inventario);
    }

    /** @param subtotal el subtotal que el cliente vio con VERCAR para esa selección (R054). */
    static SolicitudPedido recojo(String receptor, Set<String> productos, String subtotal) {
        return new SolicitudPedido(ModalidadEntrega.RECOJO, null, null, receptor, "70000114", productos,
                new BigDecimal(subtotal), null);
    }

    static SolicitudPedido delivery(String direccion, Set<String> productos, String subtotalEsperado) {
        return new SolicitudPedido(ModalidadEntrega.DELIVERY, direccion, "Edificio de tres pisos", "Andrés Molina Soliz",
                "70000114", productos, subtotalEsperado == null ? null : new BigDecimal(subtotalEsperado), "Por la tarde");
    }
}
