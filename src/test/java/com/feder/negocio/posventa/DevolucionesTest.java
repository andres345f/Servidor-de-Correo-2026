package com.feder.negocio.posventa;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.pagos.ApoyoCu6;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static com.feder.negocio.pagos.ApoyoCu6.escribir;
import static com.feder.negocio.pagos.ApoyoCu6.exito;
import static com.feder.negocio.pagos.ApoyoCu6.leer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Devoluciones (R018, R020-R026) sobre la semilla: DEV-2026-0003 (SOLICITADA, 1 frasco de mermelada del pedido
 * PED-2026-0007 de Daniela, despachado desde LF-2026-008; la línea son 2 frascos por Bs 48) y PED-2026-0006 de Andrés.
 */
class DevolucionesTest {

    private static final String DEV = "DEV-2026-0003";

    private ContextoNegocio ctx;
    private ComandoDispatcher d;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        d = ApoyoCu6.despachador(ctx);
    }

    @Test
    @DisplayName("R018: el importe se atribuye por línea sin superar su subtotal, aunque el redondeo reparta centavos")
    void atribucionDelImporte() {
        BigDecimal subtotal = new BigDecimal("100.00");
        BigDecimal cantidad = new BigDecimal("3");
        BigDecimal primera = AtribucionImporte.calcular(subtotal, cantidad, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal segunda = AtribucionImporte.calcular(subtotal, cantidad, BigDecimal.ONE, primera, BigDecimal.ONE);
        BigDecimal tercera = AtribucionImporte.calcular(subtotal, cantidad, new BigDecimal("2"), primera.add(segunda), BigDecimal.ONE);
        assertIgual("33.33", primera);
        assertIgual("33.34", segunda);
        assertIgual("33.33", tercera);
        assertIgual("100.00", primera.add(segunda).add(tercera));
        assertIgual("0", AtribucionImporte.calcular(subtotal, cantidad, new BigDecimal("3"), new BigDecimal("100.00"), BigDecimal.ONE));
        assertIgual("56.00", AtribucionImporte.calcular(new BigDecimal("112.00"), new BigDecimal("4"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("2")));
    }

    @Test
    @DisplayName("Recepción, inspección y aprobación con REEMBOLSO: importe fijo, reintegro ordenado, reingreso de lo apto y cierre tras ejecutar")
    void reembolsoCompleto() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimal saldoAntes = decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 8");
            exito(d, CARLA, "RECDEV", DEV, "1");
            exito(d, CARLA, "INSDEV", DEV, "1");
            assertEquals(0, numero("SELECT count(*) FROM movimiento_inventario WHERE devolucion_id = 3"));
            ResultadoOperacion a = exito(d, JORGE, "APRDEV", DEV, "REEMBOLSO", "1", "Tapa abollada verificada");
            assertEquals("24.00", detalle(a, "Importe reembolsable (Bs)"));
            assertEquals("1", detalle(a, "Reincorporado al stock"));
            assertEquals("PENDIENTE|24.00|8", texto("SELECT estado || '|' || monto || '|' || pago_id FROM reembolso "
                    + "WHERE clave_idempotencia = 'DEV3-PAGO8-1'"));
            assertIgual(saldoAntes.add(BigDecimal.ONE).toPlainString(), decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 8"));
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 11 AND tipo = 'DEVOLUCION_RESUELTA'"
                    + " AND pedido_id = 7 AND titulo = 'Devolución aprobada'"));
            ResultadoOperacion prematuro = escribir(d, CARLA, "CERDEV", DEV);
            assertTrue(prematuro.mensaje().contains("EJECUTADO deben sumar Bs 24.00"), prematuro.mensaje());
            exito(d, CARLA, "EJEREM", "DEV3-PAGO8-1", "RQR-20261116-000500");
            exito(d, CARLA, "CERDEV", DEV);
            assertEquals("COMPLETADA", texto("SELECT estado FROM devolucion WHERE id = 3 AND fecha_cierre IS NOT NULL"));
            assertEquals("Estado no permitido", escribir(d, CARLA, "APRDEV", DEV, "REEMBOLSO", "1", "").titulo());
        });
    }

    @Test
    @DisplayName("SOLDEV: solo sobre pedidos propios ENTREGADO, sin superar el despacho; reenviar el mismo código no duplica")
    void solicitudYLimites() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = exito(d, ANDRES, "SOLDEV", "PED-2026-0006", "FED-CAF-250", "1", "Bolsa rota", "DEV-AM-T1");
            assertEquals("SOLICITADA", detalle(r, "Estado"));
            assertEquals(23, numero("SELECT movimiento_inventario_id FROM devolucion WHERE codigo = 'DEV-AM-T1'"));
            assertEquals("Devolución ya registrada", exito(d, ANDRES, "SOLDEV", "PED-2026-0006", "FED-CAF-250", "1", "Bolsa rota",
                    "DEV-AM-T1").titulo());
            ResultadoOperacion exceso = escribir(d, ANDRES, "SOLDEV", "PED-2026-0006", "FED-CAF-250", "3", "Todas rotas", "");
            assertTrue(exceso.mensaje().contains("lo máximo devolvible en una sola solicitud es 2"), exceso.mensaje());
            assertEquals("No encontrado", escribir(d, ELMER, "SOLDEV", "PED-2026-0006", "FED-CAF-250", "1", "x", "").titulo());
            assertEquals("Registro duplicado", escribir(d, DANIELA, "SOLDEV", "PED-2026-0001", "FED-CAF-250", "1", "x", "DEV-AM-T1").titulo());
            // Reutilizar el código con otra cantidad o motivo no devuelve en silencio la solicitud anterior.
            assertEquals("Registro duplicado", escribir(d, ANDRES, "SOLDEV", "PED-2026-0006", "FED-CAF-250", "2", "Bolsa rota",
                    "DEV-AM-T1").titulo());
            assertEquals("Registro duplicado", escribir(d, ANDRES, "SOLDEV", "PED-2026-0006", "FED-CAF-250", "1", "Otro motivo",
                    "DEV-AM-T1").titulo());
            // Los códigos DEV-AAAA-NNNN los asigna FEDER: un cliente no puede ocupar el de otra devolución.
            ResultadoOperacion reservado = escribir(d, ANDRES, "SOLDEV", "PED-2026-0006", "FED-CAF-250", "1", "x", "dev-2026-0099");
            assertEquals("Regla de negocio", reservado.titulo());
            assertTrue(reservado.mensaje().contains("los asigna FEDER"), reservado.mensaje());
            assertEquals("Estado no permitido", escribir(d, PATRICIA, "SOLDEV", "PED-2026-0010", "FED-CHO-100", "1", "x", "").titulo());
            assertTrue(escribir(d, ANDRES, "SOLDEV", "PED-2026-0006", "FED-MIE-500", "1", "x", "").mensaje().contains("no incluye"));
            assertEquals(1, leer(d, ANDRES, "LISDEV", "*").tablas().get(0).filas().size());
        });
    }

    @Test
    @DisplayName("R022 (b) y R024: lo recibido no supera lo reclamado ni se inspecciona sin recepción")
    void recepcionEInspeccion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertTrue(escribir(d, CARLA, "INSDEV", DEV, "1").mensaje().contains("Primero registre la recepción"));
            assertTrue(escribir(d, CARLA, "RECDEV", DEV, "2").mensaje().contains("más de lo reclamado"));
            exito(d, CARLA, "RECDEV", DEV, "1");
            assertEquals("Estado no permitido", escribir(d, CARLA, "RECDEV", DEV, "1").titulo());
            assertTrue(escribir(d, CARLA, "INSDEV", DEV, "2").mensaje().contains("no pueden superar las recibidas"));
        });
    }

    @Test
    @DisplayName("Reposición: se entrega con SALIDA_REPOSICION hasta lo aprobado y la devolución se completa")
    void reposicion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion a = exito(d, CARLA, "APRDEV", DEV, "REPOSICION", "1", "");
            assertEquals("", detalle(a, "Importe reembolsable (Bs)"));
            assertTrue(escribir(d, CARLA, "CERDEV", DEV).mensaje().contains("reposición entregada"));
            assertTrue(escribir(d, CARLA, "REPDEV", DEV, "1", "DEV3-REPO-1").mensaje().contains("las asigna FEDER"));
            exito(d, CARLA, "REPDEV", DEV, "1", "");
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE devolucion_id = 3 AND tipo = 'SALIDA_REPOSICION'"));
            assertTrue(escribir(d, CARLA, "REPDEV", DEV, "1", "").mensaje().contains("superaría lo aprobado"));
            assertEquals(0, numero("SELECT count(*) FROM reembolso WHERE devolucion_id = 3"));
            exito(d, CARLA, "CERDEV", DEV);
            assertEquals("COMPLETADA", texto("SELECT estado FROM devolucion WHERE id = 3"));
        });
    }

    @Test
    @DisplayName("Rectificación: la aprobación equivocada se anula compensando su reingreso y la nueva devolución hereda lo recibido")
    void rectificacion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECDEV", DEV, "1");
            exito(d, CARLA, "INSDEV", DEV, "1");
            exito(d, CARLA, "APRDEV", DEV, "REPOSICION", "1", "");
            BigDecimal saldoAprobada = decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 8");
            ResultadoOperacion r = exito(d, CARLA, "ANUDEV", DEV, "Se aprobó una reposición: la clienta pidió reembolso");
            // La rectificación recibe un código que solo FEDER asigna, a partir de su identificador.
            String rectificacion = detalle(r, "Devolución que la rectifica");
            assertTrue(rectificacion.matches("DEV-\\d{4}-\\d{4,}") && !rectificacion.equals(DEV), rectificacion);
            assertEquals("ANULADA", texto("SELECT estado FROM devolucion WHERE id = 3 AND fecha_anulacion IS NOT NULL"));
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE devolucion_id = 3 AND tipo = 'AJUSTE_NEGATIVO' "
                    + "AND lote_feder_id = 8 AND cantidad = 1"));
            assertIgual(saldoAprobada.subtract(BigDecimal.ONE).toPlainString(), decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 8"));
            assertEquals("SOLICITADA|1.000|1.000|3", texto("SELECT estado || '|' || cantidad_recibida || '|' || cantidad_apta || '|' "
                    + "|| devolucion_anulada_id FROM devolucion WHERE codigo = ?", rectificacion));
            ResultadoOperacion nueva = exito(d, CARLA, "APRDEV", rectificacion, "REEMBOLSO", "1", "");
            assertEquals("24.00", detalle(nueva, "Importe reembolsable (Bs)"));
            assertIgual(saldoAprobada.toPlainString(), decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 8"));
            assertTrue(escribir(d, CARLA, "ANUDEV", rectificacion, "Otro error").mensaje().contains("decisión queda en firme"));
        });
    }

    @Test
    @DisplayName("Separación de funciones: el personal no aprueba la devolución de un pedido propio; otro miembro sí")
    void aprobacionDeUnPedidoPropio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            // Jorge es PERSONAL_FEDER y CLIENTE: se le asigna el pedido de la devolución de la semilla.
            ejecutar("UPDATE pedido SET usuario_id = 3 WHERE codigo = 'PED-2026-0007'");
            exito(d, CARLA, "RECDEV", DEV, "1");
            ResultadoOperacion propia = escribir(d, JORGE, "APRDEV", DEV, "REEMBOLSO", "1", "");
            assertEquals("Acceso denegado", propia.titulo());
            assertTrue(propia.mensaje().contains("otro miembro del personal"), propia.mensaje());
            assertEquals("SOLICITADA", texto("SELECT estado FROM devolucion WHERE id = 3"));
            exito(d, CARLA, "APRDEV", DEV, "REEMBOLSO", "1", "");
        });
    }

    @Test
    @DisplayName("El rechazo deja la mercancía en custodia, avisa a la clienta y la decisión ya no cambia")
    void rechazo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECDEV", DEV, "1");
            exito(d, JORGE, "RCHDEV", DEV, "La abolladura no afecta el cierre hermético");
            assertEquals("RECHAZADA", texto("SELECT estado FROM devolucion WHERE id = 3 AND fecha_resolucion IS NOT NULL"));
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 11 AND titulo = 'Devolución rechazada'"));
            assertEquals(0, numero("SELECT count(*) FROM movimiento_inventario WHERE devolucion_id = 3"));
            assertEquals("Estado no permitido", escribir(d, CARLA, "APRDEV", DEV, "REEMBOLSO", "1", "").titulo());
            assertTrue(leer(d, ANDRES, "LISDEV", "*").tablas().get(0).filas().stream().noneMatch(f -> f.get(0).equals(DEV)));
            assertEquals("Acceso denegado", leer(d, DANIELA, "RCHDEV", DEV, "x").titulo());
        });
    }
}
