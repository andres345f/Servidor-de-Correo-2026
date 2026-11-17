package com.feder.negocio.comandos;

import com.feder.modelo.TipoRol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Definición declarativa de un comando por correo: nombre, parámetros, quién puede usarlo,
 * si modifica datos (y por tanto exige confirmación) y el servicio que lo atiende.
 */
public final class ComandoSpec {

    /** Quién puede ejecutar el comando. */
    public enum Acceso {
        /** Cualquier remitente, incluso no registrado (p. ej. HELP, catálogo público, registro de cliente). */
        PUBLICO,
        /** Cualquier usuario registrado y activo, con al menos un rol vigente. */
        REGISTRADO,
        /** Solo los roles indicados en {@link #roles()}. */
        ROLES
    }

    private static final Pattern NOMBRE_VALIDO = Pattern.compile("[A-Z]{3,12}");

    private final String nombre;
    private final String casoDeUso;
    private final String descripcion;
    private final List<ParametroSpec> parametros;
    private final Acceso acceso;
    private final Set<TipoRol> roles;
    private final boolean modificaDatos;
    private final String ejemplo;
    private final ComandoHandler handler;

    private ComandoSpec(Constructor c) {
        this.nombre = c.nombre;
        this.casoDeUso = c.casoDeUso;
        this.descripcion = c.descripcion;
        this.parametros = List.copyOf(c.parametros);
        this.acceso = c.acceso;
        this.roles = c.roles.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(c.roles));
        this.modificaDatos = c.modificaDatos;
        this.ejemplo = c.ejemplo;
        this.handler = c.handler;
    }

    public static Constructor comando(String nombre) {
        return new Constructor(nombre);
    }

    public String nombre() {
        return nombre;
    }

    public String casoDeUso() {
        return casoDeUso;
    }

    public String descripcion() {
        return descripcion;
    }

    public List<ParametroSpec> parametros() {
        return parametros;
    }

    public Acceso acceso() {
        return acceso;
    }

    public Set<TipoRol> roles() {
        return roles;
    }

    /** Si es true, el comando se ejecuta solo después de la confirmación por correo. */
    public boolean modificaDatos() {
        return modificaDatos;
    }

    public String ejemplo() {
        return ejemplo;
    }

    public ComandoHandler handler() {
        return handler;
    }

    /** Sintaxis para la ayuda: NOMBRE["p1","p2"]. */
    public String sintaxis() {
        StringBuilder sb = new StringBuilder(nombre).append('[');
        for (int i = 0; i < parametros.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(parametros.get(i).nombre()).append('"');
        }
        return sb.append(']').toString();
    }

    /** El comando tal como lo escribió el usuario, para repetirlo en la respuesta. */
    public String sintaxisConValores(java.util.List<String> valores) {
        StringBuilder sb = new StringBuilder(nombre).append('[');
        for (int i = 0; i < valores.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(valores.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return sb.append(']').toString();
    }

    /** Constructor fluido para que cada módulo declare sus comandos de forma legible. */
    public static final class Constructor {
        private final String nombre;
        private String casoDeUso;
        private String descripcion;
        private final List<ParametroSpec> parametros = new ArrayList<>();
        private Acceso acceso;
        private final Set<TipoRol> roles = EnumSet.noneOf(TipoRol.class);
        private boolean modificaDatos;
        private String ejemplo;
        private ComandoHandler handler;

        private Constructor(String nombre) {
            String normalizado = Objects.requireNonNull(nombre).toUpperCase(Locale.ROOT);
            if (!NOMBRE_VALIDO.matcher(normalizado).matches()) {
                throw new IllegalArgumentException("Nombre de comando inválido: " + nombre);
            }
            this.nombre = normalizado;
        }

        public Constructor casoDeUso(String cu) {
            this.casoDeUso = cu;
            return this;
        }

        public Constructor descripcion(String texto) {
            this.descripcion = texto;
            return this;
        }

        public Constructor parametros(ParametroSpec... lista) {
            parametros.addAll(List.of(lista));
            return this;
        }

        public Constructor publico() {
            this.acceso = Acceso.PUBLICO;
            return this;
        }

        public Constructor registrado() {
            this.acceso = Acceso.REGISTRADO;
            return this;
        }

        public Constructor roles(TipoRol... permitidos) {
            this.acceso = Acceso.ROLES;
            roles.addAll(List.of(permitidos));
            return this;
        }

        /** Marca el comando como de escritura: exigirá confirmación por correo. */
        public Constructor modificaDatos() {
            this.modificaDatos = true;
            return this;
        }

        public Constructor ejemplo(String texto) {
            this.ejemplo = texto;
            return this;
        }

        public Constructor handler(ComandoHandler h) {
            this.handler = h;
            return this;
        }

        public ComandoSpec construir() {
            if (casoDeUso == null || descripcion == null || acceso == null || handler == null || ejemplo == null) {
                throw new IllegalStateException("El comando " + nombre + " está incompleto (caso de uso, descripción, acceso, ejemplo y handler son obligatorios)");
            }
            if (parametros.isEmpty()) {
                throw new IllegalStateException("El comando " + nombre + " debe declarar al menos un parámetro (use \"*\" si no necesita datos)");
            }
            if (acceso == Acceso.ROLES && roles.isEmpty()) {
                throw new IllegalStateException("El comando " + nombre + " requiere al menos un rol");
            }
            return new ComandoSpec(this);
        }
    }
}
