package com.feder.negocio.productores;

import com.feder.datos.productores.ComunidadRepositorio.ComunidadResumen;
import com.feder.datos.productores.ProductorRepositorio.PerfilProductor;
import com.feder.modelo.ComunidadAgricola;
import com.feder.modelo.EstadoSolicitudProductor;
import com.feder.modelo.Productor;
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
import com.feder.negocio.listados.ModuloListadosGenerales;
import com.feder.negocio.usuarios.FormatoNegocio;
import com.feder.negocio.usuarios.ServicioRoles;

import java.util.List;

/** CU2 Gestión de Productores y Comunidades Agrícolas. */
public final class ModuloProductores implements ModuloComandos {

    private static final String CU = "CU2";

    private final ServicioComunidades comunidades;
    private final ServicioProductores productores;
    private final ServicioRoles roles;

    public ModuloProductores(ContextoNegocio ctx) {
        this.comunidades = new ServicioComunidades(ctx);
        this.productores = new ServicioProductores(ctx);
        this.roles = new ServicioRoles(ctx);
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        registrarComunidades(catalogo);
        registrarSolicitudes(catalogo);
        registrarPerfiles(catalogo);
    }

    private void registrarComunidades(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("LISCOM").casoDeUso(CU)
                .descripcion("Lista las comunidades agrícolas (productores y clientes ven solo las activas)")
                .parametros(ParametroSpec.texto("filtro", 60).conComodin())
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER, TipoRol.PRODUCTOR, TipoRol.CLIENTE)
                .ejemplo("LISCOM[\"*\"]")
                .handler(this::listarComunidades).construir());
        catalogo.registrar(ComandoSpec.comando("VERCOM").casoDeUso(CU)
                .descripcion("Muestra una comunidad agrícola por su número; el personal ve también sus productores afiliados "
                        + "(productores y clientes, solo las activas)")
                .parametros(ParametroSpec.entero("comunidad"))
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER, TipoRol.PRODUCTOR, TipoRol.CLIENTE)
                .ejemplo("VERCOM[\"1\"]")
                .handler(this::verComunidad).construir());
        catalogo.registrar(ComandoSpec.comando("INSCOM").casoDeUso(CU)
                .descripcion("Registra una comunidad agrícola; departamento vacío = Santa Cruz")
                .parametros(ParametroSpec.texto("nombre", 120), ParametroSpec.texto("municipio", 80),
                        ParametroSpec.texto("departamento", 40).opcional(), ParametroSpec.texto("descripcion", 500).opcional())
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER).modificaDatos()
                .ejemplo("INSCOM[\"Comunidad San Antonio del Lomerío\",\"San Antonio de Lomerío\",\"\",\"Artesanas del jabón de totaí\"]")
                .handler((p, s) -> detalleComunidad("Comunidad registrada", comunidades.crear(s, datos(p))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("MODCOM").casoDeUso(CU)
                .descripcion("Modifica una comunidad; deje en \"\" los datos que no cambian")
                .parametros(ParametroSpec.entero("comunidad"), ParametroSpec.texto("nombre", 120).opcional(),
                        ParametroSpec.texto("municipio", 80).opcional(), ParametroSpec.texto("departamento", 40).opcional(),
                        ParametroSpec.texto("descripcion", 500).opcional())
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER).modificaDatos()
                .ejemplo("MODCOM[\"2\",\"\",\"\",\"\",\"Apicultores organizados alrededor de un apiario comunitario\"]")
                .handler((p, s) -> detalleComunidad("Comunidad modificada",
                        comunidades.modificar(s, p.entero("comunidad"), datos(p))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("DESCOM").casoDeUso(CU)
                .descripcion("Desactiva una comunidad (baja lógica); sus productores conservan su afiliación")
                .parametros(ParametroSpec.entero("comunidad"), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("DESCOM[\"3\",\"La comunidad se fusionó con otra vecina\"]")
                .handler((p, s) -> detalleComunidad("Comunidad desactivada",
                        comunidades.desactivar(s, p.entero("comunidad"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("ACTCOM").casoDeUso(CU)
                .descripcion("Reactiva una comunidad desactivada")
                .parametros(ParametroSpec.entero("comunidad"), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("ACTCOM[\"4\",\"Se afiliaron nuevas familias productoras\"]")
                .handler((p, s) -> detalleComunidad("Comunidad reactivada",
                        comunidades.reactivar(s, p.entero("comunidad"), p.texto("motivo"))))
                .construir());
    }

    private void registrarSolicitudes(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("SOLPRD").casoDeUso(CU)
                .descripcion("Quiero ser productor de FEDER: presenta (o vuelve a presentar) su solicitud de alta")
                .parametros(ParametroSpec.entero("comunidad"), ParametroSpec.texto("ubicacion_actividad", 200))
                .roles(TipoRol.CLIENTE).modificaDatos()
                .ejemplo("SOLPRD[\"2\",\"Colmenas familiares junto al río, zona sur de la comunidad\"]")
                .handler((p, s) -> detallePerfil("Solicitud presentada",
                        productores.solicitar(s, p.entero("comunidad"), p.texto("ubicacion_actividad"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("LISPRD").casoDeUso(CU)
                .descripcion("Lista los productores y solicitudes, opcionalmente por estado de la solicitud")
                .parametros(ParametroSpec.enumeracion("estado", EstadoSolicitudProductor.class).conComodin())
                .roles(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER)
                .ejemplo("LISPRD[\"PENDIENTE\"]")
                .handler(this::listarProductores).construir());
        catalogo.registrar(ComandoSpec.comando("VERPRD").casoDeUso(CU)
                .descripcion("Muestra un perfil de productor por número o código; \"*\" muestra el suyo")
                .parametros(ParametroSpec.texto("productor", 20).conComodin())
                .registrado()
                .ejemplo("VERPRD[\"PROD-0001\"]")
                .handler((p, s) -> detallePerfil("Perfil de productor", productores.consultar(s, p.texto("productor"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("APRPRD").casoDeUso(CU)
                .descripcion("Aprueba una solicitud PENDIENTE, concede el rol PRODUCTOR y avisa por correo al titular")
                .parametros(ParametroSpec.entero("productor"), ParametroSpec.texto("justificacion", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("APRPRD[\"4\",\"Identidad verificada con el documento y colmenas comprobadas en la visita\"]")
                .handler((p, s) -> detallePerfil("Solicitud aprobada",
                        productores.aprobar(s, p.entero("productor"), p.texto("justificacion"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("RECPRD").casoDeUso(CU)
                .descripcion("Rechaza una solicitud PENDIENTE con su motivo, que se envía por correo al titular")
                .parametros(ParametroSpec.entero("productor"), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("RECPRD[\"4\",\"No se pudo verificar la actividad declarada; corrija sus datos y vuelva a solicitar\"]")
                .handler((p, s) -> detallePerfil("Solicitud rechazada",
                        productores.rechazar(s, p.entero("productor"), p.texto("motivo"))))
                .construir());
    }

    private void registrarPerfiles(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("REHPRD").casoDeUso(CU)
                .descripcion("Rehabilita un perfil APROBADA cuyo rol PRODUCTOR fue revocado y avisa por correo al titular")
                .parametros(ParametroSpec.entero("productor"), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("REHPRD[\"1\",\"Se resolvió la causa de la revocación tras la revisión\"]")
                .handler((p, s) -> detallePerfil("Productor rehabilitado",
                        productores.rehabilitar(s, p.entero("productor"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("DESPRD").casoDeUso(CU)
                .descripcion("Da de baja a un productor por número o código: revoca su rol PRODUCTOR (igual que REVROL), "
                        + "su perfil queda inactivo y se avisa por correo al titular; se revierte con REHPRD")
                .parametros(ParametroSpec.texto("productor", 20), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("DESPRD[\"PROD-0001\",\"Dejó de producir y pidió la baja de su perfil\"]")
                .handler(this::darDeBaja)
                .construir());
        catalogo.registrar(ComandoSpec.comando("CAMPRD").casoDeUso(CU)
                .descripcion("Cambia la comunidad actual de un productor sin alterar el origen de su producción registrada")
                .parametros(ParametroSpec.entero("productor"), ParametroSpec.entero("comunidad"), ParametroSpec.texto("motivo", 255))
                .roles(TipoRol.ADMINISTRADOR).modificaDatos()
                .ejemplo("CAMPRD[\"2\",\"3\",\"El productor trasladó su apiario a la comunidad El Palmar\"]")
                .handler((p, s) -> detallePerfil("Comunidad del productor cambiada",
                        productores.cambiarComunidad(s, p.entero("productor"), p.entero("comunidad"), p.texto("motivo"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("MODPRD").casoDeUso(CU)
                .descripcion("El productor actualiza la ubicación general de su actividad")
                .parametros(ParametroSpec.texto("ubicacion_actividad", 200))
                .roles(TipoRol.PRODUCTOR).modificaDatos()
                .ejemplo("MODPRD[\"Taller familiar de extracción de aceite, zona norte de la comunidad\"]")
                .handler((p, s) -> detallePerfil("Perfil actualizado",
                        productores.actualizarUbicacion(s, p.texto("ubicacion_actividad"))))
                .construir());
    }

    private static ServicioComunidades.DatosComunidad datos(Parametros p) {
        return new ServicioComunidades.DatosComunidad(p.texto("nombre"), p.texto("municipio"), p.texto("departamento"),
                p.texto("descripcion"));
    }

    private ResultadoOperacion listarComunidades(Parametros p, Solicitante s) {
        List<ComunidadResumen> obtenidas = comunidades.listar(s, p.texto("filtro"));
        List<ComunidadResumen> lista = Listados.recortar(obtenidas);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Comunidades agrícolas",
                List.of("N.º", "Nombre", "Municipio", "Departamento", "Activa", "Productores", "Descripción"));
        for (ComunidadResumen r : lista) {
            ComunidadAgricola c = r.comunidad();
            tabla.fila(c.getId(), c.getNombre(), c.getMunicipio(), c.getDepartamento(), FormatoNegocio.siNo(c.getActiva()),
                    r.productores(), c.getDescripcion());
        }
        return ResultadoOperacion.exito("Comunidades agrícolas", lista.size() + " comunidad(es)." + Listados.aviso(obtenidas))
                .tabla(tabla);
    }

    /**
     * DESPRD (plan fase C, C6): atajo de REVROL[correo,"PRODUCTOR",motivo] para el CRUD del productor. Localiza el
     * perfil por número o código y revoca el rol con la misma lógica (bitácora, perfil inactivo y aviso al titular).
     */
    private ResultadoOperacion darDeBaja(Parametros p, Solicitante s) {
        PerfilProductor perfil = productores.consultar(s, p.texto("productor"));
        roles.revocar(s, perfil.correoTitular(), TipoRol.PRODUCTOR, p.texto("motivo"));
        return detallePerfil("Productor dado de baja", productores.consultar(s, p.texto("productor")));
    }

    private ResultadoOperacion verComunidad(Parametros p, Solicitante s) {
        ServicioComunidades.ComunidadConProductores x = comunidades.ver(s, p.entero("comunidad"));
        long activos = x.productores().stream().filter(f -> "Sí".equals(f.get(4))).count();
        ResultadoOperacion r = detalleComunidad("Comunidad agrícola", x.comunidad())
                .detalle("Productores activos", activos);
        if (!s.esPersonalInterno()) {
            return r;
        }
        List<List<Object>> lista = Listados.recortar(x.productores());
        return r.tabla(ModuloListadosGenerales.tabla("Productores afiliados", List.of("Código", "Titular", "Correo",
                "Solicitud", "Activo", "Afiliación"), lista));
    }

    private ResultadoOperacion listarProductores(Parametros p, Solicitante s) {
        List<PerfilProductor> obtenidos = productores.listar(s, p.enumeracion("estado", EstadoSolicitudProductor.class));
        List<PerfilProductor> lista = Listados.recortar(obtenidos);
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Productores",
                List.of("N.º", "Código", "Titular", "Correo", "Comunidad", "Solicitud", "Activo", "Presentada"));
        for (PerfilProductor perfil : lista) {
            Productor pr = perfil.productor();
            tabla.fila(pr.getId(), pr.getCodigoProductor(), perfil.nombreTitular(), perfil.correoTitular(), perfil.comunidad(),
                    pr.getEstadoSolicitud(), FormatoNegocio.siNo(pr.getActivo()), FormatoNegocio.fechaHora(pr.getFechaSolicitud()));
        }
        return ResultadoOperacion.exito("Productores y solicitudes", lista.size() + " perfil(es)." + Listados.aviso(obtenidos))
                .tabla(tabla);
    }

    private static ResultadoOperacion detalleComunidad(String titulo, ComunidadAgricola c) {
        return ResultadoOperacion.exito(titulo, "")
                .detalle("N.º", c.getId())
                .detalle("Nombre", c.getNombre())
                .detalle("Municipio", c.getMunicipio())
                .detalle("Departamento", c.getDepartamento())
                .detalle("Descripción", c.getDescripcion())
                .detalle("Activa", FormatoNegocio.siNo(c.getActiva()));
    }

    private static ResultadoOperacion detallePerfil(String titulo, PerfilProductor perfil) {
        Productor p = perfil.productor();
        return ResultadoOperacion.exito(titulo, "")
                .detalle("N.º de perfil", p.getId())
                .detalle("Código de productor", p.getCodigoProductor())
                .detalle("Titular", perfil.nombreTitular())
                .detalle("Correo", perfil.correoTitular())
                .detalle("Comunidad", perfil.comunidad() + " (" + perfil.municipio() + ")"
                        + (perfil.comunidadActiva() ? "" : " - comunidad inactiva"))
                .detalle("Ubicación de la actividad", p.getUbicacionActividad())
                .detalle("Estado de la solicitud", p.getEstadoSolicitud())
                .detalle("Presentada", FormatoNegocio.fechaHora(p.getFechaSolicitud()))
                .detalle("Revisada", FormatoNegocio.fechaHora(p.getFechaRevision()))
                .detalle("Motivo del rechazo", p.getMotivoRechazo())
                .detalle("Afiliación", FormatoNegocio.fecha(p.getFechaAfiliacion()))
                .detalle("Perfil activo", FormatoNegocio.siNo(p.getActivo()));
    }
}
