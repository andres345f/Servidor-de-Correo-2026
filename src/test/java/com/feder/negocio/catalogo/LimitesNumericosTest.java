package com.feder.negocio.catalogo;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.catalogo.ProductoCatalogo;
import com.feder.datos.catalogo.ProductoRepositorio;
import com.feder.modelo.TipoRol;
import com.feder.modelo.UnidadMedida;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.NegocioException.Codigo;
import com.feder.negocio.comun.Solicitante;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Optional;

import static com.feder.negocio.inventario.ApoyoCu45.assertNegocio;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Los precios, montos y cantidades que llegan por correo se comparan con el mayor valor de su columna NUMERIC antes de
 * llegar a SQL: un valor fuera de rango es una regla de negocio con el máximo en el mensaje, nunca un error interno.
 * Pruebas sin base de datos.
 */
class LimitesNumericosTest {

    @Test
    @DisplayName("Precio NUMERIC(12,2): se admite 9999999999.99 y se rechaza 10000000000.00 indicando el máximo")
    void limiteDelPrecio() {
        assertEquals(new BigDecimal("9999999999.99"), Importes.validarPrecio(new BigDecimal("9999999999.99"), false, "precio de venta"));
        assertNegocio(Codigo.REGLA_DE_NEGOCIO, "9999999999.99",
                () -> Importes.validarPrecio(new BigDecimal("10000000000.00"), false, "precio de venta"));
        assertNegocio(Codigo.REGLA_DE_NEGOCIO, "9999999999.99",
                () -> Importes.validarPrecio(new BigDecimal("999999999999"), true, "precio unitario"));
    }

    @Test
    @DisplayName("Monto NUMERIC(14,2): se admite 999999999999.99 y se rechaza un centavo más indicando el máximo")
    void limiteDelMonto() {
        assertEquals(new BigDecimal("999999999999.99"), Importes.validarMonto(new BigDecimal("999999999999.99"), "monto del pago"));
        assertNegocio(Codigo.REGLA_DE_NEGOCIO, "999999999999.99",
                () -> Importes.validarMonto(new BigDecimal("1000000000000.00"), "monto del pago"));
        assertNegocio(Codigo.REGLA_DE_NEGOCIO, "mayor que 0", () -> Importes.validarMonto(BigDecimal.ZERO, "monto del pago"));
    }

    @Test
    @DisplayName("Cantidad NUMERIC(12,3): se admite 999999999.999 y se rechaza una milésima más indicando el máximo")
    void limiteDeLaCantidad() {
        assertEquals(new BigDecimal("999999999.999"),
                Cantidades.validarPositiva(new BigDecimal("999999999.999"), UnidadMedida.KILOGRAMO, "cantidad"));
        assertNegocio(Codigo.REGLA_DE_NEGOCIO, "999999999.999",
                () -> Cantidades.validarPositiva(new BigDecimal("1000000000.000"), UnidadMedida.KILOGRAMO, "cantidad"));
        assertNegocio(Codigo.REGLA_DE_NEGOCIO, "999999999.999",
                () -> Cantidades.validarNoNegativa(new BigDecimal("1000000000"), UnidadMedida.UNIDAD, "cantidad de stock mínimo"));
    }

    @Test
    @DisplayName("PREPRO con un precio fuera de rango se rechaza sin abrir la transacción ni llamar al repositorio")
    void precioFueraDeRangoNoLlegaAlRepositorio() {
        // Sin conexión: si el servicio intentara abrir una transacción o consultar el repositorio, la prueba fallaría.
        ContextoNegocio ctx = new ContextoNegocio(new GestorTransacciones(null), Clock.systemUTC(), null, null, null);
        ProductoRepositorio repositorio = new ProductoRepositorio() {
            @Override
            public Optional<ProductoCatalogo> bloquearPorCodigo(String codigo) {
                throw new AssertionError("No debe llegar al repositorio");
            }

            @Override
            public void actualizarPrecio(long id, BigDecimal precio) {
                throw new AssertionError("No debe llegar al repositorio");
            }
        };
        ServicioProductos productos = new ServicioProductos(ctx, repositorio, null, null);
        Solicitante carla = Solicitante.registrado(2, "almacen.carla.mendez@feder.example", "Carla Méndez",
                EnumSet.of(TipoRol.PERSONAL_FEDER));
        NegocioException e = assertNegocio(Codigo.REGLA_DE_NEGOCIO, "9999999999.99", () -> productos.cambiarPrecio(carla,
                "FED-ACE-020", new BigDecimal("10000000000.00"), "Precio fuera de rango"));
        assertEquals(Codigo.REGLA_DE_NEGOCIO, e.codigo());
    }
}
