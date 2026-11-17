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
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.negocio.mensajeria.SolicitudCorreo;
import com.feder.presentacion.comandos.ComandoAnalizado;
import com.feder.presentacion.comandos.CommandParser;
import com.feder.presentacion.correo.mime.CodificadorCabeceras;
import com.feder.presentacion.procesamiento.InterpreteCorreo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprueba el catálogo completo tal como lo arma la raíz de composición: los 142 comandos de CU1–CU8 y del
 * sistema están registrados una sola vez, y el ejemplo documentado de cada uno pasa por el analizador del asunto y
 * por la validación de parámetros. No necesita base de datos: solo se construyen los módulos.
 */
class CatalogoCompletoTest {

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

    @Test
    @DisplayName("Los 142 comandos están registrados, uno por nombre, repartidos en CU1–CU8 y el sistema")
    void cantidadYReparto() {
        CatalogoComandos catalogo = catalogo();
        assertEquals(142, catalogo.todos().size());
        Map<String, Integer> porCaso = new TreeMap<>();
        catalogo.todos().forEach(c -> porCaso.merge(c.casoDeUso(), 1, Integer::sum));
        for (String cu : List.of("CU1", "CU2", "CU3", "CU4", "CU5", "CU6", "CU7", "CU8")) {
            assertTrue(porCaso.getOrDefault(cu, 0) > 0, "sin comandos para " + cu + ": " + porCaso);
        }
    }

    @Test
    @DisplayName("El ejemplo de cada comando se analiza y valida sin errores")
    void ejemplosAnalizablesYValidos() throws Exception {
        CommandParser analizador = new CommandParser();
        List<String> errores = new ArrayList<>();
        for (ComandoSpec comando : catalogo().todos()) {
            try {
                ComandoAnalizado analizado = analizador.analizar(comando.ejemplo());
                if (!analizado.nombre().equals(comando.nombre())) {
                    errores.add(comando.nombre() + ": el ejemplo invoca " + analizado.nombre());
                    continue;
                }
                ValidadorParametros.validar(comando, analizado.parametros());
            } catch (Exception e) {
                errores.add(comando.nombre() + ": " + comando.ejemplo() + " -> " + e.getMessage());
            }
        }
        assertTrue(errores.isEmpty(), "Ejemplos con errores:\n" + String.join("\n", errores));
    }

    @Test
    @DisplayName("El ejemplo de cada comando llega idéntico a negocio con el asunto codificado como lo envían los clientes")
    void ejemplosCodificadosLleganIguales() throws Exception {
        CommandParser analizador = new CommandParser();
        InterpreteCorreo interprete = new InterpreteCorreo("buzon", "sistema@feder.local");
        List<String> errores = new ArrayList<>();
        int comprobaciones = 0;
        for (ComandoSpec comando : catalogo().todos()) {
            ComandoAnalizado esperado = analizador.analizar(comando.ejemplo());
            byte[] utf8 = comando.ejemplo().getBytes(StandardCharsets.UTF_8);
            Map<String, byte[]> variantes = new LinkedHashMap<>();
            variantes.put("B UTF-8", ascii("=?UTF-8?B?" + Base64.getEncoder().encodeToString(utf8) + "?="));
            variantes.put("Q UTF-8", ascii(palabraQ(utf8)));
            variantes.put("codificador del proyecto", ascii(CodificadorCabeceras.textoNoEstructurado(comando.ejemplo(), "Subject")));
            variantes.put("UTF-8 sin codificar", utf8);
            variantes.put("B partido en palabras plegadas", ascii(enPalabrasPartidas(utf8, 23)));
            for (Map.Entry<String, byte[]> variante : variantes.entrySet()) {
                comprobaciones++;
                byte[] crudo = unir(ascii("From: <ana@feder.example>\r\nTo: <sistema@feder.local>\r\nSubject: "),
                        variante.getValue(), ascii("\r\n\r\nCuerpo\r\n"));
                SolicitudCorreo s = interprete.interpretar("uid", crudo);
                if (s.errorCodificacion != null || s.errorSintaxis != null || !comando.nombre().equals(s.comando)
                        || !esperado.parametros().equals(s.parametros)) {
                    errores.add(comando.nombre() + " (" + variante.getKey() + "): " + s.comando + " " + s.parametros
                            + " codificación=" + s.errorCodificacion + " sintaxis=" + s.errorSintaxis);
                }
            }
        }
        assertEquals(142 * 5, comprobaciones);
        assertTrue(errores.isEmpty(), "Ejemplos que no llegaron iguales:\n" + String.join("\n", errores));
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] unir(byte[]... partes) {
        java.io.ByteArrayOutputStream salida = new java.io.ByteArrayOutputStream();
        for (byte[] parte : partes) {
            salida.writeBytes(parte);
        }
        return salida.toByteArray();
    }

    /** Una palabra Q: ASCII imprimible tal cual (salvo = ? _), espacio como '_', el resto como =XX. */
    private static String palabraQ(byte[] bytes) {
        StringBuilder sb = new StringBuilder("=?UTF-8?Q?");
        for (byte b : bytes) {
            int v = b & 0xFF;
            if (v == ' ') {
                sb.append('_');
            } else if (v > 0x20 && v < 0x7F && v != '=' && v != '?' && v != '_') {
                sb.append((char) v);
            } else {
                sb.append(String.format("=%02X", v));
            }
        }
        return sb.append("?=").toString();
    }

    /** Palabras B de a lo sumo {@code tamano} bytes cada una (pueden partir una letra UTF-8), en líneas plegadas. */
    private static String enPalabrasPartidas(byte[] bytes, int tamano) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i += tamano) {
            byte[] trozo = java.util.Arrays.copyOfRange(bytes, i, Math.min(bytes.length, i + tamano));
            if (i > 0) {
                sb.append("\r\n ");
            }
            sb.append("=?UTF-8?B?").append(Base64.getEncoder().encodeToString(trozo)).append("?=");
        }
        return sb.toString();
    }
}
