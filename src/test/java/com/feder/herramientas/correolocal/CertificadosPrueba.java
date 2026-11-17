package com.feder.herramientas.correolocal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Certificados autofirmados generados en target/ con el keytool del JDK, solo para pruebas.
 *
 * El cliente confía únicamente en el certificado generado; nunca se desactiva la validación.
 */
public final class CertificadosPrueba {

    private static final char[] CLAVE = "clave-almacen-pruebas".toCharArray();
    private static final String ALIAS = "feder-prueba";

    private final KeyStore almacen;

    private CertificadosPrueba(KeyStore almacen) {
        this.almacen = almacen;
    }

    /** Ruta del keytool del JDK que ejecuta las pruebas. */
    public static Path keytool() {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        return Path.of(System.getProperty("java.home"), "bin", windows ? "keytool.exe" : "keytool");
    }

    /**
     * Genera un par de claves RSA con un certificado cuyos nombres alternativos son {@code san}
     * (por ejemplo "ip:127.0.0.1,dns:localhost").
     */
    public static CertificadosPrueba generar(String nombre, String san) throws IOException, GeneralSecurityException,
            InterruptedException {
        Path carpeta = Path.of("target", "tls-pruebas");
        Files.createDirectories(carpeta);
        Path archivo = carpeta.resolve(nombre + ".p12");
        Files.deleteIfExists(archivo);
        List<String> comando = List.of(keytool().toString(), "-genkeypair", "-alias", ALIAS, "-keyalg", "RSA",
                "-keysize", "2048", "-validity", "2", "-dname", "CN=FEDER pruebas", "-ext", "SAN=" + san,
                "-storetype", "PKCS12", "-keystore", archivo.toString(),
                "-storepass", new String(CLAVE), "-keypass", new String(CLAVE), "-noprompt");
        Process proceso = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String salida = new String(proceso.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!proceso.waitFor(60, TimeUnit.SECONDS) || proceso.exitValue() != 0) {
            throw new IOException("keytool no pudo generar el certificado de prueba: " + salida);
        }
        KeyStore almacen = KeyStore.getInstance("PKCS12");
        try (InputStream entrada = Files.newInputStream(archivo)) {
            almacen.load(entrada, CLAVE);
        }
        return new CertificadosPrueba(almacen);
    }

    /** Contexto TLS del servidor con la clave privada generada. */
    public SSLContext contextoServidor() throws GeneralSecurityException {
        KeyManagerFactory claves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        claves.init(almacen, CLAVE);
        SSLContext contexto = SSLContext.getInstance("TLS");
        contexto.init(claves.getKeyManagers(), null, null);
        return contexto;
    }

    /** Contexto TLS de cliente que confía solo en este certificado. */
    public SSLContext contextoClienteQueConfiaSoloEnEste() throws GeneralSecurityException, IOException {
        Certificate certificado = almacen.getCertificate(ALIAS);
        KeyStore confianza = KeyStore.getInstance(KeyStore.getDefaultType());
        confianza.load(null, null);
        confianza.setCertificateEntry("servidor-prueba", certificado);
        TrustManagerFactory fabrica = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        fabrica.init(confianza);
        SSLContext contexto = SSLContext.getInstance("TLS");
        contexto.init(null, fabrica.getTrustManagers(), null);
        return contexto;
    }
}
