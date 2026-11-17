package com.feder.negocio.pagos;

import com.feder.datos.pagos.FacturaRepositorio;
import com.feder.datos.pagos.PagoRepositorio;
import com.feder.datos.pagos.ReembolsoRepositorio;
import com.feder.datos.posventa.DevolucionRepositorio;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.ventas.CambioEstadoPedido;

/**
 * CU6 · Pagos multicanal, facturas y reembolsos. Usa la {@link PasarelaPagosLocal}, que no está conectada a
 * ningún proveedor real: los cobros QR y con tarjeta los verifica el personal.
 */
public final class ModuloPagos implements ModuloComandos {

    private final ComandosPagos comandosPagos;
    private final ComandosFacturasYReembolsos comandosFacturas;

    public ModuloPagos(ContextoNegocio ctx) {
        this(ctx, new PasarelaPagosLocal());
    }

    public ModuloPagos(ContextoNegocio ctx, PasarelaPagos pasarela) {
        PedidoRepositorio pedidos = new PedidoRepositorio();
        PagoRepositorio pagos = new PagoRepositorio();
        ServicioReembolsos reembolsos = new ServicioReembolsos(ctx, new ReembolsoRepositorio(), pagos, pedidos,
                new DevolucionRepositorio());
        ConfirmacionComercial confirmacion = new ConfirmacionComercial(
                new CambioEstadoPedido(ctx, pedidos, new HistorialPedidoRepositorio()), ServicioInventario.crear(ctx),
                new NotificadorCliente(ctx));
        ServicioFacturas facturas = new ServicioFacturas(ctx, new FacturaRepositorio(), pedidos, pagos, new CuentaRepositorio());
        this.comandosPagos = new ComandosPagos(new ServicioPagos(ctx, pagos, pedidos, reembolsos, confirmacion, pasarela, facturas));
        this.comandosFacturas = new ComandosFacturasYReembolsos(facturas, reembolsos);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        comandosPagos.registrar(catalogo);
        comandosFacturas.registrar(catalogo);
    }
}
