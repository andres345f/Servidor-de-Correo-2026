package com.feder.negocio.pagos;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static com.feder.negocio.pagos.ApoyoCu6.exito;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R053 (3) y R049: un pago en efectivo registrado sin aceptación comercial no es un cobro por conciliar, así que no
 * impide la expiración del pedido; al expirar, ese intento termina RECHAZADO. Las reservas de PED-2026-0011 vencen el
 * 2026-11-15 a las 20:40 (-04): el reloj de la prueba está después de ese momento.
 */
class ExpiracionConEfectivoTest {

    @Test
    @DisplayName("Un pedido PENDIENTE con efectivo sin aceptar expira y su pago en efectivo queda RECHAZADO")
    void efectivoNoImpideLaExpiracion() {
        Clock reloj = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC);
        ContextoNegocio ctx = new ContextoNegocio(new GestorTransacciones(BaseDatosPrueba.conexion()), reloj,
                new ServicioBitacora(new BitacoraRepositorio()), new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
        ComandoDispatcher d = ApoyoCu6.despachador(ctx);
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECPAG", "pago-ped0011-01", "El código QR venció sin cobro");
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "EFECTIVO", "143.00", "");
            ResultadoOperacion r = exito(d, CARLA, "EXPRES", "*");
            assertTrue(detalle(r, "Pedidos cancelados").contains("PED-2026-0011"), detalle(r, "Pedidos cancelados"));
            assertEquals("CANCELADO", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals("RECHAZADO", texto("SELECT estado FROM pago WHERE pedido_id = 11 AND metodo = 'EFECTIVO'"));
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND estado = 'EXPIRADA'"));
        });
    }
}
