package com.feder.negocio.entregas;

import com.feder.datos.entregas.RetornoRepositorio;
import com.feder.datos.inventario.LoteFederRepositorio;
import com.feder.datos.inventario.MovimientoRepositorio;
import com.feder.datos.inventario.ReservaRepositorio;
import com.feder.datos.pagos.FacturaRepositorio;
import com.feder.datos.pagos.PagoRepositorio;
import com.feder.datos.usuarios.CuentaRepositorio;
import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.HistorialPedidoRepositorio;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.inventario.ServicioInventario;
import com.feder.negocio.notificaciones.NotificadorCliente;
import com.feder.negocio.pagos.CobrosPedido;
import com.feder.negocio.pagos.ConfirmacionComercial;
import com.feder.negocio.pagos.ServicioFacturas;
import com.feder.negocio.ventas.CambioEstadoPedido;

/** CU6 · Procesamiento de pedidos y entregas: confirmación comercial, preparación, despacho, logística y retornos. */
public final class ModuloEntregas implements ModuloComandos {

    private final ComandosEntregas comandos;

    public ModuloEntregas(ContextoNegocio ctx) {
        PedidoRepositorio pedidos = new PedidoRepositorio();
        EntregaRepositorio entregas = new EntregaRepositorio();
        RetornoRepositorio retornos = new RetornoRepositorio();
        ServicioInventario inventario = ServicioInventario.crear(ctx);
        CambioEstadoPedido cambioPedido = new CambioEstadoPedido(ctx, pedidos, new HistorialPedidoRepositorio());
        PagoRepositorio pagos = new PagoRepositorio();
        CobrosPedido cobros = new CobrosPedido(ctx, pagos);
        ServicioFacturas facturas = new ServicioFacturas(ctx, new FacturaRepositorio(), pedidos, pagos, new CuentaRepositorio());
        ConfirmacionComercial confirmacion = new ConfirmacionComercial(cambioPedido, inventario, new NotificadorCliente(ctx));
        ServicioProcesamiento procesamiento = new ServicioProcesamiento(ctx, pedidos, new ReservaRepositorio(), entregas,
                cambioPedido, inventario, cobros, confirmacion);
        RegistroRetornos registro = new RegistroRetornos(ctx, inventario, retornos, new MovimientoRepositorio(),
                new LoteFederRepositorio());
        ServicioEntregas servicioEntregas = new ServicioEntregas(ctx, pedidos, entregas, retornos, cambioPedido, registro, cobros,
                facturas);
        this.comandos = new ComandosEntregas(procesamiento, servicioEntregas);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        comandos.registrar(catalogo);
    }
}
