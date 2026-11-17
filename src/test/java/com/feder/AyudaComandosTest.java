package com.feder;

import com.feder.datos.ConexionBD;
import com.feder.datos.GestorTransacciones;
import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comandos.ValidadorParametros;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.presentacion.respuesta.ResponseFormatter;
import com.feder.presentacion.respuesta.RespuestaFormateada;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HELP para personas sin conocimientos de informática (2026-11-16): pasos, reglas, temas con nombre y una ficha
 * por comando con un ejemplo listo para copiar. No necesita base de datos.
 */
class AyudaComandosTest {

    private static final CatalogoComandos CATALOGO = catalogo();

    private static CatalogoComandos catalogo() {
        Clock reloj = Clock.systemUTC();
        ContextoNegocio contexto = new ContextoNegocio(
                new GestorTransacciones(new ConexionBD("jdbc:postgresql://127.0.0.1:1/sin_conexion", "nadie", "")),
                reloj, new ServicioBitacora(new BitacoraRepositorio()), new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
        CatalogoComandos catalogo = new CatalogoComandos();
        for (ModuloComandos modulo : Aplicacion.modulos(contexto, 30)) {
            catalogo.registrarModulo(modulo);
        }
        return catalogo;
    }

    private static ResultadoOperacion help(String tema) {
        ComandoSpec help = CATALOGO.buscar("HELP").orElseThrow();
        return help.handler().ejecutar(ValidadorParametros.validar(help, List.of(tema)), null);
    }

    @Test
    @DisplayName("HELP[\"*\"]: pasos y reglas, un tema por caso de uso y una ficha por comando con su ejemplo para copiar")
    void guiaCompleta() {
        ResultadoOperacion r = help("*");
        assertEquals("Guía de comandos", r.titulo());
        assertEquals(List.of(ResultadoOperacion.Recuadro.Estilo.PASOS, ResultadoOperacion.Recuadro.Estilo.NOTA),
                r.recuadros().stream().map(ResultadoOperacion.Recuadro::estilo).toList());
        assertTrue(r.tablas().stream().allMatch(ResultadoOperacion.Tabla::esEnFichas));
        assertEquals("1. Usuarios y cuentas", r.tablas().get(0).titulo(), "Los temas se nombran en palabras, no CU1");
        Map<String, ComandoSpec> porNombre = CATALOGO.todos().stream().collect(Collectors.toMap(ComandoSpec::nombre, Function.identity()));
        List<List<String>> fichas = r.tablas().stream().flatMap(t -> t.filas().stream()).toList();
        assertEquals(porNombre.size(), fichas.size(), "Una ficha por comando");
        for (List<String> ficha : fichas) {
            ComandoSpec c = porNombre.get(ficha.get(0));
            assertEquals(c.ejemplo(), ficha.get(2), "Ejemplo de " + c.nombre());
            assertEquals(c.parametros().size(), ficha.get(3).split(" · ").length,
                    "«Datos, en orden» explica cada valor del ejemplo de " + c.nombre() + ": " + ficha.get(3));
        }
        assertEquals(List.of("Comando", "Qué hace", "Ejemplo para copiar en el asunto", "Datos, en orden"),
                r.tablas().get(0).columnas(), "Las fichas no llevan «Quién puede usarlo» ni «¿Pide confirmación?»");
    }

    @Test
    @DisplayName("HELP[\"*\"] en HTML pesa menos de 100 KB (Gmail recorta desde 102 KB) y trae las fichas y el ejemplo destacado")
    void guiaCompletaEnHtml() {
        RespuestaFormateada f = new ResponseFormatter().formatear(help("*"), "HELP[\"*\"]");
        int bytes = f.html().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < 100_000, "HELP[\"*\"] pesa " + bytes + " bytes");
        assertTrue(f.html().contains("<div class=\"recuadro pasos\">"));
        assertTrue(f.html().contains("<code class=\"ej\">LISUSU[&quot;*&quot;]</code>") || f.html().contains("<code class=\"ej\">LISUSU[\"*\"]</code>"),
                "El ejemplo de cada comando se destaca para copiar");
        assertTrue(f.textoPlano().contains("Ejemplo para copiar en el asunto: LISUSU[\"*\"]"), "También en la versión de texto");
        assertTrue(f.textoPlano().contains("  1. Escriba un correo nuevo"), "Los pasos numerados en texto");
        assertTrue(f.html().contains("<b>Datos, en orden:</b> 1. ci · 2. ci complemento (opcional) · 3. nombres"),
                "La guía completa explica qué es cada valor del ejemplo de REGCLI");
    }

    @Test
    @DisplayName("HELP[\"CU4\"]: las fichas del tema con los datos en orden; HELP[\"LISUSU\"]: ejemplo, forma y qué escribir")
    void temaYComando() {
        ResultadoOperacion tema = help("cu4");
        assertEquals("Ayuda del tema 4. Catálogo, compras e inventario", tema.titulo());
        List<String> lispro = tema.tablas().get(0).filas().stream().filter(f -> f.get(0).equals("LISPRO")).findFirst().orElseThrow();
        assertEquals("1. filtro (\"*\" = todos)", lispro.get(3));

        ResultadoOperacion comando = help("lisusu");
        Map<String, String> detalles = comando.detalles().stream().collect(Collectors.toMap(d -> d[0], d -> d[1]));
        assertEquals("LISUSU[\"*\"]", detalles.get("Ejemplo para copiar en el asunto"));
        assertEquals("Administrador", detalles.get("Quién puede usarlo"), "En el detalle de un comando sí se dice, en palabras");
        assertEquals("No", detalles.get("¿Pide código de confirmación?"));
        assertEquals(List.of("1", "filtro", "Texto, \"*\" = todos (hasta 60 caracteres)"), comando.tablas().get(0).filas().get(0));

        assertThrows(NegocioException.class, () -> help("NOEXISTE"));
    }
}
