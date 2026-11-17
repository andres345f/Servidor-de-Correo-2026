package com.feder.negocio.mensajeria;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextoSintaxisTest {

    @Test
    @DisplayName("El error de sintaxis termina en punto antes de «Formato esperado»")
    void puntoFinal() {
        assertEquals("Se esperaba un parámetro entre comillas en la posición 8, pero se encontró '*'.",
                ServicioMensajeria.conPuntoFinal("Se esperaba un parámetro entre comillas en la posición 8, pero se encontró '*'"));
        assertEquals("Falta el corchete de cierre.", ServicioMensajeria.conPuntoFinal("Falta el corchete de cierre."));
        assertEquals("¿Comando vacío?", ServicioMensajeria.conPuntoFinal("¿Comando vacío?"));
    }

    @Test
    @DisplayName("El asunto que se muestra queda en una línea: el tabulador del plegado de Gmail pasa a ser un espacio")
    void asuntoEnUnaLinea() {
        assertEquals("=?UTF-8?Q?Ana_Pe?= =?UTF-8?Q?=C3=B1a?=",
                ServicioMensajeria.enUnaLinea("=?UTF-8?Q?Ana_Pe?=\t=?UTF-8?Q?=C3=B1a?="));
        assertEquals("primera segunda", ServicioMensajeria.enUnaLinea("primera\r\n\tsegunda"));
        assertEquals("", ServicioMensajeria.enUnaLinea(null));
    }
}
