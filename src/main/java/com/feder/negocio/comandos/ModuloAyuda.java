package com.feder.negocio.comandos;

import com.feder.modelo.TipoRol;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.ResultadoOperacion.Recuadro;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Comando HELP: la ayuda se genera a partir del propio catálogo, así nunca queda desactualizada.
 *   HELP["*"]      guía completa: cómo enviar un comando, reglas y una ficha por comando, agrupadas por tema.
 *   HELP["CU4"]    las fichas de un tema (caso de uso).
 *   HELP["INSCAT"] el detalle de un comando: ejemplo para copiar, forma general y qué escribir en cada dato.
 *
 * Está escrita para personas sin conocimientos de informática: los temas se nombran en palabras, cada comando
 * trae un ejemplo listo para copiar en el asunto y los permisos se dicen como «Administrador», no como códigos.
 */
public final class ModuloAyuda implements ModuloComandos {

    /** Nombre en palabras y de qué trata cada tema (caso de uso), en el orden en que se muestran. */
    private static final Map<String, String[]> TEMAS = new LinkedHashMap<>();

    static {
        TEMAS.put("CU1", new String[] {"1. Usuarios y cuentas",
                "Registrarse, ver y cambiar sus datos y, el administrador, gestionar cuentas, roles e invitaciones."});
        TEMAS.put("CU2", new String[] {"2. Productores y comunidades",
                "Comunidades agrícolas y solicitudes para ser productor de FEDER."});
        TEMAS.put("CU3", new String[] {"3. Producción y lotes de origen",
                "Lo que elabora cada productor, sus lotes y de dónde viene cada producto."});
        TEMAS.put("CU4", new String[] {"4. Catálogo, compras e inventario",
                "Productos y categorías; compras a productores, stock y vencimientos."});
        TEMAS.put("CU5", new String[] {"5. Carrito y pedidos",
                "Armar el carrito, hacer pedidos y encargos, y seguirlos."});
        TEMAS.put("CU6", new String[] {"6. Pagos, entregas y devoluciones",
                "Pagos, facturas, entregas, devoluciones, reembolsos y avisos."});
        TEMAS.put("CU7", new String[] {"7. Indicadores financieros",
                "Ingresos, costos, márgenes e inventario valorizado."});
        TEMAS.put("CU8", new String[] {"8. Reportes",
                "Reportes en PDF o Excel que llegan como archivo adjunto."});
        TEMAS.put("SISTEMA", new String[] {"Ayuda", "Esta guía de comandos."});
    }

    /** Sin «Quién puede usarlo» ni «¿Pide confirmación?» (pedido del usuario): esos datos están en HELP["NOMBRE"]. */
    private static final List<String> COLUMNAS_FICHA = List.of("Comando", "Qué hace", "Ejemplo para copiar en el asunto",
            "Datos, en orden");

    private CatalogoComandos catalogo;

    @Override
    public void registrar(CatalogoComandos catalogo) {
        this.catalogo = catalogo;
        catalogo.registrar(ComandoSpec.comando("HELP")
                .casoDeUso("SISTEMA")
                .descripcion("Muestra esta guía de comandos, los comandos de un tema (por ejemplo CU4) o el detalle de uno")
                .parametros(ParametroSpec.texto("tema", 12).conComodin())
                .publico()
                .ejemplo("HELP[\"*\"]")
                .handler((p, s) -> p.esComodin("tema") ? listarTodo() : detalle(p.texto("tema")))
                .construir());
    }

    private ResultadoOperacion listarTodo() {
        Map<String, List<ComandoSpec>> porTema = porTema();
        String indice = porTema.keySet().stream().map(ModuloAyuda::nombreTema).collect(Collectors.joining(" · "));
        ResultadoOperacion resultado = ResultadoOperacion.exito("Guía de comandos",
                "Con esta guía puede usar el sistema FEDER solo enviando correos; no necesita instalar nada.\n"
                        + "Temas: " + indice + ".");
        pasos(resultado);
        reglas(resultado);
        // Con «Datos, en orden» en formato breve: sin él, el ejemplo no explica qué es cada valor. Gmail recorta los
        // correos HTML de más de 102 KB; AyudaComandosTest vigila que la guía completa no pase de 100 KB.
        porTema.forEach((tema, comandos) -> resultado.tabla(fichas(tema, comandos)));
        return resultado;
    }

    private ResultadoOperacion detalle(String tema) {
        String buscado = tema.trim().toUpperCase(Locale.ROOT);
        List<ComandoSpec> delCaso = catalogo.delCasoDeUso(buscado);
        if (!delCaso.isEmpty()) {
            ResultadoOperacion resultado = ResultadoOperacion.exito("Ayuda del tema " + nombreTema(buscado),
                    "Copie el ejemplo del comando que necesita en el asunto de un correo nuevo y cambie los datos por los suyos.");
            reglas(resultado);
            return resultado.tabla(fichas(buscado, delCaso));
        }
        ComandoSpec comando = catalogo.buscar(buscado).orElseThrow(() -> NegocioException.noEncontrado(
                "No existe el comando ni el tema " + buscado + ". Envíe HELP[\"*\"] para ver la guía completa."));
        ResultadoOperacion resultado = ResultadoOperacion.exito("Ayuda de " + comando.nombre(), comando.descripcion()
                        + "\nCopie el ejemplo en el asunto de un correo nuevo y cambie los datos por los suyos.")
                .detalle("Ejemplo para copiar en el asunto", comando.ejemplo())
                .detalle("Forma general", comando.sintaxis())
                .detalle("Tema", nombreTema(comando.casoDeUso()))
                .detalle("Quién puede usarlo", quien(comando))
                .detalle("¿Pide código de confirmación?", confirmacion(comando));
        ResultadoOperacion.Tabla datos = new ResultadoOperacion.Tabla("Qué escribir en cada dato", List.of("N.º", "Dato", "Qué escribir"));
        for (int i = 0; i < comando.parametros().size(); i++) {
            ParametroSpec p = comando.parametros().get(i);
            datos.fila(i + 1, p.nombre().replace('_', ' '), queEscribir(p));
        }
        return resultado.tabla(datos);
    }

    private Map<String, List<ComandoSpec>> porTema() {
        Map<String, List<ComandoSpec>> porTema = new LinkedHashMap<>();
        TreeMap<String, List<ComandoSpec>> todos = catalogo.todos().stream()
                .collect(Collectors.groupingBy(ComandoSpec::casoDeUso, TreeMap::new, Collectors.toList()));
        TEMAS.keySet().stream().filter(todos::containsKey).forEach(t -> porTema.put(t, todos.remove(t)));
        porTema.putAll(todos); // un tema sin nombre conocido va al final con su código
        return porTema;
    }

    /** Una ficha por comando: qué hace, ejemplo para copiar y qué va en cada dato. */
    private static ResultadoOperacion.Tabla fichas(String tema, List<ComandoSpec> comandos) {
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla(nombreTema(tema), COLUMNAS_FICHA)
                .descripcion(descripcionTema(tema)).enFichas();
        comandos.forEach(c -> tabla.fila(c.nombre(), c.descripcion(), c.ejemplo(), datosEnOrden(c)));
        return tabla;
    }

    private static void pasos(ResultadoOperacion resultado) {
        resultado.recuadro("Cómo enviar un comando (paso a paso)", Recuadro.Estilo.PASOS,
                "Escriba un correo nuevo a la misma dirección a la que envió este mensaje.",
                "En el Asunto copie el ejemplo del comando que necesita y cambie los datos por los suyos. "
                        + "El cuerpo del correo puede quedar vacío.",
                "Envíe el correo. En unos segundos recibirá la respuesta en su bandeja de entrada.",
                "Si el comando cambia datos, le llegará un código. Envíe otro correo con el asunto CONFIRMAR[\"CÓDIGO\"] "
                        + "(con el código que recibió) antes de 30 minutos.");
    }

    private static void reglas(ResultadoOperacion resultado) {
        resultado.recuadro("Reglas para escribir el asunto", Recuadro.Estilo.NOTA,
                "Cada dato va entre comillas dobles y separado por comas: COMANDO[\"dato1\",\"dato2\"].",
                "En cada ficha, «Datos, en orden» dice qué va en cada posición del ejemplo: el 1.º valor es el dato 1, "
                        + "el 2.º es el dato 2, y así.",
                "\"*\" significa todos (por ejemplo, LISPRO[\"*\"] muestra todos los productos).",
                "Un dato opcional o que no quiere cambiar se deja vacío: \"\".",
                "Da igual escribir en mayúsculas o minúsculas, y los textos aceptan tildes y ñ.",
                "Para ver el detalle de un comando envíe HELP[\"NOMBRE\"], por ejemplo HELP[\"LISPRO\"].");
    }

    static String nombreTema(String tema) {
        String[] t = TEMAS.get(tema);
        return t == null ? tema : t[0];
    }

    private static String descripcionTema(String tema) {
        String[] t = TEMAS.get(tema);
        return t == null ? "" : t[1];
    }

    /**
     * Qué va en cada posición del ejemplo, en el mismo orden: "1. ci · 2. ci complemento (opcional) · …".
     * Solo se agrega entre paréntesis lo que cambia cómo se escribe el dato (opcional, "*", fechas, listas…);
     * es corto a propósito porque también va en las más de cien fichas de HELP["*"].
     */
    private static String datosEnOrden(ComandoSpec c) {
        return IntStream.range(0, c.parametros().size())
                .mapToObj(i -> (i + 1) + ". " + datoBreve(c.parametros().get(i)))
                .collect(Collectors.joining(" · "));
    }

    private static String datoBreve(ParametroSpec p) {
        List<String> marcas = new java.util.ArrayList<>();
        switch (p.tipo()) {
            case FECHA -> marcas.add("AAAA-MM-DD");
            case FECHA_HORA -> marcas.add("AAAA-MM-DD HH:MM");
            case BOOLEANO -> marcas.add("true o false");
            case DECIMAL -> marcas.add("número, ej. 35.50");
            case ENUMERACION -> marcas.add(String.join(", ", p.valoresPermitidos()));
            default -> { }
        }
        if (!p.esObligatorio()) {
            marcas.add("opcional");
        }
        if (p.admiteComodin()) {
            marcas.add("\"*\" = todos");
        }
        String nombre = p.nombre().replace('_', ' ');
        return marcas.isEmpty() ? nombre : nombre + " (" + String.join("; ", marcas) + ")";
    }

    /** Lo que va entre paréntesis en describir(), con la primera letra en mayúscula. */
    private static String queEscribir(ParametroSpec p) {
        String descripcion = p.describir();
        int abre = descripcion.indexOf('(');
        String tipo = abre < 0 ? descripcion : descripcion.substring(abre + 1, descripcion.length() - 1);
        String texto = tipo.substring(0, 1).toUpperCase(Locale.ROOT) + tipo.substring(1);
        return p.tipo() == TipoParametro.TEXTO || p.tipo() == TipoParametro.CODIGO
                ? texto + " (hasta " + p.longitudMaxima() + " caracteres)" : texto;
    }

    private static String quien(ComandoSpec c) {
        switch (c.acceso()) {
            case PUBLICO:
                return "Cualquier persona";
            case REGISTRADO:
                return "Cualquier usuario registrado";
            default:
                return List.of(TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER, TipoRol.PRODUCTOR, TipoRol.CLIENTE).stream()
                        .filter(c.roles()::contains).map(ModuloAyuda::rol).collect(Collectors.joining(", "));
        }
    }

    private static String rol(TipoRol rol) {
        switch (rol) {
            case ADMINISTRADOR:
                return "Administrador";
            case PERSONAL_FEDER:
                return "Personal de FEDER";
            case PRODUCTOR:
                return "Productor";
            default:
                return "Cliente";
        }
    }

    private static String confirmacion(ComandoSpec c) {
        return c.modificaDatos() ? "Sí: le llegará un código para confirmar" : "No";
    }
}
