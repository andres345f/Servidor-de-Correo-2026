package com.feder.negocio.entregas;

import com.feder.modelo.EstadoEntrega;
import com.feder.modelo.ModalidadEntrega;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.pagos.ApoyoCu6;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.feder.negocio.inventario.ApoyoCu45.*;
import static com.feder.negocio.pagos.ApoyoCu6.escribir;
import static com.feder.negocio.pagos.ApoyoCu6.exito;
import static com.feder.negocio.pagos.ApoyoCu6.leer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Procesamiento y entrega de pedidos (R027-R029, R047, R049, R055, R057) sobre la semilla: PED-2026-0010 (CONFIRMADO,
 * pagado, RECOJO), PED-2026-0011 (PENDIENTE, DELIVERY, QR pendiente) y PED-2026-0009 (DESPACHADO, DELIVERY EN_CAMINO).
 */
class EntregasTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher d;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        d = ApoyoCu6.despachador(ctx);
    }

    @Test
    @DisplayName("Máquina de estados de la entrega según la modalidad: RECOJO nunca usa EN_CAMINO y CANCELADA solo antes del despacho")
    void transicionesDeEntrega() {
        assertTrue(TransicionesEntrega.permitida(ModalidadEntrega.RECOJO, EstadoEntrega.LISTA_PARA_RECOJO, EstadoEntrega.RETORNADA));
        assertFalse(TransicionesEntrega.permitida(ModalidadEntrega.RECOJO, EstadoEntrega.PENDIENTE, EstadoEntrega.EN_CAMINO));
        assertTrue(TransicionesEntrega.permitida(ModalidadEntrega.DELIVERY, EstadoEntrega.FALLIDA, EstadoEntrega.EN_CAMINO));
        assertFalse(TransicionesEntrega.permitida(ModalidadEntrega.DELIVERY, EstadoEntrega.FALLIDA, EstadoEntrega.ENTREGADA));
        assertFalse(TransicionesEntrega.permitida(ModalidadEntrega.DELIVERY, EstadoEntrega.EN_CAMINO, EstadoEntrega.CANCELADA));
        assertFalse(TransicionesEntrega.permitida(ModalidadEntrega.DELIVERY, EstadoEntrega.NO_RECUPERADA, EstadoEntrega.EN_CAMINO));
    }

    @Test
    @DisplayName("PREPED, DESPED y ENTPED: el despacho consume las reservas con su SALIDA_VENTA y la entrega queda ENTREGADA con su fecha")
    void flujoDeRecojo() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertIgual("76", decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 7"));
            exito(d, CARLA, "PREPED", "PED-2026-0010");
            ResultadoOperacion despacho = exito(d, CARLA, "DESPED", "PED-2026-0010", "", "Retira la clienta en mostrador");
            assertEquals("DESPACHADO", detalle(despacho, "Estado"));
            assertEquals("RECOJO - LISTA_PARA_RECOJO", detalle(despacho, "Entrega"));
            assertEquals(2, numero("SELECT count(*) FROM reserva_stock WHERE id IN (17, 18) AND estado = 'CONSUMIDA'"));
            assertEquals(2, numero("SELECT count(*) FROM movimiento_inventario WHERE tipo = 'SALIDA_VENTA' AND reserva_id IN (17, 18)"));
            assertIgual("70", decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 7"));
            assertIgual("0", decimal("SELECT cantidad_reservada FROM lote_feder WHERE id = 7"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'ENTREGA' AND entidad_id = 10 "
                    + "AND detalle ->> 'estado_anterior' = 'PENDIENTE' AND detalle ->> 'estado_nuevo' = 'LISTA_PARA_RECOJO'"));
            assertEquals("Estado no permitido", escribir(d, CARLA, "DESPED", "PED-2026-0010", "", "").titulo());

            ResultadoOperacion entregado = exito(d, JORGE, "ENTPED", "PED-2026-0010", "Recogió la clienta");
            assertEquals("ENTREGADO", detalle(entregado, "Estado"));
            assertEquals("No correspondía", detalle(entregado, "Cobro en efectivo"));
            assertEquals("ENTREGADA", texto("SELECT estado FROM entrega WHERE pedido_id = 10 AND fecha_entrega IS NOT NULL"));
            assertEquals("DESPACHADO>ENTREGADO", texto("SELECT estado_anterior || '>' || estado_nuevo FROM historial_pedido "
                    + "WHERE pedido_id = 10 ORDER BY id DESC LIMIT 1"));
        });
    }

    @Test
    @DisplayName("Cada paso exige su estado: no se despacha un CONFIRMADO, un encargo se acepta con ACEENC y sin cobro no hay confirmación")
    void ordenDeLosPasos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            assertEquals("Estado no permitido", escribir(d, CARLA, "DESPED", "PED-2026-0010", "", "").titulo());
            assertEquals("Estado no permitido", escribir(d, CARLA, "ENTPED", "PED-2026-0010", "").titulo());
            assertTrue(escribir(d, CARLA, "CONPED", "PED-2026-0008", "").mensaje().contains("ACEENC"));
            ResultadoOperacion sinCobro = escribir(d, CARLA, "CONPED", "PED-2026-0011", "");
            assertEquals("Regla de negocio", sinCobro.titulo());
            assertEquals("PENDIENTE", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals("Acceso denegado", leer(d, ANDRES, "PREPED", "PED-2026-0011").titulo());
        });
    }

    @Test
    @DisplayName("Efectivo contra entrega: CONPED confirma sin cobro previo y ENTPED confirma el efectivo en la misma transacción")
    void efectivoContraEntrega() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECPAG", "pago-ped0011-01", "El cliente pagará en efectivo al recibir");
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "EFECTIVO", "143.00", "");
            assertEquals("Regla de negocio", escribir(d, CARLA, "CONPAG", "PAG-PED-2026-0011-02", "EF-1", "143.00").titulo());
            exito(d, CARLA, "CONPED", "PED-2026-0011", "");
            assertEquals("CONFIRMADO", texto("SELECT estado FROM pedido WHERE id = 11"));
            assertEquals(0, numero("SELECT count(*) FROM reserva_stock WHERE id IN (19, 20) AND fecha_expiracion IS NOT NULL"));
            // R049: aceptado contra entrega, el efectivo sigue PENDIENTE hasta cobrarse; no se rechaza con RECPAG.
            ResultadoOperacion rechazo = escribir(d, CARLA, "RECPAG", "PAG-PED-2026-0011-02", "Entrega fallida");
            assertEquals("Estado no permitido", rechazo.titulo());
            assertTrue(rechazo.mensaje().contains("aceptado contra entrega"), rechazo.mensaje());
            assertEquals("PENDIENTE", texto("SELECT estado FROM pago WHERE clave_idempotencia = 'PAG-PED-2026-0011-02'"));
            exito(d, CARLA, "PREPED", "PED-2026-0011");
            ResultadoOperacion despacho = exito(d, CARLA, "DESPED", "PED-2026-0011", "2030-01-10 10:00", "");
            assertEquals("DELIVERY - EN_CAMINO", detalle(despacho, "Entrega"));
            assertEquals("2030-01-10 10:00", detalle(despacho, "Fecha programada"));
            ResultadoOperacion entregado = exito(d, JORGE, "ENTPED", "PED-2026-0011", "Cobrado en el domicilio");
            assertEquals("Bs 143.00 recibidos en la entrega", detalle(entregado, "Cobro en efectivo"));
            assertEquals("PAGADO", texto("SELECT estado FROM pago WHERE clave_idempotencia = 'PAG-PED-2026-0011-02' AND fecha_pago IS NOT NULL"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'PAGO' AND usuario_id = 3 "
                    + "AND detalle ->> 'verificacion' = 'EFECTIVO_RECIBIDO_EN_LA_ENTREGA'"));
            assertEquals("ENTREGADO", texto("SELECT estado FROM pedido WHERE id = 11"));
            // R030: con el efectivo cobrado el pedido queda pagado y se factura en la misma transacción.
            assertEquals("VIGENTE|143.00", texto("SELECT estado || '|' || total FROM factura WHERE pedido_id = 11"));
            assertEquals(detalle(entregado, "Factura emitida"), texto("SELECT numero FROM factura WHERE pedido_id = 11"));
        });
    }

    @Test
    @DisplayName("Separación de funciones: el personal no cobra en efectivo la entrega de su propio pedido; otro miembro sí")
    void cobroEnEfectivoDeUnPedidoPropio() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "RECPAG", "pago-ped0011-01", "El cliente pagará en efectivo al recibir");
            exito(d, ANDRES, "REGPAG", "PED-2026-0011", "EFECTIVO", "143.00", "");
            exito(d, CARLA, "CONPED", "PED-2026-0011", "");
            exito(d, CARLA, "PREPED", "PED-2026-0011");
            exito(d, CARLA, "DESPED", "PED-2026-0011", "2030-01-10 10:00", "");
            // Jorge es PERSONAL_FEDER y CLIENTE: se le asigna el pedido para probar el conflicto.
            ejecutar("UPDATE pedido SET usuario_id = 3 WHERE id = 11");
            assertEquals("Acceso denegado", escribir(d, JORGE, "ENTPED", "PED-2026-0011", "Me lo entregué").titulo());
            assertEquals("PENDIENTE", texto("SELECT estado FROM pago WHERE clave_idempotencia = 'PAG-PED-2026-0011-02'"));
            exito(d, CARLA, "ENTPED", "PED-2026-0011", "Cobrado en el domicilio");
        });
    }

    @Test
    @DisplayName("Un pedido sin cobertura completa de reservas (lote bloqueado) no pasa a EN_PREPARACION")
    void preparacionExigeCobertura() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "BLQLTF", "LF-2026-007", "Tabletas con humedad detectadas en la revisión");
            ResultadoOperacion r = escribir(d, CARLA, "PREPED", "PED-2026-0010");
            assertEquals("Regla de negocio", r.titulo());
            assertTrue(r.mensaje().contains("sin cobertura completa"), r.mensaje());
            assertEquals("CONFIRMADO", texto("SELECT estado FROM pedido WHERE id = 10"));
        });
    }

    @Test
    @DisplayName("Entrega fallida: retornos por reserva con baja de lo no apto, cierre NO_RECUPERADA y cancelación con reintegro")
    void entregaFallidaYRetornos() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            BigDecimalLote antes = new BigDecimalLote(decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 6"));
            exito(d, CARLA, "ESTENT", "PED-2026-0009", "FALLIDA", "Nadie atendió en el domicilio");
            assertEquals("Estado no permitido", escribir(d, CARLA, "ENTPED", "PED-2026-0009", "").titulo());
            // Las claves con la forma que genera FEDER no se aceptan indicadas: chocarían con un retorno o una baja.
            assertTrue(escribir(d, CARLA, "RETENT", "PED-2026-0009", "15", "4", "3", "DANADO", "Una bolsa rota",
                    "Guía de retorno GR-2026-0031", "RET-RES15-1").mensaje().contains("las asigna FEDER"));
            assertTrue(escribir(d, CARLA, "RETENT", "PED-2026-0009", "15", "4", "3", "DANADO", "Una bolsa rota",
                    "Guía de retorno GR-2026-0031", "RET-T-15-BAJA").mensaje().contains("las asigna FEDER"));
            ResultadoOperacion r = exito(d, CARLA, "RETENT", "PED-2026-0009", "15", "4", "3", "DANADO", "Una bolsa rota",
                    "Guía de retorno GR-2026-0031", "RET-T-15");
            assertEquals("1", detalle(r, "Dada de baja (no apta)"));
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE reserva_id = 15 AND tipo = 'ENTRADA_RETORNO_ENTREGA'"));
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE reserva_id = 15 AND tipo = 'BAJA_DANADO'"));
            assertIgual(antes.mas("3"), decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 6"));
            assertEquals(1, numero("SELECT count(*) FROM bitacora WHERE entidad = 'MOVIMIENTO_INVENTARIO' "
                    + "AND detalle ->> 'clave_operacion' = 'RET-T-15' AND detalle ->> 'evidencia' = 'Guía de retorno GR-2026-0031'"));
            assertEquals("Retorno ya registrado", exito(d, CARLA, "RETENT", "PED-2026-0009", "15", "4", "3", "DANADO",
                    "Una bolsa rota", "Guía de retorno GR-2026-0031", "RET-T-15").titulo());
            assertEquals(1, numero("SELECT count(*) FROM movimiento_inventario WHERE reserva_id = 15 AND tipo = 'BAJA_DANADO'"));
            assertTrue(escribir(d, CARLA, "RETENT", "PED-2026-0009", "15", "1", "1", "", "", "Acta 2", "").mensaje()
                    .contains("supera lo despachado"));
            assertEquals("Estado no permitido", escribir(d, CARLA, "ESTENT", "PED-2026-0009", "EN_CAMINO", "Reintento").titulo());
            assertTrue(escribir(d, CARLA, "ESTENT", "PED-2026-0009", "RETORNADA", "Cierre").mensaje().contains("faltan 5"));
            exito(d, CARLA, "ESTENT", "PED-2026-0009", "NO_RECUPERADA", "El transportista no encontró las barras de jabón");

            exito(d, CARLA, "CANPED", "PED-2026-0009", "Entrega no completada: mercancía parcialmente perdida");
            assertEquals("CANCELADO", texto("SELECT estado FROM pedido WHERE id = 9"));
            assertEquals("NO_RECUPERADA", texto("SELECT estado FROM entrega WHERE pedido_id = 9"));
            assertEquals("PENDIENTE|193.00", texto("SELECT estado || '|' || monto FROM reembolso WHERE clave_idempotencia = 'CANC-PED9-PAGO9'"));
            assertEquals("Acceso denegado", escribir(d, CARLA, "RETENT", "PED-2026-0009", "16", "5", "5", "", "Apareció en el depósito",
                    "Acta 3", "").titulo());
            exito(d, ADMIN, "RETENT", "PED-2026-0009", "16", "5", "5", "", "Apareció en el depósito del transportista", "Acta 3", "");
            assertEquals("NO_RECUPERADA", texto("SELECT estado FROM entrega WHERE pedido_id = 9"));
        });
    }

    @Test
    @DisplayName("Un RECOJO no recogido pasa a RETORNADA con el retorno de todo lo despachado en la misma transacción")
    void recojoNoRecogido() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            exito(d, CARLA, "PREPED", "PED-2026-0010");
            exito(d, CARLA, "DESPED", "PED-2026-0010", "", "");
            exito(d, CARLA, "ESTENT", "PED-2026-0010", "RETORNADA", "La clienta no recogió en el plazo acordado");
            assertEquals("RETORNADA", texto("SELECT estado FROM entrega WHERE pedido_id = 10"));
            assertIgual("8", decimal("SELECT sum(cantidad) FROM movimiento_inventario WHERE reserva_id IN (17, 18) "
                    + "AND tipo = 'ENTRADA_RETORNO_ENTREGA'"));
            assertIgual("76", decimal("SELECT cantidad_disponible FROM lote_feder WHERE id = 7"));
            ResultadoOperacion ver = leer(d, PATRICIA, "VERENT", "PED-2026-0010");
            assertTrue(ver.tablas().get(0).filas().stream().allMatch(f -> f.get(6).equals("0")));
        });
    }

    @Test
    @DisplayName("El cliente consulta solo sus entregas")
    void consultaDeEntregas() {
        BaseDatosPrueba.enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion propias = leer(d, ANDRES, "LISENT", "*");
            assertTrue(propias.tablas().get(0).filas().stream().allMatch(f -> f.get(0).equals("PED-2026-0006") || f.get(0).equals("PED-2026-0011")));
            assertEquals(11, leer(d, CARLA, "LISENT", "*").tablas().get(0).filas().size());
            assertEquals("No encontrado", leer(d, ELMER, "VERENT", "PED-2026-0009").titulo());
        });
    }

    /** Saldo de un lote antes de la operación, para comparar después. */
    private record BigDecimalLote(java.math.BigDecimal valor) {
        String mas(String delta) {
            return valor.add(new java.math.BigDecimal(delta)).toPlainString();
        }
    }
}
