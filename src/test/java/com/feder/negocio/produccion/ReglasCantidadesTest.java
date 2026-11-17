package com.feder.negocio.produccion;

import com.feder.modelo.UnidadMedida;
import com.feder.negocio.comun.NegocioException.Codigo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.feder.negocio.inventario.ApoyoCu45.assertNegocio;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Límites de las columnas NUMERIC en las cantidades y el precio ofrecido de CU3, sin base de datos. */
class ReglasCantidadesTest {

    @Test
    @DisplayName("El precio ofrecido admite 9999999999.99 (NUMERIC(12,2)) y rechaza un centavo más")
    void limiteDelPrecioOfrecido() {
        assertEquals(new BigDecimal("9999999999.99"), ReglasCantidades.precio(new BigDecimal("9999999999.99")));
        assertNegocio(Codigo.PARAMETRO_INVALIDO, "9999999999.99", () -> ReglasCantidades.precio(new BigDecimal("10000000000.00")));
    }

    @Test
    @DisplayName("Las cantidades de producción admiten 999999999.999 (NUMERIC(12,3)) y rechazan una milésima más")
    void limiteDeLasCantidades() {
        assertEquals(new BigDecimal("999999999.999"),
                ReglasCantidades.positiva(new BigDecimal("999999999.999"), UnidadMedida.KILOGRAMO, "La cantidad producida"));
        assertNegocio(Codigo.PARAMETRO_INVALIDO, "999999999.999", () -> ReglasCantidades.positiva(
                new BigDecimal("1000000000.000"), UnidadMedida.KILOGRAMO, "La cantidad producida"));
        assertNegocio(Codigo.PARAMETRO_INVALIDO, "999999999.999", () -> ReglasCantidades.noNegativa(
                new BigDecimal("1000000000"), UnidadMedida.UNIDAD, "La cantidad ofrecida"));
    }
}
