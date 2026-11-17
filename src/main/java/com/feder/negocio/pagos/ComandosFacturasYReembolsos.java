package com.feder.negocio.pagos;

import com.feder.datos.pagos.ReembolsoRepositorio.ReembolsoVista;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.EstadoReembolso;
import com.feder.modelo.Factura;
import com.feder.modelo.Reembolso;
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

import static com.feder.negocio.pagos.ComandosPagos.CU;
import static com.feder.negocio.pagos.ComandosPagos.PERSONAL;
import static com.feder.negocio.pagos.ComandosPagos.TODOS;

/** Comandos de CU6 · Facturas al cliente y reintegros (reembolsos). */
final class ComandosFacturasYReembolsos {

    private final ServicioFacturas facturas;
    private final ServicioReembolsos reembolsos;

    ComandosFacturasYReembolsos(ServicioFacturas facturas, ServicioReembolsos reembolsos) {
        this.facturas = facturas;
        this.reembolsos = reembolsos;
    }

    void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("FACPED").casoDeUso(CU)
                .descripcion("Devuelve la factura de un pedido pagado por completo, que FEDER emite al completarse el pago; si un "
                        + "pedido pagado quedó sin factura, la emite (NIT/CI y razón social opcionales: por defecto, los del "
                        + "cliente). El ejemplo supone verificado el pago del ejemplo de CONPAG")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.texto("nit_ci", 20).opcional(),
                        ParametroSpec.texto("razon_social", 150).opcional())
                .roles(TODOS).modificaDatos()
                .ejemplo("FACPED[\"PED-2026-0011\",\"99000014\",\"Andrés Molina Soliz\"]")
                .handler(this::emitir).construir());
        catalogo.registrar(ComandoSpec.comando("ANUFAC").casoDeUso(CU)
                .descripcion("Anula una factura cuando el procedimiento fiscal lo determina (la cancelación del pedido no la anula)")
                .parametros(ParametroSpec.codigo("numero_factura", 30), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("ANUFAC[\"FAC-2026-000008\",\"Resolución SIN-2026-0042 del procedimiento de anulación fiscal\"]")
                .handler(this::anular).construir());
        catalogo.registrar(ComandoSpec.comando("VERFAC").casoDeUso(CU)
                .descripcion("Muestra la factura de un pedido con sus líneas")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30))
                .roles(TODOS).ejemplo("VERFAC[\"PED-2026-0010\"]").handler(this::ver).construir());
        catalogo.registrar(ComandoSpec.comando("EJEREM").casoDeUso(CU)
                .descripcion("Registra que un reembolso PENDIENTE se ejecutó (dinero devuelto), con su comprobante. El ejemplo "
                        + "supone cancelado el pedido PED-2026-0010, cuyo pago se reintegra")
                .parametros(ParametroSpec.texto("clave_reembolso", 64), ParametroSpec.texto("comprobante", 100))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("EJEREM[\"CANC-PED10-PAGO10\",\"RQR-20261116-000077\"]")
                .handler(this::ejecutar).construir());
        catalogo.registrar(ComandoSpec.comando("FALREM").casoDeUso(CU)
                .descripcion("Registra que un reembolso PENDIENTE falló; con \"true\" crea otro PENDIENTE que lo sustituye "
                        + "por el mismo monto, dentro de los límites (con \"false\", el sustituto se ordena después con SUSREM)")
                .parametros(ParametroSpec.texto("clave_reembolso", 64), ParametroSpec.texto("motivo", 255),
                        ParametroSpec.booleano("sustituir"))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("FALREM[\"CANC-PED10-PAGO10\",\"La cuenta de destino rechazó la transferencia\",\"true\"]")
                .handler(this::fallar).construir());
        catalogo.registrar(ComandoSpec.comando("SUSREM").casoDeUso(CU)
                .descripcion("Ordena un reembolso nuevo que sustituye a uno FALLIDO (por ejemplo, cuando el cliente aporta otra "
                        + "cuenta), si no hay otro PENDIENTE por la misma causa y dentro de lo cobrado y no reintegrado. El ejemplo "
                        + "supone el reembolso del ejemplo de FALREM registrado con \"false\"")
                .parametros(ParametroSpec.texto("clave_reembolso_fallido", 64))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("SUSREM[\"CANC-PED10-PAGO10\"]")
                .handler(this::sustituir).construir());
        catalogo.registrar(ComandoSpec.comando("LISREM").casoDeUso(CU)
                .descripcion("Lista los reembolsos por estado (el cliente ve solo los de sus pedidos)")
                .parametros(ParametroSpec.enumeracion("estado", EstadoReembolso.class).conComodin())
                .roles(TODOS).ejemplo("LISREM[\"*\"]").handler(this::listar).construir());
    }

    private ResultadoOperacion emitir(Parametros p, Solicitante s) {
        ServicioFacturas.FacturaDetallada f = facturas.emitir(s, p.texto("codigo_pedido"), p.texto("nit_ci"), p.texto("razon_social"));
        return factura(f.yaExistia() ? "Factura ya emitida" : "Factura emitida", f.yaExistia()
                ? "El pedido ya tenía factura: no se emitió otra." : "", f);
    }

    private ResultadoOperacion anular(Parametros p, Solicitante s) {
        Factura f = facturas.anular(s, p.texto("numero_factura"), p.texto("motivo"));
        return ResultadoOperacion.exito("Factura anulada", "La factura se conserva con su historial.")
                .detalle("Número", f.getNumero()).detalle("Estado", f.getEstado())
                .detalle("Fecha de anulación", FechasBolivia.formatear(f.getFechaAnulacion()));
    }

    private ResultadoOperacion ver(Parametros p, Solicitante s) {
        ServicioFacturas.FacturaDetallada f = facturas.ver(s, p.texto("codigo_pedido"));
        return factura("Factura " + f.factura().getNumero(), "", f);
    }

    private ResultadoOperacion ejecutar(Parametros p, Solicitante s) {
        ServicioReembolsos.ResultadoReembolso r = reembolsos.ejecutar(s, p.texto("clave_reembolso"), p.texto("comprobante"));
        return reembolso("Reembolso ejecutado", "Se avisó al cliente que el dinero fue devuelto.", r.reembolso(), r.codigoPedido());
    }

    private ResultadoOperacion fallar(Parametros p, Solicitante s) {
        ServicioReembolsos.ResultadoReembolso r = reembolsos.fallar(s, p.texto("clave_reembolso"), p.texto("motivo"),
                Boolean.TRUE.equals(p.booleano("sustituir")));
        ResultadoOperacion res = reembolso("Reembolso fallido", r.sustituto() == null ? "El reembolso quedó FALLIDO."
                : "El reembolso quedó FALLIDO y se ordenó uno nuevo que lo sustituye.", r.reembolso(), r.codigoPedido());
        if (r.sustituto() != null) {
            res.detalle("Clave del sustituto", r.sustituto().getClaveIdempotencia())
                    .detalle("Monto del sustituto (Bs)", Importes.formatear(r.sustituto().getMonto()));
        }
        return res;
    }

    private ResultadoOperacion sustituir(Parametros p, Solicitante s) {
        ServicioReembolsos.ResultadoReembolso r = reembolsos.sustituirFallido(s, p.texto("clave_reembolso_fallido"));
        return reembolso("Reembolso sustituto ordenado", "Se ordenó un reembolso nuevo PENDIENTE en lugar del FALLIDO.",
                r.sustituto(), r.codigoPedido())
                .detalle("Sustituye a", r.reembolso().getClaveIdempotencia());
    }

    private ResultadoOperacion listar(Parametros p, Solicitante s) {
        List<ReembolsoVista> obtenidos = reembolsos.listar(s, p.enumeracion("estado", EstadoReembolso.class));
        List<ReembolsoVista> lista = Listados.recortar(obtenidos);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Reembolsos", List.of("Clave", "Pedido", "Pago", "Devolución",
                "Monto (Bs)", "Estado", "Solicitado", "Ejecutado", "Comprobante", "Motivo"));
        lista.forEach(v -> tabla.fila(v.reembolso().getClaveIdempotencia(), v.codigoPedido(), v.clavePago(), v.codigoDevolucion(),
                Importes.formatear(v.reembolso().getMonto()), v.reembolso().getEstado(),
                FechasBolivia.formatear(v.reembolso().getFechaSolicitud()), FechasBolivia.formatear(v.reembolso().getFechaEjecucion()),
                v.reembolso().getReferenciaExterna(), v.reembolso().getMotivo()));
        return ResultadoOperacion.exito("Reembolsos", lista.size() + " reembolso(s), del más reciente al más antiguo."
                + Listados.aviso(obtenidos)).tabla(tabla);
    }

    private static ResultadoOperacion factura(String titulo, String mensaje, ServicioFacturas.FacturaDetallada d) {
        Factura f = d.factura();
        ResultadoOperacion r = ResultadoOperacion.exito(titulo, mensaje)
                .detalle("Número", f.getNumero()).detalle("Pedido", d.pedido().getCodigo())
                .detalle("Fecha de emisión", FechasBolivia.formatear(f.getFechaEmision())).detalle("NIT o CI", f.getNitCi())
                .detalle("Razón social", f.getRazonSocial()).detalle("Total (Bs)", Importes.formatear(f.getTotal()))
                .detalle("Costo de envío incluido (Bs)", Importes.formatear(d.pedido().getCostoEnvio()))
                .detalle("Estado", f.getEstado()).detalle("Fecha de anulación", FechasBolivia.formatear(f.getFechaAnulacion()));
        ResultadoOperacion.Tabla lineas = new ResultadoOperacion.Tabla("Líneas facturadas", List.of("Producto", "Cantidad",
                "Precio unitario (Bs)", "Subtotal (Bs)"));
        for (DetallePedido l : d.lineas()) {
            lineas.fila(l.getDescripcionVenta(), Cantidades.formatear(l.getCantidad()), Importes.formatear(l.getPrecioUnitario()),
                    Importes.formatear(l.getSubtotal()));
        }
        return r.tabla(lineas);
    }

    private static ResultadoOperacion reembolso(String titulo, String mensaje, Reembolso r, String codigoPedido) {
        return ResultadoOperacion.exito(titulo, mensaje).detalle("Clave", r.getClaveIdempotencia()).detalle("Pedido", codigoPedido)
                .detalle("Monto (Bs)", Importes.formatear(r.getMonto())).detalle("Estado", r.getEstado())
                .detalle("Comprobante", r.getReferenciaExterna());
    }
}
