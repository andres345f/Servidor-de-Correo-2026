package com.feder.presentacion.comandos;

import com.feder.presentacion.comandos.LexerComando.Tipo;
import com.feder.presentacion.comandos.LexerComando.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Analizador sintáctico del asunto de un correo.
 *
 * Gramática:
 * <pre>
 * comando := WS* NOMBRE WS* "[" WS* param (WS* "," WS* param)* WS* "]" WS*
 * NOMBRE  := 3 a 12 letras ASCII (sin distinguir mayúsculas)
 * param   := '"' ( carácter que no sea '"', '\' ni de control | '\"' | '\\' )* '"'
 * </pre>
 * Solo valida la forma: que el comando exista y que sus parámetros sean correctos se verifica después.
 */
public final class CommandParser {

    public static final int MAXIMO_CARACTERES = 1000;
    public static final int MAXIMO_PARAMETROS = 60;
    public static final int MINIMO_NOMBRE = 3;
    public static final int MAXIMO_NOMBRE = 12;

    public ComandoAnalizado analizar(String asunto) throws ErrorSintaxisException {
        if (asunto == null || asunto.isBlank()) {
            throw new ErrorSintaxisException("El asunto está vacío: se esperaba un comando, por ejemplo HELP[\"*\"]", 1);
        }
        int longitud = asunto.codePointCount(0, asunto.length());
        if (longitud > MAXIMO_CARACTERES) {
            throw new ErrorSintaxisException("El asunto tiene " + longitud + " caracteres y el máximo es "
                    + MAXIMO_CARACTERES, MAXIMO_CARACTERES + 1);
        }
        LexerComando lexer = new LexerComando(asunto);

        Token nombre = lexer.siguiente();
        // Una letra con tilde o ñ en el nombre (al inicio o pegada a él) se explica tal cual; si no, el nombre
        // quedaría cortado en esa letra y el mensaje hablaría de su longitud ("'H' tiene 1").
        if (nombre.tipo() == Tipo.OTRO && Character.isLetter(nombre.valor().codePointAt(0))) {
            throw letraNoPermitida(nombre.valor(), nombre.posicion());
        }
        if (nombre.tipo() != Tipo.NOMBRE) {
            throw esperado("el nombre del comando (solo letras)", nombre);
        }
        int posicionSiguiente = nombre.posicion() + nombre.valor().length();
        int siguiente = puntoDeCodigo(asunto, posicionSiguiente);
        if (siguiente >= 0 && Character.isLetter(siguiente)) {
            throw letraNoPermitida(new String(Character.toChars(siguiente)), posicionSiguiente);
        }
        if (nombre.valor().length() < MINIMO_NOMBRE || nombre.valor().length() > MAXIMO_NOMBRE) {
            throw new ErrorSintaxisException("El nombre del comando debe tener entre " + MINIMO_NOMBRE + " y "
                    + MAXIMO_NOMBRE + " letras y '" + nombre.valor() + "' tiene " + nombre.valor().length()
                    + " (posición " + nombre.posicion() + ")", nombre.posicion());
        }
        Token abre = lexer.siguiente();
        if (abre.tipo() != Tipo.ABRE) {
            throw esperado("'[' después del nombre del comando", abre);
        }

        List<String> parametros = new ArrayList<>();
        while (true) {
            Token parametro = lexer.siguiente();
            if (parametro.tipo() != Tipo.CADENA) {
                if (parametros.isEmpty() && parametro.tipo() == Tipo.CIERRA) {
                    throw new ErrorSintaxisException("Se esperaba un parámetro entre comillas en la posición "
                            + parametro.posicion() + ": los corchetes vacíos no están permitidos (use, por ejemplo, [\"*\"])",
                            parametro.posicion());
                }
                throw esperado("un parámetro entre comillas", parametro);
            }
            if (parametros.size() == MAXIMO_PARAMETROS) {
                throw new ErrorSintaxisException("El comando supera el máximo de " + MAXIMO_PARAMETROS
                        + " parámetros (posición " + parametro.posicion() + ")", parametro.posicion());
            }
            parametros.add(parametro.valor());
            Token separador = lexer.siguiente();
            if (separador.tipo() == Tipo.CIERRA) {
                break;
            }
            if (separador.tipo() != Tipo.COMA) {
                throw esperado("',' o ']'", separador);
            }
        }

        Token fin = lexer.siguiente();
        if (fin.tipo() != Tipo.FIN) {
            throw new ErrorSintaxisException("Texto inesperado después de ']' en la posición " + fin.posicion()
                    + ": el comando debe terminar en ']'", fin.posicion());
        }
        return new ComandoAnalizado(nombre.valor().toUpperCase(Locale.ROOT), parametros, asunto);
    }

    private static ErrorSintaxisException letraNoPermitida(String letra, int posicion) {
        return new ErrorSintaxisException("El nombre del comando solo admite letras sin tilde ni ñ (A-Z) y en la posición "
                + posicion + " hay '" + letra + "'. Escríbalo sin tildes, por ejemplo HELP[\"*\"]", posicion);
    }

    /** Punto de código en la posición indicada (contada desde 1, por carácter), o -1 si el texto termina antes. */
    private static int puntoDeCodigo(String texto, int posicion) {
        int indice = posicion - 1;
        if (indice >= texto.codePointCount(0, texto.length())) {
            return -1;
        }
        return texto.codePointAt(texto.offsetByCodePoints(0, indice));
    }

    private static ErrorSintaxisException esperado(String que, Token encontrado) {
        return new ErrorSintaxisException("Se esperaba " + que + " en la posición " + encontrado.posicion()
                + ", pero se encontró " + encontrado.describir(), encontrado.posicion());
    }
}
