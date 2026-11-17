package com.feder.negocio.inventario;

import com.feder.datos.compras.CompraRepositorio.CompraResumen;
import com.feder.datos.compras.DetalleCompraVista;
import com.feder.modelo.CompraFeder;
import com.feder.modelo.DetalleCompra;
import com.feder.modelo.EstadoCompra;
import com.feder.modelo.TipoRol;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

import java.util.List;

/** Comandos de CU4 · Compras a productores y recepción de mercancía. */
final class ComandosCompras {

    private static final String CU = "CU4";
    private static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};

    private final ServicioCompras compras;
    private final ServicioRecepcion recepcion;

    ComandosCompras(ServicioCompras compras, ServicioRecepcion recepcion) {
        this.compras = compras;
        this.recepcion = recepcion;
    }

    void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("INSCMP").casoDeUso(CU)
                .descripcion("Registra una compra a un productor con su primera línea (precio vacío = precio ofrecido)")
                .parametros(ParametroSpec.codigo("codigo_productor", 20), ParametroSpec.codigo("codigo_lote_origen", 30),
                        ParametroSpec.decimal("cantidad"), ParametroSpec.decimal("precio_unitario").opcional(),
                        ParametroSpec.texto("observacion", 500).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("INSCMP[\"PROD-0001\",\"LP-2026-002\",\"20\",\"9.00\",\"Jabones para la campaña de diciembre\"]")
                .handler((p, s) -> compra("Compra registrada", compras.registrarCompra(s, p.texto("codigo_productor"),
                        p.texto("codigo_lote_origen"), p.decimal("cantidad"), p.decimal("precio_unitario"), p.texto("observacion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ADDCMP").casoDeUso(CU)
                .descripcion("Agrega una línea a una compra REGISTRADA")
                .parametros(ParametroSpec.codigo("codigo_compra", 30), ParametroSpec.codigo("codigo_lote_origen", 30),
                        ParametroSpec.decimal("cantidad"), ParametroSpec.decimal("precio_unitario").opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ADDCMP[\"CMP-2026-005\",\"LP-2026-001\",\"30\",\"15.00\"]")
                .handler((p, s) -> compra("Línea agregada", compras.agregarLinea(s, p.texto("codigo_compra"),
                        p.texto("codigo_lote_origen"), p.decimal("cantidad"), p.decimal("precio_unitario"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("RECCMP").casoDeUso(CU)
                .descripcion("Registra la recepción física de una línea: aceptado (entra al inventario) y rechazado, con clave única")
                .parametros(ParametroSpec.codigo("codigo_compra", 30), ParametroSpec.codigo("codigo_lote_origen", 30),
                        ParametroSpec.decimal("cantidad_aceptada"), ParametroSpec.decimal("cantidad_rechazada"),
                        ParametroSpec.texto("ubicacion", 60).opcional(), ParametroSpec.texto("motivo", 255).opcional(),
                        ParametroSpec.texto("documento_entrega", 60).opcional(), ParametroSpec.codigo("clave_operacion", 64))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("RECCMP[\"CMP-2026-005\",\"LP-2026-011\",\"28\",\"2\",\"Estante A-03\",\"Dos botellas con el sello roto\","
                        + "\"NE-MQ-0002\",\"REC-CMP005-LP011-01\"]")
                .handler((p, s) -> linea("Recepción registrada", recepcion.recibir(s, new SolicitudRecepcion(
                        p.texto("codigo_compra"), p.texto("codigo_lote_origen"), p.decimal("cantidad_aceptada"),
                        p.decimal("cantidad_rechazada"), p.texto("ubicacion"), p.texto("motivo"),
                        p.texto("documento_entrega"), p.texto("clave_operacion")))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("CANCMP").casoDeUso(CU)
                .descripcion("Cancela cantidad pendiente de una línea de compra (\"*\" = todo lo pendiente), con motivo")
                .parametros(ParametroSpec.codigo("codigo_compra", 30), ParametroSpec.codigo("codigo_lote_origen", 30),
                        ParametroSpec.decimal("cantidad").conComodin(), ParametroSpec.texto("motivo", 255),
                        ParametroSpec.codigo("clave_operacion", 64))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("CANCMP[\"CMP-2026-003\",\"LP-2026-008\",\"*\",\"La productora no entregará las tabletas restantes\","
                        + "\"CAN-CMP003-LP008-01\"]")
                .handler((p, s) -> linea("Cantidad pendiente cancelada", recepcion.cancelarPendiente(s, p.texto("codigo_compra"),
                        p.texto("codigo_lote_origen"), p.decimal("cantidad"), p.texto("motivo"), p.texto("clave_operacion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ANUREC").casoDeUso(CU)
                .descripcion("Corrige una aceptación registrada de más (ANULACION_RECEPCION); opcionalmente la registra como rechazada")
                .parametros(ParametroSpec.codigo("codigo_compra", 30), ParametroSpec.codigo("codigo_lote_origen", 30),
                        ParametroSpec.decimal("cantidad"), ParametroSpec.booleano("registrar_como_rechazo"),
                        ParametroSpec.texto("motivo", 255), ParametroSpec.codigo("clave_operacion", 64))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ANUREC[\"CMP-2026-003\",\"LP-2026-008\",\"2\",\"false\",\"Se contaron dos tabletas de más\","
                        + "\"ANU-CMP003-LP008-01\"]")
                .handler((p, s) -> linea("Recepción corregida", recepcion.anularRecepcion(s, p.texto("codigo_compra"),
                        p.texto("codigo_lote_origen"), p.decimal("cantidad"), p.booleano("registrar_como_rechazo"),
                        p.texto("motivo"), p.texto("clave_operacion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("PRECMP").casoDeUso(CU)
                .descripcion("Corrige el precio pactado de una línea que todavía no tuvo mercancía aceptada")
                .parametros(ParametroSpec.codigo("codigo_compra", 30), ParametroSpec.codigo("codigo_lote_origen", 30),
                        ParametroSpec.decimal("precio_unitario"), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("PRECMP[\"CMP-2026-005\",\"LP-2026-011\",\"58.00\",\"Precio acordado por escrito con la productora\"]")
                .handler((p, s) -> compra("Precio de compra corregido", compras.corregirPrecio(s, p.texto("codigo_compra"),
                        p.texto("codigo_lote_origen"), p.decimal("precio_unitario"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("PAGCMP").casoDeUso(CU)
                .descripcion("Registra el pago al productor de una compra RECIBIDA (se paga lo aceptado). En la semilla, "
                        + "CMP-2026-003 queda RECIBIDA después del ejemplo de CANCMP")
                .parametros(ParametroSpec.codigo("codigo_compra", 30))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("PAGCMP[\"CMP-2026-003\"]")
                .handler((p, s) -> compra("Pago al productor registrado", compras.registrarPago(s, p.texto("codigo_compra"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("RECPRE").casoDeUso(CU)
                .descripcion("Documenta en la bitácora una diferencia de precio descubierta después de la aceptación o del pago "
                        + "(RECTIFICACION_PRECIO); no cambia el precio histórico")
                .parametros(ParametroSpec.codigo("codigo_compra", 30), ParametroSpec.codigo("codigo_lote_origen", 30),
                        ParametroSpec.decimal("precio_correcto"), ParametroSpec.decimal("cantidad_afectada"),
                        ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("RECPRE[\"CMP-2026-003\",\"LP-2026-008\",\"16.00\",\"80\",\"El acta de compra fijó 16.00 Bs por tableta\"]")
                .handler(this::rectificar)
                .construir());
        catalogo.registrar(ComandoSpec.comando("LISCMP").casoDeUso(CU)
                .descripcion("Lista las compras (un productor ve solo las suyas)")
                .parametros(ParametroSpec.enumeracion("estado", EstadoCompra.class).conComodin())
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER, TipoRol.PRODUCTOR)
                .ejemplo("LISCMP[\"*\"]").handler(this::listar).construir());
        catalogo.registrar(ComandoSpec.comando("VERCMP").casoDeUso(CU)
                .descripcion("Muestra una compra con sus líneas (un productor solo ve sus compras)")
                .parametros(ParametroSpec.codigo("codigo_compra", 30))
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER, TipoRol.PRODUCTOR)
                .ejemplo("VERCMP[\"CMP-2026-003\"]")
                .handler((p, s) -> compra("Compra " + p.texto("codigo_compra"), compras.ver(s, p.texto("codigo_compra"))))
                .construir());
    }

    private ResultadoOperacion rectificar(Parametros p, Solicitante s) {
        ServicioCompras.RectificacionPrecio r = compras.rectificarPrecio(s, p.texto("codigo_compra"), p.texto("codigo_lote_origen"),
                p.decimal("precio_correcto"), p.decimal("cantidad_afectada"), p.texto("motivo"));
        return ResultadoOperacion.exito("Rectificación de precio registrada",
                        "Constancia documental en la bitácora: el precio histórico, el total y los movimientos no cambian.")
                .detalle("Compra", r.codigoCompra()).detalle("Lote de origen", r.codigoLoteOrigen())
                .detalle("Precio registrado (Bs)", Importes.formatear(r.precioRegistrado()))
                .detalle("Precio correcto (Bs)", Importes.formatear(r.precioCorrecto()))
                .detalle("Cantidad afectada", Cantidades.formatear(r.cantidadAfectada()))
                .detalle("Diferencia (Bs)", Importes.formatear(r.importeDiferencia()));
    }

    private ResultadoOperacion listar(Parametros p, Solicitante s) {
        List<CompraResumen> obtenidas = compras.listar(s, p.enumeracion("estado", EstadoCompra.class));
        List<CompraResumen> lista = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Compras", List.of("Código", "Fecha", "Productor",
                "Estado", "Total acordado (Bs)", "Pagada"));
        lista.forEach(r -> tabla.fila(r.compra().getCodigo(), FechasBolivia.formatear(r.compra().getFechaCompra()),
                r.codigoProductor() + " " + r.nombreProductor(), r.compra().getEstado(), Importes.formatear(r.compra().getTotal()),
                r.compra().getFechaPagoProductor() == null ? "No" : FechasBolivia.formatear(r.compra().getFechaPagoProductor())));
        return ResultadoOperacion.exito("Compras a productores", lista.size() + " compra(s)." + Listados.aviso(obtenidas))
                .tabla(tabla);
    }

    private static ResultadoOperacion compra(String titulo, CompraDetallada d) {
        CompraFeder c = d.compra();
        ResultadoOperacion r = ResultadoOperacion.exito(titulo, "")
                .detalle("Código", c.getCodigo())
                .detalle("Productor", d.productor().codigo() + " " + d.productor().nombre())
                .detalle("Fecha de compra", FechasBolivia.formatear(c.getFechaCompra()))
                .detalle("Estado", c.getEstado())
                .detalle("Total acordado (Bs)", Importes.formatear(c.getTotal()))
                .detalle("Importe a pagar por lo aceptado (Bs)", Importes.formatear(d.importeAPagar()))
                .detalle("Pago al productor", c.getFechaPagoProductor() == null ? "Pendiente" : FechasBolivia.formatear(c.getFechaPagoProductor()))
                .detalle("Observación", c.getObservacion());
        return r.tabla(tablaLineas(d.lineas()));
    }

    private static ResultadoOperacion.Tabla tablaLineas(List<DetalleCompraVista> lineas) {
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Líneas", List.of("Lote de origen", "Producto",
                "Comprada", "Precio unitario (Bs)", "Aceptada", "Rechazada", "Cancelada", "Pendiente"));
        lineas.forEach(l -> {
            DetalleCompra d = l.detalle();
            tabla.fila(l.codigoLoteOrigen(), l.codigoProducto() + " " + l.nombreProducto(),
                    Cantidades.formatear(d.getCantidadComprada()) + " " + l.unidad(), Importes.formatear(d.getPrecioUnitario()),
                    Cantidades.formatear(d.getCantidadRecibida()), Cantidades.formatear(d.getCantidadRechazada()),
                    Cantidades.formatear(d.getCantidadCancelada()), Cantidades.formatear(l.pendiente()));
        });
        return tabla;
    }

    private static ResultadoOperacion linea(String titulo, ResultadoLinea r) {
        String mensaje = r.yaRegistrada()
                ? "Esta operación ya estaba registrada con la misma clave: no se aplicó de nuevo."
                : "Operación registrada en la bitácora.";
        return ResultadoOperacion.exito(r.yaRegistrada() ? "Operación ya registrada" : titulo, mensaje)
                .detalle("Compra", r.codigoCompra())
                .detalle("Estado de la compra", r.estadoCompra())
                .detalle("Lote de origen", r.linea().codigoLoteOrigen())
                .detalle("Aceptado en esta operación", Cantidades.formatear(r.aceptada()))
                .detalle("Rechazado en esta operación", Cantidades.formatear(r.rechazada()))
                .detalle("Cancelado en esta operación", Cantidades.formatear(r.cancelada()))
                .detalle("Aceptación anulada en esta operación", Cantidades.formatear(r.anulada()))
                .detalle("Pendiente de la línea", Cantidades.formatear(r.linea().pendiente()))
                .detalle("Lote de FEDER", r.codigoLoteFeder() == null ? "Sin lote (nada aceptado)" : r.codigoLoteFeder())
                .detalle("Pedidos abastecidos con lo aceptado", r.abastecidos().isEmpty() ? "Ninguno"
                        : String.join(", ", r.abastecidos().stream().map(ComandosCompras::abastecido).toList()));
    }

    private static String abastecido(PedidoAbastecido a) {
        return a.codigoPedido() + " (" + Cantidades.formatear(a.reservado()) + " reservadas"
                + (a.confirmado() ? ", encargo CONFIRMADO" : a.cubierto() ? "" : ", todavía incompleto") + ")";
    }
}
