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
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comandos.TipoParametro;
import com.feder.negocio.comandos.ValidadorParametros;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.mensajeria.SolicitudCorreo;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.presentacion.comandos.CommandParser;
import com.feder.presentacion.comandos.ComandoAnalizado;
import com.feder.presentacion.comandos.ErrorSintaxisException;
import com.feder.presentacion.procesamiento.InterpreteCorreo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprueba, con el catálogo completo, que un comando bien escrito funciona igual sin importar mayúsculas y
 * minúsculas, y que los textos aceptan letras especiales (ñ, tildes, ü, ¿¡), siempre que se respeten los
 * parámetros y valores de cada comando. Cada caso pasa por el mismo camino que un correo real: asunto codificado
 * en UTF-8 (como lo envía Gmail) → intérprete → analizador → validación de parámetros. No necesita base de datos.
 */
class MayusculasYLetrasEspecialesTest {

    /** Tipos cuyo valor no distingue mayúsculas: el sistema lo normaliza antes de usarlo. */
    private static final Set<TipoParametro> SIN_DISTINGUIR =
            Set.of(TipoParametro.CODIGO, TipoParametro.ENUMERACION, TipoParametro.CORREO, TipoParametro.BOOLEANO);
    private static final String ESPECIALES = "Peña Ñandú áéíóú ÁÉÍÓÚ ü ¿Sí? ¡Ya!";

    private final CommandParser analizador = new CommandParser();
    private final InterpreteCorreo interprete = new InterpreteCorreo("buzon", "sistema@feder.local");

    @Test
    @DisplayName("Cada comando se reconoce igual escrito en mayúsculas, minúsculas o mezclado (HELP, help, Help, HeLp)")
    void nombreSinDistinguirMayusculas() throws Exception {
        List<String> errores = new ArrayList<>();
        int casos = 0;
        for (ComandoSpec comando : catalogo().todos()) {
            ComandoAnalizado original = analizador.analizar(comando.ejemplo());
            Parametros esperados = ValidadorParametros.validar(comando, original.parametros());
            String nombre = comando.nombre();
            for (String variante : List.of(nombre.toLowerCase(Locale.ROOT), capitalizado(nombre), alternado(nombre))) {
                casos++;
                SolicitudCorreo s = porCorreo(asunto(variante, original.parametros()));
                if (!nombre.equals(s.comando) || !original.parametros().equals(s.parametros)) {
                    errores.add(variante + ": llegó comando=" + s.comando + " sintaxis=" + s.errorSintaxis);
                    continue;
                }
                List<String> distintos = diferencias(comando, esperados, ValidadorParametros.validar(comando, s.parametros));
                if (!distintos.isEmpty()) {
                    errores.add(variante + ": " + distintos);
                }
            }
        }
        assertTrue(errores.isEmpty(), "Nombres que no se reconocen igual:\n" + String.join("\n", errores));
        assertEquals(catalogo().todos().size() * 3, casos);
    }

    @Test
    @DisplayName("Códigos, opciones, correos y true/false valen igual en mayúsculas, minúsculas o mezclados")
    void valoresSinDistinguirMayusculas() throws Exception {
        List<String> errores = new ArrayList<>();
        int casos = 0;
        for (ComandoSpec comando : catalogo().todos()) {
            List<String> ejemplo = analizador.analizar(comando.ejemplo()).parametros();
            Parametros esperados = ValidadorParametros.validar(comando, ejemplo);
            for (int i = 0; i < comando.parametros().size(); i++) {
                ParametroSpec spec = comando.parametros().get(i);
                String valor = ejemplo.get(i).strip();
                if (!SIN_DISTINGUIR.contains(spec.tipo()) || valor.isEmpty() || valor.equals("*")) {
                    continue;
                }
                for (String variante : List.of(valor.toLowerCase(Locale.ROOT), valor.toUpperCase(Locale.ROOT), alternado(valor))) {
                    casos++;
                    List<String> valores = new ArrayList<>(ejemplo);
                    valores.set(i, variante);
                    SolicitudCorreo s = porCorreo(asunto(comando.nombre(), valores));
                    try {
                        List<String> distintos = diferencias(comando, esperados, ValidadorParametros.validar(comando, s.parametros));
                        if (!distintos.isEmpty()) {
                            errores.add(comando.nombre() + " " + spec.nombre() + "=\"" + variante + "\": " + distintos);
                        }
                    } catch (NegocioException e) {
                        errores.add(comando.nombre() + " " + spec.nombre() + "=\"" + variante + "\": " + e.getMessage());
                    }
                }
            }
        }
        assertTrue(errores.isEmpty(), "Valores que cambian con las mayúsculas:\n" + String.join("\n", errores));
        assertTrue(casos > 0);
    }

    @Test
    @DisplayName("Los parámetros de texto aceptan ñ, tildes, ü y ¿¡ y llegan exactamente como se escribieron")
    void textoConLetrasEspeciales() throws Exception {
        List<String> errores = new ArrayList<>();
        int casos = 0;
        for (ComandoSpec comando : catalogo().todos()) {
            List<String> ejemplo = analizador.analizar(comando.ejemplo()).parametros();
            for (int i = 0; i < comando.parametros().size(); i++) {
                ParametroSpec spec = comando.parametros().get(i);
                if (spec.tipo() != TipoParametro.TEXTO) {
                    continue;
                }
                casos++;
                String texto = ESPECIALES.substring(0, Math.min(ESPECIALES.length(), spec.longitudMaxima())).strip();
                List<String> valores = new ArrayList<>(ejemplo);
                valores.set(i, texto);
                SolicitudCorreo s = porCorreo(asunto(comando.nombre(), valores));
                try {
                    String recibido = ValidadorParametros.validar(comando, s.parametros).texto(spec.nombre());
                    if (!texto.equals(recibido)) {
                        errores.add(comando.nombre() + " " + spec.nombre() + ": llegó \"" + recibido + "\"");
                    }
                } catch (NegocioException e) {
                    errores.add(comando.nombre() + " " + spec.nombre() + ": " + e.getMessage());
                }
            }
        }
        assertTrue(errores.isEmpty(), "Textos con letras especiales rechazados o alterados:\n" + String.join("\n", errores));
        assertTrue(casos > 0);
    }

    @Test
    @DisplayName("Límites: el nombre del comando y los códigos no admiten tildes ni ñ; esos asuntos se rechazan")
    void limitesDeLetrasEspeciales() throws Exception {
        // Una letra con tilde o ñ en el nombre se rechaza diciendo cuál es y dónde está.
        for (String[] caso : new String[][] {{"HÉLP[\"*\"]", "2", "É"}, {"ÑANDU[\"*\"]", "1", "Ñ"},
                {"  helpñ[\"*\"]", "7", "ñ"}, {"LISPRÓ[\"miel\"]", "6", "Ó"}}) {
            ErrorSintaxisException e = assertThrows(ErrorSintaxisException.class, () -> analizador.analizar(caso[0]));
            assertTrue(e.getMessage().startsWith("El nombre del comando solo admite letras sin tilde ni ñ (A-Z) y en la posición "
                    + caso[1] + " hay '" + caso[2] + "'"), caso[0] + " -> " + e.getMessage());
        }
        // Las letras especiales DESPUÉS de un espacio no son parte del nombre: el error sigue siendo que falta '['.
        ErrorSintaxisException sinCorchete = assertThrows(ErrorSintaxisException.class, () -> analizador.analizar("Ana Peña"));
        assertTrue(sinCorchete.getMessage().startsWith("Se esperaba '[' después del nombre del comando en la posición 5"),
                sinCorchete.getMessage());

        // Primer comando del catálogo con un parámetro de tipo código: se le pone un código con ñ.
        for (ComandoSpec comando : catalogo().todos()) {
            for (int i = 0; i < comando.parametros().size(); i++) {
                if (comando.parametros().get(i).tipo() == TipoParametro.CODIGO) {
                    List<String> valores = new ArrayList<>(analizador.analizar(comando.ejemplo()).parametros());
                    valores.set(i, "PEÑA-01");
                    NegocioException codigo = assertThrows(NegocioException.class,
                            () -> ValidadorParametros.validar(comando, valores));
                    assertTrue(codigo.getMessage().contains("solo admite letras, dígitos"), codigo.getMessage());
                    return;
                }
            }
        }
        throw new AssertionError("El catálogo no tiene ningún parámetro de tipo código");
    }

    // ---------------- utilidades ----------------

    private static CatalogoComandos catalogo() {
        Clock reloj = Clock.systemUTC();
        // La conexión no se abre al construir los módulos.
        ContextoNegocio contexto = new ContextoNegocio(
                new GestorTransacciones(new ConexionBD("jdbc:postgresql://127.0.0.1:1/sin_conexion", "nadie", "")),
                reloj, new ServicioBitacora(new BitacoraRepositorio()), new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
        CatalogoComandos catalogo = new CatalogoComandos();
        for (ModuloComandos modulo : Aplicacion.modulos(contexto, 30)) {
            catalogo.registrarModulo(modulo);
        }
        return catalogo;
    }

    /** Envía el asunto como lo hace Gmail con letras no ASCII: codificado en UTF-8 Base64. */
    private SolicitudCorreo porCorreo(String asunto) {
        String codificado = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(asunto.getBytes(StandardCharsets.UTF_8)) + "?=";
        String correo = "From: <ana@feder.example>\r\nTo: <sistema@feder.local>\r\nSubject: " + codificado + "\r\n\r\nCuerpo\r\n";
        return interprete.interpretar("uid", correo.getBytes(StandardCharsets.US_ASCII));
    }

    private static String asunto(String nombre, List<String> valores) {
        List<String> citados = new ArrayList<>();
        for (String v : valores) {
            citados.add("\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        }
        return nombre + "[" + String.join(",", citados) + "]";
    }

    private static String capitalizado(String s) {
        return s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }

    /** "HELP" → "HeLp": alterna mayúscula y minúscula letra por letra. */
    private static String alternado(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(i % 2 == 0 ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static List<String> diferencias(ComandoSpec comando, Parametros esperados, Parametros obtenidos) {
        List<String> distintos = new ArrayList<>();
        for (ParametroSpec spec : comando.parametros()) {
            Object a = valor(esperados, spec);
            Object b = valor(obtenidos, spec);
            if (a == null ? b != null : !a.equals(b)) {
                distintos.add(spec.nombre() + ": esperado " + a + ", obtenido " + b);
            }
        }
        return distintos;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object valor(Parametros p, ParametroSpec spec) {
        String n = spec.nombre();
        if (p.esComodin(n)) {
            return "*";
        }
        switch (spec.tipo()) {
            case ENTERO:
                return p.entero(n);
            case DECIMAL:
                return p.decimal(n);
            case FECHA:
                return p.fecha(n);
            case FECHA_HORA:
                return p.fechaHora(n);
            case BOOLEANO:
                return p.booleano(n);
            case ENUMERACION:
                return p.enumeracion(n, (Class) spec.enumeracion());
            default:
                return p.texto(n);
        }
    }
}
