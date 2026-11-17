package com.feder.negocio.comandos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextosDeErrorTest {

    @Test
    @DisplayName("Las frases del error de comando inexistente se unen con un solo espacio, sin huecos dobles")
    void unirFrasesSinEspaciosDobles() {
        assertEquals("El comando XYZ no existe. Envíe HELP[\"*\"] para ver la lista completa.",
                ComandoDispatcher.unirFrases("El comando XYZ no existe.", "", "Envíe HELP[\"*\"] para ver la lista completa."));
        assertEquals("El comando LISPRD no existe. ¿Quiso decir: LISPRO? Envíe HELP[\"*\"].",
                ComandoDispatcher.unirFrases("El comando LISPRD no existe.", "¿Quiso decir: LISPRO?", "Envíe HELP[\"*\"]."));
    }
}
