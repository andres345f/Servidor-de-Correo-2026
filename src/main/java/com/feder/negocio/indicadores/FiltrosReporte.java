package com.feder.negocio.indicadores;

import com.feder.datos.reportes.FiltrosConsulta;
import com.feder.modelo.EstadoCompra;
import com.feder.modelo.EstadoLoteFeder;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.MetodoPago;
import com.feder.modelo.ModalidadEntrega;
import com.feder.modelo.TipoPedido;
import com.feder.negocio.comun.NegocioException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Filtros opcionales de los reportes de CU8, escritos en un solo parámetro como "clave=valor;clave=valor"
 * (por ejemplo "tipo=ENCARGO;categoria=Mieles"). "*" o un texto vacío significa «sin filtros».
 * Cada reporte admite solo sus claves; los valores de estado, tipo, modalidad y método se validan contra
 * las enumeraciones del modelo antes de consultar.
 */
public final class FiltrosReporte {

    /** Claves de filtro que conoce algún reporte. */
    public enum Clave {
        PRODUCTO, CATEGORIA, TIPO, MODALIDAD, ESTADO, METODO, PRODUCTOR, COMUNIDAD, DESDE, HASTA;

        String nombre() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Claves y enumeración de estado de cada reporte que admite filtros. */
    enum Reporte {
        VENTAS(EstadoPedido.class, Clave.PRODUCTO, Clave.CATEGORIA, Clave.ESTADO, Clave.TIPO, Clave.MODALIDAD, Clave.METODO),
        INVENTARIO(EstadoLoteFeder.class, Clave.PRODUCTO, Clave.CATEGORIA, Clave.PRODUCTOR, Clave.COMUNIDAD, Clave.ESTADO,
                Clave.DESDE, Clave.HASTA),
        ABASTECIMIENTO(EstadoCompra.class, Clave.PRODUCTO, Clave.CATEGORIA, Clave.PRODUCTOR, Clave.COMUNIDAD, Clave.ESTADO);

        private final Class<? extends Enum<?>> estados;
        private final Set<Clave> claves;

        Reporte(Class<? extends Enum<?>> estados, Clave... claves) {
            this.estados = estados;
            this.claves = Set.of(claves);
        }

        String clavesPermitidas() {
            return Arrays.stream(Clave.values()).filter(claves::contains).map(Clave::nombre).collect(Collectors.joining(", "));
        }
    }

    private static final FiltrosReporte NINGUNO = new FiltrosReporte(new EnumMap<>(Clave.class));

    private final Map<Clave, String> valores;

    private FiltrosReporte(Map<Clave, String> valores) {
        this.valores = valores;
    }

    public static FiltrosReporte ninguno() {
        return NINGUNO;
    }

    /** Analiza y valida el texto de filtros de un reporte. */
    static FiltrosReporte analizar(String texto, Reporte reporte) {
        if (texto == null || texto.isBlank() || texto.strip().equals("*")) {
            return NINGUNO;
        }
        Map<Clave, String> valores = new EnumMap<>(Clave.class);
        for (String par : texto.split(";")) {
            if (par.isBlank()) {
                continue;
            }
            int igual = par.indexOf('=');
            if (igual < 0) {
                throw invalido("El filtro «" + par.strip() + "» debe tener la forma clave=valor. Claves admitidas: "
                        + reporte.clavesPermitidas() + ".");
            }
            Clave clave = clave(par.substring(0, igual).strip(), reporte);
            String valor = par.substring(igual + 1).strip();
            if (valor.isEmpty()) {
                throw invalido("Falta el valor del filtro " + clave.nombre() + ".");
            }
            if (valores.put(clave, normalizar(clave, valor, reporte)) != null) {
                throw invalido("El filtro " + clave.nombre() + " está repetido.");
            }
        }
        FiltrosReporte filtros = new FiltrosReporte(valores);
        filtros.exigirRangoCoherente();
        return filtros;
    }

    public boolean vacio() {
        return valores.isEmpty();
    }

    /** Valor del filtro, o null si no se indicó. */
    public String valor(Clave clave) {
        return valores.get(clave);
    }

    public LocalDate fecha(Clave clave) {
        String valor = valores.get(clave);
        return valor == null ? null : LocalDate.parse(valor);
    }

    public boolean tieneAlguno(Clave... claves) {
        return Arrays.stream(claves).anyMatch(valores::containsKey);
    }

    public String describir() {
        if (vacio()) {
            return "Sin filtros";
        }
        StringJoiner texto = new StringJoiner("; ");
        valores.forEach((clave, valor) -> texto.add(clave.nombre() + "=" + valor));
        return texto.toString();
    }

    FiltrosConsulta.Ventas aVentas() {
        return new FiltrosConsulta.Ventas(valor(Clave.PRODUCTO), valor(Clave.CATEGORIA), valor(Clave.TIPO),
                valor(Clave.MODALIDAD), valor(Clave.ESTADO));
    }

    FiltrosConsulta.Lotes aLotes() {
        return new FiltrosConsulta.Lotes(valor(Clave.PRODUCTO), valor(Clave.CATEGORIA), valor(Clave.PRODUCTOR),
                valor(Clave.COMUNIDAD), valor(Clave.ESTADO));
    }

    FiltrosConsulta.Compras aCompras() {
        return new FiltrosConsulta.Compras(valor(Clave.PRODUCTO), valor(Clave.CATEGORIA), valor(Clave.PRODUCTOR),
                valor(Clave.COMUNIDAD), valor(Clave.ESTADO));
    }

    // ---- Validación ----

    private static Clave clave(String texto, Reporte reporte) {
        for (Clave clave : reporte.claves) {
            if (clave.nombre().equalsIgnoreCase(texto)) {
                return clave;
            }
        }
        throw invalido("Filtro desconocido «" + texto + "». Claves admitidas en este reporte: "
                + reporte.clavesPermitidas() + ".");
    }

    private static String normalizar(Clave clave, String valor, Reporte reporte) {
        switch (clave) {
            case TIPO:
                return enumeracion(clave, valor, TipoPedido.class);
            case MODALIDAD:
                return enumeracion(clave, valor, ModalidadEntrega.class);
            case METODO:
                return enumeracion(clave, valor, MetodoPago.class);
            case ESTADO:
                return enumeracion(clave, valor, reporte.estados);
            case DESDE:
            case HASTA:
                return fecha(clave, valor);
            case PRODUCTO:
            case PRODUCTOR:
                return valor.toUpperCase(Locale.ROOT);
            default:
                return valor;
        }
    }

    private static String enumeracion(Clave clave, String valor, Class<? extends Enum<?>> tipo) {
        String nombre = valor.toUpperCase(Locale.ROOT);
        for (Enum<?> constante : tipo.getEnumConstants()) {
            if (constante.name().equals(nombre)) {
                return nombre;
            }
        }
        String permitidos = Arrays.stream(tipo.getEnumConstants()).map(Enum::name).collect(Collectors.joining(", "));
        throw invalido("El filtro " + clave.nombre() + " debe ser uno de: " + permitidos + ".");
    }

    private static String fecha(Clave clave, String valor) {
        try {
            return LocalDate.parse(valor).toString();
        } catch (DateTimeParseException e) {
            throw invalido("El filtro " + clave.nombre() + " debe ser una fecha con el formato AAAA-MM-DD.");
        }
    }

    private void exigirRangoCoherente() {
        LocalDate desde = fecha(Clave.DESDE);
        LocalDate hasta = fecha(Clave.HASTA);
        if (desde != null && hasta != null && desde.isAfter(hasta)) {
            throw invalido("El filtro desde (" + desde + ") es posterior al filtro hasta (" + hasta + ").");
        }
    }

    private static NegocioException invalido(String mensaje) {
        return new NegocioException(NegocioException.Codigo.PARAMETRO_INVALIDO, mensaje);
    }
}
