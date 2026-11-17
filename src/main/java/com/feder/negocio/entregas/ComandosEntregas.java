package com.feder.negocio.entregas;

import com.feder.datos.entregas.RetornoRepositorio.ReservaDespachada;
import com.feder.datos.ventas.EntregaRepositorio.EntregaVista;
import com.feder.modelo.Entrega;
import com.feder.modelo.EstadoEntrega;
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
import com.feder.negocio.entregas.SolicitudRetorno.TipoBajaRetorno;
import com.feder.negocio.ventas.PresentacionPedido;

import java.util.List;

/** Comandos de CU6 · Procesamiento de pedidos y entregas. */
final class ComandosEntregas {

    private static final String CU = "CU6";
    private static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};
    private static final TipoRol[] TODOS = {TipoRol.CLIENTE, TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};

    private final ServicioProcesamiento procesamiento;
    private final ServicioEntregas entregas;

    ComandosEntregas(ServicioProcesamiento procesamiento, ServicioEntregas entregas) {
        this.procesamiento = procesamiento;
        this.entregas = entregas;
    }

    void registrar(CatalogoComandos catalogo) {
        registrarProcesamiento(catalogo);
        registrarLogistica(catalogo);
    }

    private void registrarProcesamiento(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("CONPED").casoDeUso(CU)
                .descripcion("Confirmación comercial de un pedido inmediato PENDIENTE: pagado por completo, o aceptado para efectivo "
                        + "contra entrega. El ejemplo supone rechazado el intento QR del pedido (RECPAG) y registrado "
                        + "REGPAG[\"PED-2026-0011\",\"EFECTIVO\",\"143.00\",\"\"]")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.texto("comentario", 255).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("CONPED[\"PED-2026-0011\",\"Aceptado para pago en efectivo contra entrega\"]")
                .handler((p, s) -> PresentacionPedido.resumen("Pedido confirmado",
                        procesamiento.confirmar(s, p.texto("codigo_pedido"), p.texto("comentario"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("PREPED").casoDeUso(CU)
                .descripcion("Pasa un pedido CONFIRMADO a EN_PREPARACION (todas sus líneas reservadas y el cobro resuelto)")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("PREPED[\"PED-2026-0010\"]")
                .handler((p, s) -> PresentacionPedido.resumen("Pedido en preparación", procesamiento.preparar(s, p.texto("codigo_pedido"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("DESPED").casoDeUso(CU)
                .descripcion("Despacha un pedido EN_PREPARACION: consume sus reservas (salida de inventario) y la entrega pasa a "
                        + "EN_CAMINO o LISTA_PARA_RECOJO. Fecha programada opcional. El ejemplo supone PREPED[\"PED-2026-0010\"]")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.fechaHora("fecha_programada").opcional(),
                        ParametroSpec.texto("observacion", 255).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("DESPED[\"PED-2026-0010\",\"2026-12-20 10:00\",\"Retira la clienta en mostrador\"]")
                .handler(this::despachar).construir());
    }

    private void registrarLogistica(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("ESTENT").casoDeUso(CU)
                .descripcion("Registra el avance de una entrega despachada: FALLIDA, EN_CAMINO (reintento), RETORNADA "
                        + "(todo volvió; en recojo, no recogido) o NO_RECUPERADA (cierre de la búsqueda), con la observación")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.enumeracion("estado", EstadoEntrega.class),
                        ParametroSpec.texto("observacion", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ESTENT[\"PED-2026-0009\",\"FALLIDA\",\"Nadie atendió en el domicilio; se dejó aviso\"]")
                .handler(this::cambiarEstado).construir());
        catalogo.registrar(ComandoSpec.comando("RETENT").casoDeUso(CU)
                .descripcion("Registra un retorno físico de mercancía de una entrega fallida, por reserva: recibido, apto (lo no apto "
                        + "se da de baja como DANADO o DEFECTUOSO), motivo, evidencia y clave opcional. Supone el ejemplo de ESTENT")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.entero("reserva"),
                        ParametroSpec.decimal("cantidad_recibida"), ParametroSpec.decimal("cantidad_apta"),
                        ParametroSpec.enumeracion("tipo_baja", TipoBajaRetorno.class).opcional(),
                        ParametroSpec.texto("motivo", 255).opcional(), ParametroSpec.texto("evidencia", 255),
                        ParametroSpec.texto("clave_operacion", 59).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("RETENT[\"PED-2026-0009\",\"15\",\"4\",\"4\",\"\",\"Devuelto por el repartidor tras la entrega fallida\","
                        + "\"Guía de retorno GR-2026-0031\",\"RET-PED0009-RES15-01\"]")
                .handler(this::retornar).construir());
        catalogo.registrar(ComandoSpec.comando("ENTPED").casoDeUso(CU)
                .descripcion("Registra la entrega al cliente: el pedido pasa a ENTREGADO con el cobro completo (el efectivo contra "
                        + "entrega se confirma en este momento)")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.texto("observacion", 255).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ENTPED[\"PED-2026-0009\",\"Recibió Marco Antelo en su domicilio\"]")
                .handler(this::entregar).construir());
        catalogo.registrar(ComandoSpec.comando("LISENT").casoDeUso(CU)
                .descripcion("Lista las entregas por estado (el cliente ve solo las de sus pedidos)")
                .parametros(ParametroSpec.enumeracion("estado", EstadoEntrega.class).conComodin())
                .roles(TODOS).ejemplo("LISENT[\"*\"]").handler(this::listar).construir());
        catalogo.registrar(ComandoSpec.comando("VERENT").casoDeUso(CU)
                .descripcion("Muestra la entrega de un pedido con lo despachado y lo retornado por reserva")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30))
                .roles(TODOS).ejemplo("VERENT[\"PED-2026-0009\"]")
                .handler((p, s) -> situacion("Entrega del pedido", "", entregas.situacion(s, p.texto("codigo_pedido"))))
                .construir());
    }

    private ResultadoOperacion despachar(Parametros p, Solicitante s) {
        ServicioProcesamiento.Despacho d = procesamiento.despachar(s, p.texto("codigo_pedido"), p.fechaHora("fecha_programada"),
                p.texto("observacion"));
        ResultadoOperacion r = PresentacionPedido.resumen("Pedido despachado", d.pedido())
                .detalle("Fecha programada", FechasBolivia.formatear(d.pedido().entrega().getFechaProgramada()));
        ResultadoOperacion.Tabla salidas = new ResultadoOperacion.Tabla("Salidas de inventario", List.of("Movimiento", "Lote",
                "Reserva", "Cantidad", "Clave"));
        d.salidas().forEach(m -> salidas.fila(m.getId(), m.getLoteFederId(), m.getReservaId(), Cantidades.formatear(m.getCantidad()),
                m.getClaveOperacion()));
        return r.tabla(salidas);
    }

    private ResultadoOperacion cambiarEstado(Parametros p, Solicitante s) {
        ServicioEntregas.SituacionEntrega sit = entregas.cambiarEstado(s, p.texto("codigo_pedido"),
                p.enumeracion("estado", EstadoEntrega.class), p.texto("observacion"));
        return situacion("Entrega actualizada", "La entrega quedó " + sit.pedido().entrega().getEstado() + ".", sit);
    }

    private ResultadoOperacion retornar(Parametros p, Solicitante s) {
        ServicioEntregas.RetornoRegistrado r = entregas.registrarRetorno(s, new SolicitudRetorno(p.texto("codigo_pedido"),
                p.entero("reserva"), p.decimal("cantidad_recibida"), p.decimal("cantidad_apta"),
                p.enumeracion("tipo_baja", TipoBajaRetorno.class), p.texto("motivo"), p.texto("evidencia"), p.texto("clave_operacion")));
        return situacion(r.yaRegistrado() ? "Retorno ya registrado" : "Retorno registrado", r.yaRegistrado()
                ? "Esa clave ya registró este retorno: no se duplicó." : "La mercancía apta volvió al lote de su salida.", r.situacion())
                .detalle("Clave del retorno", r.clave()).detalle("Cantidad retornada", Cantidades.formatear(r.recibida()))
                .detalle("Dada de baja (no apta)", Cantidades.formatear(r.noApta()));
    }

    private ResultadoOperacion entregar(Parametros p, Solicitante s) {
        ServicioEntregas.Entregado e = entregas.entregar(s, p.texto("codigo_pedido"), p.texto("observacion"));
        return PresentacionPedido.resumen("Pedido entregado", e.pedido())
                .detalle("Fecha de entrega", FechasBolivia.formatear(e.pedido().entrega().getFechaEntrega()))
                .detalle("Cobro en efectivo", e.efectivoCobrado() == null ? "No correspondía"
                        : "Bs " + Importes.formatear(e.efectivoCobrado().getMonto()) + " recibidos en la entrega")
                .detalle("Factura emitida", e.factura() == null ? "Ya emitida con el pago (VERFAC)" : e.factura().getNumero());
    }

    private ResultadoOperacion listar(Parametros p, Solicitante s) {
        List<EntregaVista> obtenidas = entregas.listar(s, p.enumeracion("estado", EstadoEntrega.class));
        List<EntregaVista> lista = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Entregas", List.of("Pedido", "Modalidad", "Estado", "Recibe",
                "Teléfono", "Dirección", "Programada", "Entregada"));
        lista.forEach(v -> tabla.fila(v.codigoPedido(), v.entrega().getModalidad(), v.entrega().getEstado(),
                v.entrega().getNombreReceptor(), v.entrega().getTelefonoContacto(), v.entrega().getDireccion(),
                FechasBolivia.formatear(v.entrega().getFechaProgramada()), FechasBolivia.formatear(v.entrega().getFechaEntrega())));
        return ResultadoOperacion.exito("Entregas", lista.size() + " entrega(s), del pedido más reciente al más antiguo."
                + Listados.aviso(obtenidas)).tabla(tabla);
    }

    private static ResultadoOperacion situacion(String titulo, String mensaje, ServicioEntregas.SituacionEntrega sit) {
        Entrega e = sit.pedido().entrega();
        ResultadoOperacion r = ResultadoOperacion.exito(titulo, mensaje)
                .detalle("Pedido", sit.pedido().pedido().getCodigo()).detalle("Estado del pedido", sit.pedido().pedido().getEstado())
                .detalle("Modalidad", e.getModalidad()).detalle("Estado de la entrega", e.getEstado())
                .detalle("Recibe", e.getNombreReceptor()).detalle("Programada", FechasBolivia.formatear(e.getFechaProgramada()))
                .detalle("Entregada", FechasBolivia.formatear(e.getFechaEntrega())).detalle("Observación", e.getObservacion());
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Mercancía despachada", List.of("Reserva", "Producto", "Lote",
                "Salió", "Retornó", "Dada de baja", "Sin recuperar"));
        for (ReservaDespachada d : sit.despachadas()) {
            tabla.fila(d.reservaId(), d.descripcion(), d.codigoLote(), Cantidades.formatear(d.salida()),
                    Cantidades.formatear(d.retornado()), Cantidades.formatear(d.bajas()), Cantidades.formatear(d.noRecuperado()));
        }
        return r.tabla(tabla);
    }
}
