package com.feder;

import com.feder.configuracion.AppConfig;
import com.feder.datos.ConexionBD;
import com.feder.datos.GestorTransacciones;
import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.mensajeria.CorreoSalienteRepositorio;
import com.feder.datos.mensajeria.MensajeCorreoRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.datos.seguridad.IdentidadRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ModuloAyuda;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.mensajeria.ServicioMensajeria;
import com.feder.negocio.notificaciones.ModuloNotificaciones;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.negocio.pagos.ModuloPagos;
import com.feder.negocio.pagos.PasarelaPagos;
import com.feder.negocio.pagos.PasarelaPagosLocal;
import com.feder.pagofacil.ConfiguracionPagoFacil;
import com.feder.pagofacil.PasarelaPagoFacil;
import com.feder.negocio.posventa.ModuloPosventa;
import com.feder.negocio.seguridad.ServicioConfirmacion;
import com.feder.negocio.seguridad.ServicioIdentidad;
import com.feder.negocio.catalogo.ModuloCatalogo;
import com.feder.negocio.entregas.ModuloEntregas;
import com.feder.negocio.indicadores.ModuloIndicadores;
import com.feder.negocio.mantenimiento.ServicioMantenimiento;
import com.feder.negocio.inventario.ModuloInventario;
import com.feder.negocio.listados.ModuloListadosGenerales;
import com.feder.negocio.produccion.ModuloProduccion;
import com.feder.negocio.productores.ModuloProductores;
import com.feder.negocio.reportes.ModuloReportes;
import com.feder.negocio.usuarios.ModuloUsuarios;
import com.feder.negocio.ventas.ModuloVentas;
import com.feder.presentacion.correo.mime.AdjuntoCorreo;
import com.feder.presentacion.procesamiento.CicloCorreo;
import com.feder.presentacion.respuesta.exportacion.ExportadorReporte;
import com.feder.presentacion.respuesta.exportacion.FabricaExportadores;
import com.feder.presentacion.procesamiento.ConfiguracionCorreo;
import com.feder.presentacion.procesamiento.GeneradorAdjuntos;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * Raíz de composición: crea y conecta los objetos de las tres capas a partir de la configuración.
 * Es el único lugar donde se instancian todas las piezas; el resto del código las recibe.
 */
public final class Aplicacion {

    private final AppConfig config;
    private final ConexionBD conexion;
    private final GestorTransacciones transacciones;
    private final ContextoNegocio contexto;
    private final CatalogoComandos catalogo;
    private final ServicioMensajeria mensajeria;
    /** Configuración de PagoFácil, o null si no está configurado (se usa la pasarela local). */
    private final ConfiguracionPagoFacil pagoFacil;
    private final ModuloPagos moduloPagos;

    public Aplicacion(AppConfig config) {
        this(config, Clock.systemUTC());
    }

    public Aplicacion(AppConfig config, Clock reloj) {
        this.config = config;
        this.conexion = new ConexionBD(config.requerido("db.url"), config.requerido("db.username"),
                config.secreto("db.password"));
        this.transacciones = new GestorTransacciones(conexion);
        this.contexto = new ContextoNegocio(transacciones, reloj, new ServicioBitacora(new BitacoraRepositorio()),
                new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
        this.catalogo = new CatalogoComandos();
        int diasProximoVencimiento = config.entero("negocio.dias.proximo.vencimiento", 1, 365);
        this.pagoFacil = ConfiguracionPagoFacil.desde(config);
        PasarelaPagos pasarela = pagoFacil == null ? new PasarelaPagosLocal() : new PasarelaPagoFacil(pagoFacil);
        this.moduloPagos = new ModuloPagos(contexto, pasarela);
        for (ModuloComandos modulo : modulos(contexto, diasProximoVencimiento, moduloPagos)) {
            catalogo.registrarModulo(modulo);
        }
        int limitePorHora = config.entero("seguridad.limite.solicitudes.por.hora", 1, 10_000);
        ServicioConfirmacion confirmaciones = new ServicioConfirmacion(new ConfirmacionRepositorio(), reloj,
                Duration.ofMinutes(config.entero("seguridad.confirmacion.minutos", 1, 1440)), limitePorHora);
        ComandoDispatcher dispatcher = new ComandoDispatcher(catalogo, new ServicioIdentidad(new IdentidadRepositorio()),
                confirmaciones, transacciones);
        this.mensajeria = new ServicioMensajeria(transacciones, new MensajeCorreoRepositorio(),
                new CorreoSalienteRepositorio(), dispatcher, reloj, limitePorHora,
                config.entero("procesamiento.max.intentos.envio", 1, 1000));
    }

    /** Módulos de comandos de los ocho casos de uso más la ayuda, con la pasarela de pagos local. */
    static List<ModuloComandos> modulos(ContextoNegocio contexto, int diasProximoVencimiento) {
        return modulos(contexto, diasProximoVencimiento, new ModuloPagos(contexto));
    }

    static List<ModuloComandos> modulos(ContextoNegocio contexto, int diasProximoVencimiento, ModuloPagos moduloPagos) {
        return List.of(
                new ModuloAyuda(),
                new ModuloUsuarios(contexto),                          // CU1
                new ModuloProductores(contexto),                       // CU2
                new ModuloProduccion(contexto),                        // CU3
                new ModuloCatalogo(contexto),                          // CU4 catálogo
                new ModuloInventario(contexto),                        // CU4 compras e inventario
                new ModuloVentas(contexto),                            // CU5
                moduloPagos,                                           // CU6 pagos, facturas y reembolsos
                new ModuloEntregas(contexto),                          // CU6 preparación, despacho y entregas
                new ModuloPosventa(contexto),                          // CU6 devoluciones
                new ModuloNotificaciones(contexto),                    // CU6 notificaciones
                new ModuloIndicadores(contexto),                       // CU7
                new ModuloReportes(contexto, diasProximoVencimiento),  // CU8
                new ModuloListadosGenerales(contexto));                // listados por tabla (CU1, CU4, CU5, CU6)
    }

    public CicloCorreo nuevoCiclo() {
        Runnable verificarPagos = pagoFacil == null ? null
                : moduloPagos.verificadorPagos(Duration.ofSeconds(pagoFacil.consultaSegundos()), contexto.reloj());
        return new CicloCorreo(ConfiguracionCorreo.desde(config), mensajeria, generadorAdjuntos(),
                new ServicioMantenimiento(contexto, verificarPagos));
    }

    /** Pasarela de pagos en uso, para mostrarla al arrancar. */
    public String nombrePasarelaPagos() {
        return moduloPagos.nombrePasarela();
    }

    /** Configuración de PagoFácil, o null si no está configurado. */
    public ConfiguracionPagoFacil pagoFacil() {
        return pagoFacil;
    }

    /** Los reportes de CU8 piden un archivo PDF o Excel: se genera aquí, en presentación, y se adjunta. */
    static GeneradorAdjuntos generadorAdjuntos() {
        return resultado -> {
            if (resultado.imagenContenido() != null) {
                // Por ejemplo, el código QR de un pago con PagoFácil.
                return new AdjuntoCorreo(resultado.imagenNombre(), "image/png", resultado.imagenContenido());
            }
            if (resultado.formatoExportacion() == null) {
                return null;
            }
            ExportadorReporte exportador = FabricaExportadores.para(resultado.formatoExportacion());
            return new AdjuntoCorreo(resultado.nombreArchivo() + "." + exportador.extension(),
                    exportador.tipoMime(), exportador.exportar(resultado));
        };
    }

    public Duration intervalo() {
        return Duration.ofSeconds(config.entero("procesamiento.intervalo.segundos", 5, 3600));
    }

    public AppConfig config() {
        return config;
    }

    public ConexionBD conexion() {
        return conexion;
    }

    public ContextoNegocio contexto() {
        return contexto;
    }

    public CatalogoComandos catalogo() {
        return catalogo;
    }

    public ServicioMensajeria mensajeria() {
        return mensajeria;
    }
}
