package com.feder.negocio.produccion;

import com.feder.datos.produccion.LoteProduccionRepositorio.LoteResumen;
import com.feder.datos.produccion.ProduccionRepositorio.ProduccionResumen;
import com.feder.datos.produccion.TrazabilidadRepositorio.TrazaLote;
import com.feder.modelo.EstadoProduccion;
import com.feder.modelo.LoteProduccion;
import com.feder.modelo.Produccion;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.usuarios.FormatoNegocio;

import java.util.List;

/** CU3 Producción Agrícola, asignación de lotes y trazabilidad de origen. */
public final class ModuloProduccion implements ModuloComandos {

    private static final String CU = "CU3";
    private static final TipoRol[] CONSULTA = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER, TipoRol.PRODUCTOR};

    private final ServicioProduccion produccion;
    private final ServicioLotes lotes;
    private final ServicioTrazabilidad trazabilidad;
    private final ServicioCorreccionOrigen correcciones;

    public ModuloProduccion(ContextoNegocio ctx) {
        this.produccion = new ServicioProduccion(ctx);
        this.lotes = new ServicioLotes(ctx);
        this.trazabilidad = new ServicioTrazabilidad(ctx);
        this.correcciones = new ServicioCorreccionOrigen(ctx);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        registrarAltas(catalogo);
        registrarCambios(catalogo);
        registrarLotes(catalogo);
        registrarConsultas(catalogo);
    }

    private void registrarAltas(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("INSPCN").casoDeUso(CU)
                .descripcion("Registra una producción PLANIFICADA propia; estimaciones y ubicación solo si se conocen")
                .parametros(ParametroSpec.codigo("producto", 30), ParametroSpec.fecha("fecha_inicio"),
                        ParametroSpec.fecha("fecha_disponibilidad_estimada").opcional(),
                        ParametroSpec.decimal("cantidad_estimada").opcional(),
                        ParametroSpec.texto("ubicacion_produccion", 200).opcional(), ParametroSpec.texto("observacion", 1000).opcional())
                .roles(TipoRol.PRODUCTOR).modificaDatos()
                .ejemplo("INSPCN[\"FED-ACE-100\",\"2026-12-01\",\"2027-01-15\",\"100\",\"Taller familiar de extracción de aceite\",\"\"]")
                .handler(this::registrarPlanificada).construir());
        catalogo.registrar(ComandoSpec.comando("INSTER").casoDeUso(CU)
                .descripcion("Registra un producto ya elaborado: producción TERMINADA con su primer lote; comunidad vacía = la suya; "
                        + "fecha de inicio y observación solo si se conocen")
                .parametros(ParametroSpec.codigo("producto", 30), ParametroSpec.fecha("fecha_elaboracion").opcional(),
                        ParametroSpec.fecha("fecha_vencimiento").opcional(), ParametroSpec.decimal("cantidad_producida"),
                        ParametroSpec.decimal("cantidad_ofrecida"), ParametroSpec.decimal("precio_ofrecido").opcional(),
                        ParametroSpec.texto("ubicacion_produccion", 200).opcional(), ParametroSpec.entero("comunidad_origen").opcional(),
                        ParametroSpec.fecha("fecha_inicio").opcional(), ParametroSpec.texto("observacion", 1000).opcional())
                .roles(TipoRol.PRODUCTOR).modificaDatos()
                .ejemplo("INSTER[\"FED-JAB-090\",\"2026-11-10\",\"\",\"200\",\"150\",\"9.50\",\"Taller familiar de extracción de aceite\","
                        + "\"\",\"2026-11-01\",\"Jabón de aceite de totaí curado durante nueve días\"]")
                .handler(this::registrarTerminada).construir());
        catalogo.registrar(ComandoSpec.comando("VALORI").casoDeUso(CU)
                .descripcion("Valida, antes del registro, que un productor elaboró en otra comunidad en la fecha indicada")
                .parametros(ParametroSpec.entero("productor"), ParametroSpec.entero("comunidad"), ParametroSpec.fecha("fecha_elaboracion"),
                        ParametroSpec.texto("motivo", 255), ParametroSpec.texto("evidencia", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("VALORI[\"2\",\"1\",\"2026-10-01\",\"Trabajó en el taller de Totaizal hasta octubre\",\"Acta comunal 14 del 2026-09-30\"]")
                .handler((p, s) -> ResultadoOperacion.exito("Origen validado", "El productor ya puede registrar esa producción "
                                + "con INSTER indicando esta comunidad y esta fecha de elaboración.")
                        .detalle("Comunidad", correcciones.validarOrigen(s, p.entero("productor"), p.entero("comunidad"),
                                p.fecha("fecha_elaboracion"), p.texto("motivo"), p.texto("evidencia")).getNombre())
                        .detalle("Fecha de elaboración", p.fecha("fecha_elaboracion")))
                .construir());
    }

    private void registrarCambios(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("ESTPCN").casoDeUso(CU)
                .descripcion("Cambia el estado de su producción: EN_CURSO, TERMINADA o CANCELADA (con motivo)")
                .parametros(ParametroSpec.codigo("produccion", 30), ParametroSpec.enumeracion("estado", EstadoProduccion.class),
                        ParametroSpec.texto("motivo", 255).opcional())
                .roles(TipoRol.PRODUCTOR).modificaDatos()
                .ejemplo("ESTPCN[\"PRD-2026-012\",\"EN_CURSO\",\"\"]")
                .handler((p, s) -> cambio("Estado de la producción actualizado", produccion.cambiarEstado(s, p.texto("produccion"),
                        p.enumeracion("estado", EstadoProduccion.class), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("MODPCN").casoDeUso(CU)
                .descripcion("Modifica fechas, estimación, ubicación u observación de su producción; \"\" = sin cambio")
                .parametros(ParametroSpec.codigo("produccion", 30), ParametroSpec.fecha("fecha_inicio").opcional(),
                        ParametroSpec.fecha("fecha_disponibilidad_estimada").opcional(),
                        ParametroSpec.decimal("cantidad_estimada").opcional(),
                        ParametroSpec.texto("ubicacion_produccion", 200).opcional(), ParametroSpec.texto("observacion", 1000).opcional())
                .roles(TipoRol.PRODUCTOR).modificaDatos()
                .ejemplo("MODPCN[\"PRD-2026-011\",\"\",\"2026-12-15\",\"\",\"\",\"La extracción se retrasó por las lluvias\"]")
                .handler((p, s) -> cambio("Producción modificada", produccion.modificar(s, p.texto("produccion"),
                        new ServicioProduccion.Cambios(p.fecha("fecha_inicio"), p.fecha("fecha_disponibilidad_estimada"),
                                p.decimal("cantidad_estimada"), p.texto("ubicacion_produccion"), p.texto("observacion")))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("CORPCN").casoDeUso(CU)
                .descripcion("Corrige con motivo la ubicación de elaboración o la instantánea de origen de una producción")
                .parametros(ParametroSpec.codigo("produccion", 30), ParametroSpec.enumeracion("campo", CampoCorregible.class),
                        ParametroSpec.texto("valor", 200), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("CORPCN[\"PRD-2026-003\",\"UBICACION_PRODUCCION\",\"Sala de envasado nueva del apiario\",\"Error al registrar la sala\"]")
                .handler((p, s) -> detalleProduccion("Producción corregida", correcciones.corregir(s, p.texto("produccion"),
                        p.enumeracion("campo", CampoCorregible.class), p.texto("valor"), p.texto("motivo"))))
                .construir());
    }

    private void registrarLotes(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("INSLOT").casoDeUso(CU)
                .descripcion("Asigna un lote de origen a su producción EN_CURSO o TERMINADA")
                .parametros(ParametroSpec.codigo("produccion", 30), ParametroSpec.fecha("fecha_elaboracion").opcional(),
                        ParametroSpec.fecha("fecha_vencimiento").opcional(), ParametroSpec.decimal("cantidad_producida"),
                        ParametroSpec.decimal("cantidad_ofrecida"), ParametroSpec.decimal("precio_ofrecido").opcional())
                .roles(TipoRol.PRODUCTOR).modificaDatos()
                .ejemplo("INSLOT[\"PRD-2026-011\",\"2026-11-12\",\"2027-11-12\",\"30\",\"25\",\"60.00\"]")
                .handler((p, s) -> detalleLote("Lote registrado", lotes.registrar(s, p.texto("produccion"),
                        new ServicioLotes.DatosLote(p.fecha("fecha_elaboracion"), p.fecha("fecha_vencimiento"),
                                p.decimal("cantidad_producida"), p.decimal("cantidad_ofrecida"), p.decimal("precio_ofrecido"))),
                        p.texto("produccion")))
                .construir());
        catalogo.registrar(ComandoSpec.comando("MODLOT").casoDeUso(CU)
                .descripcion("Corrige un lote; tras su compra, elaboración, vencimiento y cantidad producida solo los corrige el ADMINISTRADOR")
                .parametros(ParametroSpec.codigo("lote", 30), ParametroSpec.fecha("fecha_elaboracion").opcional(),
                        ParametroSpec.fecha("fecha_vencimiento").opcional(), ParametroSpec.decimal("cantidad_producida").opcional(),
                        ParametroSpec.decimal("cantidad_ofrecida").opcional(), ParametroSpec.decimal("precio_ofrecido").opcional(),
                        ParametroSpec.texto("motivo", 255).opcional())
                .roles(TipoRol.PRODUCTOR, TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("MODLOT[\"LP-2026-011\",\"\",\"\",\"\",\"35\",\"\",\"Parte del lote se reservó para una feria\"]")
                .handler((p, s) -> filaLote("Lote modificado", lotes.modificar(s, p.texto("lote"),
                        new ServicioLotes.CambiosLote(p.fecha("fecha_elaboracion"), p.fecha("fecha_vencimiento"),
                                p.decimal("cantidad_producida"), p.decimal("cantidad_ofrecida"), p.decimal("precio_ofrecido"),
                                p.texto("motivo")))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("RETLOT").casoDeUso(CU)
                .descripcion("Retira de la oferta a FEDER un lote DISPONIBLE propio")
                .parametros(ParametroSpec.codigo("lote", 30), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.PRODUCTOR).modificaDatos()
                .ejemplo("RETLOT[\"LP-2026-002\",\"El lote se destinó a una feria local\"]")
                .handler((p, s) -> filaLote("Lote retirado", lotes.retirar(s, p.texto("lote"), p.texto("motivo"))))
                .construir());
    }

    private void registrarConsultas(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("LISPCN").casoDeUso(CU)
                .descripcion("Lista producciones (el productor ve solo las suyas), opcionalmente por estado")
                .parametros(ParametroSpec.enumeracion("estado", EstadoProduccion.class).conComodin())
                .roles(CONSULTA)
                .ejemplo("LISPCN[\"*\"]")
                .handler(this::listarProducciones).construir());
        catalogo.registrar(ComandoSpec.comando("VERPCN").casoDeUso(CU)
                .descripcion("Muestra una producción con sus lotes y su balance de cantidades")
                .parametros(ParametroSpec.codigo("produccion", 30))
                .roles(CONSULTA)
                .ejemplo("VERPCN[\"PRD-2026-011\"]")
                .handler(this::verProduccion).construir());
        catalogo.registrar(ComandoSpec.comando("LISLOT").casoDeUso(CU)
                .descripcion("Lista lotes de origen (el productor ve solo los suyos), de una producción o de todas")
                .parametros(ParametroSpec.codigo("produccion", 30).conComodin())
                .roles(CONSULTA)
                .ejemplo("LISLOT[\"PRD-2026-005\"]")
                .handler((p, s) -> listadoLotes("Lotes de origen", lotes.listar(s, p.texto("produccion")))).construir());
        catalogo.registrar(ComandoSpec.comando("TRZLOT").casoDeUso(CU)
                .descripcion("Trazabilidad de origen de un lote: producto, producción y comunidad de procedencia")
                .parametros(ParametroSpec.codigo("lote", 30))
                .publico()
                .ejemplo("TRZLOT[\"LP-2026-001\"]")
                .handler((p, s) -> trazar(trazabilidad.trazar(s, p.texto("lote")))).construir());
    }

    private ResultadoOperacion registrarPlanificada(Parametros p, Solicitante s) {
        ServicioProduccion.Alta alta = produccion.registrarPlanificada(s, new ServicioProduccion.DatosPlan(p.texto("producto"),
                p.fecha("fecha_inicio"), p.fecha("fecha_disponibilidad_estimada"), p.decimal("cantidad_estimada"),
                p.texto("ubicacion_produccion"), p.texto("observacion")));
        return detalleProduccion("Producción registrada", alta.produccion()).detalle("Procedencia", alta.origen());
    }

    private ResultadoOperacion registrarTerminada(Parametros p, Solicitante s) {
        ServicioLotes.DatosLote lote = new ServicioLotes.DatosLote(p.fecha("fecha_elaboracion"), p.fecha("fecha_vencimiento"),
                p.decimal("cantidad_producida"), p.decimal("cantidad_ofrecida"), p.decimal("precio_ofrecido"));
        ServicioProduccion.Alta alta = produccion.registrarTerminada(s, new ServicioProduccion.DatosTerminada(p.texto("producto"),
                lote, p.texto("ubicacion_produccion"), p.entero("comunidad_origen"), p.fecha("fecha_inicio"), p.texto("observacion")));
        return detalleProduccion("Producción terminada registrada", alta.produccion())
                .detalle("Procedencia", alta.origen())
                .detalle("Lote asignado", alta.lote().getCodigoLote());
    }

    private ResultadoOperacion listarProducciones(Parametros p, Solicitante s) {
        List<ProduccionResumen> obtenidas = produccion.listar(s, p.enumeracion("estado", EstadoProduccion.class));
        List<ProduccionResumen> lista = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Producciones",
                List.of("Código", "Producto", "Productor", "Estado", "Inicio", "Disponible aprox.", "Cantidad estimada",
                        "Comunidad de origen"));
        for (ProduccionResumen r : lista) {
            Produccion pr = r.produccion();
            tabla.fila(pr.getCodigo(), r.nombreProducto(), r.codigoProductor(), pr.getEstado(), FormatoNegocio.fecha(pr.getFechaInicio()),
                    FormatoNegocio.fecha(pr.getFechaDisponibilidadEstimada()), FormatoNegocio.cantidad(pr.getCantidadEstimada()),
                    pr.getNombreComunidadOrigen());
        }
        return ResultadoOperacion.exito("Producciones", lista.size() + " producción(es)." + Listados.aviso(obtenidas)).tabla(tabla);
    }

    private ResultadoOperacion verProduccion(Parametros p, Solicitante s) {
        ServicioProduccion.Detalle d = produccion.consultar(s, p.texto("produccion"));
        BalanceProduccion b = d.balance();
        ResultadoOperacion resultado = detalleProduccion("Producción " + d.resumen().produccion().getCodigo(), d.resumen())
                .detalle("Compromiso vigente con encargos", FormatoNegocio.cantidad(b.compromisoVigente()))
                .detalle("Adquirida por FEDER", FormatoNegocio.cantidad(b.adquirida()))
                .detalle("Recibida por FEDER", FormatoNegocio.cantidad(b.recibida()))
                .detalle("En tránsito", FormatoNegocio.cantidad(b.enTransito()))
                .detalle("Pendiente de adquirir", FormatoNegocio.cantidad(b.pendienteDeAdquirir()));
        return resultado.tabla(tablaLotes("Lotes", d.lotes()));
    }

    private static ResultadoOperacion cambio(String titulo, ServicioProduccion.Cambio cambio) {
        String mensaje = cambio.encargosSinFecha() == 0 ? ""
                : cambio.encargosSinFecha() + " encargo(s) respaldado(s) quedaron sin fecha estimada y sus clientes fueron notificados.";
        return detalleProduccion(titulo, cambio.produccion()).detalle("Encargos sin fecha estimada", cambio.encargosSinFecha())
                .detalle("Aviso", mensaje);
    }

    /** Vista del personal interno o del titular: incluye productor y ubicación (nunca llega a clientes). */
    private static ResultadoOperacion detalleProduccion(String titulo, ProduccionResumen r) {
        Produccion p = r.produccion();
        return ResultadoOperacion.exito(titulo, "")
                .detalle("Código", p.getCodigo())
                .detalle("Producto", r.codigoProducto() + " - " + r.nombreProducto() + " (" + r.unidad() + ")")
                .detalle("Estado", p.getEstado())
                .detalle("Inicio", FormatoNegocio.fecha(p.getFechaInicio()))
                .detalle("Disponibilidad estimada", FormatoNegocio.fecha(p.getFechaDisponibilidadEstimada()))
                .detalle("Cantidad estimada", FormatoNegocio.cantidad(p.getCantidadEstimada()))
                .detalle("Comunidad de origen", origen(p, r.comunidadOrigenActiva()))
                .detalle("Productor", r.codigoProductor() + " - " + r.nombreProductor())
                .detalle("Ubicación de la producción", p.getUbicacionProduccion())
                .detalle("Observación", p.getObservacion());
    }

    private static ResultadoOperacion detalleLote(String titulo, LoteProduccion l, String codigoProduccion) {
        return ResultadoOperacion.exito(titulo, "")
                .detalle("Código de lote", l.getCodigoLote())
                .detalle("Producción", codigoProduccion)
                .detalle("Elaboración", FormatoNegocio.fecha(l.getFechaElaboracion()))
                .detalle("Vencimiento", FormatoNegocio.fecha(l.getFechaVencimiento()))
                .detalle("Cantidad producida", FormatoNegocio.cantidad(l.getCantidadProducida()))
                .detalle("Cantidad ofrecida", FormatoNegocio.cantidad(l.getCantidadOfrecida()))
                .detalle("Precio ofrecido (Bs)", FormatoNegocio.dinero(l.getPrecioOfrecido()))
                .detalle("Estado", l.getEstado());
    }

    private static ResultadoOperacion filaLote(String titulo, LoteResumen r) {
        return detalleLote(titulo, r.lote(), r.codigoProduccion())
                .detalle("Comprometido en compras", FormatoNegocio.cantidad(r.comprometido()));
    }

    private static ResultadoOperacion listadoLotes(String titulo, List<LoteResumen> obtenidos) {
        List<LoteResumen> lista = Listados.recortar(obtenidos);
        return ResultadoOperacion.exito(titulo, lista.size() + " lote(s)." + Listados.aviso(obtenidos)).tabla(tablaLotes(titulo, lista));
    }

    private static ResultadoOperacion.Tabla tablaLotes(String titulo, List<LoteResumen> lista) {
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla(titulo, List.of("Lote", "Producción", "Producto",
                "Elaboración", "Vencimiento", "Producida", "Ofrecida", "Comprometida", "Precio (Bs)", "Estado"));
        for (LoteResumen r : lista) {
            LoteProduccion l = r.lote();
            tabla.fila(l.getCodigoLote(), r.codigoProduccion(), r.nombreProducto(), FormatoNegocio.fecha(l.getFechaElaboracion()),
                    FormatoNegocio.fecha(l.getFechaVencimiento()), FormatoNegocio.cantidad(l.getCantidadProducida()),
                    FormatoNegocio.cantidad(l.getCantidadOfrecida()), FormatoNegocio.cantidad(r.comprometido()),
                    FormatoNegocio.dinero(l.getPrecioOfrecido()), l.getEstado());
        }
        return tabla;
    }

    private static ResultadoOperacion trazar(ServicioTrazabilidad.Trazabilidad t) {
        TrazaLote traza = t.traza();
        LoteProduccion l = traza.lote();
        Produccion p = traza.produccion();
        ResultadoOperacion r = ResultadoOperacion.exito("Trazabilidad del lote " + l.getCodigoLote(),
                        "El origen se muestra con los datos guardados al registrar la producción. Es una procedencia declarada "
                                + "y validada, no comprobada por el sistema.")
                .detalle("Producto", traza.codigoProducto() + " - " + traza.nombreProducto())
                .detalle("Producción", p.getCodigo() + " (" + p.getEstado() + ")")
                .detalle("Elaboración", FormatoNegocio.fecha(l.getFechaElaboracion()))
                .detalle("Vencimiento", FormatoNegocio.fecha(l.getFechaVencimiento()))
                .detalle("Comunidad de origen", origen(p, traza.comunidadOrigenActiva()))
                .detalle("Procedencia", t.procedencia());
        if (t.vistaCompleta()) {
            r.detalle("Productor", traza.codigoProductor() + " - " + traza.nombreProductor())
                    .detalle("Ubicación de la producción", p.getUbicacionProduccion())
                    .detalle("Cantidad producida", FormatoNegocio.cantidad(l.getCantidadProducida()))
                    .detalle("Cantidad ofrecida", FormatoNegocio.cantidad(l.getCantidadOfrecida()))
                    .detalle("Comprometido en compras de FEDER", FormatoNegocio.cantidad(traza.compradoPorFeder()))
                    .detalle("Recibido por FEDER", FormatoNegocio.cantidad(traza.recibidoPorFeder()))
                    .detalle("Estado del lote", l.getEstado());
        }
        return r;
    }

    private static String origen(Produccion p, boolean comunidadActiva) {
        return p.getNombreComunidadOrigen() + ", " + p.getMunicipioOrigen() + " (" + p.getDepartamentoOrigen() + ")"
                + (comunidadActiva ? "" : " - la comunidad está actualmente inactiva");
    }
}
