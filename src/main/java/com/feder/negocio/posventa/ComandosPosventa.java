package com.feder.negocio.posventa;

import com.feder.datos.posventa.DevolucionRepositorio.DevolucionVista;
import com.feder.modelo.Devolucion;
import com.feder.modelo.EstadoDevolucion;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.ResolucionDevolucion;
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
import com.feder.negocio.listados.ModuloListadosGenerales;

import java.util.List;

/** Comandos de CU6 · Posventa (devoluciones). */
final class ComandosPosventa {

    private static final String CU = "CU6";
    private static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};
    private static final TipoRol[] TODOS = {TipoRol.CLIENTE, TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};

    private final ServicioDevoluciones solicitudes;
    private final ServicioResolucionDevoluciones resolucion;

    ComandosPosventa(ServicioDevoluciones solicitudes, ServicioResolucionDevoluciones resolucion) {
        this.solicitudes = solicitudes;
        this.resolucion = resolucion;
    }

    void registrar(CatalogoComandos catalogo) {
        registrarSolicitud(catalogo);
        registrarResolucion(catalogo);
    }

    private void registrarSolicitud(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("SOLDEV").casoDeUso(CU)
                .descripcion("Solicita la devolución de unidades de un producto de su pedido ENTREGADO. El código es opcional: "
                        + "reenviar la misma solicitud con el mismo código devuelve la ya registrada (los códigos DEV-AAAA-NNNN "
                        + "los asigna FEDER)")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.codigo("codigo_producto", 30),
                        ParametroSpec.decimal("cantidad"), ParametroSpec.texto("motivo", 255),
                        ParametroSpec.codigo("codigo_devolucion", 30).opcional())
                .roles(TODOS).modificaDatos()
                .ejemplo("SOLDEV[\"PED-2026-0006\",\"FED-CAF-250\",\"1\",\"Una bolsa llegó rota y con el café humedecido\","
                        + "\"DEV-AM-0006-01\"]")
                .handler(this::solicitar).construir());
        catalogo.registrar(ComandoSpec.comando("RECDEV").casoDeUso(CU)
                .descripcion("Registra la recepción física de la mercancía devuelta (una vez por devolución)")
                .parametros(ParametroSpec.codigo("codigo_devolucion", 30), ParametroSpec.decimal("cantidad_recibida"))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("RECDEV[\"DEV-2026-0003\",\"1\"]")
                .handler((p, s) -> devolucion("Recepción registrada", solicitudes.recibir(s, p.texto("codigo_devolucion"),
                        p.decimal("cantidad_recibida"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("INSDEV").casoDeUso(CU)
                .descripcion("Registra la inspección de lo recibido: unidades aptas para volver a la venta (si ya está aprobada, "
                        + "las aptas aprobadas se reincorporan al stock). Supone el ejemplo de RECDEV")
                .parametros(ParametroSpec.codigo("codigo_devolucion", 30), ParametroSpec.decimal("cantidad_apta"))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("INSDEV[\"DEV-2026-0003\",\"1\"]")
                .handler((p, s) -> devolucion("Inspección registrada", solicitudes.inspeccionar(s, p.texto("codigo_devolucion"),
                        p.decimal("cantidad_apta"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("LISDEV").casoDeUso(CU)
                .descripcion("Lista las devoluciones por estado (el cliente ve solo las de sus pedidos)")
                .parametros(ParametroSpec.enumeracion("estado", EstadoDevolucion.class).conComodin())
                .roles(TODOS).ejemplo("LISDEV[\"*\"]").handler(this::listar).construir());
        catalogo.registrar(ComandoSpec.comando("VERDEV").casoDeUso(CU)
                .descripcion("Muestra una devolución por su código: estado, recepción, inspección, resolución y sus reembolsos "
                        + "(el cliente, solo las de sus pedidos; el personal ve también los movimientos de inventario)")
                .parametros(ParametroSpec.codigo("devolucion", 30))
                .roles(TODOS).ejemplo("VERDEV[\"DEV-2026-0001\"]").handler(this::ver).construir());
    }

    private void registrarResolucion(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("APRDEV").casoDeUso(CU)
                .descripcion("Aprueba una devolución SOLICITADA con REPOSICION o REEMBOLSO y la cantidad aprobada; con REEMBOLSO "
                        + "fija el importe y ordena los reintegros")
                .parametros(ParametroSpec.codigo("codigo_devolucion", 30), ParametroSpec.enumeracion("resolucion", ResolucionDevolucion.class),
                        ParametroSpec.decimal("cantidad_aprobada"), ParametroSpec.texto("comentario", 255).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("APRDEV[\"DEV-2026-0003\",\"REEMBOLSO\",\"1\",\"Tapa abollada verificada en la inspección\"]")
                .handler(this::aprobar).construir());
        catalogo.registrar(ComandoSpec.comando("RCHDEV").casoDeUso(CU)
                .descripcion("Rechaza una devolución SOLICITADA con su motivo (lo recibido queda en custodia de FEDER)")
                .parametros(ParametroSpec.codigo("codigo_devolucion", 30), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("RCHDEV[\"DEV-2026-0003\",\"La abolladura no afecta el cierre hermético del frasco\"]")
                .handler((p, s) -> devolucion("Devolución rechazada", resolucion.rechazar(s, p.texto("codigo_devolucion"),
                        p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("REPDEV").casoDeUso(CU)
                .descripcion("Registra la entrega en FEDER de unidades de reposición de una devolución aprobada con REPOSICION "
                        + "(clave opcional). Supone APRDEV[\"DEV-2026-0003\",\"REPOSICION\",\"1\",\"\"]")
                .parametros(ParametroSpec.codigo("codigo_devolucion", 30), ParametroSpec.decimal("cantidad"),
                        ParametroSpec.texto("clave_operacion", 60).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("REPDEV[\"DEV-2026-0003\",\"1\",\"\"]")
                .handler(this::reponer).construir());
        catalogo.registrar(ComandoSpec.comando("CERDEV").casoDeUso(CU)
                .descripcion("Completa una devolución APROBADA cuando su reembolso o su reposición están completos y lo recibido está resuelto")
                .parametros(ParametroSpec.codigo("codigo_devolucion", 30))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("CERDEV[\"DEV-2026-0003\"]")
                .handler((p, s) -> devolucion("Devolución completada", resolucion.completar(s, p.texto("codigo_devolucion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ANUDEV").casoDeUso(CU)
                .descripcion("Anula una aprobación equivocada (sin reembolsos ordenados ni reposición entregada) y crea la devolución "
                        + "que la rectifica, SOLICITADA, para decidir de nuevo")
                .parametros(ParametroSpec.codigo("codigo_devolucion", 30), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ANUDEV[\"DEV-2026-0003\",\"Se aprobó por error una reposición: el cliente pidió reembolso\"]")
                .handler(this::anular).construir());
    }

    private ResultadoOperacion solicitar(Parametros p, Solicitante s) {
        ServicioDevoluciones.DevolucionRegistrada r = solicitudes.solicitar(s, p.texto("codigo_pedido"), p.texto("codigo_producto"),
                p.decimal("cantidad"), p.texto("motivo"), p.texto("codigo_devolucion"));
        ResultadoOperacion res = devolucion(r.yaExistia() ? "Devolución ya registrada" : "Devolución solicitada", r.vista());
        return res.detalle("Nota", r.yaExistia() ? "Esa solicitud ya estaba registrada: no se creó otra."
                : "FEDER revisará su solicitud y le avisará la decisión. Entregue el producto en FEDER para su inspección.");
    }

    private ResultadoOperacion aprobar(Parametros p, Solicitante s) {
        ServicioResolucionDevoluciones.Aprobacion a = resolucion.aprobar(s, p.texto("codigo_devolucion"),
                p.enumeracion("resolucion", ResolucionDevolucion.class), p.decimal("cantidad_aprobada"), p.texto("comentario"));
        ResultadoOperacion r = devolucion("Devolución aprobada", a.vista())
                .detalle("Reincorporado al stock", Cantidades.formatear(a.reincorporado()));
        ResultadoOperacion.Tabla reintegros = new ResultadoOperacion.Tabla("Reintegros ordenados", List.of("Clave", "Monto (Bs)", "Estado"));
        a.reintegros().forEach(x -> reintegros.fila(x.getClaveIdempotencia(), Importes.formatear(x.getMonto()), x.getEstado()));
        return r.tabla(reintegros);
    }

    private ResultadoOperacion reponer(Parametros p, Solicitante s) {
        List<MovimientoInventario> salidas = resolucion.reponer(s, p.texto("codigo_devolucion"), p.decimal("cantidad"),
                p.texto("clave_operacion"));
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Salidas de reposición", List.of("Movimiento", "Lote", "Cantidad", "Clave"));
        salidas.forEach(m -> tabla.fila(m.getId(), m.getLoteFederId(), Cantidades.formatear(m.getCantidad()), m.getClaveOperacion()));
        return ResultadoOperacion.exito("Reposición entregada", "Las unidades de reposición salieron del stock libre con FEFO.")
                .detalle("Devolución", p.texto("codigo_devolucion")).tabla(tabla);
    }

    private ResultadoOperacion anular(Parametros p, Solicitante s) {
        ServicioResolucionDevoluciones.Rectificacion r = resolucion.anular(s, p.texto("codigo_devolucion"), p.texto("motivo"));
        return devolucion("Devolución anulada", r.anulada())
                .detalle("Devolución que la rectifica", r.rectificacion().devolucion().getCodigo())
                .detalle("Estado de la rectificación", r.rectificacion().devolucion().getEstado());
    }

    private ResultadoOperacion listar(Parametros p, Solicitante s) {
        List<DevolucionVista> obtenidas = solicitudes.listar(s, p.enumeracion("estado", EstadoDevolucion.class));
        List<DevolucionVista> lista = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Devoluciones", List.of("Código", "Pedido", "Producto", "Cantidad",
                "Estado", "Resolución", "Aprobada", "Recibida", "Apta", "Importe (Bs)", "Solicitada"));
        lista.forEach(v -> {
            Devolucion d = v.devolucion();
            tabla.fila(d.getCodigo(), v.codigoPedido(), v.descripcion(), Cantidades.formatear(d.getCantidad()), d.getEstado(),
                    d.getResolucion(), Cantidades.formatear(d.getCantidadAprobada()), Cantidades.formatear(d.getCantidadRecibida()),
                    Cantidades.formatear(d.getCantidadApta()), Importes.formatear(d.getImporteReembolsable()),
                    FechasBolivia.formatear(d.getFechaSolicitud()));
        });
        return ResultadoOperacion.exito("Devoluciones", lista.size() + " devolución(es), de la más reciente a la más antigua."
                + Listados.aviso(obtenidas)).tabla(tabla);
    }

    private ResultadoOperacion ver(Parametros p, Solicitante s) {
        ServicioDevoluciones.DevolucionConDetalle x = solicitudes.ver(s, p.texto("devolucion"));
        Devolucion d = x.vista().devolucion();
        ResultadoOperacion r = devolucion("Devolución " + d.getCodigo(), x.vista())
                .detalle("Recepción", FechasBolivia.formatear(d.getFechaRecepcion()))
                .detalle("Inspección", FechasBolivia.formatear(d.getFechaInspeccion()))
                .detalle("Resolución (fecha)", FechasBolivia.formatear(d.getFechaResolucion()))
                .detalle("Cierre", FechasBolivia.formatear(d.getFechaCierre()))
                .detalle("Anulación", FechasBolivia.formatear(d.getFechaAnulacion()))
                .detalle("Motivo de anulación", d.getMotivoAnulacion())
                .tabla(ModuloListadosGenerales.tabla("Reembolsos", List.of("N.º", "Monto (Bs)", "Estado", "Motivo", "Solicitado",
                        "Ejecutado", "Referencia"), x.reembolsos()));
        if (s.esPersonalInterno()) {
            r.tabla(ModuloListadosGenerales.tabla("Movimientos de inventario", List.of("Fecha", "Lote", "Tipo", "Cantidad", "Motivo"),
                    x.movimientos()));
        }
        return r;
    }

    private static ResultadoOperacion devolucion(String titulo, DevolucionVista v) {
        Devolucion d = v.devolucion();
        return ResultadoOperacion.exito(titulo, "")
                .detalle("Código", d.getCodigo()).detalle("Pedido", v.codigoPedido()).detalle("Producto", v.descripcion())
                .detalle("Cantidad reclamada", Cantidades.formatear(d.getCantidad())).detalle("Motivo", d.getMotivo())
                .detalle("Estado", d.getEstado()).detalle("Resolución", d.getResolucion())
                .detalle("Cantidad aprobada", Cantidades.formatear(d.getCantidadAprobada()))
                .detalle("Cantidad recibida", Cantidades.formatear(d.getCantidadRecibida()))
                .detalle("Cantidad apta", Cantidades.formatear(d.getCantidadApta()))
                .detalle("Importe reembolsable (Bs)", Importes.formatear(d.getImporteReembolsable()))
                .detalle("Solicitada", FechasBolivia.formatear(d.getFechaSolicitud()));
    }
}
