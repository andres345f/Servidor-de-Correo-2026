package com.feder.negocio.produccion;

import com.feder.datos.produccion.LoteProduccionRepositorio;
import com.feder.datos.produccion.LoteProduccionRepositorio.LoteResumen;
import com.feder.datos.produccion.ProduccionRepositorio;
import com.feder.datos.produccion.ProduccionRepositorio.ProduccionResumen;
import com.feder.datos.produccion.ProductoReferenciaRepositorio;
import com.feder.datos.produccion.ProductoReferenciaRepositorio.ProductoReferencia;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoProduccion;
import com.feder.modelo.LoteProduccion;
import com.feder.modelo.Produccion;
import com.feder.modelo.Productor;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.usuarios.CambiosBitacora;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.negocio.usuarios.ValidadorTexto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Producción agrícola (CU3): alta planificada o directa como TERMINADA, cambios de estado, edición del plan,
 * consultas y balance. Registrar producción no crea inventario de FEDER: es disponibilidad en origen (R061).
 * Las correcciones del ADMINISTRADOR están en {@link ServicioCorreccionOrigen}.
 */
public class ServicioProduccion {

    private static final String ENTIDAD = "PRODUCCION";

    /** Alta planificada: PLANIFICADA con fecha de inicio; estimaciones y ubicación solo si se conocen. */
    public record DatosPlan(String codigoProducto, LocalDate fechaInicio, LocalDate fechaDisponibilidad,
                            BigDecimal cantidadEstimada, String ubicacion, String observacion) {
    }

    /**
     * Alta de un producto ya elaborado: TERMINADA con su primer lote, sin simular etapas ni estimaciones. La fecha
     * de inicio y la observación se registran solo si el productor las conoce (nulas en otro caso).
     */
    public record DatosTerminada(String codigoProducto, ServicioLotes.DatosLote lote, String ubicacion, Long comunidadOrigenId,
                                 LocalDate fechaInicio, String observacion) {
    }

    /** Cambios del plan; nulo significa «sin cambio». */
    public record Cambios(LocalDate fechaInicio, LocalDate fechaDisponibilidad, BigDecimal cantidadEstimada, String ubicacion,
                          String observacion) {
        boolean vacios() {
            return fechaInicio == null && fechaDisponibilidad == null && cantidadEstimada == null && ubicacion == null
                    && observacion == null;
        }
    }

    public record Alta(ProduccionResumen produccion, LoteProduccion lote, String origen) {
    }

    /** Resultado de un cambio: la producción y cuántos encargos perdieron su fecha estimada (R064). */
    public record Cambio(ProduccionResumen produccion, int encargosSinFecha) {
    }

    public record Detalle(ProduccionResumen resumen, List<LoteResumen> lotes, BalanceProduccion balance) {
    }

    private final ContextoNegocio ctx;
    private final ProduccionRepositorio producciones = new ProduccionRepositorio();
    private final LoteProduccionRepositorio lotes = new LoteProduccionRepositorio();
    private final ProductoReferenciaRepositorio productos = new ProductoReferenciaRepositorio();
    private final TitularProduccion titular = new TitularProduccion();
    private final OrigenProduccion origenes = new OrigenProduccion();
    private final AltaLote altaLote;
    private final ReaccionCapacidad reaccion;

    public ServicioProduccion(ContextoNegocio ctx) {
        this.ctx = ctx;
        this.altaLote = new AltaLote(ctx);
        this.reaccion = new ReaccionCapacidad(ctx);
    }

    public Alta registrarPlanificada(Solicitante solicitante, DatosPlan datos) {
        return ctx.transacciones().enTransaccion(() -> {
            Productor productor = titular.operativo(solicitante);
            ProductoReferencia producto = productoActivo(datos.codigoProducto());
            if (datos.fechaInicio() == null) {
                throw NegocioException.regla("Una producción PLANIFICADA requiere su fecha de inicio.");
            }
            exigirDisponibilidadDesdeInicio(datos.fechaInicio(), datos.fechaDisponibilidad());
            Produccion p = nueva(productor, producto, EstadoProduccion.PLANIFICADA);
            p.setFechaInicio(datos.fechaInicio());
            p.setFechaDisponibilidadEstimada(datos.fechaDisponibilidad());
            p.setCantidadEstimada(datos.cantidadEstimada() == null ? null
                    : ReglasCantidades.positiva(datos.cantidadEstimada(), producto.unidad(), "La cantidad estimada"));
            p.setUbicacionProduccion(ValidadorTexto.opcional(datos.ubicacion(), "La ubicación de la producción", 200));
            p.setObservacion(ValidadorTexto.opcional(datos.observacion(), "La observación", 1000));
            OrigenProduccion.Origen origen = origenes.resolver(productor, null, null);
            insertar(solicitante, p, origen);
            return new Alta(resumen(p.getId()), null, origen.base());
        });
    }

    /** R061 forma 2: TERMINADA y, en la misma transacción, su primer LOTE_PRODUCCION (R040). */
    public Alta registrarTerminada(Solicitante solicitante, DatosTerminada datos) {
        return ctx.transacciones().enTransaccion(() -> {
            Productor productor = titular.operativo(solicitante);
            ProductoReferencia producto = productoActivo(datos.codigoProducto());
            OrigenProduccion.Origen origen = origenes.resolver(productor, datos.comunidadOrigenId(),
                    datos.lote().fechaElaboracion());
            Produccion p = nueva(productor, producto, EstadoProduccion.TERMINADA);
            // La fecha de inicio no se inventa: se registra si el productor la conoce y, si no, queda vacía.
            p.setFechaInicio(inicioConocido(datos.fechaInicio()));
            p.setUbicacionProduccion(ValidadorTexto.opcional(datos.ubicacion(), "La ubicación de la producción", 200));
            p.setObservacion(ValidadorTexto.opcional(datos.observacion(), "La observación", 1000));
            // Al preparar el lote se comprueba también que la elaboración no sea anterior a esa fecha de inicio.
            LoteProduccion lote = altaLote.preparar(p, producto.unidad(), datos.lote());
            insertar(solicitante, p, origen);
            altaLote.registrar(solicitante.usuarioId(), p, lote);
            return new Alta(resumen(p.getId()), lote, origen.base());
        });
    }

    /** Transiciones del productor titular; cancelar exige motivo y desencadena la reacción de R064. */
    public Cambio cambiarEstado(Solicitante solicitante, String codigo, EstadoProduccion nuevo, String motivo) {
        return ctx.transacciones().enTransaccion(() -> {
            Productor productor = titular.operativo(solicitante);
            Produccion p = bloquear(codigo);
            titular.exigirPropio(productor, p.getProductorId());
            if (!TransicionesProduccion.permitida(p.getEstado(), nuevo)) {
                throw NegocioException.estadoInvalido("La producción " + codigo + " está " + p.getEstado() + " y no puede pasar a "
                        + nuevo + ". Estados siguientes permitidos: " + TransicionesProduccion.siguientes(p.getEstado()) + ".");
            }
            String justificacion = nuevo == EstadoProduccion.CANCELADA
                    ? ValidadorTexto.obligatorio(motivo, "El motivo de la cancelación", 255)
                    : ValidadorTexto.opcional(motivo, "El motivo", 255);
            EstadoProduccion anterior = p.getEstado();
            producciones.actualizarEstado(p.getId(), nuevo);
            p.setEstado(nuevo);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, p.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", anterior)
                    .con("estado_nuevo", nuevo)
                    .con("motivo", justificacion));
            int afectados = nuevo == EstadoProduccion.CANCELADA ? reaccion.trasCancelar(p, solicitante.usuarioId()) : 0;
            return new Cambio(resumen(p.getId()), afectados);
        });
    }

    /**
     * Edición del plan por el titular (R065): fechas, estimación y observación mientras la producción no se cerró;
     * la ubicación solo mientras no tenga lotes (después la corrige un ADMINISTRADOR).
     */
    public Cambio modificar(Solicitante solicitante, String codigo, Cambios cambios) {
        if (cambios.vacios()) {
            throw NegocioException.regla("Indique al menos un dato para modificar; deje en \"\" los que no cambian.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            Productor productor = titular.operativo(solicitante);
            Produccion actual = bloquear(codigo);
            titular.exigirPropio(productor, actual.getProductorId());
            ProductoReferencia producto = productos.buscarPorId(actual.getProductoId()).orElseThrow();
            Produccion nueva = aplicar(actual, cambios, producto);
            DetalleJson detalle = DetalleJson.nuevo();
            boolean hay = CambiosBitacora.anotar(detalle, "fecha_inicio", actual.getFechaInicio(), nueva.getFechaInicio())
                    | CambiosBitacora.anotar(detalle, "fecha_disponibilidad_estimada", actual.getFechaDisponibilidadEstimada(),
                    nueva.getFechaDisponibilidadEstimada())
                    | anotarCantidad(detalle, actual.getCantidadEstimada(), nueva.getCantidadEstimada())
                    | CambiosBitacora.anotar(detalle, "ubicacion_produccion", actual.getUbicacionProduccion(),
                    nueva.getUbicacionProduccion())
                    | CambiosBitacora.anotar(detalle, "observacion", actual.getObservacion(), nueva.getObservacion());
            if (!hay) {
                throw NegocioException.regla("Los datos indicados son iguales a los registrados; no se modificó la producción.");
            }
            producciones.actualizarDatos(nueva);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, nueva.getId(), detalle);
            int afectados = esReduccion(actual.getCantidadEstimada(), nueva.getCantidadEstimada())
                    ? reaccion.trasReducirEstimacion(nueva, solicitante.usuarioId()) : 0;
            return new Cambio(resumen(nueva.getId()), afectados);
        });
    }

    public List<ProduccionResumen> listar(Solicitante solicitante, EstadoProduccion estado) {
        return ctx.transacciones().enTransaccion(() -> producciones.listar(titular.ambitoDeConsulta(solicitante).orElse(null), estado));
    }

    /** Detalle con sus lotes y el balance de cantidades (R063). */
    public Detalle consultar(Solicitante solicitante, String codigo) {
        return ctx.transacciones().enTransaccion(() -> {
            ProduccionResumen r = producciones.resumenPorCodigo(codigo).orElseThrow(() -> noEncontrada(codigo));
            titular.exigirConsultable(solicitante, r.produccion().getProductorId());
            return new Detalle(r, lotes.listar(null, codigo), reaccion.balance(r.produccion()));
        });
    }

    private Produccion aplicar(Produccion actual, Cambios cambios, ProductoReferencia producto) {
        boolean cambiaPlan = cambios.fechaDisponibilidad() != null || cambios.cantidadEstimada() != null;
        if (actual.getEstado() == EstadoProduccion.CANCELADA) {
            throw NegocioException.estadoInvalido("La producción " + actual.getCodigo() + " está CANCELADA y no se modifica.");
        }
        if (cambiaPlan && !TransicionesProduccion.abierta(actual.getEstado())) {
            throw NegocioException.estadoInvalido("La disponibilidad y la cantidad estimadas solo se modifican en una "
                    + "producción PLANIFICADA o EN_CURSO.");
        }
        if (cambios.ubicacion() != null && producciones.tieneLotes(actual.getId())) {
            throw NegocioException.regla("La producción ya tiene lotes: su ubicación solo la corrige un ADMINISTRADOR con motivo (CORPCN).");
        }
        Produccion nueva = copiar(actual);
        if (cambios.fechaInicio() != null) {
            producciones.primeraElaboracion(actual.getId()).filter(cambios.fechaInicio()::isAfter).ifPresent(primera -> {
                throw NegocioException.regla("La fecha de inicio no puede ser posterior a la elaboración de sus lotes (" + primera + ").");
            });
            nueva.setFechaInicio(cambios.fechaInicio());
        }
        if (cambios.fechaDisponibilidad() != null) {
            nueva.setFechaDisponibilidadEstimada(cambios.fechaDisponibilidad());
        }
        if (cambios.cantidadEstimada() != null) {
            nueva.setCantidadEstimada(ReglasCantidades.positiva(cambios.cantidadEstimada(), producto.unidad(), "La cantidad estimada"));
        }
        if (cambios.ubicacion() != null) {
            nueva.setUbicacionProduccion(ValidadorTexto.obligatorio(cambios.ubicacion(), "La ubicación de la producción", 200));
        }
        if (cambios.observacion() != null) {
            nueva.setObservacion(ValidadorTexto.obligatorio(cambios.observacion(), "La observación", 1000));
        }
        exigirDisponibilidadDesdeInicio(nueva.getFechaInicio(), nueva.getFechaDisponibilidadEstimada());
        return nueva;
    }

    private void insertar(Solicitante solicitante, Produccion p, OrigenProduccion.Origen origen) {
        long id = producciones.reservarId();
        p.setId(id);
        p.setCodigo(String.format("PRD-%d-%03d", FormatoNegocio.hoy(ctx.reloj()).getYear(), id));
        // Instantánea de la comunidad de origen al crear la producción: no se actualiza si la comunidad cambia (R066).
        p.setComunidadOrigenId(origen.comunidad().getId());
        p.setNombreComunidadOrigen(origen.comunidad().getNombre());
        p.setMunicipioOrigen(origen.comunidad().getMunicipio());
        p.setDepartamentoOrigen(origen.comunidad().getDepartamento());
        producciones.insertar(p);
        ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, ENTIDAD, id, DetalleJson.nuevo()
                .con("codigo", p.getCodigo())
                .con("estado_inicial", p.getEstado())
                .con("comunidad_origen_id", origen.comunidad().getId())
                .con("origen", origen.base())
                .con("declarado_por", solicitante.usuarioId())
                .con("evidencia_bitacora_id", origen.evidenciaBitacoraId())
                .con("validado_por", origen.validadoPorId()));
    }

    private static Produccion nueva(Productor productor, ProductoReferencia producto, EstadoProduccion estado) {
        Produccion p = new Produccion();
        p.setProductorId(productor.getId());
        p.setProductoId(producto.id());
        p.setEstado(estado);
        return p;
    }

    private ProductoReferencia productoActivo(String codigo) {
        ProductoReferencia producto = productos.buscarPorCodigo(codigo)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el producto " + codigo + " en el catálogo."));
        if (!producto.activo()) {
            throw NegocioException.regla("El producto " + codigo + " está inactivo y no admite producciones nuevas.");
        }
        return producto;
    }

    /** Una producción que ya terminó no puede haber empezado en el futuro. */
    private LocalDate inicioConocido(LocalDate fechaInicio) {
        LocalDate hoy = FormatoNegocio.hoy(ctx.reloj());
        if (fechaInicio != null && fechaInicio.isAfter(hoy)) {
            throw NegocioException.regla("La fecha de inicio de una producción TERMINADA no puede ser posterior a hoy (" + hoy + ").");
        }
        return fechaInicio;
    }

    private static void exigirDisponibilidadDesdeInicio(LocalDate inicio, LocalDate disponibilidad) {
        if (inicio != null && disponibilidad != null && disponibilidad.isBefore(inicio)) {
            throw NegocioException.regla("La fecha de disponibilidad estimada no puede ser anterior a la fecha de inicio.");
        }
    }

    private static boolean esReduccion(BigDecimal anterior, BigDecimal nueva) {
        return anterior != null && nueva != null && nueva.compareTo(anterior) < 0;
    }

    private static boolean anotarCantidad(DetalleJson detalle, BigDecimal anterior, BigDecimal nueva) {
        boolean iguales = anterior == null ? nueva == null : nueva != null && anterior.compareTo(nueva) == 0;
        return !iguales && CambiosBitacora.anotar(detalle, "cantidad_estimada", anterior, nueva);
    }

    private static Produccion copiar(Produccion a) {
        Produccion p = new Produccion();
        p.setId(a.getId());
        p.setProductorId(a.getProductorId());
        p.setProductoId(a.getProductoId());
        p.setComunidadOrigenId(a.getComunidadOrigenId());
        p.setCodigo(a.getCodigo());
        p.setFechaInicio(a.getFechaInicio());
        p.setFechaDisponibilidadEstimada(a.getFechaDisponibilidadEstimada());
        p.setCantidadEstimada(a.getCantidadEstimada());
        p.setUbicacionProduccion(a.getUbicacionProduccion());
        p.setEstado(a.getEstado());
        p.setObservacion(a.getObservacion());
        p.setNombreComunidadOrigen(a.getNombreComunidadOrigen());
        p.setMunicipioOrigen(a.getMunicipioOrigen());
        p.setDepartamentoOrigen(a.getDepartamentoOrigen());
        return p;
    }

    private Produccion bloquear(String codigo) {
        return producciones.bloquearPorCodigo(codigo).orElseThrow(() -> noEncontrada(codigo));
    }

    private ProduccionResumen resumen(long id) {
        return producciones.resumenPorId(id).orElseThrow();
    }

    private static NegocioException noEncontrada(String codigo) {
        return NegocioException.noEncontrado("No existe la producción " + codigo + ".");
    }
}
