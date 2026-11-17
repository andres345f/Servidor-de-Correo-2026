package com.feder.negocio.inventario;

import com.feder.datos.inventario.LoteFederVista;
import com.feder.modelo.DetalleCompra;
import com.feder.modelo.EstadoCompra;
import com.feder.modelo.EstadoLoteFeder;
import com.feder.modelo.UnidadMedida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Reglas de cálculo sin base de datos: reparto FEFO (R037) y estado derivado de la compra (R005). */
class ReglasPurasInventarioTest {

    private static LoteFederVista lote(long id, String libre, LocalDate vence, int diaIngreso) {
        return new LoteFederVista(id, "LF-" + id, id, id, "LP-" + id, 1, "P", "Producto", UnidadMedida.UNIDAD,
                OffsetDateTime.of(2026, 1, diaIngreso, 10, 0, 0, 0, ZoneOffset.ofHours(-4)), new BigDecimal(libre),
                BigDecimal.ZERO, null, EstadoLoteFeder.DISPONIBLE, vence);
    }

    private static Map<Long, BigDecimal> libres(List<LoteFederVista> lotes) {
        Map<Long, BigDecimal> m = new HashMap<>();
        lotes.forEach(l -> m.put(l.id(), l.libre()));
        return m;
    }

    @Test
    @DisplayName("FEFO: primero el vencimiento más próximo, los lotes sin fecha al final, después ingreso e id")
    void ordenFefo() {
        List<LoteFederVista> lotes = List.of(
                lote(1, "5", null, 1),
                lote(2, "5", LocalDate.of(2027, 5, 1), 3),
                lote(3, "5", LocalDate.of(2027, 1, 1), 5),
                lote(4, "5", LocalDate.of(2027, 1, 1), 2));
        List<AsignadorFefo.Asignacion> a = AsignadorFefo.asignar(lotes, libres(lotes), new BigDecimal("18"));
        assertEquals(List.of(4L, 3L, 2L, 1L), a.stream().map(AsignadorFefo.Asignacion::loteFederId).toList());
        assertEquals(0, new BigDecimal("3").compareTo(a.get(3).cantidad()));
    }

    @Test
    @DisplayName("El reparto descuenta lo asignado y nunca asigna más de lo libre; si no alcanza devuelve menos")
    void noExcedeLoLibre() {
        List<LoteFederVista> lotes = List.of(lote(1, "2", null, 1), lote(2, "1", null, 2));
        Map<Long, BigDecimal> libres = libres(lotes);
        List<AsignadorFefo.Asignacion> primera = AsignadorFefo.asignar(lotes, libres, new BigDecimal("2"));
        assertEquals(0, new BigDecimal("2").compareTo(AsignadorFefo.total(primera)));
        List<AsignadorFefo.Asignacion> segunda = AsignadorFefo.asignar(lotes, libres, new BigDecimal("5"));
        assertEquals(0, BigDecimal.ONE.compareTo(AsignadorFefo.total(segunda)));
    }

    @Test
    @DisplayName("Un lote con vencimiento de origen anterior a hoy no es vendible; sin fecha nunca vence")
    void vencimientoDelLote() {
        LocalDate hoy = LocalDate.of(2026, 11, 15);
        assertFalse(lote(1, "1", LocalDate.of(2026, 11, 14), 1).vendible(hoy));
        assertTrue(lote(1, "1", hoy, 1).vendible(hoy));
        assertTrue(lote(1, "1", null, 1).vendible(hoy));
    }

    private static DetalleCompra linea(String comprada, String recibida, String rechazada, String cancelada) {
        DetalleCompra d = new DetalleCompra();
        d.setCantidadComprada(new BigDecimal(comprada));
        d.setCantidadRecibida(new BigDecimal(recibida));
        d.setCantidadRechazada(new BigDecimal(rechazada));
        d.setCantidadCancelada(new BigDecimal(cancelada));
        return d;
    }

    @Test
    @DisplayName("Estado de la compra derivado de las cantidades de sus líneas (R005)")
    void estadoDerivado() {
        assertEquals(EstadoCompra.REGISTRADA, EstadoCompraDerivado.derivar(List.of(linea("10", "0", "0", "0"))));
        assertEquals(EstadoCompra.REGISTRADA, EstadoCompraDerivado.derivar(List.of(linea("10", "0", "0", "4"))));
        assertEquals(EstadoCompra.RECIBIDA_PARCIAL, EstadoCompraDerivado.derivar(List.of(linea("10", "0", "2", "0"))));
        assertEquals(EstadoCompra.RECIBIDA_PARCIAL,
                EstadoCompraDerivado.derivar(List.of(linea("10", "10", "0", "0"), linea("5", "0", "0", "0"))));
        assertEquals(EstadoCompra.RECIBIDA, EstadoCompraDerivado.derivar(List.of(linea("10", "8", "2", "0"))));
        assertEquals(EstadoCompra.CANCELADA, EstadoCompraDerivado.derivar(List.of(linea("10", "0", "6", "4"))));
    }
}
