package com.feder.configuracion;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Configuración central de la aplicación.
 *
 * El entorno se elige con APP_ENV (local o tecnoweb), nunca editando código.
 * Cada valor se busca en este orden:
 *   1. Variable de entorno (la clave en mayúsculas y con '_' en lugar de '.', p. ej. DB_PASSWORD).
 *   2. Archivo privado config/application-{entorno}.properties (excluido de Git).
 *   3. Valores por defecto no sensibles definidos en esta clase.
 * Las contraseñas solo se leen; jamás se imprimen ni se incluyen en mensajes de error.
 */
public final class AppConfig {

    public static final String ENTORNO_LOCAL = "local";
    public static final String ENTORNO_TECNOWEB = "tecnoweb";

    private static final Set<String> CLAVES_SECRETAS = Set.of("db.password", "mail.password");

    /** Todas las claves admitidas: cualquiera puede darse por variable de entorno (DB_URL, MAIL_HOST...). */
    private static final Set<String> CLAVES_ADMITIDAS = Set.of(
            "db.url", "db.username", "db.password",
            "mail.host", "mail.username", "mail.password", "mail.from", "mail.buzon.id",
            "mail.pop3.host", "mail.pop3.port", "mail.pop3.security", "mail.pop3.eliminar.procesados",
            "mail.smtp.host", "mail.smtp.port", "mail.smtp.security", "mail.smtp.auth",
            "mail.timeout.ms", "mail.plazo.envio.segundos", "mail.plazo.recepcion.segundos", "mail.permitir.texto.plano",
            "procesamiento.intervalo.segundos", "procesamiento.max.mensajes.por.ciclo", "procesamiento.max.intentos.envio",
            "seguridad.confirmacion.minutos", "seguridad.limite.solicitudes.por.hora", "logs.dir",
            "negocio.dias.proximo.vencimiento");

    private static final Map<String, String> POR_DEFECTO = Map.ofEntries(
            Map.entry("mail.pop3.security", "TLS"),
            Map.entry("mail.smtp.security", "STARTTLS"),
            Map.entry("mail.smtp.auth", "PLAIN"),
            Map.entry("mail.timeout.ms", "20000"),
            Map.entry("mail.plazo.envio.segundos", "120"),
            Map.entry("mail.plazo.recepcion.segundos", "120"),
            Map.entry("procesamiento.intervalo.segundos", "60"),
            Map.entry("procesamiento.max.mensajes.por.ciclo", "20"),
            // Con esperas de 2, 4, 8... hasta 60 minutos, 24 intentos cubren aproximadamente un día.
            Map.entry("procesamiento.max.intentos.envio", "24"),
            Map.entry("seguridad.confirmacion.minutos", "30"),
            // Panel de vencimientos de CU8: lotes que vencen dentro de este número de días.
            Map.entry("negocio.dias.proximo.vencimiento", "30"),
            Map.entry("seguridad.limite.solicitudes.por.hora", "30"),
            Map.entry("logs.dir", "logs"));

    private final String entorno;
    private final Map<String, String> valores;

    private AppConfig(String entorno, Map<String, String> valores) {
        this.entorno = entorno;
        this.valores = Collections.unmodifiableMap(valores);
    }

    /** Carga la configuración del entorno indicado por APP_ENV (por defecto, local). */
    public static AppConfig cargar(Path carpetaConfig) {
        String entorno = leerVariable("APP_ENV");
        if (entorno == null || entorno.isBlank()) {
            entorno = System.getProperty("app.env", ENTORNO_LOCAL);
        }
        entorno = entorno.trim().toLowerCase(Locale.ROOT);
        if (!entorno.equals(ENTORNO_LOCAL) && !entorno.equals(ENTORNO_TECNOWEB)) {
            throw new ConfiguracionException("APP_ENV debe ser 'local' o 'tecnoweb' (valor recibido: " + entorno + ").");
        }

        Map<String, String> valores = new HashMap<>(POR_DEFECTO);
        Path archivo = carpetaConfig.resolve("application-" + entorno + ".properties");
        if (Files.isRegularFile(archivo)) {
            valores.putAll(leerArchivo(archivo));
        }
        // Las variables de entorno tienen prioridad sobre el archivo privado.
        for (String clave : CLAVES_ADMITIDAS) {
            String delEntorno = leerVariable(nombreVariable(clave));
            if (delEntorno != null && !delEntorno.isEmpty()) {
                // Las contraseñas se toman tal cual: un espacio al inicio o al final puede ser parte de ellas.
                valores.put(clave, CLAVES_SECRETAS.contains(clave) ? delEntorno : delEntorno.trim());
            }
        }
        return new AppConfig(entorno, valores);
    }

    /** Construye una configuración en memoria (pruebas automatizadas). */
    public static AppConfig deMapa(String entorno, Map<String, String> valores) {
        for (String clave : valores.keySet()) {
            if (!CLAVES_ADMITIDAS.contains(clave)) {
                throw new ConfiguracionException("Clave de configuración desconocida: " + clave);
            }
        }
        Map<String, String> todos = new HashMap<>(POR_DEFECTO);
        todos.putAll(valores);
        return new AppConfig(entorno, todos);
    }

    public String entorno() {
        return entorno;
    }

    public boolean esTecnoweb() {
        return ENTORNO_TECNOWEB.equals(entorno);
    }

    /** Devuelve un valor obligatorio o explica cómo configurarlo si falta. */
    public String requerido(String clave) {
        String valor = valores.get(clave);
        if (valor == null || valor.isBlank()) {
            throw new ConfiguracionException("Falta la configuración '" + clave + "'. Defínala en la variable de entorno "
                    + nombreVariable(clave) + " o en config/application-" + entorno + ".properties.");
        }
        return valor;
    }

    public String opcional(String clave, String porDefecto) {
        String valor = valores.get(clave);
        return valor == null || valor.isBlank() ? porDefecto : valor;
    }

    public int entero(String clave) {
        String valor = requerido(clave);
        try {
            return Integer.parseInt(valor.trim());
        } catch (NumberFormatException e) {
            throw new ConfiguracionException("La configuración '" + clave + "' debe ser un número entero.");
        }
    }

    /** Entero dentro de un rango; por ejemplo, un máximo de 0 mensajes por ciclo desactivaría la recepción. */
    public int entero(String clave, int minimo, int maximo) {
        int valor = entero(clave);
        if (valor < minimo || valor > maximo) {
            throw new ConfiguracionException("La configuración '" + clave + "' debe estar entre " + minimo + " y " + maximo
                    + " (valor actual: " + valor + ").");
        }
        return valor;
    }

    /**
     * Contraseñas. En el entorno local pueden quedar vacías (PostgreSQL local con autenticación trust);
     * en TecnoWeb son obligatorias y su ausencia se explica en lugar de intentar conectar sin ellas.
     */
    public String secreto(String clave) {
        if (!CLAVES_SECRETAS.contains(clave)) {
            throw new IllegalArgumentException("La clave no está declarada como secreta: " + clave);
        }
        String valor = valores.getOrDefault(clave, "");
        if (esTecnoweb() && valor.isEmpty()) {
            throw new ConfiguracionException("Falta la contraseña '" + clave + "' del entorno tecnoweb. Defínala en la "
                    + "variable de entorno " + nombreVariable(clave) + " (nunca en archivos versionados).");
        }
        return valor;
    }

    /** Indica si la contraseña está configurada, sin revelarla (para el diagnóstico). */
    public boolean tieneSecreto(String clave) {
        if (!CLAVES_SECRETAS.contains(clave)) {
            throw new IllegalArgumentException("La clave no está declarada como secreta: " + clave);
        }
        return !valores.getOrDefault(clave, "").isEmpty();
    }

    /** Resumen apto para logs: nunca contiene secretos, tampoco los que pudieran venir dentro de una URL. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AppConfig[entorno=").append(entorno);
        valores.keySet().stream().sorted().forEach(clave -> sb.append(", ").append(clave).append('=')
                .append(CLAVES_SECRETAS.contains(clave) ? "****" : sinCredenciales(valores.get(clave))));
        return sb.append(']').toString();
    }

    /** Quita la parte de consulta (?user=...&password=...) e información de usuario de una URL. */
    static String sinCredenciales(String valor) {
        if (valor == null) {
            return null;
        }
        return valor.replaceFirst("\\?.*$", "?****").replaceFirst("//[^/@]*@", "//****@");
    }

    static String nombreVariable(String clave) {
        return clave.toUpperCase(Locale.ROOT).replace('.', '_');
    }

    private static Map<String, String> leerArchivo(Path archivo) {
        Properties propiedades = new Properties();
        try {
            String contenido = Files.readString(archivo, java.nio.charset.StandardCharsets.UTF_8);
            // El Bloc de notas y PowerShell pueden guardar UTF-8 con marca BOM al inicio: se descarta.
            if (contenido.startsWith("﻿")) {
                contenido = contenido.substring(1);
            }
            propiedades.load(new java.io.StringReader(contenido));
        } catch (IOException e) {
            throw new ConfiguracionException("No se pudo leer " + archivo.getFileName() + ": " + e.getMessage());
        }
        Map<String, String> mapa = new HashMap<>();
        for (String clave : propiedades.stringPropertyNames()) {
            String nombre = clave.trim();
            if (!CLAVES_ADMITIDAS.contains(nombre)) {
                throw new ConfiguracionException("Clave desconocida '" + nombre + "' en " + archivo.getFileName()
                        + " (¿error de escritura?).");
            }
            String valor = propiedades.getProperty(clave);
            mapa.put(nombre, CLAVES_SECRETAS.contains(nombre) ? valor : valor.trim());
        }
        return mapa;
    }

    private static String leerVariable(String nombre) {
        return System.getenv(nombre);
    }
}
