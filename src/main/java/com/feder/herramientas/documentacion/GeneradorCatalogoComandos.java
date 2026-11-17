package com.feder.herramientas.documentacion;

import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ParametroSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Genera docs/COMANDOS.md a partir del catálogo real de comandos. Así la documentación de la
 * gramática y de cada comando nunca se desincroniza del código que los atiende.
 */
public final class GeneradorCatalogoComandos {

    private static final Map<String, String> NOMBRES_CASOS = Map.of(
            "SISTEMA", "Sistema",
            "CU1", "CU1 - Gestión de Usuarios",
            "CU2", "CU2 - Gestión de Productores y Comunidades Agrícolas",
            "CU3", "CU3 - Producción Agrícola, Asignación de Lotes y Trazabilidad de Origen",
            "CU4", "CU4 - Catálogo Unificado, Inventarios, Alertas y Control de Concurrencia",
            "CU5", "CU5 - Comercio Electrónico, Carrito de Compras, Ventas Inmediatas y Encargos",
            "CU6", "CU6 - Procesamiento de Pedidos, Pagos Multicanal, Entregas y Notificaciones",
            "CU7", "CU7 - Indicadores Financieros",
            "CU8", "CU8 - Reportes y Estadísticas");

    private GeneradorCatalogoComandos() {
    }

    public static void escribir(CatalogoComandos catalogo, Path destino) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# Catálogo de comandos FEDER\n\n");
        md.append("Documento generado a partir del código (`java -jar feder-email-driven.jar documentar-comandos`).\n\n");
        md.append("Formato general del asunto del correo: `COMANDO[\"param1\",\"param2\",...]`. ")
                .append("Todos los parámetros van entre comillas dobles; dentro de un valor se escribe `\\\"` para una comilla ")
                .append("y `\\\\` para una barra invertida. `\"*\"` significa «todos» solo donde se indica. ")
                .append("Un parámetro opcional se envía vacío (`\"\"`).\n\n");
        md.append("Los comandos marcados con **Confirmación: sí** modifican datos: el sistema responde con un código de un solo uso ")
                .append("(válido 30 minutos) enviado a la dirección registrada, y la operación se ejecuta al enviar `CONFIRMAR[\"CODIGO\"]` ")
                .append("desde esa misma cuenta.\n\n");
        md.append("Total de comandos: ").append(catalogo.todos().size()).append(" (más `CONFIRMAR`).\n\n");

        Map<String, List<ComandoSpec>> porCaso = catalogo.todos().stream()
                .collect(Collectors.groupingBy(ComandoSpec::casoDeUso, TreeMap::new, Collectors.toList()));
        for (Map.Entry<String, List<ComandoSpec>> caso : porCaso.entrySet()) {
            md.append("## ").append(NOMBRES_CASOS.getOrDefault(caso.getKey(), caso.getKey())).append("\n\n");
            md.append("| Comando | Descripción | Acceso | Confirmación |\n|---|---|---|---|\n");
            for (ComandoSpec c : caso.getValue()) {
                md.append("| `").append(c.nombre()).append("` | ").append(celda(c.descripcion())).append(" | ")
                        .append(acceso(c)).append(" | ").append(c.modificaDatos() ? "sí" : "no").append(" |\n");
            }
            md.append('\n');
            for (ComandoSpec c : caso.getValue()) {
                md.append("### ").append(c.nombre()).append("\n\n");
                md.append(c.descripcion()).append("\n\n");
                md.append("- Sintaxis: `").append(c.sintaxis()).append("`\n");
                md.append("- Ejemplo: `").append(c.ejemplo()).append("`\n");
                md.append("- Acceso: ").append(acceso(c)).append("\n");
                md.append("- Parámetros:\n");
                List<ParametroSpec> parametros = c.parametros();
                for (int i = 0; i < parametros.size(); i++) {
                    md.append("  ").append(i + 1).append(". ").append(celda(parametros.get(i).describir())).append('\n');
                }
                md.append('\n');
            }
        }
        Files.createDirectories(destino.getParent());
        Files.writeString(destino, md.toString(), StandardCharsets.UTF_8);
    }

    private static String acceso(ComandoSpec c) {
        switch (c.acceso()) {
            case PUBLICO:
                return "Público";
            case REGISTRADO:
                return "Usuario registrado";
            default:
                return c.roles().stream().map(Enum::name).sorted().collect(Collectors.joining(", "));
        }
    }

    private static String celda(String texto) {
        return texto == null ? "" : texto.replace("|", "\\|").replace("\n", " ");
    }
}
