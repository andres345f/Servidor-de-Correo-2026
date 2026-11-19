package com.feder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.presentacion.correo.mime.AdjuntoCorreo;
import org.junit.jupiter.api.Test;

/** El QR de PagoFácil viaja como imagen PNG adjunta a la respuesta del REGPAG. */
class AdjuntoQrTest {

    @Test
    void unaImagenDelResultadoSeAdjuntaComoPng() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G'};
        ResultadoOperacion r = ResultadoOperacion.exito("Pago registrado", "")
                .adjuntarImagenPng("QR-PAG-PED-2026-0008-01", png);
        AdjuntoCorreo adjunto = Aplicacion.generadorAdjuntos().generar(r);
        assertEquals("QR-PAG-PED-2026-0008-01.png", adjunto.nombreArchivo());
        assertEquals("image/png", adjunto.tipoMime());
        assertArrayEquals(png, adjunto.contenido());
    }

    @Test
    void sinImagenNiReporteNoHayAdjunto() {
        assertNull(Aplicacion.generadorAdjuntos().generar(ResultadoOperacion.exito("Pagos", "")));
    }
}
