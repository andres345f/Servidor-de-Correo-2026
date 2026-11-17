package com.feder.negocio.ventas;

import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.ventas.CarritoRepositorio;
import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.datos.ventas.ProduccionRespaldoRepositorio;
import com.feder.datos.ventas.ReintegroCancelacionRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.inventario.ServicioInventario;

/** CU5 · Comercio electrónico: carrito, pedidos inmediatos y encargos. Arma sus servicios y registra sus comandos. */
public final class ModuloVentas implements ModuloComandos {

    private final ComandosCarrito comandosCarrito;
    private final ComandosPedidos comandosPedidos;

    public ModuloVentas(ContextoNegocio ctx) {
        ServicioInventario inventario = ServicioInventario.crear(ctx);
        PedidoRepositorio pedidos = new PedidoRepositorio();
        HistorialPedidoRepositorio historial = new HistorialPedidoRepositorio();
        ReservaRepositorio reservas = new ReservaRepositorio();
        CarritoRepositorio carrito = new CarritoRepositorio();
        ServicioCarrito servicioCarrito = new ServicioCarrito(ctx, carrito, new ProductoRepositorio(), inventario.stock());
        ServicioPedidos servicioPedidos = new ServicioPedidos(ctx, carrito, pedidos, new EntregaRepositorio(), historial,
                reservas, inventario, new ReintegroPendiente(ctx, new ReintegroCancelacionRepositorio()));
        ServicioEncargos servicioEncargos = new ServicioEncargos(ctx, pedidos, historial, new ProduccionRespaldoRepositorio(),
                reservas, inventario);
        this.comandosCarrito = new ComandosCarrito(servicioCarrito);
        this.comandosPedidos = new ComandosPedidos(servicioPedidos, servicioEncargos);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        comandosCarrito.registrar(catalogo);
        comandosPedidos.registrar(catalogo);
    }
}
