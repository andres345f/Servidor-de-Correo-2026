package com.feder.negocio.produccion;

import static com.feder.negocio.usuarios.ApoyoPruebas.CARLA;
import static com.feder.negocio.usuarios.ApoyoPruebas.DANIELA;
import static com.feder.negocio.usuarios.ApoyoPruebas.MARIA;
import static com.feder.negocio.usuarios.ApoyoPruebas.ROSA;
import static com.feder.negocio.usuarios.ApoyoPruebas.confirmado;
import static com.feder.negocio.usuarios.ApoyoPruebas.detalle;
import static com.feder.negocio.usuarios.ApoyoPruebas.numero;
import static com.feder.pruebas.BaseDatosPrueba.enTransaccionRevertida;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.usuarios.ApoyoPruebas;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

/** Comandos de CU3 de extremo a extremo con el despachador real. */
class ModuloProduccionComandosTest {

    private ContextoNegocio ctx;
    private ComandoDispatcher despachador;

    @BeforeEach
    void preparar() {
        ctx = BaseDatosPrueba.contexto();
        despachador = ApoyoPruebas.despachador(ctx);
    }

    @Test
    @DisplayName("INSPCN exige confirmación y CONFIRMAR registra la producción del productor")
    void registrarProduccionPorCorreo() {
        enTransaccionRevertida(ctx, () -> {
            long antes = numero("SELECT count(*) FROM produccion");
            ResultadoOperacion pedido = despachador.despachar("INSPCN",
                    List.of("FED-ACE-100", "2026-12-01", "2027-01-15", "100", "Taller familiar de extracción de aceite", ""), MARIA)
                    .resultado;
            assertEquals("Confirmación requerida", pedido.titulo());
            assertEquals(antes, numero("SELECT count(*) FROM produccion"));
            ResultadoOperacion r = despachador.despachar(ComandoDispatcher.COMANDO_CONFIRMAR, List.of(ApoyoPruebas.codigo(pedido)),
                    MARIA).resultado;
            assertTrue(r.esExito(), r::mensaje);
            assertEquals("PLANIFICADA", detalle(r, "Estado"));
            assertEquals(antes + 1, numero("SELECT count(*) FROM produccion"));
        });
    }

    @Test
    @DisplayName("ESTPCN e INSLOT confirmados: la producción pasa a EN_CURSO y recibe su lote")
    void estadoYLotePorCorreo() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion estado = confirmado(despachador, ROSA, "ESTPCN", "PRD-2026-012", "EN_CURSO", "");
            assertEquals("EN_CURSO", detalle(estado, "Estado"));
            ResultadoOperacion lote = confirmado(despachador, MARIA, "INSLOT", "PRD-2026-011", "2026-11-12", "2027-11-12", "30", "25", "60.00");
            assertTrue(lote.esExito(), lote::mensaje);
            assertEquals("60.00", detalle(lote, "Precio ofrecido (Bs)"));
            ResultadoOperacion ajeno = confirmado(despachador, ROSA, "INSLOT", "PRD-2026-011", "2026-11-12", "", "30", "25", "");
            assertEquals("Acceso denegado", ajeno.titulo(), "Un productor no asigna lotes a producción ajena");
        });
    }

    @Test
    @DisplayName("TRZLOT es público: el visitante no ve los datos del productor; el titular sí")
    void trazabilidadPublica() {
        enTransaccionRevertida(ctx, () -> {
            ResultadoOperacion publico = despachador.despachar("TRZLOT", List.of("LP-2026-001"), "visitante@feder.example").resultado;
            assertTrue(publico.esExito(), publico::mensaje);
            assertNotNull(detalle(publico, "Comunidad de origen"));
            assertNull(detalle(publico, "Productor"));
            assertNull(detalle(publico, "Ubicación de la producción"));
            ResultadoOperacion titular = despachador.despachar("TRZLOT", List.of("lp-2026-001"), MARIA).resultado;
            assertTrue(detalle(titular, "Productor").startsWith("PROD-0001"));
        });
    }

    @Test
    @DisplayName("Consultas de producción: el cliente no accede; el personal ve todo sin confirmación")
    void consultas() {
        enTransaccionRevertida(ctx, () -> {
            assertEquals("Acceso denegado", despachador.despachar("LISPCN", List.of("*"), DANIELA).resultado.titulo());
            ResultadoOperacion todas = despachador.despachar("LISPCN", List.of("*"), CARLA).resultado;
            assertEquals(12, todas.tablas().get(0).filas().size());
            ResultadoOperacion ver = despachador.despachar("VERPCN", List.of("PRD-2026-011"), CARLA).resultado;
            assertEquals("12", detalle(ver, "Compromiso vigente con encargos"));
            assertEquals(1, ver.tablas().get(0).filas().size());
            assertFalse(despachador.despachar("VERPCN", List.of("PRD-2026-012"), MARIA).resultado.esExito());
        });
    }
}
