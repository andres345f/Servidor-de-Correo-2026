package com.feder.negocio.listados;

import com.feder.datos.consultas.ListadosGeneralesRepositorio;
import com.feder.modelo.EstadoFactura;
import com.feder.modelo.TipoRol;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comandos.Parametros;
import com.feder.negocio.comun.Listados;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.usuarios.FormatoNegocio;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Supplier;

/**
 * Listados generales de las tablas que no tenían uno propio (plan, fase C, C0, 2026-11-16). Todos son de solo
 * lectura, aceptan "*" = todo y muestran como máximo {@link Listados#TOPE} filas. Cada comando conserva el caso
 * de uso de su tabla, así HELP lo muestra junto a los demás comandos de esa tabla.
 */
public final class ModuloListadosGenerales implements ModuloComandos {

    private static final TipoRol[] PERSONAL = {TipoRol.ADMINISTRADOR, TipoRol.PERSONAL_FEDER};

    private final ContextoNegocio ctx;
    private final ListadosGeneralesRepositorio consultas = new ListadosGeneralesRepositorio();

    public ModuloListadosGenerales(ContextoNegocio ctx) {
        this.ctx = ctx;
    }

    @Override
    public void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("LISRLS").casoDeUso("CU1")
                .descripcion("Lista los roles del sistema con la cantidad de cuentas que lo tienen vigente; filtra por código o nombre")
                .parametros(ParametroSpec.texto("filtro", 30).conComodin())
                .roles(TipoRol.ADMINISTRADOR).ejemplo("LISRLS[\"*\"]")
                .handler(this::roles).construir());
        catalogo.registrar(ComandoSpec.comando("LISDCM").casoDeUso("CU4")
                .descripcion("Lista las líneas de las compras a productores con lo recibido, rechazado, cancelado y pendiente "
                        + "(\"*\" = de todas las compras)")
                .parametros(ParametroSpec.codigo("compra", 30).conComodin())
                .roles(PERSONAL).ejemplo("LISDCM[\"*\"]")
                .handler(this::lineasDeCompra).construir());
        catalogo.registrar(ComandoSpec.comando("LISCAR").casoDeUso("CU5")
                .descripcion("Lista los productos en los carritos de los clientes con su precio vigente (\"*\" = de todos los clientes)")
                .parametros(ParametroSpec.correo("cliente").conComodin())
                .roles(PERSONAL).ejemplo("LISCAR[\"*\"]")
                .handler(this::carritos).construir());
        catalogo.registrar(ComandoSpec.comando("LISDPE").casoDeUso("CU5")
                .descripcion("Lista las líneas de los pedidos: producto, cantidad, precio y subtotal (\"*\" = de todos los pedidos)")
                .parametros(ParametroSpec.codigo("pedido", 30).conComodin())
                .roles(PERSONAL).ejemplo("LISDPE[\"*\"]")
                .handler(this::lineasDePedido).construir());
        catalogo.registrar(ComandoSpec.comando("LISRES").casoDeUso("CU5")
                .descripcion("Lista las reservas de stock por lote de FEDER y su estado (\"*\" = de todos los pedidos)")
                .parametros(ParametroSpec.codigo("pedido", 30).conComodin())
                .roles(PERSONAL).ejemplo("LISRES[\"*\"]")
                .handler(this::reservas).construir());
        catalogo.registrar(ComandoSpec.comando("LISHIS").casoDeUso("CU5")
                .descripcion("Lista los cambios de estado de los pedidos, del más reciente al más antiguo (\"*\" = de todos los pedidos)")
                .parametros(ParametroSpec.codigo("pedido", 30).conComodin())
                .roles(PERSONAL).ejemplo("LISHIS[\"*\"]")
                .handler(this::historial).construir());
        catalogo.registrar(ComandoSpec.comando("LISFAC").casoDeUso("CU6")
                .descripcion("Lista las facturas emitidas, de la más reciente a la más antigua (\"*\" = todas, o VIGENTE / ANULADA)")
                .parametros(ParametroSpec.enumeracion("estado", EstadoFactura.class).conComodin())
                .roles(PERSONAL).ejemplo("LISFAC[\"*\"]")
                .handler(this::facturas).construir());
    }

    private ResultadoOperacion roles(Parametros p, Solicitante s) {
        return respuesta("Roles del sistema", List.of("Código", "Nombre", "Descripción", "Cuentas con el rol vigente"),
                leer(() -> consultas.roles(p.texto("filtro"), Listados.A_PEDIR)), "rol(es)");
    }

    private ResultadoOperacion lineasDeCompra(Parametros p, Solicitante s) {
        String compra = p.texto("compra");
        return respuesta("Líneas de compra", List.of("Compra", "Línea", "Productor", "Lote de origen", "Producto", "Comprada",
                        "Precio (Bs)", "Recibida", "Rechazada", "Cancelada", "Pendiente"),
                leer(() -> {
                    if (compra != null && !consultas.existeCompra(compra)) {
                        throw NegocioException.noEncontrado("No existe la compra " + compra + ". Consulte LISCMP[\"*\"].");
                    }
                    return consultas.lineasDeCompra(compra, Listados.A_PEDIR);
                }), "línea(s), de la compra más reciente a la más antigua");
    }

    private ResultadoOperacion carritos(Parametros p, Solicitante s) {
        String cliente = p.texto("cliente");
        return respuesta("Carritos de los clientes", List.of("Cliente", "Producto", "Cantidad", "Precio vigente (Bs)", "Agregado"),
                leer(() -> {
                    if (cliente != null && !consultas.existeCuenta(cliente)) {
                        throw NegocioException.noEncontrado("No existe la cuenta " + cliente + ". Consulte LISUSU[\"*\"].");
                    }
                    return consultas.carritos(cliente, Listados.A_PEDIR);
                }), "producto(s) en carritos. El carrito no reserva stock");
    }

    private ResultadoOperacion lineasDePedido(Parametros p, Solicitante s) {
        String pedido = p.texto("pedido");
        return respuesta("Líneas de pedido", List.of("Pedido", "Línea", "Producto", "Cantidad", "Precio (Bs)", "Subtotal (Bs)",
                        "Descripción", "Producción"),
                leer(() -> conPedido(pedido, () -> consultas.lineasDePedido(pedido, Listados.A_PEDIR))),
                "línea(s), del pedido más reciente al más antiguo");
    }

    private ResultadoOperacion reservas(Parametros p, Solicitante s) {
        String pedido = p.texto("pedido");
        return respuesta("Reservas de stock", List.of("N.º", "Pedido", "Producto", "Lote de FEDER", "Cantidad", "Estado",
                        "Creada", "Expira", "Cierre"),
                leer(() -> conPedido(pedido, () -> consultas.reservas(pedido, Listados.A_PEDIR))),
                "reserva(s), de la más reciente a la más antigua");
    }

    private ResultadoOperacion historial(Parametros p, Solicitante s) {
        String pedido = p.texto("pedido");
        return respuesta("Historial de pedidos", List.of("Pedido", "Fecha", "Estado anterior", "Estado nuevo", "Usuario", "Comentario"),
                leer(() -> conPedido(pedido, () -> consultas.historialDePedidos(pedido, Listados.A_PEDIR))),
                "cambio(s) de estado, del más reciente al más antiguo");
    }

    private ResultadoOperacion facturas(Parametros p, Solicitante s) {
        EstadoFactura estado = p.enumeracion("estado", EstadoFactura.class);
        return respuesta("Facturas", List.of("Número", "Pedido", "Emisión", "NIT/CI", "Razón social", "Total (Bs)", "Estado", "Anulación"),
                leer(() -> consultas.facturas(estado == null ? null : estado.name(), Listados.A_PEDIR)),
                "factura(s), de la más reciente a la más antigua");
    }

    private List<List<Object>> conPedido(String pedido, Supplier<List<List<Object>>> consulta) {
        if (pedido != null && !consultas.existePedido(pedido)) {
            throw NegocioException.noEncontrado("No existe el pedido " + pedido + ". Consulte LISPED[\"*\"].");
        }
        return consulta.get();
    }

    private List<List<Object>> leer(Supplier<List<List<Object>>> consulta) {
        return ctx.transacciones().enTransaccion(consulta::get);
    }

    /**
     * Respuesta común de un listado: recorta al tope, avisa si hay más y da formato a cada celda (fechas en hora
     * de Bolivia, montos con 2 decimales, cantidades sin ceros sobrantes, vacío en lugar de null).
     */
    public static ResultadoOperacion respuesta(String titulo, List<String> columnas, List<List<Object>> obtenidas, String unidad) {
        List<List<Object>> lista = Listados.recortar(obtenidas);
        return ResultadoOperacion.exito(titulo, lista.size() + " " + unidad + "." + Listados.aviso(obtenidas))
                .tabla(tabla(titulo, columnas, lista));
    }

    /** Tabla con el formato común de las celdas, a partir de filas genéricas. */
    public static ResultadoOperacion.Tabla tabla(String titulo, List<String> columnas, List<List<Object>> filas) {
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla(titulo, columnas);
        for (List<Object> fila : filas) {
            tabla.fila(fila.stream().map(ModuloListadosGenerales::celda).toArray());
        }
        return tabla;
    }

    private static String celda(Object valor) {
        if (valor == null) {
            return "";
        }
        if (valor instanceof OffsetDateTime momento) {
            return FormatoNegocio.fechaHora(momento);
        }
        if (valor instanceof LocalDate fecha) {
            return FormatoNegocio.fecha(fecha);
        }
        if (valor instanceof BigDecimal numero) {
            // Las columnas de dinero tienen 2 decimales (NUMERIC(12,2)); las de cantidad, 3 (NUMERIC(12,3)).
            return numero.scale() == 2 ? FormatoNegocio.dinero(numero) : FormatoNegocio.cantidad(numero);
        }
        return valor.toString();
    }
}
