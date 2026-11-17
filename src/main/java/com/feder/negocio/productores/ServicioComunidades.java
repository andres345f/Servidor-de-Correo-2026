package com.feder.negocio.productores;

import com.feder.datos.consultas.ListadosGeneralesRepositorio;
import com.feder.datos.productores.ComunidadRepositorio;
import com.feder.datos.productores.ComunidadRepositorio.ComunidadResumen;
import com.feder.modelo.AccionBitacora;
import com.feder.modelo.ComunidadAgricola;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.DetalleJson;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.usuarios.CambiosBitacora;
import com.feder.negocio.usuarios.Permisos;
import com.feder.negocio.usuarios.ValidadorTexto;

import java.util.List;

/**
 * Comunidades agrícolas (R008). El ADMINISTRADOR crea, consulta, modifica y desactiva; PERSONAL_FEDER crea,
 * consulta y modifica; PRODUCTOR y CLIENTE solo consultan las activas. Toda alta, modificación y desactivación
 * queda en BITACORA. Desactivar no afecta automáticamente a los productores afiliados.
 */
public class ServicioComunidades {

    private static final String ENTIDAD = "COMUNIDAD_AGRICOLA";
    private static final String DEPARTAMENTO_POR_DEFECTO = "Santa Cruz";

    /** Datos de una comunidad; en una modificación, nulo significa «sin cambio». */
    public record DatosComunidad(String nombre, String municipio, String departamento, String descripcion) {
    }

    private final ContextoNegocio ctx;
    private final ComunidadRepositorio comunidades = new ComunidadRepositorio();
    private final ListadosGeneralesRepositorio consultas = new ListadosGeneralesRepositorio();

    public ServicioComunidades(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    /** Las vistas administrativas muestran todas; las demás, solo las activas. */
    public List<ComunidadResumen> listar(Solicitante solicitante, String filtro) {
        Permisos.exigirRegistrado(solicitante);
        boolean interno = solicitante.esPersonalInterno();
        if (!interno && !solicitante.tieneRol(TipoRol.PRODUCTOR) && !solicitante.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Su cuenta no tiene un rol vigente que permita consultar comunidades.");
        }
        return ctx.transacciones().enTransaccion(() -> comunidades.listar(!interno, filtro));
    }

    /** Comunidad con sus productores afiliados (código, titular, correo, solicitud, activo, afiliación). */
    public record ComunidadConProductores(ComunidadAgricola comunidad, List<List<Object>> productores) {
    }

    /**
     * Detalle de una comunidad (VERCOM, plan fase C, C2). Mismos permisos que el listado: el personal ve cualquiera;
     * productores y clientes, solo las activas (una inactiva se responde como inexistente).
     */
    public ComunidadConProductores ver(Solicitante solicitante, long id) {
        Permisos.exigirRegistrado(solicitante);
        boolean interno = solicitante.esPersonalInterno();
        if (!interno && !solicitante.tieneRol(TipoRol.PRODUCTOR) && !solicitante.tieneRol(TipoRol.CLIENTE)) {
            throw NegocioException.sinPermiso("Su cuenta no tiene un rol vigente que permita consultar comunidades.");
        }
        return ctx.transacciones().enTransaccion(() -> {
            ComunidadAgricola c = comunidades.buscarPorId(id).filter(x -> interno || Boolean.TRUE.equals(x.getActiva()))
                    .orElseThrow(() -> NegocioException.noEncontrado("No existe la comunidad " + id + ". Consulte LISCOM[\"*\"]."));
            return new ComunidadConProductores(c, consultas.productoresDeComunidad(id, Listados.A_PEDIR));
        });
    }

    public ComunidadAgricola crear(Solicitante solicitante, DatosComunidad datos) {
        Permisos.exigirPersonalInterno(solicitante, "registrar comunidades");
        ComunidadAgricola nueva = new ComunidadAgricola();
        nueva.setNombre(ValidadorTexto.obligatorio(datos.nombre(), "El nombre", 120));
        nueva.setMunicipio(ValidadorTexto.obligatorio(datos.municipio(), "El municipio", 80));
        String departamento = ValidadorTexto.opcional(datos.departamento(), "El departamento", 40);
        nueva.setDepartamento(departamento == null ? DEPARTAMENTO_POR_DEFECTO : departamento);
        nueva.setDescripcion(ValidadorTexto.opcional(datos.descripcion(), "La descripción", 500));
        return ctx.transacciones().enTransaccion(() -> {
            exigirNombreLibre(nueva.getNombre(), nueva.getMunicipio(), null);
            nueva.setId(comunidades.insertar(nueva));
            nueva.setActiva(true);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.CREAR, ENTIDAD, nueva.getId(), DetalleJson.nuevo()
                    .con("nombre", nueva.getNombre())
                    .con("municipio", nueva.getMunicipio())
                    .con("departamento", nueva.getDepartamento()));
            return nueva;
        });
    }

    /** Cambia nombre, ubicación o descripción. Las instantáneas de origen de las producciones no se tocan (R066). */
    public ComunidadAgricola modificar(Solicitante solicitante, long id, DatosComunidad datos) {
        Permisos.exigirPersonalInterno(solicitante, "modificar comunidades");
        return ctx.transacciones().enTransaccion(() -> {
            ComunidadAgricola actual = bloquear(id);
            ComunidadAgricola nueva = new ComunidadAgricola();
            nueva.setId(id);
            nueva.setNombre(datos.nombre() == null ? actual.getNombre() : ValidadorTexto.obligatorio(datos.nombre(), "El nombre", 120));
            nueva.setMunicipio(datos.municipio() == null ? actual.getMunicipio()
                    : ValidadorTexto.obligatorio(datos.municipio(), "El municipio", 80));
            nueva.setDepartamento(datos.departamento() == null ? actual.getDepartamento()
                    : ValidadorTexto.obligatorio(datos.departamento(), "El departamento", 40));
            nueva.setDescripcion(datos.descripcion() == null ? actual.getDescripcion()
                    : ValidadorTexto.obligatorio(datos.descripcion(), "La descripción", 500));
            nueva.setActiva(actual.getActiva());
            DetalleJson cambios = DetalleJson.nuevo();
            boolean hay = CambiosBitacora.anotar(cambios, "nombre", actual.getNombre(), nueva.getNombre())
                    | CambiosBitacora.anotar(cambios, "municipio", actual.getMunicipio(), nueva.getMunicipio())
                    | CambiosBitacora.anotar(cambios, "departamento", actual.getDepartamento(), nueva.getDepartamento())
                    | CambiosBitacora.anotar(cambios, "descripcion", actual.getDescripcion(), nueva.getDescripcion());
            if (!hay) {
                throw NegocioException.regla("Los datos indicados son iguales a los registrados; no se modificó la comunidad.");
            }
            exigirNombreLibre(nueva.getNombre(), nueva.getMunicipio(), id);
            comunidades.actualizar(nueva);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, id, cambios);
            return nueva;
        });
    }

    /** Baja lógica: los productores afiliados conservan su afiliación, cuenta, roles y perfil. */
    public ComunidadAgricola desactivar(Solicitante solicitante, long id, String motivo) {
        return cambiarActiva(solicitante, id, false, motivo);
    }

    public ComunidadAgricola reactivar(Solicitante solicitante, long id, String motivo) {
        return cambiarActiva(solicitante, id, true, motivo);
    }

    private ComunidadAgricola cambiarActiva(Solicitante solicitante, long id, boolean activa, String motivo) {
        Permisos.exigirRol(solicitante, TipoRol.ADMINISTRADOR, activa ? "reactivar comunidades" : "desactivar comunidades");
        String justificacion = ValidadorTexto.obligatorio(motivo, "El motivo", 255);
        return ctx.transacciones().enTransaccion(() -> {
            ComunidadAgricola actual = bloquear(id);
            if (actual.getActiva() == activa) {
                throw NegocioException.estadoInvalido("La comunidad " + actual.getNombre() + " ya está "
                        + (activa ? "activa." : "inactiva."));
            }
            comunidades.cambiarActiva(id, activa);
            actual.setActiva(activa);
            ctx.bitacora().registrar(solicitante.usuarioId(), AccionBitacora.MODIFICAR, ENTIDAD, id, DetalleJson.nuevo()
                    .con("activa_anterior", !activa)
                    .con("activa_nuevo", activa)
                    .con("motivo", justificacion));
            return actual;
        });
    }

    private ComunidadAgricola bloquear(long id) {
        return comunidades.bloquearPorId(id)
                .orElseThrow(() -> NegocioException.noEncontrado("No existe la comunidad agrícola " + id + "."));
    }

    private void exigirNombreLibre(String nombre, String municipio, Long excluirId) {
        if (comunidades.existeNombreEnMunicipio(nombre, municipio, excluirId)) {
            throw NegocioException.duplicado("Ya existe una comunidad llamada " + nombre + " en el municipio " + municipio + ".");
        }
    }
}
