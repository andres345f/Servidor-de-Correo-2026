package com.feder.negocio.pagos;

import com.feder.datos.pagos.PagoRepositorio.PagoVista;
import com.feder.modelo.MetodoPago;
import com.feder.modelo.Pago;
import com.feder.modelo.TipoRol;
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

/** Comandos de CU6 · Pagos multicanal: registro del intento, verificación por el personal y consulta. */
final class ComandosPagos {

    static final String CU = "CU6";
    static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};
    static final TipoRol[] TODOS = {TipoRol.CLIENTE, TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};

    private final ServicioPagos pagos;

    ComandosPagos(ServicioPagos pagos) {
        this.pagos = pagos;
    }

    void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("REGPAG").casoDeUso(CU)
                .descripcion("Registra un intento de pago de su pedido por QR o TARJETA (anticipo o saldo) o en EFECTIVO contra "
                        + "entrega (saldo completo). La clave es opcional (el prefijo PAG- lo reserva FEDER): reenviar la misma "
                        + "clave no duplica el pago")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.enumeracion("metodo", MetodoPago.class),
                        ParametroSpec.decimal("monto"), ParametroSpec.texto("clave_idempotencia", 64).opcional())
                .roles(TODOS).modificaDatos()
                .ejemplo("REGPAG[\"PED-2026-0008\",\"QR\",\"300.00\",\"\"]")
                .handler(this::registrarPago).construir());
        catalogo.registrar(ComandoSpec.comando("CONPAG").casoDeUso(CU)
                .descripcion("Confirma un pago QR o TARJETA PENDIENTE tras verificar el cobro: referencia de la transacción e importe "
                        + "efectivamente cobrado (si no coincide, el pago sigue PENDIENTE). Si el pedido queda pagado por completo "
                        + "se emite su factura. Nadie verifica el pago de un pedido propio")
                .parametros(ParametroSpec.texto("clave_pago", 64), ParametroSpec.texto("referencia_externa", 100),
                        ParametroSpec.decimal("monto_cobrado"))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("CONPAG[\"pago-ped0011-01\",\"QR-20261115-000245\",\"143.00\"]")
                .handler(this::confirmarPago).construir());
        catalogo.registrar(ComandoSpec.comando("RECPAG").casoDeUso(CU)
                .descripcion("Rechaza un pago PENDIENTE con un resultado definitivo sin cobro (rechazo, anulación o vencimiento del "
                        + "intento). El efectivo de un pedido ya aceptado contra entrega no se rechaza: se cobra al entregar")
                .parametros(ParametroSpec.texto("clave_pago", 64), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("RECPAG[\"pago-ped0011-01\",\"El emisor informó que el código QR venció sin cobro\"]")
                .handler(this::rechazarPago).construir());
        catalogo.registrar(ComandoSpec.comando("LISPAG").casoDeUso(CU)
                .descripcion("Lista los pagos de un pedido (\"*\" = todos; el cliente ve solo los de sus pedidos)")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30).conComodin())
                .roles(TODOS).ejemplo("LISPAG[\"*\"]").handler(this::listar).construir());
    }

    private ResultadoOperacion registrarPago(Parametros p, Solicitante s) {
        ServicioPagos.RegistroPago r = pagos.registrar(s, p.texto("codigo_pedido"), p.enumeracion("metodo", MetodoPago.class),
                p.decimal("monto"), p.texto("clave_idempotencia"));
        return pago(r.yaExistia() ? "Pago ya registrado" : "Pago registrado", r.yaExistia()
                ? "Este intento ya estaba registrado: no se creó otro." : "El intento quedó PENDIENTE.", r.pago(), r.codigoPedido())
                .detalle("Indicaciones", r.indicaciones());
    }

    private ResultadoOperacion confirmarPago(Parametros p, Solicitante s) {
        ServicioPagos.Verificacion v = pagos.confirmar(s, p.texto("clave_pago"), p.texto("referencia_externa"), p.decimal("monto_cobrado"));
        String nota = v.yaProcesado() ? "El pago ya estaba confirmado con esa referencia: no se cambió nada."
                : v.reintegroTardio() != null ? "El pedido estaba CANCELADO: no se reactiva y se ordenó el reintegro "
                + v.reintegroTardio().getClaveIdempotencia() + " por Bs " + Importes.formatear(v.reintegroTardio().getMonto()) + "."
                : v.pedidoConfirmado() ? "El pedido quedó pagado por completo y pasó a CONFIRMADO." : "Pago confirmado.";
        ResultadoOperacion r = pago(v.yaProcesado() ? "Pago ya confirmado" : "Pago confirmado", nota, v.pago(), v.pedido().getCodigo())
                .detalle("Estado del pedido", v.pedido().getEstado());
        if (v.factura() != null) {
            r.detalle("Factura emitida", v.factura().getNumero());
        }
        return r;
    }

    private ResultadoOperacion rechazarPago(Parametros p, Solicitante s) {
        ServicioPagos.Verificacion v = pagos.rechazar(s, p.texto("clave_pago"), p.texto("motivo"));
        return pago("Pago rechazado", "El intento quedó RECHAZADO; el cliente puede registrar otro.", v.pago(), v.pedido().getCodigo());
    }

    private ResultadoOperacion listar(Parametros p, Solicitante s) {
        List<PagoVista> obtenidos = pagos.listar(s, p.texto("codigo_pedido"));
        List<PagoVista> lista = Listados.recortar(obtenidos);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Pagos", List.of("Clave", "Pedido", "Método", "Monto (Bs)",
                "Estado", "Registrado", "Pagado", "Referencia externa"));
        lista.forEach(v -> tabla.fila(v.pago().getClaveIdempotencia(), v.codigoPedido(), v.pago().getMetodo(),
                Importes.formatear(v.pago().getMonto()), v.pago().getEstado(), FechasBolivia.formatear(v.pago().getFechaRegistro()),
                FechasBolivia.formatear(v.pago().getFechaPago()), v.pago().getReferenciaExterna()));
        return ResultadoOperacion.exito("Pagos", lista.size() + " pago(s), del más reciente al más antiguo."
                + Listados.aviso(obtenidos)).tabla(tabla);
    }

    private static ResultadoOperacion pago(String titulo, String mensaje, Pago pago, String codigoPedido) {
        return ResultadoOperacion.exito(titulo, mensaje)
                .detalle("Clave del pago", pago.getClaveIdempotencia()).detalle("Pedido", codigoPedido)
                .detalle("Método", pago.getMetodo()).detalle("Monto (Bs)", Importes.formatear(pago.getMonto()))
                .detalle("Estado", pago.getEstado()).detalle("Referencia externa", pago.getReferenciaExterna());
    }
}
