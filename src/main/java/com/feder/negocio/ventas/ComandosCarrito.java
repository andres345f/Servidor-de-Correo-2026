package com.feder.negocio.ventas;

import com.feder.modelo.Producto;
import com.feder.modelo.TipoRol;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.comandos.CatalogoComandos;
import com.feder.negocio.comandos.ComandoSpec;
import com.feder.negocio.comandos.ParametroSpec;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;

import java.util.List;
import java.util.Set;

/** Comandos de CU5 · Carrito de compras del CLIENTE. */
final class ComandosCarrito {

    private static final String CU = "CU5";

    private final ServicioCarrito carrito;

    ComandosCarrito(ServicioCarrito carrito) {
        this.carrito = carrito;
    }

    void registrar(CatalogoComandos catalogo) {
        catalogo.registrar(ComandoSpec.comando("AGRCAR").casoDeUso(CU)
                .descripcion("Agrega un producto al carrito (si ya está, suma la cantidad). El carrito no reserva stock")
                .parametros(ParametroSpec.codigo("codigo_producto", 30), ParametroSpec.decimal("cantidad"))
                .roles(TipoRol.CLIENTE).modificaDatos()
                .ejemplo("AGRCAR[\"FED-ACE-020\",\"2\"]")
                .handler((p, s) -> resumen("Producto agregado al carrito",
                        carrito.agregar(s, p.texto("codigo_producto"), p.decimal("cantidad"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("QUICAR").casoDeUso(CU)
                .descripcion("Quita una cantidad de un producto del carrito (\"*\" = quitar el producto)")
                .parametros(ParametroSpec.codigo("codigo_producto", 30), ParametroSpec.decimal("cantidad").conComodin())
                .roles(TipoRol.CLIENTE).modificaDatos()
                .ejemplo("QUICAR[\"FED-ACE-020\",\"*\"]")
                .handler((p, s) -> resumen("Carrito actualizado",
                        carrito.quitar(s, p.texto("codigo_producto"), p.decimal("cantidad"))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("VERCAR").casoDeUso(CU)
                .descripcion("Muestra su carrito (\"*\") o solo los productos indicados, separados por comas, con precios vigentes, "
                        + "stock vendible y el subtotal que debe indicar en PEDINM o PEDENC")
                .parametros(ParametroSpec.texto("productos", 200).conComodin())
                .roles(TipoRol.CLIENTE)
                .ejemplo("VERCAR[\"*\"]")
                .handler((p, s) -> resumen("Su carrito", carrito.ver(s,
                        p.esComodin("productos") ? Set.of() : ComandosPedidos.codigos(p.texto("productos")))))
                .construir());
        catalogo.registrar(ComandoSpec.comando("VACCAR").casoDeUso(CU)
                .descripcion("Vacía su carrito")
                .parametros(ParametroSpec.texto("filtro", 1).conComodin())
                .roles(TipoRol.CLIENTE).modificaDatos()
                .ejemplo("VACCAR[\"*\"]")
                .handler((p, s) -> {
                    if (p.tieneValor("filtro")) {
                        throw NegocioException.regla("Use VACCAR[\"*\"] para vaciar todo el carrito, o QUICAR para quitar un producto.");
                    }
                    return ResultadoOperacion.exito("Carrito vaciado", carrito.vaciar(s) + " producto(s) quitados del carrito.");
                })
                .construir());
    }

    private static ResultadoOperacion resumen(String titulo, ServicioCarrito.ResumenCarrito r) {
        ResultadoOperacion.Tabla tabla = new ResultadoOperacion.Tabla("Carrito", List.of("Código", "Producto", "Cantidad",
                "Precio vigente (Bs)", "Subtotal (Bs)", "Stock vendible", "Situación"));
        r.lineas().forEach(l -> {
            Producto p = l.item().producto().producto();
            tabla.fila(p.getCodigo(), p.getNombre() + " - " + p.getPresentacion(),
                    Cantidades.formatear(l.item().item().getCantidad()) + " " + p.getUnidadMedida(),
                    Importes.formatear(p.getPrecioVenta()), Importes.formatear(l.subtotal()),
                    Cantidades.formatear(l.vendible()), l.observacion());
        });
        return ResultadoOperacion.exito(titulo, r.lineas().isEmpty() ? "Su carrito está vacío."
                        : "Confirme la compra con PEDINM (inmediata) o PEDENC (encargo). El carrito no aparta unidades.")
                .detalle("Subtotal de productos disponibles (Bs)", Importes.formatear(r.subtotal()))
                .tabla(tabla);
    }
}
