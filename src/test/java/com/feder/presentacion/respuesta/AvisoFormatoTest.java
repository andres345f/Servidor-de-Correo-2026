package com.feder.presentacion.respuesta;

import com.feder.negocio.comun.ResultadoOperacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvisoFormatoTest {

    private final ResponseFormatter formateador = new ResponseFormatter();

    @Test
    @DisplayName("Un aviso a un tercero se presenta como Aviso y no muestra «Comando procesado»")
    void avisoSinComando() {
        ResultadoOperacion aviso = ResultadoOperacion.exito("Pago confirmado", "Recibimos Bs 300.00 del pedido PED-2026-0008 (QR).")
                .detalle("Pedido", "PED-2026-0008");
        RespuestaFormateada r = formateador.formatearAviso(aviso);
        assertEquals("FEDER - Aviso: Pago confirmado", r.asunto());
        assertFalse(r.textoPlano().contains("Comando procesado"), r.textoPlano());
        assertTrue(r.textoPlano().contains("Recibimos Bs 300.00"));
        assertFalse(r.html().contains("Comando procesado"));
        assertTrue(r.html().contains("estado aviso"));
        assertTrue(r.html().contains("PED-2026-0008"));
    }

    @Test
    @DisplayName("La respuesta a un comando sigue mostrando el comando procesado")
    void respuestaConComando() {
        RespuestaFormateada r = formateador.formatear(ResultadoOperacion.exito("Pagos", "1 pago."), "LISPAG[\"*\"]");
        assertTrue(r.asunto().startsWith("FEDER - LISPAG - Éxito: "));
        assertTrue(r.textoPlano().contains("Comando procesado: LISPAG[\"*\"]"));
        assertTrue(r.html().contains("Comando procesado"));
    }
}
