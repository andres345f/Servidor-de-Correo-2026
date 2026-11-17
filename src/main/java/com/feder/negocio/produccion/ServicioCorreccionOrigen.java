package com.feder.negocio.produccion;

import com.feder.datos.productores.ComunidadRepositorio;
import com.feder.datos.productores.HistorialComunidadRepositorio;
import com.feder.datos.productores.ProductorRepositorio;
import com.feder.datos.produccion.ProduccionRepositorio;
import com.feder.datos.produccion.ProduccionRepositorio.ProduccionResumen;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.ComunidadAgricola;
import com.feder.modelo.Produccion;
import com.feder.modelo.Productor;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.negocio.usuarios.Permisos;
import com.feder.negocio.usuarios.ValidadorTexto;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Intervenciones del ADMINISTRADOR sobre la procedencia de la producción, siempre con motivo y registro en BITACORA:
 * corregir la ubicación de elaboración o la instantánea de origen (R065, R066) y validar, antes del registro, una
 * comunidad de origen que no consta en el historial del productor (R066). No registra producción en nombre de nadie.
 */
public class ServicioCorreccionOrigen {

    private final ContextoNegocio ctx;
    private final ProduccionRepositorio producciones = new ProduccionRepositorio();
    private final ProductorRepositorio productores = new ProductorRepositorio();
    private final ComunidadRepositorio comunidades = new ComunidadRepositorio();

    public ServicioCorreccionOrigen(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    public ProduccionResumen corregir(Solicitante admin, String codigo, CampoCorregible campo, String valor, String motivo) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "corregir datos de una producción");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo de la corrección", 255);
        String nuevo = ValidadorTexto.obligatorio(valor, "El valor corregido", longitudDe(campo));
        return ctx.transacciones().enTransaccion(() -> {
            Produccion p = producciones.bloquearPorCodigo(codigo)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe la producción " + codigo + "."));
            // Igual que en VALORI: la corrección necesita un control independiente del titular (R088).
            Permisos.exigirDistintoDelTitular(admin, producciones.resumenPorId(p.getId()).orElseThrow().usuarioProductorId(),
                    "Nadie corrige su propia producción: otro ADMINISTRADOR debe hacerlo.");
            String anterior = valorDe(p, campo);
            if (nuevo.equals(anterior)) {
                throw NegocioException.regla("El valor indicado es igual al registrado; no se corrigió nada.");
            }
            aplicar(p, campo, nuevo);
            ctx.bitacora().registrar(admin.usuarioId(), AccionBitacora.MODIFICAR, "PRODUCCION", p.getId(), DetalleJson.nuevo()
                    .con("campo", campo.name().toLowerCase(Locale.ROOT))
                    .con("valor_anterior", anterior)
                    .con("valor_nuevo", nuevo)
                    .con("motivo", justificacion));
            return producciones.resumenPorId(p.getId()).orElseThrow();
        });
    }

    /**
     * Deja constancia de que un ADMINISTRADOR verificó que el productor pertenecía a la comunidad indicada en la fecha
     * de elaboración declarada. El productor podrá después registrar esa producción con ese origen (INSTER).
     */
    public ComunidadAgricola validarOrigen(Solicitante admin, long productorId, long comunidadId, LocalDate fechaElaboracion,
                                           String motivo, String evidencia) {
        Permisos.exigirRol(admin, TipoRol.ADMINISTRADOR, "validar el origen declarado de una producción");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        String respaldo = ValidadorTexto.obligatorio(evidencia, "La evidencia", 255);
        return ctx.transacciones().enTransaccion(() -> {
            Productor productor = productores.bloquearPorId(productorId)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe el perfil de productor " + productorId + "."));
            Permisos.exigirDistintoDelTitular(admin, productor.getUsuarioId(), "Nadie valida el origen de su propia producción.");
            ComunidadAgricola comunidad = comunidades.buscarPorId(comunidadId)
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe la comunidad agrícola " + comunidadId + "."));
            if (comunidad.getId().equals(productor.getComunidadAgricolaId())) {
                throw NegocioException.regla("Es la comunidad actual del productor: no necesita validación.");
            }
            if (fechaElaboracion.isAfter(FormatoNegocio.hoy(ctx.reloj()))) {
                throw NegocioException.regla("La fecha de elaboración validada no puede ser posterior a hoy.");
            }
            ctx.bitacora().registrar(admin.usuarioId(), AccionBitacora.MODIFICAR, "PRODUCTOR", productor.getId(), DetalleJson.nuevo()
                    .con("operacion", HistorialComunidadRepositorio.OPERACION_VALIDACION)
                    .con("comunidad_id", comunidad.getId())
                    .con("fecha_elaboracion", fechaElaboracion)
                    .con("motivo", justificacion)
                    .con("evidencia", respaldo));
            return comunidad;
        });
    }

    private static String valorDe(Produccion p, CampoCorregible campo) {
        switch (campo) {
            case UBICACION_PRODUCCION:
                return p.getUbicacionProduccion();
            case NOMBRE_COMUNIDAD_ORIGEN:
                return p.getNombreComunidadOrigen();
            case MUNICIPIO_ORIGEN:
                return p.getMunicipioOrigen();
            default:
                return p.getDepartamentoOrigen();
        }
    }

    private static int longitudDe(CampoCorregible campo) {
        switch (campo) {
            case UBICACION_PRODUCCION:
                return 200;
            case NOMBRE_COMUNIDAD_ORIGEN:
                return 120;
            case MUNICIPIO_ORIGEN:
                return 80;
            default:
                return 40;
        }
    }

    private void aplicar(Produccion p, CampoCorregible campo, String valor) {
        if (campo == CampoCorregible.UBICACION_PRODUCCION) {
            p.setUbicacionProduccion(valor);
            producciones.actualizarDatos(p);
            return;
        }
        String nombre = campo == CampoCorregible.NOMBRE_COMUNIDAD_ORIGEN ? valor : p.getNombreComunidadOrigen();
        String municipio = campo == CampoCorregible.MUNICIPIO_ORIGEN ? valor : p.getMunicipioOrigen();
        String departamento = campo == CampoCorregible.DEPARTAMENTO_ORIGEN ? valor : p.getDepartamentoOrigen();
        producciones.actualizarInstantanea(p.getId(), nombre, municipio, departamento);
    }
}
