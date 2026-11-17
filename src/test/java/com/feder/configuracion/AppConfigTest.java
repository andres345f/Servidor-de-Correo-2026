package com.feder.configuracion;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigTest {

    @Test
    void enTecnowebLaContrasenaEsObligatoriaYSeExplicaComoConfigurarla() {
        AppConfig config = AppConfig.deMapa(AppConfig.ENTORNO_TECNOWEB, Map.of("db.url", "jdbc:postgresql://db/x"));
        ConfiguracionException e = assertThrows(ConfiguracionException.class, () -> config.secreto("db.password"));
        assertTrue(e.getMessage().contains("DB_PASSWORD"));
    }

    @Test
    void enLocalLaContrasenaPuedeQuedarVacia() {
        AppConfig config = AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.of());
        assertEquals("", config.secreto("db.password"));
    }

    @Test
    void claveDesconocidaSeRechaza() {
        assertThrows(ConfiguracionException.class,
                () -> AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.of("mail.pasword", "x")));
    }

    @Test
    void elResumenNoMuestraSecretosNiCredencialesEnUrls() {
        AppConfig config = AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.of(
                "db.password", "s3creta",
                "db.url", "jdbc:postgresql://h:5432/db?user=u&password=otra"));
        String resumen = config.toString();
        assertFalse(resumen.contains("s3creta"));
        assertFalse(resumen.contains("otra"));
    }

    @Test
    void archivoGuardadoConMarcaBomSeLeeCorrectamente(@org.junit.jupiter.api.io.TempDir java.nio.file.Path carpeta)
            throws Exception {
        java.nio.file.Files.writeString(carpeta.resolve("application-local.properties"),
                "﻿# comentario\ndb.url=jdbc:postgresql://127.0.0.1:55432/feder_local\n", java.nio.charset.StandardCharsets.UTF_8);
        AppConfig config = AppConfig.cargar(carpeta);
        assertEquals("jdbc:postgresql://127.0.0.1:55432/feder_local", config.requerido("db.url"));
    }

    @Test
    void rangosNumericosSeValidan() {
        AppConfig config = AppConfig.deMapa(AppConfig.ENTORNO_LOCAL, Map.of("procesamiento.max.mensajes.por.ciclo", "0"));
        assertThrows(ConfiguracionException.class, () -> config.entero("procesamiento.max.mensajes.por.ciclo", 1, 500));
    }
}
