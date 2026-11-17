package com.feder.negocio.pagos;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static com.feder.negocio.pagos.ApoyoCu6.escribir;
import static com.feder.negocio.pagos.ApoyoCu6.exito;
import static com.feder.negocio.pagos.ApoyoCu6.leer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reintegros (R076-R082): el pedido PED-2026-0010 (Patricia, pagado Bs 218 por QR) se cancela y su reembolso
 * PENDIENTE se ejecuta o falla y se sustituye, siempre dentro de lo cobrado.
 */
class ReembolsosTest {

    private static final String CLAVE = "CANC-PED10-PAGO10";

    private ContextoNegocio ctx;
    private ComandoDispatcher d;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        d = ApoyoCu6.despachador(ctx);
    }

    @Test
    @DisplayName("EJEREM ejecuta el reembolso con su comprobante y avisa al cliente; un reembolso EJECUTADO no cambia más")
    void ejecucion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, PATRICIA, "CANPED", "PED-2026-0010", "Compré lo mismo en otra tienda");
            assertEquals("PENDIENTE|218.00", texto("SELECT estado || '|' || monto FROM reembolso WHERE clave_idempotencia = ?", CLAVE));
            assertEquals("Registro duplicado", escribir(d, CARLA, "EJEREM", CLAVE, "RQR-20260706-000052").titulo());
            ResultadoOperacion r = exito(d, CARLA, "EJEREM", CLAVE, "RQR-20261116-000077");
            assertEquals("EJECUTADO", detalle(r, "Estado"));
            assertEquals(1, numero("SELECT count(*) FROM reembolso WHERE clave_idempotencia = ? AND fecha_ejecucion IS NOT NULL", CLAVE));
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 13 AND pedido_id = 10 AND tipo = 'REEMBOLSO_EJECUTADO'"));
            assertEquals("Estado no permitido", escribir(d, CARLA, "EJEREM", CLAVE, "RQR-20261116-000078").titulo());
            assertEquals("Estado no permitido", escribir(d, CARLA, "FALREM", CLAVE, "x", "false").titulo());
        });
    }

    @Test
    @DisplayName("FALREM deja el reembolso FALLIDO y lo sustituye por otro PENDIENTE del mismo monto, sin superar lo cobrado")
    void falloYSustitucion() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, PATRICIA, "CANPED", "PED-2026-0010", "Compré lo mismo en otra tienda");
            ResultadoOperacion r = exito(d, JORGE, "FALREM", CLAVE, "La cuenta de destino rechazó la transferencia", "true");
            assertEquals("FALLIDO", detalle(r, "Estado"));
            String sustituto = detalle(r, "Clave del sustituto");
            assertEquals(CLAVE + "-S2", sustituto);
            assertEquals("PENDIENTE|218.00", texto("SELECT estado || '|' || monto FROM reembolso WHERE clave_idempotencia = ?", sustituto));
            assertIgual("218", decimal("SELECT sum(monto) FROM reembolso WHERE pago_id = 10 AND estado IN ('PENDIENTE', 'EJECUTADO')"));
            exito(d, JORGE, "EJEREM", sustituto, "RQR-20261117-000101");
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'REEMBOLSO' AND accion = 'MODIFICAR' "
                    + "AND detalle ->> 'estado_nuevo' = 'FALLIDO'"));
        });
    }

    @Test
    @DisplayName("R082: un reembolso FALLIDO sin sustituto se sustituye después con SUSREM, sin duplicar y sin superar lo cobrado")
    void sustitucionPosterior() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, PATRICIA, "CANPED", "PED-2026-0010", "Compré lo mismo en otra tienda");
            assertEquals("Estado no permitido", escribir(d, CARLA, "SUSREM", CLAVE).titulo());
            exito(d, CARLA, "FALREM", CLAVE, "La clienta debe enviar otra cuenta", "false");
            assertEquals(0, numero("SELECT count(*) FROM reembolso WHERE pago_id = 10 AND estado = 'PENDIENTE'"));
            ResultadoOperacion r = exito(d, CARLA, "SUSREM", CLAVE);
            String sustituto = detalle(r, "Clave");
            assertEquals(CLAVE + "-S2", sustituto);
            assertEquals(CLAVE, detalle(r, "Sustituye a"));
            assertEquals("PENDIENTE|218.00", texto("SELECT estado || '|' || monto FROM reembolso WHERE clave_idempotencia = ?", sustituto));
            // Con un sustituto PENDIENTE no se ordena otro para la misma pareja.
            assertEquals("Estado no permitido", escribir(d, CARLA, "SUSREM", CLAVE).titulo());
            exito(d, CARLA, "EJEREM", sustituto, "RQR-20261118-000200");
            // Lo cobrado ya está reintegrado: no queda importe para otro sustituto.
            ResultadoOperacion sinSaldo = escribir(d, CARLA, "SUSREM", CLAVE);
            assertEquals("Regla de negocio", sinSaldo.titulo());
            assertEquals(2, numero("SELECT count(*) FROM reembolso WHERE pago_id = 10"));
            assertEquals("Acceso denegado", leer(d, PATRICIA, "SUSREM", CLAVE).titulo());
        });
    }

    @Test
    @DisplayName("Separación de funciones: el personal no registra el resultado de los reembolsos de un pedido propio")
    void reembolsoDeUnPedidoPropio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, PATRICIA, "CANPED", "PED-2026-0010", "Compré lo mismo en otra tienda");
            // Jorge es PERSONAL_FEDER y CLIENTE: se le asigna el pedido para probar el conflicto.
            ejecutar("UPDATE pedido SET usuario_id = 3 WHERE id = 10");
            assertEquals("Acceso denegado", escribir(d, JORGE, "EJEREM", CLAVE, "RQR-20261118-000300").titulo());
            assertEquals("Acceso denegado", escribir(d, JORGE, "FALREM", CLAVE, "x", "true").titulo());
            assertEquals("PENDIENTE", texto("SELECT estado FROM reembolso WHERE clave_idempotencia = ?", CLAVE));
            exito(d, CARLA, "EJEREM", CLAVE, "RQR-20261118-000300");
        });
    }

    @Test
    @DisplayName("Un cliente ve solo sus reembolsos y no puede ejecutarlos")
    void propiedadYPermisos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, PATRICIA, "CANPED", "PED-2026-0010", "Compré lo mismo en otra tienda");
            assertEquals("Acceso denegado", leer(d, PATRICIA, "EJEREM", CLAVE, "RQR-1").titulo());
            ResultadoOperacion propios = leer(d, PATRICIA, "LISREM", "*");
            assertTrue(propios.tablas().get(0).filas().stream().allMatch(f -> f.get(1).equals("PED-2026-0004") || f.get(1).equals("PED-2026-0010")));
            assertEquals(0, leer(d, ELMER, "LISREM", "*").tablas().get(0).filas().size());
        });
    }
}
