package com.feder.negocio.posventa;

import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.pagos.PagoRepositorio;
import com.feder.datos.pagos.ReembolsoRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.pagos.CobrosPedido;
import com.feder.negocio.pagos.ServicioReembolsos;

/** CU6 · Posventa: devoluciones de productos entregados, con su recepción, inspección, decisión y resolución. */
public final class ModuloPosventa implements ModuloComandos {

    private final ComandosPosventa comandos;

    public ModuloPosventa(ContextoNegocio ctx) {
        DevolucionRepositorio devoluciones = new DevolucionRepositorio();
        PedidoRepositorio pedidos = new PedidoRepositorio();
        ReembolsoRepositorio reembolsos = new ReembolsoRepositorio();
        PagoRepositorio pagos = new PagoRepositorio();
        ReservaRepositorio reservas = new ReservaRepositorio();
        ServicioInventario inventario = ServicioInventario.crear(ctx);
        TramiteDevolucion tramite = new TramiteDevolucion(ctx, devoluciones, pedidos, reservas, inventario);
        ServicioDevoluciones solicitudes = new ServicioDevoluciones(ctx, devoluciones, pedidos, reservas, new ProductoRepositorio(), tramite);
        ServicioResolucionDevoluciones resolucion = new ServicioResolucionDevoluciones(ctx, devoluciones, reembolsos, tramite,
                new ServicioReembolsos(ctx, reembolsos, pagos, pedidos, devoluciones), inventario, new CobrosPedido(ctx, pagos));
        this.comandos = new ComandosPosventa(solicitudes, resolucion);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        comandos.registrar(catalogo);
    }
}
