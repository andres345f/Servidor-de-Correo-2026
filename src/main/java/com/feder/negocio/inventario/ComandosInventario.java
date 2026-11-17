package com.feder.negocio.inventario;

import com.feder.datos.inventario.LoteFederVista;
import com.feder.datos.inventario.MovimientoRepositorio.MovimientoConLote;
import com.feder.datos.inventario.StockRepositorio.ResumenStock;
import com.feder.modelo.MovimientoInventario;
import com.feder.modelo.TipoMovimiento;
import com.feder.modelo.TipoRol;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

import java.time.LocalDate;
import java.util.List;

/** Comandos de CU4 · Inventario de FEDER: stock, lotes, movimientos, ajustes y alertas. */
final class ComandosInventario {

    private static final String CU = "CU4";
    private static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};

    private final ServicioInventario inventario;

    ComandosInventario(ServicioInventario inventario) {
        this.inventario = inventario;
    }

    void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("LISSTK").casoDeUso(CU)
                .descripcion("Muestra por producto la existencia física, lo reservado, el stock vendible y la alerta")
                .parametros(ParametroSpec.texto("filtro", 60).conComodin()).roles(PERSONAL)
                .ejemplo("LISSTK[\"*\"]").handler((p, s) -> stock(p, false)).construir());
        catalogo.registrar(ComandoSpec.comando("ALESTK").casoDeUso(CU)
                .descripcion("Lista los productos activos agotados o bajo su stock mínimo")
                .parametros(ParametroSpec.texto("filtro", 60).conComodin()).roles(PERSONAL)
                .ejemplo("ALESTK[\"*\"]").handler((p, s) -> stock(p, true)).construir());
        catalogo.registrar(ComandoSpec.comando("LISLTF").casoDeUso(CU)
                .descripcion("Lista los lotes de FEDER con saldo, reservado, estado, ubicación y vencimiento de origen")
                .parametros(ParametroSpec.texto("filtro", 60).conComodin()).roles(PERSONAL)
                .ejemplo("LISLTF[\"FED-MIE\"]").handler(this::lotes).construir());
        catalogo.registrar(ComandoSpec.comando("LISMOV").casoDeUso(CU)
                .descripcion("Muestra los movimientos de inventario de un lote de FEDER (\"*\" = de todos los lotes, del más reciente al más antiguo)")
                .parametros(ParametroSpec.codigo("codigo_lote", 30).conComodin()).roles(PERSONAL)
                .ejemplo("LISMOV[\"LF-2026-001\"]").handler(this::movimientos).construir());
        catalogo.registrar(ComandoSpec.comando("AJUSTK").casoDeUso(CU)
                .descripcion("Registra un ajuste o una baja de un lote con motivo (AJUSTE_POSITIVO solo ADMINISTRADOR, con evidencia)")
                .parametros(ParametroSpec.codigo("codigo_lote", 30), ParametroSpec.enumeracion("tipo", TipoMovimiento.class),
                        ParametroSpec.decimal("cantidad"), ParametroSpec.texto("motivo", 255),
                        ParametroSpec.codigo("clave_operacion", 64), ParametroSpec.texto("evidencia", 255).opcional())
                .roles(PERSONAL).modificaDatos()
                .ejemplo("AJUSTK[\"LF-2026-001\",\"BAJA_DANADO\",\"2\",\"Dos botellas rotas al reordenar el estante\","
                        + "\"BAJA-LF001-20261115\",\"\"]")
                .handler(this::ajustar).construir());
        catalogo.registrar(ComandoSpec.comando("LISVEN").casoDeUso(CU)
                .descripcion("Lista los lotes con saldo que vencen en los próximos N días y los ya vencidos pendientes de baja "
                        + "(\"*\" = todos los lotes con saldo, por vencimiento)")
                .parametros(ParametroSpec.entero("dias").conComodin()).roles(PERSONAL)
                .ejemplo("LISVEN[\"30\"]").handler(this::porVencer).construir());
        catalogo.registrar(ComandoSpec.comando("BLQLTF").casoDeUso(CU)
                .descripcion("Bloquea un lote de FEDER (mercancía dañada o error de origen): libera sus reservas y las reasigna con FEFO")
                .parametros(ParametroSpec.codigo("codigo_lote", 30), ParametroSpec.texto("motivo", 255))
                .roles(PERSONAL).modificaDatos()
                .ejemplo("BLQLTF[\"LF-2026-008\",\"Humedad en las bolsas detectada en la revisión del almacén\"]")
                .handler((p, s) -> inhabilitado("Lote bloqueado",
                        inventario.bloquearLote(s, p.texto("codigo_lote"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("VENLTF").casoDeUso(CU)
                .descripcion("Proceso diario: pasa a VENCIDO los lotes cuyo origen venció, libera sus reservas y las reasigna con FEFO")
                .parametros(ParametroSpec.texto("filtro", 1).conComodin()).roles(PERSONAL).modificaDatos()
                .ejemplo("VENLTF[\"*\"]").handler(this::vencer).construir());
    }

    private ResultadoOperacion porVencer(Parametros p, Solicitante s) {
        if (p.esComodin("dias")) {
            return todosPorVencimiento();
        }
        long dias = p.entero("dias");
        List<LoteFederVista> obtenidos = inventario.lotesPorVencer((int) Math.min(dias, Integer.MAX_VALUE));
        List<LoteFederVista> lista = Listados.recortar(obtenidos);
        LocalDate hoy = inventario.stock().hoy();
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Lotes por vencimiento", List.of("Lote", "Producto",
                "Saldo", "Reservado", "Estado", "Vence", "Situación"));
        lista.forEach(l -> tabla.fila(l.codigo(), l.codigoProducto() + " " + l.nombreProducto(),
                Cantidades.formatear(l.disponible()) + " " + l.unidad(), Cantidades.formatear(l.reservada()), l.estado(),
                l.fechaVencimiento().toString(), l.vencido(hoy) ? "Vencido: pendiente de baja (BAJA_VENCIDO)" : "Próximo a vencer"));
        return ResultadoOperacion.exito("Vencimientos de lotes", lista.size() + " lote(s) con saldo que vencen hasta el "
                + hoy.plusDays(dias) + " o ya vencidos." + Listados.aviso(obtenidos)).tabla(tabla);
    }

    private ResultadoOperacion todosPorVencimiento() {
        List<LoteFederVista> obtenidos = inventario.lotesConSaldoPorVencimiento(Listados.A_PEDIR);
        List<LoteFederVista> lista = Listados.recortar(obtenidos);
        LocalDate hoy = inventario.stock().hoy();
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Lotes por vencimiento", List.of("Lote", "Producto",
                "Saldo", "Reservado", "Estado", "Vence", "Situación"));
        lista.forEach(l -> tabla.fila(l.codigo(), l.codigoProducto() + " " + l.nombreProducto(),
                Cantidades.formatear(l.disponible()) + " " + l.unidad(), Cantidades.formatear(l.reservada()), l.estado(),
                l.fechaVencimiento() == null ? "Sin vencimiento" : l.fechaVencimiento().toString(),
                l.vencido(hoy) ? "Vencido: pendiente de baja (BAJA_VENCIDO)" : "Vigente"));
        return ResultadoOperacion.exito("Vencimientos de lotes", lista.size()
                + " lote(s) con saldo, del vencimiento más cercano al más lejano; al final, los que no vencen."
                + Listados.aviso(obtenidos)).tabla(tabla);
    }

    private ResultadoOperacion vencer(Parametros p, Solicitante s) {
        if (p.tieneValor("filtro")) {
            throw NegocioException.regla("Use VENLTF[\"*\"]: el proceso revisa todos los lotes.");
        }
        List<LoteInhabilitado> vencidos = inventario.vencerLotes(s);
        ResultadoOperacion.Tabla tabla = tablaInhabilitados(vencidos);
        return ResultadoOperacion.exito("Proceso de vencimiento", vencidos.size() + " lote(s) pasaron a VENCIDO.").tabla(tabla);
    }

    private static ResultadoOperacion inhabilitado(String titulo, LoteInhabilitado r) {
        return ResultadoOperacion.exito(titulo, "Operación registrada en la bitácora.")
                .detalle("Lote", r.lote().codigo() + " (" + r.lote().codigoProducto() + ")")
                .detalle("Estado", r.estadoAnterior() + " -> " + r.lote().estado())
                .detalle("Reservas liberadas", r.reservasLiberadas())
                .detalle("Cantidad liberada", Cantidades.formatear(r.liberado()))
                .detalle("Cantidad reasignada desde otros lotes", Cantidades.formatear(r.reasignado()))
                .detalle("Pedidos sin cobertura completa", r.pedidosSinCobertura().isEmpty() ? "Ninguno"
                        : String.join(", ", r.pedidosSinCobertura()) + " (abastézcalos con ABAPED cuando haya stock o cancélelos)");
    }

    private static ResultadoOperacion.Tabla tablaInhabilitados(List<LoteInhabilitado> lista) {
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Lotes vencidos", List.of("Lote", "Producto", "Saldo",
                "Reservas liberadas", "Reasignado", "Pedidos sin cobertura"));
        lista.forEach(r -> tabla.fila(r.lote().codigo(), r.lote().codigoProducto(),
                Cantidades.formatear(r.lote().disponible()) + " " + r.lote().unidad(), r.reservasLiberadas(),
                Cantidades.formatear(r.reasignado()), String.join(", ", r.pedidosSinCobertura())));
        return tabla;
    }

    private ResultadoOperacion stock(Parametros p, boolean soloAlertas) {
        List<ResumenStock> obtenidas = inventario.resumenStock(p.tieneValor("filtro") ? p.texto("filtro") : null).stream()
                .filter(r -> !soloAlertas || alerta(r) != null).toList();
        List<ResumenStock> filas = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla(soloAlertas ? "Productos en alerta" : "Stock por producto",
                List.of("Código", "Producto", "Unidad", "Existencia física", "Reservado", "Stock vendible", "Stock mínimo", "Alerta"));
        filas.forEach(r -> tabla.fila(r.codigo(), r.nombre(), r.unidad(), Cantidades.formatear(r.existencia()),
                Cantidades.formatear(r.reservado()), Cantidades.formatear(r.vendible()), Cantidades.formatear(r.stockMinimo()),
                alerta(r) == null ? (r.activo() ? "" : "Inactivo") : alerta(r)));
        String mensaje = "Stock vendible = saldo menos reservado de los lotes DISPONIBLE y no vencidos.";
        return ResultadoOperacion.exito(soloAlertas ? "Alertas de stock" : "Stock de FEDER",
                filas.size() + " producto(s). " + mensaje + Listados.aviso(obtenidas)).tabla(tabla);
    }

    /** R070: «agotado» si el vendible es 0 y «bajo mínimo» si es menor que stock_minimo; solo productos activos. */
    private static String alerta(ResumenStock r) {
        if (!r.activo()) {
            return null;
        }
        if (r.vendible().signum() == 0) {
            return "Agotado";
        }
        return r.vendible().compareTo(r.stockMinimo()) < 0 ? "Bajo mínimo" : null;
    }

    private ResultadoOperacion lotes(Parametros p, Solicitante s) {
        List<LoteFederVista> obtenidos = inventario.listarLotes(p.tieneValor("filtro") ? p.texto("filtro") : null, null);
        List<LoteFederVista> lista = Listados.recortar(obtenidos);
        LocalDate hoy = inventario.stock().hoy();
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Lotes de FEDER", List.of("Lote", "Producto", "Lote de origen",
                "Ingreso", "Saldo", "Reservado", "Estado", "Ubicación", "Vence"));
        lista.forEach(l -> tabla.fila(l.codigo(), l.codigoProducto() + " " + l.nombreProducto(), l.codigoLoteOrigen(),
                FechasBolivia.formatear(l.fechaIngreso()), Cantidades.formatear(l.disponible()) + " " + l.unidad(),
                Cantidades.formatear(l.reservada()), l.estado(), l.ubicacion(),
                l.fechaVencimiento() == null ? "Sin vencimiento declarado"
                        : l.fechaVencimiento() + (l.vencido(hoy) ? " (vencido: no se vende)" : "")));
        return ResultadoOperacion.exito("Lotes de FEDER", lista.size() + " lote(s)." + Listados.aviso(obtenidos)).tabla(tabla);
    }

    private ResultadoOperacion movimientos(Parametros p, Solicitante s) {
        if (p.esComodin("codigo_lote")) {
            return movimientosDeTodos();
        }
        ServicioInventario.LoteConMovimientos x = inventario.movimientosDeLote(p.texto("codigo_lote"));
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Movimientos", List.of("Fecha", "Tipo", "Cantidad",
                "Reserva", "Devolución", "Motivo", "Clave de operación"));
        x.movimientos().forEach(m -> tabla.fila(FechasBolivia.formatear(m.getFecha()), m.getTipo(),
                Cantidades.formatear(m.getCantidad()), m.getReservaId(), m.getDevolucionId(), m.getMotivo(), m.getClaveOperacion()));
        return ResultadoOperacion.exito("Movimientos del lote " + x.lote().codigo(),
                        x.lote().codigoProducto() + " " + x.lote().nombreProducto() + ". Los movimientos no se modifican ni se borran.")
                .detalle("Saldo actual", Cantidades.formatear(x.lote().disponible()) + " " + x.lote().unidad())
                .detalle("Reservado", Cantidades.formatear(x.lote().reservada()))
                .detalle("Estado", x.lote().estado())
                .tabla(tabla);
    }

    private ResultadoOperacion movimientosDeTodos() {
        List<MovimientoConLote> obtenidos = inventario.movimientosRecientes(Listados.A_PEDIR);
        List<MovimientoConLote> lista = Listados.recortar(obtenidos);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Movimientos", List.of("Fecha", "Lote", "Tipo", "Cantidad",
                "Reserva", "Devolución", "Motivo", "Clave de operación"));
        lista.forEach(x -> {
            MovimientoInventario m = x.movimiento();
            tabla.fila(FechasBolivia.formatear(m.getFecha()), x.codigoLote(), m.getTipo(), Cantidades.formatear(m.getCantidad()),
                    m.getReservaId(), m.getDevolucionId(), m.getMotivo(), m.getClaveOperacion());
        });
        return ResultadoOperacion.exito("Movimientos de inventario", lista.size()
                + " movimiento(s), del más reciente al más antiguo. Los movimientos no se modifican ni se borran."
                + Listados.aviso(obtenidos)).tabla(tabla);
    }

    private ResultadoOperacion ajustar(Parametros p, Solicitante s) {
        MovimientoInventario m = inventario.registrarAjuste(s, new SolicitudAjuste(p.texto("codigo_lote"),
                p.enumeracion("tipo", TipoMovimiento.class), p.decimal("cantidad"), p.texto("motivo"),
                p.texto("clave_operacion"), p.texto("evidencia")));
        return ResultadoOperacion.exito("Movimiento de inventario registrado", "Operación registrada en la bitácora.")
                .detalle("Lote", p.texto("codigo_lote")).detalle("Tipo", m.getTipo())
                .detalle("Cantidad", Cantidades.formatear(m.getCantidad())).detalle("Motivo", m.getMotivo())
                .detalle("Clave de operación", m.getClaveOperacion());
    }
}
