package com.feder.negocio.produccion;

import com.feder.datos.produccion.LoteProduccionRepositorio;
import com.feder.datos.produccion.LoteProduccionRepositorio.LoteResumen;
import com.feder.datos.produccion.ProduccionRepositorio;
import com.feder.datos.produccion.ProduccionRepositorio.ProduccionResumen;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.EstadoLoteProduccion;
import com.feder.modelo.EstadoProduccion;
import com.feder.modelo.LoteProduccion;
import com.feder.modelo.Produccion;
import com.feder.modelo.Productor;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.usuarios.CambiosBitacora;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.negocio.usuarios.Permisos;
import com.feder.negocio.usuarios.ValidadorTexto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Lotes de origen (CU3, «Asignar lotes»): alta vinculada a una producción del propio productor, corrección,
 * retiro de la oferta y consulta (R039, R040). La cantidad de un lote no es inventario de FEDER.
 */
public class ServicioLotes {

    private static final String ENTIDAD = "LOTE_PRODUCCION";

    public record DatosLote(LocalDate fechaElaboracion, LocalDate fechaVencimiento, BigDecimal cantidadProducida,
                            BigDecimal cantidadOfrecida, BigDecimal precioOfrecido) {
    }

    /** Cambios de un lote; nulo significa «sin cambio». */
    public record CambiosLote(LocalDate fechaElaboracion, LocalDate fechaVencimiento, BigDecimal cantidadProducida,
                              BigDecimal cantidadOfrecida, BigDecimal precioOfrecido, String motivo) {
        boolean tocaDatosFisicos() {
            return fechaElaboracion != null || fechaVencimiento != null || cantidadProducida != null;
        }

        boolean tocaOferta() {
            return cantidadOfrecida != null || precioOfrecido != null;
        }
    }

    private final ContextoNegocio ctx;
    private final LoteProduccionRepositorio lotes = new LoteProduccionRepositorio();
    private final ProduccionRepositorio producciones = new ProduccionRepositorio();
    private final TitularProduccion titular = new TitularProduccion();
    private final AltaLote altaLote;

    public ServicioLotes(ContextoNegocio ctx) {
        this.ctx = ctx;
        this.altaLote = new AltaLote(ctx);
    }

    /** R040: lo registra el titular con cuenta, perfil y rol vigentes, solo en una producción EN_CURSO o TERMINADA. */
    public LoteProduccion registrar(Solicitante solicitante, String codigoProduccion, DatosLote datos) {
        return ctx.transacciones().enTransaccion(() -> {
            Productor productor = titular.operativo(solicitante);
            // Bloqueamos la producción para que no cambie de estado mientras se le asigna el lote.
            Produccion p = producciones.bloquearPorCodigo(codigoProduccion)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe la producción " + codigoProduccion + "."));
            titular.exigirPropio(productor, p.getProductorId());
            if (p.getEstado() != EstadoProduccion.EN_CURSO && p.getEstado() != EstadoProduccion.TERMINADA) {
                throw NegocioException.estadoInvalido("Solo se registran lotes en una producción EN_CURSO o TERMINADA; "
                        + codigoProduccion + " está " + p.getEstado() + ".");
            }
            return altaLote.crear(solicitante.usuarioId(), p, resumen(p.getId()).unidad(), datos);
        });
    }

    /**
     * Antes de la primera compra el titular corrige su lote. Después, elaboración, vencimiento y cantidad producida
     * solo los corrige un ADMINISTRADOR con motivo; el titular aún ajusta la oferta sin bajar de lo comprometido (R039).
     */
    public LoteResumen modificar(Solicitante solicitante, String codigoLote, CambiosLote cambios) {
        if (!cambios.tocaDatosFisicos() && !cambios.tocaOferta()) {
            throw NegocioException.regla("Indique al menos un dato para modificar; deje en \"\" los que no cambian.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            LoteProduccion actual = bloquear(codigoLote);
            ProduccionResumen produccion = resumen(actual.getProduccionId());
            boolean comprado = lotes.tieneCompras(actual.getId());
            String motivo = exigirPermisoDeCambio(solicitante, produccion, actual, comprado, cambios);
            LoteProduccion nuevo = aplicar(actual, cambios);
            ReglasLote.validar(nuevo, produccion.unidad(), produccion.produccion().getFechaInicio(), FormatoNegocio.hoy(ctx.reloj()));
            if (cambios.cantidadOfrecida() != null) {
                ajustarEstadoSegunOferta(nuevo, lotes.comprometido(actual.getId()));
            }
            DetalleJson detalle = cambiosAuditados(actual, nuevo);
            lotes.actualizar(nuevo);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, nuevo.getId(),
                    detalle.con("motivo", motivo));
            return resumenLote(nuevo);
        });
    }

    /** El titular deja de ofrecer un lote DISPONIBLE; las compras ya registradas siguen su curso (CU4). */
    public LoteResumen retirar(Solicitante solicitante, String codigoLote, String motivo) {
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo del retiro", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Productor productor = titular.operativo(solicitante);
            LoteProduccion lote = bloquear(codigoLote);
            titular.exigirPropio(productor, resumen(lote.getProduccionId()).produccion().getProductorId());
            if (lote.getEstado() != EstadoLoteProduccion.DISPONIBLE) {
                throw NegocioException.estadoInvalido("Solo se retira un lote DISPONIBLE; " + codigoLote + " está "
                        + lote.getEstado() + ".");
            }
            EstadoLoteProduccion anterior = lote.getEstado();
            lote.setEstado(EstadoLoteProduccion.RETIRADO);
            lotes.actualizar(lote);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, lote.getId(), DetalleJson.nuevo()
                    .con("estado_anterior", anterior)
                    .con("estado_nuevo", lote.getEstado())
                    .con("motivo", justificacion));
            return resumenLote(lote);
        });
    }

    public List<LoteResumen> listar(Solicitante solicitante, String codigoProduccion) {
        return ctx.transacciones().enTransaccion(() -> lotes.listar(titular.ambitoDeConsulta(solicitante).orElse(null), codigoProduccion));
    }

    /**
     * Decide quién puede aplicar estos cambios y devuelve el motivo que quedará en bitácora. Un lote RETIRADO ya no lo
     * cambia su titular, pero el ADMINISTRADOR conserva la corrección de los datos físicos de un lote comprado: el
     * vencimiento de origen sigue alimentando el inventario de FEDER (R040).
     */
    private String exigirPermisoDeCambio(Solicitante solicitante, ProduccionResumen produccion, LoteProduccion actual,
                                         boolean comprado, CambiosLote cambios) {
        long productorId = produccion.produccion().getProductorId();
        if (titular.esTitular(solicitante, productorId)) {
            titular.exigirPropio(titular.operativo(solicitante), productorId);
            if (actual.getEstado() == EstadoLoteProduccion.RETIRADO) {
                throw NegocioException.estadoInvalido("El lote " + actual.getCodigoLote() + " fue retirado de la oferta y "
                        + "su productor ya no lo modifica.");
            }
            if (comprado && cambios.tocaDatosFisicos()) {
                throw NegocioException.regla("El lote ya tiene compras de FEDER: la fecha de elaboración, el vencimiento y la "
                        + "cantidad producida solo los corrige un ADMINISTRADOR con motivo.");
            }
            return ValidadorTexto.opcional(cambios.motivo(), "El motivo", 255);
        }
        if (!solicitante.estaRegistrado() || !solicitante.tieneRol(TipoRol.ADMINISTRADOR)) {
            throw NegocioException.sinPermiso("Solo el productor titular modifica sus lotes.");
        }
        // La unión de roles no amplía el ámbito: un administrador no corrige como tal los lotes de su propia producción.
        Permisos.exigirDistintoDelTitular(solicitante, produccion.usuarioProductorId(),
                "Nadie corrige como ADMINISTRADOR los lotes de su propia producción: otro ADMINISTRADOR debe hacerlo.");
        if (!comprado) {
            throw NegocioException.regla("Antes de su primera compra, el lote lo corrige su productor titular.");
        }
        if (cambios.tocaOferta()) {
            throw NegocioException.regla("El ADMINISTRADOR solo corrige la fecha de elaboración, el vencimiento y la cantidad "
                    + "producida; la oferta la decide el productor.");
        }
        return ValidadorTexto.obligatorio(cambios.motivo(), "El motivo de la corrección", 255);
    }

    private static LoteProduccion aplicar(LoteProduccion actual, CambiosLote c) {
        LoteProduccion l = new LoteProduccion();
        l.setId(actual.getId());
        l.setProduccionId(actual.getProduccionId());
        l.setCodigoLote(actual.getCodigoLote());
        l.setEstado(actual.getEstado());
        l.setFechaElaboracion(c.fechaElaboracion() == null ? actual.getFechaElaboracion() : c.fechaElaboracion());
        l.setFechaVencimiento(c.fechaVencimiento() == null ? actual.getFechaVencimiento() : c.fechaVencimiento());
        l.setCantidadProducida(elegir(actual.getCantidadProducida(), c.cantidadProducida()));
        l.setCantidadOfrecida(elegir(actual.getCantidadOfrecida(), c.cantidadOfrecida()));
        l.setPrecioOfrecido(c.precioOfrecido() == null ? actual.getPrecioOfrecido()
                : elegir(actual.getPrecioOfrecido(), ReglasCantidades.precio(c.precioOfrecido())));
        return l;
    }

    /** Conserva el valor actual si el nuevo es numéricamente igual (100 y 100.000 no son un cambio). */
    private static BigDecimal elegir(BigDecimal actual, BigDecimal nuevo) {
        if (nuevo == null || (actual != null && actual.compareTo(nuevo) == 0)) {
            return actual;
        }
        return nuevo;
    }

    /**
     * R039: la oferta nunca queda por debajo de lo comprometido en compras. El lote está AGOTADO cuando todo lo
     * ofrecido ya está comprometido, y vuelve a DISPONIBLE si el productor amplía la oferta.
     */
    private static void ajustarEstadoSegunOferta(LoteProduccion lote, BigDecimal comprometido) {
        if (lote.getCantidadOfrecida().compareTo(comprometido) < 0) {
            throw NegocioException.regla("La cantidad ofrecida no puede ser menor que lo ya comprometido en compras de FEDER ("
                    + FormatoNegocio.cantidad(comprometido) + ").");
        }
        boolean agotado = comprometido.signum() > 0 && comprometido.compareTo(lote.getCantidadOfrecida()) >= 0;
        lote.setEstado(agotado ? EstadoLoteProduccion.AGOTADO : EstadoLoteProduccion.DISPONIBLE);
    }

    private static DetalleJson cambiosAuditados(LoteProduccion a, LoteProduccion n) {
        DetalleJson d = DetalleJson.nuevo();
        boolean hay = CambiosBitacora.anotar(d, "fecha_elaboracion", a.getFechaElaboracion(), n.getFechaElaboracion())
                | CambiosBitacora.anotar(d, "fecha_vencimiento", a.getFechaVencimiento(), n.getFechaVencimiento())
                | CambiosBitacora.anotar(d, "cantidad_producida", a.getCantidadProducida(), n.getCantidadProducida())
                | CambiosBitacora.anotar(d, "cantidad_ofrecida", a.getCantidadOfrecida(), n.getCantidadOfrecida())
                | CambiosBitacora.anotar(d, "precio_ofrecido", a.getPrecioOfrecido(), n.getPrecioOfrecido())
                | CambiosBitacora.anotar(d, "estado", a.getEstado(), n.getEstado());
        if (!hay) {
            throw NegocioException.regla("Los datos indicados son iguales a los registrados; no se modificó el lote.");
        }
        return d;
    }

    private LoteProduccion bloquear(String codigoLote) {
        return lotes.bloquearPorCodigo(codigoLote)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe el lote " + codigoLote + "."));
    }

    private ProduccionResumen resumen(long produccionId) {
        return producciones.resumenPorId(produccionId).orElseThrow();
    }

    private LoteResumen resumenLote(LoteProduccion lote) {
        ProduccionResumen r = resumen(lote.getProduccionId());
        return new LoteResumen(lote, r.produccion().getCodigo(), r.codigoProducto(), r.nombreProducto(),
                lotes.comprometido(lote.getId()));
    }
}
