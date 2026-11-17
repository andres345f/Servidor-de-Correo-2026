package com.feder.negocio.ventas;

import com.feder.datos.ventas.PedidoCompleto;
import com.feder.datos.ventas.PedidoRepositorio.PedidoResumen;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.ModalidadEntrega;
import com.feder.modelo.TipoPedido;
import com.feder.modelo.TipoRol;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Comandos de CU5 · Pedidos inmediatos, encargos, consultas y cancelación. */
final class ComandosPedidos {

    private static final String CU = "CU5";
    private static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};
    private static final TipoRol[] TODOS = {TipoRol.CLIENTE, TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};
    private static final Pattern CODIGO = Pattern.compile("[A-Z0-9][A-Z0-9._-]{0,29}");

    private final ServicioPedidos pedidos;
    private final ServicioEncargos encargos;

    ComandosPedidos(ServicioPedidos pedidos, ServicioEncargos encargos) {
        this.pedidos = pedidos;
        this.encargos = encargos;
    }

    void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("PEDINM").casoDeUso(CU)
                .descripcion("Confirma su carrito como pedido inmediato: reserva el stock al instante (productos \"*\" = todo el carrito)")
                .parametros(parametrosPedido())
                .roles(TipoRol.CLIENTE).modificaDatos()
                .ejemplo("PEDINM[\"DELIVERY\",\"Barrio Las Palmeras, calle Los Mangales N.º 301\",\"Edificio de tres pisos, depto. 2B\","
                        + "\"Andrés Molina Soliz\",\"70000114\",\"*\",\"84.00\",\"Entregar por la tarde\"]")
                .handler((p, s) -> pedidoCreado(pedidos.confirmarInmediato(s, solicitud(p))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("PEDENC").casoDeUso(CU)
                .descripcion("Confirma productos del carrito como encargo (sin stock suficiente y que acepten encargo). "
                        + "El ejemplo supone AGRCAR[\"FED-ACE-100\",\"6\"] y el subtotal que muestra VERCAR")
                .parametros(parametrosPedido())
                .roles(TipoRol.CLIENTE).modificaDatos()
                .ejemplo("PEDENC[\"RECOJO\",\"\",\"\",\"Daniela Flores Justiniano\",\"70000111\",\"FED-ACE-100\",\"540.00\","
                        + "\"Para regalos de fin de año\"]")
                .handler((p, s) -> pedidoCreado(pedidos.confirmarEncargo(s, solicitud(p))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("LISPED").casoDeUso(CU)
                .descripcion("Lista pedidos y encargos (el cliente ve los suyos; el personal, todos)")
                .parametros(ParametroSpec.enumeracion("estado", EstadoPedido.class).conComodin())
                .roles(TODOS).ejemplo("LISPED[\"*\"]").handler(this::listar).construir());
        catalogo.registrar(ComandoSpec.comando("VERPED").casoDeUso(CU)
                .descripcion("Muestra un pedido con sus líneas, su entrega y su historial")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30))
                .roles(TODOS).ejemplo("VERPED[\"PED-2026-0011\"]").handler(this::ver).construir());
        catalogo.registrar(ComandoSpec.comando("CANPED").casoDeUso(CU)
                .descripcion("Cancela un pedido mientras no esté despachado (libera sus reservas); el motivo es obligatorio")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.texto("motivo", 255))
                .roles(TODOS).modificaDatos()
                .ejemplo("CANPED[\"PED-2026-0011\",\"Ya no necesito los productos\"]")
                .handler(this::cancelar).construir());
        catalogo.registrar(ComandoSpec.comando("ACEENC").casoDeUso(CU)
                .descripcion("Acepta un encargo PENDIENTE con su fecha estimada y, opcionalmente, la producción que lo respalda. "
                        + "La semilla no trae encargos PENDIENTE: el ejemplo usa el código que devuelve el ejemplo de PEDENC "
                        + "(consúltelo con LISPED[\"PENDIENTE\"])")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.fecha("fecha_estimada"),
                        ParametroSpec.codigo("codigo_produccion", 30).opcional(), ParametroSpec.texto("fundamento", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ACEENC[\"PED-2026-0012\",\"2026-12-20\",\"\",\"Producción PRD-2026-011 prevista para el 2026-12-05 más preparación\"]")
                .handler((p, s) -> pedido("Encargo aceptado", encargos.aceptar(s, p.texto("codigo_pedido"),
                        p.fecha("fecha_estimada"), p.texto("codigo_produccion"), p.texto("fundamento"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("FECENC").casoDeUso(CU)
                .descripcion("Actualiza la fecha estimada de disponibilidad de un encargo aceptado")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.fecha("fecha_estimada"),
                        ParametroSpec.texto("motivo", 255), ParametroSpec.texto("fundamento", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("FECENC[\"PED-2026-0008\",\"2026-12-19\",\"La producción de respaldo se retrasó una semana\","
                        + "\"La productora informó por teléfono la nueva fecha de envasado\"]")
                .handler((p, s) -> pedido("Fecha estimada actualizada", encargos.actualizarFecha(s, p.texto("codigo_pedido"),
                        p.fecha("fecha_estimada"), p.texto("motivo"), p.texto("fundamento"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("VINENC").casoDeUso(CU)
                .descripcion("Vincula la producción que respalda una línea de un encargo PENDIENTE o ESPERANDO_ABASTECIMIENTO "
                        + "(la línea es la del producto de la producción)")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30), ParametroSpec.codigo("codigo_produccion", 30))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("VINENC[\"PED-2026-0012\",\"PRD-2026-011\"]")
                .handler((p, s) -> pedido("Respaldo vinculado", encargos.vincularRespaldo(s, p.texto("codigo_pedido"),
                        p.texto("codigo_produccion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ABAPED").casoDeUso(CU)
                .descripcion("Abastece un encargo en espera (o un pedido confirmado sin cobertura completa) con el stock vendible de FEDER")
                .parametros(ParametroSpec.codigo("codigo_pedido", 30))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("ABAPED[\"PED-2026-0008\"]")
                .handler(this::abastecer)
                .construir());
        catalogo.registrar(ComandoSpec.comando("EXPRES").casoDeUso(CU)
                .descripcion("Expira las reservas de pedidos inmediatos pendientes cuyo plazo de pago venció y los cancela. "
                        + "Nunca rechaza un pago QR o TARJETA: un pedido con uno PENDIENTE no se cancela y se informa "
                        + "para conciliarlo con CONPAG o RECPAG")
                .parametros(ParametroSpec.texto("filtro", 1).conComodin())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("EXPRES[\"*\"]")
                .handler(this::expirar)
                .construir());
    }

    private ResultadoOperacion abastecer(Parametros p, Solicitante s) {
        ServicioEncargos.Abastecimiento a = encargos.abastecer(s, p.texto("codigo_pedido"));
        String nota = a.abastecidos().isEmpty() ? "No hay stock vendible para reservar: el pedido sigue esperando."
                : a.abastecidos().get(0).confirmado() ? "Todas las líneas quedaron cubiertas: el encargo pasó a CONFIRMADO."
                : a.abastecidos().get(0).cubierto() ? "Todas las líneas quedaron cubiertas."
                : "Se reservó una parte; el resto sigue pendiente de abastecimiento.";
        return pedido("Abastecimiento del pedido", a.pedido())
                .detalle("Reservado en esta operación", a.abastecidos().isEmpty() ? "0"
                        : Cantidades.formatear(a.abastecidos().get(0).reservado()))
                .detalle("Nota", nota);
    }

    private ResultadoOperacion expirar(Parametros p, Solicitante s) {
        if (p.tieneValor("filtro")) {
            throw NegocioException.regla("Use EXPRES[\"*\"]: el proceso revisa todos los pedidos pendientes.");
        }
        ServicioPedidos.ResultadoExpiracion r = pedidos.expirarReservasVencidas(s);
        String mensaje = r.expirados().size() + " pedido(s) cancelado(s) por reserva expirada."
                + (r.porConciliar().isEmpty() ? "" : " " + r.porConciliar().size() + " pedido(s) con un pago QR o TARJETA "
                + "PENDIENTE siguen reservados hasta conciliar el pago: el proceso no lo rechaza.");
        return ResultadoOperacion.exito("Expiración de reservas", mensaje)
                .detalle("Pedidos cancelados", r.expirados().isEmpty() ? "Ninguno" : String.join(", ", r.expirados()))
                .detalle("Pedidos con un pago por conciliar (CONPAG o RECPAG)",
                        r.porConciliar().isEmpty() ? "Ninguno" : String.join("; ", r.porConciliar()))
                .detalle("Pedidos que se dejaron como estaban", r.omitidos().isEmpty() ? "Ninguno" : String.join("; ", r.omitidos()));
    }

    private static ParametroSpec[] parametrosPedido() {
        return new ParametroSpec[] {ParametroSpec.enumeracion("modalidad", ModalidadEntrega.class),
                ParametroSpec.texto("direccion", 255).opcional(), ParametroSpec.texto("referencia", 255).opcional(),
                ParametroSpec.texto("nombre_receptor", 120), ParametroSpec.texto("telefono_contacto", 20),
                ParametroSpec.texto("productos", 200).conComodin(), ParametroSpec.decimal("subtotal_esperado"),
                ParametroSpec.texto("observacion", 500).opcional()};
    }

    private static SolicitudPedido solicitud(Parametros p) {
        return new SolicitudPedido(p.enumeracion("modalidad", ModalidadEntrega.class), p.texto("direccion"),
                p.texto("referencia"), p.texto("nombre_receptor"), p.texto("telefono_contacto"),
                p.esComodin("productos") ? Set.of() : codigos(p.texto("productos")), p.decimal("subtotal_esperado"),
                p.texto("observacion"));
    }

    /** "FED-ACE-020, FED-MIE-500" -> {FED-ACE-020, FED-MIE-500}. */
    static Set<String> codigos(String lista) {
        Set<String> codigos = new LinkedHashSet<>();
        for (String parte : lista.split(",")) {
            String codigo = parte.strip().toUpperCase(Locale.ROOT);
            if (!CODIGO.matcher(codigo).matches()) {
                throw new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO, "Parámetro productos: «" + parte.strip()
                        + "» no es un código de producto válido. Separe los códigos con comas o use \"*\".");
            }
            codigos.add(codigo);
        }
        return codigos;
    }

    private ResultadoOperacion listar(Parametros p, Solicitante s) {
        List<PedidoResumen> obtenidos = pedidos.listar(s, p.enumeracion("estado", EstadoPedido.class));
        List<PedidoResumen> lista = Listados.recortar(obtenidos);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Pedidos", List.of("Código", "Fecha", "Cliente", "Tipo",
                "Estado", "Total (Bs)", "Fecha estimada"));
        lista.forEach(r -> tabla.fila(r.pedido().getCodigo(), FechasBolivia.formatear(r.pedido().getFechaCreacion()), r.cliente(),
                r.pedido().getTipo(), r.pedido().getEstado(), Importes.formatear(r.pedido().getTotal()),
                FechasBolivia.formatear(r.pedido().getFechaEstimadaDisponibilidad())));
        return ResultadoOperacion.exito("Pedidos", lista.size() + " pedido(s), del más reciente al más antiguo."
                + Listados.aviso(obtenidos)).tabla(tabla);
    }

    private ResultadoOperacion ver(Parametros p, Solicitante s) {
        ServicioPedidos.PedidoDetallado d = pedidos.ver(s, p.texto("codigo_pedido"));
        ResultadoOperacion r = pedido("Pedido " + d.pedido().pedido().getCodigo(), d.pedido());
        ResultadoOperacion.Tabla historial = new ResultadoOperacion.Tabla("Historial", List.of("Fecha", "Estado anterior",
                "Estado nuevo", "Comentario"));
        d.historial().forEach(h -> historial.fila(FechasBolivia.formatear(h.getFecha()), h.getEstadoAnterior(),
                h.getEstadoNuevo(), h.getComentario()));
        ResultadoOperacion.Tabla reservas = new ResultadoOperacion.Tabla("Reservas de stock", List.of("Reserva", "Cantidad",
                "Estado", "Vence"));
        d.reservas().forEach(x -> reservas.fila(x.getId(), Cantidades.formatear(x.getCantidad()), x.getEstado(),
                FechasBolivia.formatear(x.getFechaExpiracion())));
        return r.tabla(historial).tabla(reservas);
    }

    private ResultadoOperacion cancelar(Parametros p, Solicitante s) {
        ResultadoCancelacion c = pedidos.cancelar(s, p.texto("codigo_pedido"), p.texto("motivo"));
        String reintegro = c.reintegros().isEmpty() ? "Sin pagos cobrados que reintegrar."
                : "Se solicitó el reintegro de Bs " + c.reintegros().stream().map(Importes::formatear).toList() + ".";
        return pedido("Pedido cancelado", c.pedido())
                .detalle("Reservas liberadas", c.reservasLiberadas())
                .detalle("Reintegros", reintegro);
    }

    private static ResultadoOperacion pedidoCreado(ResultadoPedido r) {
        String mensaje = r.yaExistia() ? "Esta confirmación ya había creado el pedido: no se creó otro."
                : r.pedido().pedido().getTipo() == TipoPedido.INMEDIATO
                ? "Sus productos quedaron reservados hasta el plazo de pago indicado."
                : "El encargo no reserva stock: FEDER lo revisará y le informará una fecha estimada, que no es una fecha garantizada.";
        return pedido(r.yaExistia() ? "Pedido ya registrado" : "Pedido registrado", r.pedido()).detalle("Nota", mensaje);
    }

    static ResultadoOperacion pedido(String titulo, PedidoCompleto c) {
        return PresentacionPedido.resumen(titulo, c);
    }
}
