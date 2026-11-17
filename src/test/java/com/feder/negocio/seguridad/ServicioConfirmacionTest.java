package com.feder.negocio.seguridad;

import com.feder.datos.seguridad.ConfirmacionRepositorio;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** El código de confirmación es de un solo uso, vence y pertenece a una cuenta concreta. */
class ServicioConfirmacionTest {

    private static final String CORREO = "almacen.carla.mendez@feder.example";

    private ContextoNegocio contexto;

    @BeforeEach
    void preparar() {
        contexto = BaseDatosPrueba.contexto();
    }

    private static ServicioConfirmacion servicio(Clock reloj) {
        return new ServicioConfirmacion(new ConfirmacionRepositorio(), reloj, Duration.ofMinutes(30), 30);
    }

    @Test
    void elCodigoSeUsaUnaSolaVez() {
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            Solicitante titular = BaseDatosPrueba.solicitante(CORREO);
            ServicioConfirmacion confirmaciones = servicio(Clock.systemUTC());
            String codigo = confirmaciones.solicitar("INSCAT", List.of("Mieles", ""), titular);

            ServicioConfirmacion.OperacionConfirmada operacion = confirmaciones.consumir(codigo, titular);
            assertEquals("INSCAT", operacion.comando);
            assertEquals(List.of("Mieles", ""), operacion.parametros);

            NegocioException repetido = assertThrows(NegocioException.class, () -> confirmaciones.consumir(codigo, titular));
            assertEquals(NegocioException.Codigo.CONFIRMACION_INVALIDA, repetido.codigo());
        });
    }

    @Test
    void elCodigoVencido() {
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            Solicitante titular = BaseDatosPrueba.solicitante(CORREO);
            Instant ahora = Instant.now();
            String codigo = servicio(Clock.fixed(ahora, ZoneOffset.UTC)).solicitar("INSCAT", List.of("Café", ""), titular);

            ServicioConfirmacion tarde = servicio(Clock.fixed(ahora.plus(Duration.ofMinutes(31)), ZoneOffset.UTC));
            assertThrows(NegocioException.class, () -> tarde.consumir(codigo, titular));
        });
    }

    @Test
    void otraCuentaConLaMismaDireccionNoPuedeConfirmar() {
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            Solicitante titular = BaseDatosPrueba.solicitante(CORREO);
            ServicioConfirmacion confirmaciones = servicio(Clock.systemUTC());
            String codigo = confirmaciones.solicitar("INSCAT", List.of("Harinas", ""), titular);

            // Simula que la dirección pasó a otra cuenta mientras el código seguía vigente.
            Solicitante otraCuenta = Solicitante.registrado(titular.usuarioId() + 1000, CORREO, "Otra persona",
                    Set.of(TipoRol.PERSONAL_FEDER));
            assertThrows(NegocioException.class, () -> confirmaciones.consumir(codigo, otraCuenta));
        });
    }

    @Test
    void otroRemitenteNoPuedeUsarElCodigo() {
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            Solicitante titular = BaseDatosPrueba.solicitante(CORREO);
            ServicioConfirmacion confirmaciones = servicio(Clock.systemUTC());
            String codigo = confirmaciones.solicitar("INSCAT", List.of("Mermeladas", ""), titular);

            Solicitante intruso = BaseDatosPrueba.solicitante("elmer.rojas@feder.example");
            assertThrows(NegocioException.class, () -> confirmaciones.consumir(codigo, intruso));
            // El intento fallido no consume el código: el titular aún puede confirmar.
            assertEquals("INSCAT", confirmaciones.consumir(codigo, titular).comando);
        });
    }

    @Test
    void unaSolicitudSinCuentaNoLaConfirmaUnaCuentaRegistrada() {
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            Solicitante anonimo = Solicitante.anonimo(CORREO);
            ServicioConfirmacion confirmaciones = servicio(Clock.systemUTC());
            String codigo = confirmaciones.solicitar("REGCLI", List.of("1234567"), anonimo);

            Solicitante registrado = BaseDatosPrueba.solicitante(CORREO);
            assertThrows(NegocioException.class, () -> confirmaciones.consumir(codigo, registrado));
        });
    }
}
