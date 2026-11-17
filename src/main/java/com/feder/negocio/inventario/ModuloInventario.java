package com.feder.negocio.inventario;

import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.compras.CompraRepositorio;
import com.feder.datos.compras.DetalleCompraRepositorio;
import com.feder.datos.compras.LoteOrigenRepositorio;
import com.feder.datos.compras.OperacionCompraRepositorio;
import com.feder.datos.compras.ProductorCompraRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.inventario.StockRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.ServicioStock;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ModuloComandos;

/** CU4 · Compras a productores, recepción e inventario de FEDER. Arma sus servicios y registra sus comandos. */
public final class ModuloInventario implements ModuloComandos {

    private final ComandosCompras comandosCompras;
    private final ComandosInventario comandosInventario;

    public ModuloInventario(ContextoNegocio ctx) {
        ProductoRepositorio productos = new ProductoRepositorio();
        LoteFederRepositorio lotesFeder = new LoteFederRepositorio();
        MovimientoRepositorio movimientos = new MovimientoRepositorio();
        CompraRepositorio compras = new CompraRepositorio();
        DetalleCompraRepositorio detalles = new DetalleCompraRepositorio();
        LoteOrigenRepositorio lotesOrigen = new LoteOrigenRepositorio();
        ServicioStock stock = new ServicioStock(ctx, new StockRepositorio(), productos);
        ServicioInventario inventario = new ServicioInventario(ctx, stock, lotesFeder, movimientos,
                new ReservaRepositorio(), productos);
        OperacionCompraRepositorio operaciones = new OperacionCompraRepositorio();
        ServicioCompras servicioCompras = new ServicioCompras(ctx, compras, detalles, lotesOrigen,
                new ProductorCompraRepositorio(), operaciones, lotesFeder);
        ServicioRecepcion recepcion = new ServicioRecepcion(ctx, compras, detalles, lotesOrigen,
                operaciones, movimientos, lotesFeder, inventario);
        this.comandosCompras = new ComandosCompras(servicioCompras, recepcion);
        this.comandosInventario = new ComandosInventario(inventario);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        comandosCompras.registrar(catalogo);
        comandosInventario.registrar(catalogo);
    }
}
