package com.feder.negocio.comun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Resultado de un caso de uso, independiente del formato de presentación.
 *
 * La capa de negocio solo describe el contenido (título, mensaje, tablas y detalles clave-valor);
 * la capa de presentación decide cómo convertirlo en texto plano o HTML para el correo.
 */
public final class ResultadoOperacion {

    /** Tabla de resultados: encabezados y filas ya convertidas a texto. */
    public static final class Tabla {
        private final String titulo;
        private final List<String> columnas;
        private final List<List<String>> filas = new ArrayList<>();
        private String descripcion = "";
        private boolean enFichas;

        public Tabla(String titulo, List<String> columnas) {
            this.titulo = titulo;
            this.columnas = List.copyOf(columnas);
        }

        public Tabla fila(Object... valores) {
            if (valores.length != columnas.size()) {
                throw new IllegalArgumentException("La fila tiene " + valores.length + " valores y la tabla "
                        + columnas.size() + " columnas");
            }
            List<String> fila = new ArrayList<>(valores.length);
            for (Object valor : valores) {
                fila.add(valor == null ? "" : String.valueOf(valor));
            }
            filas.add(Collections.unmodifiableList(fila));
            return this;
        }

        public String titulo() {
            return titulo;
        }

        public List<String> columnas() {
            return columnas;
        }

        public List<List<String>> filas() {
            return Collections.unmodifiableList(filas);
        }

        /** Texto breve que acompaña al título (por ejemplo, de qué trata un tema de la ayuda). */
        public Tabla descripcion(String texto) {
            this.descripcion = texto == null ? "" : texto;
            return this;
        }

        public String descripcion() {
            return descripcion;
        }

        /**
         * Pide mostrar cada fila como una ficha en lugar de una tabla: la 1.ª columna como nombre destacado,
         * la 2.ª como título y las demás como «columna: valor». Pensado para la ayuda, que lee gente sin
         * conocimientos técnicos y se abre también en el celular.
         */
        public Tabla enFichas() {
            this.enFichas = true;
            return this;
        }

        public boolean esEnFichas() {
            return enFichas;
        }
    }

    /**
     * Recuadro de texto destacado, antes de los detalles y las tablas: PASOS se muestra como lista numerada
     * y NOTA como lista con viñetas.
     */
    public record Recuadro(String titulo, Estilo estilo, List<String> lineas) {
        public enum Estilo { PASOS, NOTA }

        public Recuadro {
            Objects.requireNonNull(titulo);
            Objects.requireNonNull(estilo);
            lineas = List.copyOf(lineas);
        }
    }

    private final boolean exito;
    private boolean confirmacionPendiente;
    private final String titulo;
    private final String mensaje;
    private final List<String[]> detalles = new ArrayList<>();
    private final List<Tabla> tablas = new ArrayList<>();
    private final List<Recuadro> recuadros = new ArrayList<>();
    private com.feder.modelo.FormatoReporte formatoExportacion;
    private String nombreArchivo;

    private ResultadoOperacion(boolean exito, String titulo, String mensaje) {
        this.exito = exito;
        this.titulo = Objects.requireNonNull(titulo);
        this.mensaje = mensaje == null ? "" : mensaje;
    }

    public static ResultadoOperacion exito(String titulo, String mensaje) {
        return new ResultadoOperacion(true, titulo, mensaje);
    }

    public static ResultadoOperacion error(String titulo, String mensaje) {
        return new ResultadoOperacion(false, titulo, mensaje);
    }

    /** La operación no se ejecutó todavía: espera la confirmación con el código enviado por correo. */
    public static ResultadoOperacion confirmacionPendiente(String titulo, String mensaje) {
        ResultadoOperacion resultado = new ResultadoOperacion(true, titulo, mensaje);
        resultado.confirmacionPendiente = true;
        return resultado;
    }

    public boolean esConfirmacionPendiente() {
        return confirmacionPendiente;
    }

    /** Agrega un dato clave-valor (por ejemplo, "Código" -> "PED-000123"). */
    public ResultadoOperacion detalle(String clave, Object valor) {
        detalles.add(new String[] {clave, valor == null ? "" : String.valueOf(valor)});
        return this;
    }

    public ResultadoOperacion tabla(Tabla tabla) {
        tablas.add(tabla);
        return this;
    }

    public ResultadoOperacion recuadro(String titulo, Recuadro.Estilo estilo, String... lineas) {
        recuadros.add(new Recuadro(titulo, estilo, List.of(lineas)));
        return this;
    }

    public List<Recuadro> recuadros() {
        return Collections.unmodifiableList(recuadros);
    }

    /**
     * Pide que las tablas del resultado se adjunten además como archivo (reportes de CU8).
     * El negocio solo indica el formato y el nombre base; la presentación genera el archivo.
     */
    public ResultadoOperacion exportarComo(com.feder.modelo.FormatoReporte formato, String nombreBase) {
        this.formatoExportacion = Objects.requireNonNull(formato);
        this.nombreArchivo = Objects.requireNonNull(nombreBase).replaceAll("[^A-Za-z0-9_-]", "_");
        return this;
    }

    /** Formato del archivo adjunto solicitado, o null si la respuesta no lleva adjunto. */
    public com.feder.modelo.FormatoReporte formatoExportacion() {
        return formatoExportacion;
    }

    /** Nombre base del archivo adjunto (sin extensión). */
    public String nombreArchivo() {
        return nombreArchivo;
    }

    public boolean esExito() {
        return exito;
    }

    public String titulo() {
        return titulo;
    }

    public String mensaje() {
        return mensaje;
    }

    public List<String[]> detalles() {
        return Collections.unmodifiableList(detalles);
    }

    public List<Tabla> tablas() {
        return Collections.unmodifiableList(tablas);
    }
}
