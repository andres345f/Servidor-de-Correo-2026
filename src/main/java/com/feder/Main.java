package com.feder;

import com.feder.configuracion.AppConfig;
import com.feder.datos.ConexionBD;
import com.feder.configuracion.ConfiguracionException;
import com.feder.configuracion.ConfiguradorLogs;
import com.feder.herramientas.clientedemo.Argumentos;
import com.feder.herramientas.clientedemo.ClienteDemo;
import com.feder.negocio.usuarios.ServicioInicializacion;
import com.feder.herramientas.correolocal.ServidorCorreoLocal;
import com.feder.herramientas.diagnostico.DiagnosticoInfraestructura;
import com.feder.herramientas.instalacion.ActualizadorEsquema;
import com.feder.herramientas.instalacion.InstaladorEsquema;
import com.feder.herramientas.documentacion.GeneradorCatalogoComandos;
import com.feder.presentacion.procesamiento.CicloCorreo;
import com.feder.presentacion.procesamiento.ProcesadorContinuo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Punto de entrada. El entorno se elige con APP_ENV (local o tecnoweb).
 *
 *   procesar            procesa el buzón de forma continua (Ctrl+C o scripts/detener.ps1 para salir)
 *   procesar-una-vez    ejecuta un solo ciclo (útil con cron)
 *   reintentar-envios   devuelve a la bandeja de salida las respuestas que quedaron FALLIDO
 *   inicializar-admin   crea el primer ADMINISTRADOR (--correo= --ci= --nombres= --apellidos= --telefono=)
 *   instalar-esquema    muestra la base de destino sin modificarla; con --confirmar=BASE instala db/01_schema.sql
 *   actualizar-esquema  muestra las migraciones pendientes sin modificar nada; con --confirmar=BASE las aplica
 *   diagnostico         comprueba DNS, puertos, POP3, SMTP y PostgreSQL sin modificar nada
 *   documentar-comandos genera docs/COMANDOS.md a partir del catálogo real
 *   correo-local        inicia el servidor de correo local de demostración
 *   cliente-demo        envía un comando o lee respuestas en el servidor local
 */
public final class Main {

    private static final Path ARCHIVO_DETENER = Path.of("estado", "DETENER");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String accion = args.length == 0 ? "ayuda" : args[0];
        String[] resto = Arrays.copyOfRange(args, Math.min(1, args.length), args.length);
        try {
            switch (accion) {
                case "procesar":
                    procesarContinuo();
                    break;
                case "procesar-una-vez":
                    CicloCorreo.Resumen resumen = crearAplicacionDeCorreo().nuevoCiclo().ejecutar();
                    System.out.println("Ciclo terminado: " + resumen);
                    break;
                case "inicializar-admin":
                    inicializarAdministrador(resto);
                    break;
                case "reintentar-envios":
                    int reactivadas = crearAplicacionDeCorreo().mensajeria().reactivarEnviosFallidos();
                    System.out.println("Respuestas devueltas a la bandeja de salida: " + reactivadas);
                    break;
                case "instalar-esquema":
                    instalarEsquema(resto);
                    break;
                case "actualizar-esquema":
                    actualizarEsquema(resto);
                    break;
                case "diagnostico":
                    boolean ok = new DiagnosticoInfraestructura(AppConfig.cargar(Path.of("config")), System.out).ejecutar();
                    System.exit(ok ? 0 : 1);
                    break;
                case "documentar-comandos":
                    Path destino = Path.of("docs", "COMANDOS.md");
                    GeneradorCatalogoComandos.escribir(crearAplicacion().catalogo(), destino);
                    System.out.println("Catálogo de comandos escrito en " + destino);
                    break;
                case "correo-local":
                    ServidorCorreoLocal.main(resto);
                    break;
                case "cliente-demo":
                    ClienteDemo.main(resto);
                    break;
                default:
                    imprimirAyuda();
            }
        } catch (ConfiguracionException e) {
            // Errores de configuración: mensaje claro y sin trazas (nunca contienen secretos).
            System.err.println("Configuración incompleta: " + e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Instalación controlada del esquema (base institucional o nueva): primero se muestra el destino y solo se
     * instala si se confirma con el nombre exacto de la base. Nunca borra tablas ni carga datos de demostración.
     */
    private static void instalarEsquema(String[] args) {
        java.util.Map<String, String> opciones = Argumentos.leer(args);
        AppConfig config = AppConfig.cargar(Path.of("config"));
        System.out.println("Entorno: " + config.entorno());
        ConexionBD conexion = new ConexionBD(config.requerido("db.url"), config.requerido("db.username"),
                config.secreto("db.password"));
        boolean instalado = new InstaladorEsquema(conexion, Path.of(opciones.getOrDefault("archivo", "db/01_schema.sql")),
                System.out).ejecutar(opciones.get("confirmar"));
        System.exit(instalado ? 0 : 1);
    }

    /**
     * Actualización controlada de una base que ya tiene FEDER: primero se muestran el destino y las migraciones
     * pendientes (db/migraciones) y solo se aplican si se confirma con el nombre exacto de la base. Las
     * migraciones solo agregan objetos al esquema: nunca borran ni reescriben datos.
     */
    private static void actualizarEsquema(String[] args) {
        java.util.Map<String, String> opciones = Argumentos.leer(args);
        AppConfig config = AppConfig.cargar(Path.of("config"));
        System.out.println("Entorno: " + config.entorno());
        ConexionBD conexion = new ConexionBD(config.requerido("db.url"), config.requerido("db.username"),
                config.secreto("db.password"));
        boolean alDia = new ActualizadorEsquema(conexion, Path.of(opciones.getOrDefault("carpeta", "db/migraciones")),
                System.out).ejecutar(opciones.get("confirmar"));
        System.exit(alDia ? 0 : 1);
    }

    /**
     * Aplicación para las acciones que procesan correo. Antes de leer el buzón comprueba que la base tenga
     * todas las migraciones del esquema; si falta alguna se detiene con un aviso, porque con un esquema
     * anterior cada mensaje fallaría. La actualización nunca se hace sola: la ejecuta el operador.
     */
    private static Aplicacion crearAplicacionDeCorreo() throws InterruptedException {
        return crearAplicacionDeCorreo(false);
    }

    /**
     * @param reintentar en el modo continuo, si la base no responde al arrancar se vuelve a intentar con espera
     *                   creciente (hasta 10 minutos), igual que hace el ciclo de correo; no se procesa ningún
     *                   correo hasta comprobar el esquema. Devuelve null si mientras tanto se pidió detener.
     *                   En las acciones de un solo ciclo el error se informa y el proceso termina.
     */
    private static Aplicacion crearAplicacionDeCorreo(boolean reintentar) throws InterruptedException {
        Aplicacion aplicacion = crearAplicacion();
        long esperaMs = 10_000;
        List<String> pendientes;
        while (true) {
            try {
                pendientes = ActualizadorEsquema.pendientes(aplicacion.conexion());
                break;
            } catch (RuntimeException e) {
                if (!reintentar) {
                    throw e;
                }
                System.err.println("No se pudo comprobar el esquema (" + e.getMessage() + "); se reintentará en "
                        + esperaMs / 1000 + " s.");
                if (esperarODetener(esperaMs)) {
                    return null;
                }
                esperaMs = Math.min(esperaMs * 2, 600_000);
            }
        }
        if (!pendientes.isEmpty()) {
            System.err.println("El esquema de la base de datos está desactualizado: falta aplicar "
                    + String.join(", ", pendientes) + ".");
            System.err.println("No se procesó ningún correo. Ejecute 'actualizar-esquema' para ver el detalle y"
                    + " repítalo con --confirmar=<nombre exacto de la base> para aplicar las migraciones.");
            System.exit(3);
        }
        return aplicacion;
    }

    /** Espera el tiempo indicado; devuelve true si en ese lapso se creó el archivo de detención. */
    private static boolean esperarODetener(long milisegundos) throws InterruptedException {
        long fin = System.currentTimeMillis() + milisegundos;
        while (System.currentTimeMillis() < fin) {
            if (Files.exists(ARCHIVO_DETENER)) {
                try {
                    Files.deleteIfExists(ARCHIVO_DETENER);
                } catch (IOException e) {
                    System.err.println("No se pudo borrar el archivo de detención: " + e.getMessage());
                }
                return true;
            }
            Thread.sleep(Math.min(1000, Math.max(1, fin - System.currentTimeMillis())));
        }
        return false;
    }

    private static Aplicacion crearAplicacion() {
        AppConfig config = AppConfig.cargar(Path.of("config"));
        ConfiguradorLogs.configurar(Path.of(config.opcional("logs.dir", "logs")));
        return new Aplicacion(config);
    }

    private static void procesarContinuo() throws IOException, InterruptedException {
        // Un archivo de detención que quedó de una ejecución anterior se descarta antes de empezar; uno creado
        // después de arrancar (también mientras se espera a la base de datos) sí detiene el proceso.
        Files.createDirectories(ARCHIVO_DETENER.getParent());
        Files.deleteIfExists(ARCHIVO_DETENER);
        Aplicacion aplicacion = crearAplicacionDeCorreo(true);
        if (aplicacion == null || Files.deleteIfExists(ARCHIVO_DETENER)) {
            System.out.println("Detención solicitada antes de empezar a procesar.");
            return;
        }
        ProcesadorContinuo procesador = new ProcesadorContinuo(aplicacion.nuevoCiclo(), aplicacion.intervalo());
        // Apagado ordenado con Ctrl+C: se termina el ciclo en curso antes de salir.
        Runtime.getRuntime().addShutdownHook(new Thread(procesador::detener, "apagado-ordenado"));
        // Apagado ordenado desde otro proceso: scripts/detener.ps1 crea el archivo estado/DETENER.
        Thread vigilante = new Thread(() -> vigilarArchivoDetener(procesador), "vigilante-detener");
        vigilante.setDaemon(true);
        vigilante.start();
        System.out.println("Procesando el buzón (entorno " + aplicacion.config().entorno() + "). Ctrl+C para detener.");
        procesador.ejecutar();
    }

    /**
     * Instalación del primer ADMINISTRADOR (R099): se ejecuta en el servidor, nunca por correo, y solo
     * funciona si todavía no existe ninguna concesión ADMINISTRADOR. Los datos son los de la persona
     * real responsable; no hay valores por defecto ni contraseñas.
     */
    private static void inicializarAdministrador(String[] args) {
        java.util.Map<String, String> datos = Argumentos.leer(args);
        Aplicacion aplicacion = crearAplicacion();
        ServicioInicializacion.Inicializacion resultado = new ServicioInicializacion(aplicacion.contexto())
                .inicializarAdministrador(Argumentos.requerido(datos, "correo"), Argumentos.requerido(datos, "ci"),
                        Argumentos.requerido(datos, "nombres"), Argumentos.requerido(datos, "apellidos"),
                        Argumentos.requerido(datos, "telefono"));
        System.out.println("Administrador inicial creado: " + resultado.cuenta().getCorreo()
                + " (concesión " + resultado.concesionId() + "). Desde ahora gestiona el sistema por correo.");
    }

    private static void vigilarArchivoDetener(ProcesadorContinuo procesador) {
        try {
            while (!Files.exists(ARCHIVO_DETENER)) {
                Thread.sleep(2000);
            }
            Files.deleteIfExists(ARCHIVO_DETENER);
            procesador.detener();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            System.err.println("No se pudo leer el archivo de detención: " + e.getMessage());
        }
    }

    private static void imprimirAyuda() {
        System.out.println("Uso: java -jar feder-email-driven.jar <accion>");
        System.out.println("  procesar | procesar-una-vez | reintentar-envios | inicializar-admin | instalar-esquema | actualizar-esquema"
                + " | diagnostico | documentar-comandos | correo-local | cliente-demo");
        System.out.println("Entorno: variable APP_ENV=local|tecnoweb; configuración en config/application-<entorno>.properties");
    }
}
