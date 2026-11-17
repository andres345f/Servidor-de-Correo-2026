package com.feder.negocio.ventas;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.ModuloCatalogo;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.inventario.ModuloInventario;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comandos agregados o cambiados en la revisión, de extremo a extremo por el despachador real con
 * confirmación por correo: BLQLTF, LISVEN, EXPRES, RECPRE, PAGCMP tras CANCMP, VERCAR por productos y
 * PEDINM con el subtotal obligatorio.
 */
class ComandosRevisionCu45Test {

    private ContextoNegocio ctx;
    private ComandoDispatcher despachador;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        despachador = despachador(ctx, new ModuloCatalogo(ctx), new ModuloInventario(ctx), new ModuloVentas(ctx));
    }

    @Test
    @DisplayName("BLQLTF pide confirmación y bloquea el lote; LISVEN es de solo lectura")
    void bloqueoYPanelDeVencimientos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = confirmarComando(despachador, "BLQLTF",
                    List.of("LF-2026-008", "Humedad en las bolsas detectada en la revisión del almacén"), CARLA);
            assertTrue(r.esExito(), r.mensaje());
            assertEquals("DISPONIBLE -> BLOQUEADO", detalle(r, "Estado"));
            ResultadoOperacion panel = despachador.despachar("LISVEN", List.of("30"), CARLA).resultado;
            assertTrue(panel.esExito(), panel.mensaje());
            assertEquals("Acceso denegado", despachador.despachar("BLQLTF", List.of("LF-2026-001", "x"), ELMER).resultado.titulo());
        });
    }

    @Test
    @DisplayName("EXPRES y VENLTF piden confirmación y se ejecutan con CONFIRMAR")
    void procesosConConfirmacion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion exp = confirmarComando(despachador, "EXPRES", List.of("*"), JORGE);
            assertEquals("Expiración de reservas", exp.titulo(), exp.mensaje());
            ResultadoOperacion ven = confirmarComando(despachador, "VENLTF", List.of("*"), CARLA);
            assertEquals("Proceso de vencimiento", ven.titulo(), ven.mensaje());
        });
    }

    @Test
    @DisplayName("Ejemplos en orden sobre la semilla: CANCMP deja CMP-2026-003 RECIBIDA, PAGCMP la paga y RECPRE documenta la diferencia")
    void ejemplosDeComprasEnOrden() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion cancelada = confirmarComando(despachador, "CANCMP", List.of("CMP-2026-003", "LP-2026-008", "*",
                    "La productora no entregará las tabletas restantes", "CAN-CMP003-LP008-01"), CARLA);
            assertEquals("RECIBIDA", detalle(cancelada, "Estado de la compra"));
            ResultadoOperacion pago = confirmarComando(despachador, "PAGCMP", List.of("CMP-2026-003"), CARLA);
            assertTrue(pago.esExito(), pago.mensaje());
            ResultadoOperacion rect = confirmarComando(despachador, "RECPRE", List.of("CMP-2026-003", "LP-2026-008", "16.00", "80",
                    "El acta de compra fijó 16.00 Bs por tableta"), ADMIN);
            assertTrue(rect.esExito(), rect.mensaje());
            assertEquals("-80.00", detalle(rect, "Diferencia (Bs)"));
            assertEquals("Acceso denegado", despachador.despachar("RECPRE", List.of("CMP-2026-003", "LP-2026-008", "16.00", "80",
                    "x"), CARLA).resultado.titulo());
        });
    }

    @Test
    @DisplayName("VERCAR por productos muestra el subtotal de la selección; PEDINM sin subtotal no se acepta")
    void subtotalDeLaSeleccion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion vista = despachador.despachar("VERCAR", List.of("FED-CHO-100"), ANDRES).resultado;
            assertEquals("84.00", detalle(vista, "Subtotal de productos disponibles (Bs)"));
            ResultadoOperacion sinSubtotal = despachador.despachar("PEDINM", List.of("RECOJO", "", "", "Andrés Molina Soliz",
                    "70000114", "FED-CHO-100", "", ""), ANDRES).resultado;
            assertFalse(sinSubtotal.esExito(), sinSubtotal.titulo());
            ResultadoOperacion pedido = confirmarComando(despachador, "PEDINM", List.of("RECOJO", "", "", "Andrés Molina Soliz",
                    "70000114", "FED-CHO-100", "84.00", ""), ANDRES);
            assertTrue(pedido.esExito(), pedido.mensaje());
        });
    }
}
