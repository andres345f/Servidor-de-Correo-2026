package com.feder.negocio.pagos;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pagos multicanal y facturas (R030, R047-R051, R053 (4)) de extremo a extremo por el despachador, sobre la semilla:
 * PED-2026-0011 (Andrés, INMEDIATO PENDIENTE con un QR PENDIENTE de Bs 143) y PED-2026-0008 (Daniela, encargo sin pagos).
 */
class PagosYFacturasTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher d;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        d = ApoyoCu6.despachador(ctx);
    }

    @Test
    @DisplayName("CONPAG verifica el QR: el pago queda PAGADO, el pedido CONFIRMADO y sus reservas dejan de caducar; repetirlo no cambia nada")
    void verificacionConfirmaElPedido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion r = exito(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000245", "143.00");
            assertEquals("CONFIRMADO", detalle(r, "Estado del pedido"));
            assertEquals("PAGADO|QR-20261115-000245", texto("SELECT estado || '|' || referencia_externa FROM pago WHERE id = 11"));
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND fecha_expiracion IS NOT NULL"));
            assertEquals("PENDIENTE>CONFIRMADO|2", texto("SELECT estado_anterior || '>' || estado_nuevo || '|' || usuario_id "
                    + "FROM historial_pedido WHERE pedido_id = 11 ORDER BY id DESC LIMIT 1"));
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE usuario_id = 14 AND pedido_id = 11 AND tipo = 'PAGO_CONFIRMADO'"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PAGO' AND entidad_id = 11 AND accion = 'MODIFICAR' "
                    + "AND usuario_id = 2 AND detalle ->> 'verificacion' = 'MANUAL'"));
            ResultadoOperacion repetido = exito(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000245", "143.00");
            assertEquals("Pago ya confirmado", repetido.titulo());
            assertEquals(1, numero("SELECT count(*) FROM historial_pedido WHERE pedido_id = 11 AND estado_nuevo = 'CONFIRMADO'"));

            // R030: la factura se emitió al completar el pago; FACPED la devuelve sin emitir otra.
            assertEquals(texto("SELECT numero FROM factura WHERE pedido_id = 11"), detalle(r, "Factura emitida"));
            ResultadoOperacion factura = exito(d, ANDRES, "FACPED", "PED-2026-0011", "", "");
            assertEquals("Factura ya emitida", factura.titulo());
            assertEquals("143.00", detalle(factura, "Total (Bs)"));
            assertEquals("99000014", detalle(factura, "NIT o CI"));
            assertEquals("Andrés Molina Soliz", detalle(factura, "Razón social"));
            assertEquals("Factura ya emitida", exito(d, CARLA, "FACPED", "PED-2026-0011", "", "").titulo());
            assertEquals(1, numero("SELECT count(*) FROM factura WHERE pedido_id = 11"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'FACTURA' AND accion = 'CREAR' AND usuario_id = 2 "
                    + "AND detalle ->> 'pedido' = 'PED-2026-0011'"));
            assertEquals("Factura " + detalle(factura, "Número"), leer(d, ANDRES, "VERFAC", "PED-2026-0011").titulo());
        });
    }

    @Test
    @DisplayName("R030: con nombres y apellidos de 80 caracteres la razón social automática se recorta a 150 y el pago se confirma")
    void razonSocialAutomaticaCabeEnLaFactura() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            // 80 + 1 + 80 = 161 caracteres. El carácter 150 es un par sustituto (𝔸): el recorte no debe partirlo.
            String nombres = "A".repeat(80);
            String apellidos = "B".repeat(68) + "𝔸" + "B".repeat(11);
            ejecutar("UPDATE usuario SET nombres = ?, apellidos = ? WHERE id = 14", nombres, apellidos);
            ResultadoOperacion r = exito(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000245", "143.00");
            assertEquals("CONFIRMADO", detalle(r, "Estado del pedido"));
            assertEquals("PAGADO", texto("SELECT estado FROM pago WHERE id = 11"));
            assertEquals(1, numero("SELECT count(*) FROM factura WHERE pedido_id = 11"));
            String razon = texto("SELECT razon_social FROM factura WHERE pedido_id = 11");
            assertEquals(nombres + " " + "B".repeat(68) + "𝔸", razon);
            assertEquals(150, razon.codePointCount(0, razon.length()));
        });
    }

    @Test
    @DisplayName("R030: FACPED rechaza antes de escribir un NIT de más de 20 caracteres o una razón social de más de 150")
    void facturaConDatosExplicitosDemasiadoLargos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            // PED-2026-0010 está pagado por completo: sin su factura, FACPED la emite con los datos indicados.
            ejecutar("DELETE FROM factura WHERE pedido_id = 10");
            // La longitud se valida con los parámetros, antes incluso de pedir la confirmación.
            ResultadoOperacion nit = leer(d, PATRICIA, "FACPED", "PED-2026-0010", "1".repeat(21), "");
            assertEquals("Comando o parámetro inválido", nit.titulo());
            assertTrue(nit.mensaje().contains("20"), nit.mensaje());
            ResultadoOperacion razon = leer(d, PATRICIA, "FACPED", "PED-2026-0010", "", "R".repeat(151));
            assertEquals("Comando o parámetro inválido", razon.titulo());
            assertTrue(razon.mensaje().contains("150"), razon.mensaje());
            assertEquals(0, numero("SELECT count(*) FROM factura WHERE pedido_id = 10"));
            ResultadoOperacion limite = exito(d, PATRICIA, "FACPED", "PED-2026-0010", "1".repeat(20), "R".repeat(150));
            assertEquals("Factura emitida", limite.titulo());
            assertEquals("1".repeat(20), detalle(limite, "NIT o CI"));
            assertEquals("R".repeat(150), detalle(limite, "Razón social"));
        });
    }

    @Test
    @DisplayName("R030: un pedido pagado y cancelado después conserva la factura emitida al completar el pago")
    void facturaSeConservaAlCancelar() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000245", "143.00");
            String numero = texto("SELECT numero FROM factura WHERE pedido_id = 11 AND estado = 'VIGENTE'");
            exito(d, ANDRES, "CANPED", "PED-2026-0011", "Ya no lo necesito");
            assertEquals("CANCELADO", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals("VIGENTE", texto("SELECT estado FROM factura WHERE numero = ?", numero));
            assertEquals("Factura " + numero, leer(d, ANDRES, "VERFAC", "PED-2026-0011").titulo());
            assertEquals("Factura ya emitida", exito(d, ANDRES, "FACPED", "PED-2026-0011", "", "").titulo());
        });
    }

    @Test
    @DisplayName("R047 y R053 (1): un anticipo verificado de un INMEDIATO PENDIENTE es confirmación comercial: sus reservas dejan de caducar")
    void anticipoDeInmediato() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECPAG", "pago-ped0011-01", "El cliente prefiere pagar un anticipo");
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "QR", "50.00", "AM-ANT-0011");
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND fecha_expiracion IS NOT NULL"));
            exito(d, CARLA, "CONPAG", "AM-ANT-0011", "QR-20261115-000500", "50.00");
            assertEquals("PENDIENTE", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND fecha_expiracion IS NOT NULL"));
            assertEquals(0, numero("SELECT count(*) FROM factura WHERE pedido_id = 11"));
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "QR", "93.00", "AM-SAL-0011");
            ResultadoOperacion saldo = exito(d, CARLA, "CONPAG", "AM-SAL-0011", "QR-20261115-000501", "93.00");
            assertEquals("CONFIRMADO", detalle(saldo, "Estado del pedido"));
            assertEquals(1, numero("SELECT count(*) FROM factura WHERE pedido_id = 11 AND total = 143.00"));
        });
    }

    @Test
    @DisplayName("R048 y R049: RECPAG rechaza un efectivo aún sin aceptación comercial, pero no uno ya aceptado contra entrega")
    void rechazoDeEfectivo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECPAG", "pago-ped0011-01", "El cliente cambiará de método");
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "EFECTIVO", "143.00", "AM-EF-1");
            exito(d, CARLA, "RECPAG", "AM-EF-1", "El cliente prefiere pagar con QR antes de la aceptación");
            assertEquals("RECHAZADO", texto("SELECT estado FROM pago WHERE clave_idempotencia = 'AM-EF-1'"));
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "EFECTIVO", "143.00", "AM-EF-2");
            exito(d, CARLA, "CONPED", "PED-2026-0011", "Aceptado contra entrega");
            assertEquals("Estado no permitido", escribir(d, CARLA, "RECPAG", "AM-EF-2", "Cambio de idea").titulo());
            assertEquals("PENDIENTE", texto("SELECT estado FROM pago WHERE clave_idempotencia = 'AM-EF-2'"));
        });
    }

    @Test
    @DisplayName("R048 y R051: reenviar la clave de un intento PAGADO o RECHAZADO no invita a pagar otra vez")
    void reenvioDeIntentoCerrado() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion rechazado = exito(d, PATRICIA, "REGPAG", "PED-2026-0004", "TARJETA", "314.00", "pago-ped0004-01");
            assertEquals("Pago ya registrado", rechazado.titulo());
            assertEquals("RECHAZADO", detalle(rechazado, "Estado"));
            assertTrue(detalle(rechazado, "Indicaciones").contains("registre un intento nuevo"), detalle(rechazado, "Indicaciones"));
            assertFalse(detalle(rechazado, "Indicaciones").contains("Pague"));
            ResultadoOperacion pagado = exito(d, PATRICIA, "REGPAG", "PED-2026-0010", "QR", "218.00", "pago-ped0010-01");
            assertTrue(detalle(pagado, "Indicaciones").contains("ya fue cobrado"), detalle(pagado, "Indicaciones"));
            assertEquals(11, numero("SELECT count(*) FROM pago"));
        });
    }

    @Test
    @DisplayName("R051: un cliente no puede ocupar las claves PAG- que FEDER genera para los pagos de otros pedidos")
    void clavesGeneradasReservadas() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECPAG", "pago-ped0011-01", "Vencido");
            ResultadoOperacion r = escribir(d, ANDRES, "REGPAG", "PED-2026-0011", "QR", "10.00", "pag-PED-2026-0013-01");
            assertEquals("Regla de negocio", r.titulo());
            assertTrue(r.mensaje().contains("PAG-"), r.mensaje());
            assertEquals(0, numero("SELECT count(*) FROM pago WHERE upper(clave_idempotencia) LIKE 'PAG-%'"));
        });
    }

    @Test
    @DisplayName("Separación de funciones: el personal no verifica ni rechaza el pago de un pedido propio; otro miembro sí")
    void verificacionDeUnPedidoPropio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            // Jorge es PERSONAL_FEDER y CLIENTE: se le asigna el pedido para probar el conflicto.
            ejecutar("UPDATE pedido SET usuario_id = 3 WHERE id = 11");
            ResultadoOperacion propio = escribir(d, JORGE, "CONPAG", "pago-ped0011-01", "REF-INVENTADA-1", "143.00");
            assertEquals("Acceso denegado", propio.titulo());
            assertTrue(propio.mensaje().contains("otro miembro del personal"), propio.mensaje());
            assertEquals("Acceso denegado", escribir(d, JORGE, "RECPAG", "pago-ped0011-01", "x").titulo());
            assertEquals("PENDIENTE", texto("SELECT estado FROM pago WHERE id = 11"));
            exito(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000245", "143.00");
        });
    }

    /** Reservas de PED-2026-0011 vencidas hace una hora y su intento QR registrado hace 13 horas, sin verificar. */
    private static void intentoQrAntiguoConReservaVencida() {
        ejecutar("UPDATE reserva_stock SET fecha_expiracion = now() - interval '1 hour' WHERE id IN (19, 20)");
        ejecutar("UPDATE pago SET fecha_registro = now() - interval '13 hours' WHERE id = 11");
    }

    @Test
    @DisplayName("R053 (3) y R048: EXPRES no rechaza un QR PENDIENTE antiguo; lo informa por conciliar y un CONPAG tardío confirma el pedido")
    void intentoAntiguoSeConciliaEnLugarDeRechazarse() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            intentoQrAntiguoConReservaVencida();
            ResultadoOperacion r = exito(d, CARLA, "EXPRES", "*");
            assertEquals("PENDIENTE", texto("SELECT estado FROM pedido WHERE id = 11"), r.mensaje());
            assertEquals("PENDIENTE", texto("SELECT estado FROM pago WHERE id = 11"));
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND estado = 'ACTIVA'"));
            String porConciliar = detalle(r, "Pedidos con un pago por conciliar (CONPAG o RECPAG)");
            assertTrue(porConciliar.contains("PED-2026-0011") && porConciliar.contains("pago-ped0011-01"), porConciliar);
            assertTrue(detalle(r, "Pedidos cancelados").equals("Ninguno"), detalle(r, "Pedidos cancelados"));
            assertEquals(0, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PAGO' AND entidad_id = 11 "
                    + "AND detalle ->> 'estado_nuevo' = 'RECHAZADO'"));

            // El cliente sí pagó a tiempo: el personal verifica tarde el comprobante y el pedido se confirma.
            ResultadoOperacion conciliado = exito(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000245", "143.00");
            assertEquals("CONFIRMADO", detalle(conciliado, "Estado del pedido"));
            assertEquals("PAGADO", texto("SELECT estado FROM pago WHERE id = 11"));
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND estado = 'ACTIVA' "
                    + "AND fecha_expiracion IS NULL"));
            assertEquals(0, numero("SELECT count(*) FROM reembolso r JOIN pago p ON p.id = r.pago_id WHERE p.pedido_id = 11"));
            assertEquals(1, numero("SELECT count(*) FROM factura WHERE pedido_id = 11"));
            exito(d, CARLA, "EXPRES", "*");
            assertEquals("CONFIRMADO", texto("SELECT estado FROM pedido WHERE id = 11"));
        });
    }

    @Test
    @DisplayName("R048 y R053 (3): tras un RECPAG con motivo, un EXPRES posterior expira el pedido y libera su stock")
    void intentoRechazadoPorElPersonalDejaExpirar() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            intentoQrAntiguoConReservaVencida();
            String reservadoEnLotes = "SELECT sum(cantidad_reservada) FROM lote_feder WHERE id IN "
                    + "(SELECT lote_feder_id FROM reserva_stock WHERE id IN (19, 20))";
            BigDecimal reservadoAntes = decimal(reservadoEnLotes);
            BigDecimal reservasDelPedido = decimal("SELECT sum(cantidad) FROM reserva_stock WHERE id IN (19, 20)");
            exito(d, CARLA, "EXPRES", "*");
            assertEquals("PENDIENTE", texto("SELECT estado FROM pedido WHERE id = 11"));

            exito(d, JORGE, "RECPAG", "pago-ped0011-01", "El emisor confirmó que el QR venció sin cobro");
            assertEquals("RECHAZADO", texto("SELECT estado FROM pago WHERE id = 11"));
            ResultadoOperacion r = exito(d, CARLA, "EXPRES", "*");
            assertTrue(detalle(r, "Pedidos cancelados").contains("PED-2026-0011"), detalle(r, "Pedidos cancelados"));
            assertEquals("CANCELADO", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND estado = 'EXPIRADA'"));
            assertIgual(reservadoAntes.subtract(reservasDelPedido).toPlainString(), decimal(reservadoEnLotes));
            // El único rechazo es el del personal, con su motivo: el proceso nunca rechaza el pago por sí mismo.
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PAGO' AND entidad_id = 11 "
                    + "AND detalle ->> 'estado_nuevo' = 'RECHAZADO'"));
            assertEquals(0, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PAGO' AND entidad_id = 11 "
                    + "AND detalle ->> 'estado_nuevo' = 'RECHAZADO' AND usuario_id IS NULL"));
        });
    }

    @Test
    @DisplayName("Un importe distinto, una referencia ya usada o un pago en efectivo no se confirman con CONPAG: el pago sigue PENDIENTE")
    void verificacionRechazaDatosIncoherentes() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion distinto = escribir(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000245", "140.00");
            assertEquals("Regla de negocio", distinto.titulo());
            assertTrue(distinto.mensaje().contains("sigue PENDIENTE"), distinto.mensaje());
            assertEquals("Registro duplicado", escribir(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261113-001187", "143.00").titulo());
            assertEquals("PENDIENTE", texto("SELECT estado FROM pago WHERE id = 11"));
            assertEquals("PENDIENTE", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals("Estado no permitido", escribir(d, CARLA, "CONPAG", "pago-ped0010-01", "QR-X-1", "218.00").titulo());
        });
    }

    @Test
    @DisplayName("Un solo intento PENDIENTE por pedido: reenviarlo no lo duplica, otro distinto se rechaza y tras RECPAG se admite uno nuevo")
    void intentosDePago() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals("Pago ya registrado", exito(d, ANDRES, "REGPAG", "PED-2026-0011", "QR", "143.00", "").titulo());
            ResultadoOperacion otro = escribir(d, ANDRES, "REGPAG", "PED-2026-0011", "TARJETA", "143.00", "");
            assertEquals("Regla de negocio", otro.titulo());
            assertTrue(otro.mensaje().contains("ya tiene un intento de pago PENDIENTE"), otro.mensaje());
            exito(d, JORGE, "RECPAG", "pago-ped0011-01", "El emisor informó que el QR venció sin cobro");
            assertEquals("RECHAZADO", texto("SELECT estado FROM pago WHERE id = 11"));
            assertEquals(1, numero("SELECT count(*) FROM notificacion WHERE pedido_id = 11 AND tipo = 'PAGO_RECHAZADO'"));
            ResultadoOperacion nuevo = exito(d, ANDRES, "REGPAG", "PED-2026-0011", "TARJETA", "143.00", "");
            assertEquals("PAG-PED-2026-0011-02", detalle(nuevo, "Clave del pago"));
            assertTrue(detalle(nuevo, "Indicaciones").contains("no está conectado a un proveedor de pagos"));
            assertEquals("Estado no permitido", escribir(d, JORGE, "RECPAG", "pago-ped0011-01", "Otra vez").titulo());
        });
    }

    @Test
    @DisplayName("Anticipo de un encargo: no supera el saldo, el efectivo es por el saldo completo y la clave hace idempotente el reenvío")
    void anticipoDeEncargo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertTrue(escribir(d, DANIELA, "REGPAG", "PED-2026-0008", "QR", "1100.00", "").mensaje().contains("supera el saldo"));
            assertTrue(escribir(d, DANIELA, "REGPAG", "PED-2026-0008", "EFECTIVO", "500.00", "").mensaje().contains("saldo completo"));
            exito(d, DANIELA, "REGPAG", "PED-2026-0008", "QR", "300.00", "DF-ANT-0008");
            assertEquals("Pago ya registrado", exito(d, DANIELA, "REGPAG", "PED-2026-0008", "QR", "300.00", "DF-ANT-0008").titulo());
            assertEquals("Registro duplicado", escribir(d, DANIELA, "REGPAG", "PED-2026-0008", "QR", "250.00", "DF-ANT-0008").titulo());
            exito(d, CARLA, "CONPAG", "DF-ANT-0008", "QR-20261115-000300", "300.00");
            assertEquals("ESPERANDO_ABASTECIMIENTO", texto("SELECT estado FROM pedido WHERE id = 8"));
            ResultadoOperacion factura = escribir(d, DANIELA, "FACPED", "PED-2026-0008", "", "");
            assertEquals("Regla de negocio", factura.titulo());
            assertTrue(factura.mensaje().contains("pagado Bs 300.00 de Bs 1080.00"), factura.mensaje());
        });
    }

    @Test
    @DisplayName("Un cliente no registra ni consulta pagos de pedidos ajenos, y LISPAG le muestra solo los suyos")
    void propiedadDeLosPagos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals("No encontrado", escribir(d, ELMER, "REGPAG", "PED-2026-0011", "QR", "10.00", "").titulo());
            ResultadoOperacion propios = leer(d, ANDRES, "LISPAG", "*");
            assertTrue(propios.tablas().get(0).filas().stream().allMatch(f -> f.get(1).equals("PED-2026-0006") || f.get(1).equals("PED-2026-0011")));
            assertEquals(2, propios.tablas().get(0).filas().size());
            assertEquals(11, leer(d, CARLA, "LISPAG", "*").tablas().get(0).filas().size());
        });
    }

    @Test
    @DisplayName("Un cobro QR verificado después de cancelar el pedido no lo reactiva: se ordena su reintegro y nunca se factura")
    void cobroTardio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, ANDRES, "CANPED", "PED-2026-0011", "Me equivoqué de dirección");
            assertEquals("PENDIENTE", texto("SELECT estado FROM pago WHERE id = 11"));
            ResultadoOperacion r = exito(d, CARLA, "CONPAG", "pago-ped0011-01", "QR-20261115-000999", "143.00");
            assertTrue(detalle(r, "Clave del pago").equals("pago-ped0011-01"));
            assertEquals("CANCELADO", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals("PENDIENTE|143.00", texto("SELECT estado || '|' || monto FROM reembolso WHERE clave_idempotencia = 'TARD-PAGO11'"));
            assertEquals("Estado no permitido", escribir(d, CARLA, "FACPED", "PED-2026-0011", "", "").titulo());
        });
    }

    @Test
    @DisplayName("Al cancelar un pedido aceptado contra entrega, su pago en efectivo termina RECHAZADO (sin cobro)")
    void efectivoAlCancelar() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECPAG", "pago-ped0011-01", "El cliente prefiere pagar al recibir");
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "EFECTIVO", "143.00", "");
            exito(d, ANDRES, "CANPED", "PED-2026-0011", "Ya no lo necesito");
            assertEquals("RECHAZADO", texto("SELECT estado FROM pago WHERE pedido_id = 11 AND metodo = 'EFECTIVO'"));
            assertEquals(0, numero("SELECT count(*) FROM reembolso r JOIN pago p ON p.id = r.pago_id WHERE p.pedido_id = 11"));
        });
    }

    @Test
    @DisplayName("Solo un ADMINISTRADOR anula una factura, que se conserva ANULADA con su fecha")
    void anulacionDeFactura() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals("Acceso denegado", leer(d, CARLA, "ANUFAC", "FAC-2026-000008", "Procedimiento").titulo());
            exito(d, ADMIN, "ANUFAC", "FAC-2026-000008", "Resolución SIN-2026-0042 del procedimiento de anulación fiscal");
            assertEquals("ANULADA", texto("SELECT estado FROM factura WHERE numero = 'FAC-2026-000008' AND fecha_anulacion IS NOT NULL"));
            assertEquals("Estado no permitido", escribir(d, ADMIN, "ANUFAC", "FAC-2026-000008", "Otra vez").titulo());
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'FACTURA' AND accion = 'ANULAR' AND entidad_id = 8"));
        });
    }
}
