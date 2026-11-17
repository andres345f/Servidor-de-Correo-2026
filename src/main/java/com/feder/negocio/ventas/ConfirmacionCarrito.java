package com.feder.negocio.ventas;

import com.feder.datos.ventas.CarritoRepositorio;
import com.feder.datos.ventas.CarritoRepositorio.ItemCarritoVista;
import com.feder.datos.ventas.EntregaRepositorio;
import com.feder.datos.ventas.PedidoCompleto;
import com.feder.datos.ventas.PedidoRepositorio;
import com.feder.modelo.DetallePedido;
import com.feder.modelo.Entrega;
import com.feder.modelo.EstadoEntrega;
import com.feder.modelo.EstadoPedido;
import com.feder.modelo.ModalidadEntrega;
import com.feder.modelo.Pedido;
import com.feder.modelo.Producto;
import com.feder.modelo.TipoNotificacion;
import com.feder.modelo.TipoPedido;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.catalogo.Cantidades;
import com.feder.negocio.catalogo.FechasBolivia;
import com.feder.negocio.catalogo.Importes;
import com.feder.negocio.catalogo.Permisos;
import com.feder.negocio.catalogo.Textos;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.Solicitante;
import com.feder.negocio.inventario.LineaPorReservar;
import com.feder.negocio.inventario.ServicioInventario;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * PEDIDO «Confirmación del carrito» (R054) en una sola transacción: bloquea el carrito, revalida
 * productos, cantidades y precios, crea PEDIDO, DETALLE_PEDIDO, ENTREGA y el primer HISTORIAL_PEDIDO,
 * reserva en un pedido INMEDIATO (R060) y elimina del carrito los ítems convertidos.
 */
final class ConfirmacionCarrito {

    /** Plazo para pagar un pedido INMEDIATO PENDIENTE antes de que expiren sus reservas (R053). */
    static final Duration PLAZO_PAGO = Duration.ofHours(12);
    /** Tarifa única de envío a domicilio; en RECOJO el costo es 0 (R028). */
    static final BigDecimal COSTO_DELIVERY = new BigDecimal("15.00");
    /** Un reenvío de la misma solicitud dentro de este plazo devuelve el pedido ya creado (R054). */
    static final Duration VENTANA_REENVIO = Duration.ofMinutes(30);

    private static final Pattern TELEFONO = Pattern.compile("[0-9+() -]{6,20}");

    private final ContextoNegocio ctx;
    private final CarritoRepositorio carrito;
    private final PedidoRepositorio pedidos;
    private final EntregaRepositorio entregas;
    private final CambioEstadoPedido cambioEstado;
    private final ServicioInventario inventario;

    ConfirmacionCarrito(ContextoNegocio ctx, CarritoRepositorio carrito, PedidoRepositorio pedidos,
                        EntregaRepositorio entregas, CambioEstadoPedido cambioEstado, ServicioInventario inventario) {
        this.ctx = ctx;
        this.carrito = carrito;
        this.pedidos = pedidos;
        this.entregas = entregas;
        this.cambioEstado = cambioEstado;
        this.inventario = inventario;
    }

    ResultadoPedido confirmar(Solicitante solicitante, SolicitudPedido solicitud, TipoPedido tipo) {
        Permisos.exigirCliente(solicitante, "confirmar pedidos");
        Entrega entrega = validarEntrega(solicitud);
        long clienteId = solicitante.usuarioId();
        // 1. Los ítems del carrito se bloquean antes que cualquier fila de inventario: un segundo envío
        //    simultáneo espera y encuentra el carrito sin esos ítems.
        List<ItemCarritoVista> bloqueados = carrito.bloquear(clienteId);
        if (elegidos(bloqueados, solicitud.productos()).isEmpty()) {
            // Un reenvío de la misma confirmación ya no encuentra los ítems: devolvemos el pedido que creó (R054).
            Optional<PedidoCompleto> creado = pedidoDelReenvio(clienteId, tipo, solicitud, entrega);
            if (creado.isPresent()) {
                return new ResultadoPedido(creado.get(), true);
            }
        }
        List<ItemCarritoVista> items = seleccionar(bloqueados, solicitud.productos());
        String clave = claveIdempotencia(tipo, clienteId, items);
        // 2. Revalidación de productos, cantidades, precios y modalidad.
        revalidar(items, solicitud.subtotalEsperado());
        if (tipo == TipoPedido.ENCARGO) {
            exigirEncargoAdmitido(items);
        } else {
            exigirStockVendible(items);
        }
        // 3. Pedido, líneas, entrega, historial y, si es inmediato, reservas.
        Pedido pedido = insertarPedido(clienteId, tipo, items, entrega.getModalidad(), clave, Textos.opcional(solicitud.observacion()));
        List<DetallePedido> lineas = insertarLineas(pedido, items);
        entrega.setPedidoId(pedido.getId());
        entrega.setId(entregas.insertar(entrega));
        cambioEstado.registrarCreacion(pedido, clienteId,
                tipo == TipoPedido.ENCARGO ? "Encargo creado por el cliente" : "Pedido creado por el cliente");
        OffsetDateTime vence = ctx.ahora().plus(PLAZO_PAGO);
        if (tipo == TipoPedido.INMEDIATO) {
            inventario.reservarLineas(lineas.stream().map(l -> new LineaPorReservar(l.getId(), l.getCantidad())).toList(), vence);
        }
        // 4. Los ítems convertidos en líneas salen del carrito.
        items.forEach(i -> carrito.eliminar(i.item().getId()));
        notificarCreacion(pedido, vence);
        return new ResultadoPedido(new PedidoCompleto(pedido, lineas, entrega), false);
    }

    private static List<ItemCarritoVista> elegidos(List<ItemCarritoVista> bloqueados, Set<String> productos) {
        return productos == null || productos.isEmpty() ? bloqueados
                : bloqueados.stream().filter(i -> productos.contains(i.producto().producto().getCodigo())).toList();
    }

    /**
     * Pedido creado por un envío anterior de la misma solicitud: del mismo cliente y tipo, dentro de la ventana de
     * reenvío, con el mismo subtotal, la misma observación, los mismos datos de entrega (modalidad, dirección,
     * referencia, receptor y teléfono) y, si la solicitud nombra productos, con líneas de exactamente esos productos.
     * Se revisan todos los pedidos recientes del cliente, no solo el último. La solicitud no lleva cantidades (las
     * tenía el carrito, que ya no tiene esos ítems), por eso no se comparan.
     *
     * Solo se reconoce el reenvío si UN único pedido coincide. Si hay varios indistinguibles (por ejemplo, dos
     * pedidos de «todos los productos del carrito» con el mismo subtotal y los mismos datos), no se adivina cuál era:
     * se informa que el carrito no tiene esos productos y el cliente puede consultar sus pedidos con LISPED.
     */
    private Optional<PedidoCompleto> pedidoDelReenvio(long clienteId, TipoPedido tipo, SolicitudPedido s, Entrega entrega) {
        if (s.subtotalEsperado() == null) {
            return Optional.empty();
        }
        BigDecimal subtotal = Importes.redondear(s.subtotalEsperado());
        String observacion = Textos.opcional(s.observacion());
        boolean porProductos = s.productos() != null && !s.productos().isEmpty();
        PedidoCompleto encontrado = null;
        for (Pedido candidato : pedidos.recientesDelCliente(clienteId, tipo, ctx.ahora().minus(VENTANA_REENVIO))) {
            if (candidato.getSubtotal().compareTo(subtotal) != 0
                    || !Objects.equals(candidato.getObservacion(), observacion)
                    || (porProductos && !s.productos().equals(pedidos.codigosDeProductos(candidato.getId())))) {
                continue;
            }
            Optional<PedidoCompleto> completo = pedidos.buscarConLineas(candidato.getCodigo())
                    .filter(c -> c.entrega() != null && mismaEntrega(c.entrega(), entrega));
            if (completo.isPresent()) {
                if (encontrado != null) {
                    return Optional.empty(); // Dos pedidos coinciden con todos los datos: no se sabe cuál era.
                }
                encontrado = completo.get();
            }
        }
        return Optional.ofNullable(encontrado);
    }

    private static boolean mismaEntrega(Entrega registrada, Entrega pedida) {
        return registrada.getModalidad() == pedida.getModalidad()
                && Objects.equals(registrada.getNombreReceptor(), pedida.getNombreReceptor())
                && Objects.equals(registrada.getTelefonoContacto(), pedida.getTelefonoContacto())
                && Objects.equals(registrada.getDireccion(), pedida.getDireccion())
                && Objects.equals(registrada.getReferencia(), pedida.getReferencia());
    }

    private List<ItemCarritoVista> seleccionar(List<ItemCarritoVista> bloqueados, Set<String> productos) {
        List<ItemCarritoVista> elegidos = elegidos(bloqueados, productos);
        if (productos != null && !productos.isEmpty()) {
            Set<String> faltantes = new TreeSet<>(productos);
            elegidos.forEach(i -> faltantes.remove(i.producto().producto().getCodigo()));
            if (!faltantes.isEmpty()) {
                throw NegocioException.regla("Su carrito no tiene estos productos: " + String.join(", ", faltantes)
                        + ". Revise su carrito con VERCAR[\"*\"].");
            }
        }
        if (elegidos.isEmpty()) {
            throw NegocioException.regla("Su carrito no tiene productos para confirmar. Agréguelos con AGRCAR.");
        }
        return elegidos.stream().sorted(Comparator.comparingLong(i -> i.producto().producto().getId())).toList();
    }

    /** R054 (2) y R034: productos activos, cantidades enteras en UNIDAD y precio igual al que vio el cliente. */
    private static void revalidar(List<ItemCarritoVista> items, BigDecimal subtotalEsperado) {
        List<String> noDisponibles = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (ItemCarritoVista i : items) {
            Producto p = i.producto().producto();
            if (!i.producto().publicado()) {
                noDisponibles.add(p.getCodigo());
                continue;
            }
            Cantidades.validarPositiva(i.item().getCantidad(), p.getUnidadMedida(), "cantidad de " + p.getCodigo());
            subtotal = subtotal.add(Importes.subtotal(i.item().getCantidad(), p.getPrecioVenta()));
        }
        if (!noDisponibles.isEmpty()) {
            throw NegocioException.regla("No se confirmó el pedido: estos productos ya no están a la venta: "
                    + String.join(", ", noDisponibles) + ". Quítelos con QUICAR y revise el resumen con VERCAR[\"*\"].");
        }
        // R054 (2): el subtotal que el cliente vio con VERCAR es obligatorio; si no coincide con los precios
        // vigentes no se confirma nada y se le pide revisar el resumen otra vez.
        if (subtotalEsperado == null) {
            throw NegocioException.regla("Indique el subtotal que vio en VERCAR (para todo el carrito o para los productos elegidos): "
                    + "así confirmamos que los precios no cambiaron.");
        }
        if (Importes.redondear(subtotalEsperado).compareTo(Importes.redondear(subtotal)) != 0) {
            throw NegocioException.regla("Los precios cambiaron desde su consulta: el subtotal vigente es Bs "
                    + Importes.formatear(subtotal) + " y usted indicó Bs " + Importes.formatear(subtotalEsperado)
                    + ". Revise el resumen con VERCAR[\"*\"] y vuelva a confirmar.");
        }
    }

    /** R056: un encargo se admite solo para productos con permite_encargo y cuando el stock vendible no alcanza. */
    private void exigirEncargoAdmitido(List<ItemCarritoVista> items) {
        for (ItemCarritoVista i : items) {
            Producto p = i.producto().producto();
            if (!Boolean.TRUE.equals(p.getPermiteEncargo())) {
                throw NegocioException.regla("El producto " + p.getCodigo() + " no acepta encargos: cómprelo con PEDINM o quítelo de la selección.");
            }
            BigDecimal vendible = inventario.stock().vendible(p.getId());
            if (vendible.compareTo(i.item().getCantidad()) >= 0) {
                throw NegocioException.regla("Hay stock vendible suficiente de " + p.getCodigo() + " ("
                        + Cantidades.formatear(vendible) + "): confírmelo como pedido inmediato con PEDINM.");
            }
        }
    }

    /** R060: informa la cantidad vendible antes de reservar; la reserva atómica es la garantía final. */
    private void exigirStockVendible(List<ItemCarritoVista> items) {
        for (ItemCarritoVista i : items) {
            Producto p = i.producto().producto();
            BigDecimal vendible = inventario.stock().vendible(p.getId());
            if (vendible.compareTo(i.item().getCantidad()) < 0) {
                throw new NegocioException(NegocioException.Codigo.STOCK_INSUFICIENTE, "No se creó el pedido: de "
                        + p.getNombre() + " (" + p.getCodigo() + ") pidió " + Cantidades.formatear(i.item().getCantidad())
                        + " y el stock vendible es " + Cantidades.formatear(vendible) + " " + p.getUnidadMedida()
                        + ". Ajuste la cantidad con QUICAR" + (Boolean.TRUE.equals(p.getPermiteEncargo())
                        ? " o confirme un encargo por la cantidad total con PEDENC." : "."));
            }
        }
    }

    private Pedido insertarPedido(long clienteId, TipoPedido tipo, List<ItemCarritoVista> items, ModalidadEntrega modalidad,
                                  String clave, String observacion) {
        BigDecimal subtotal = items.stream()
                .map(i -> Importes.subtotal(i.item().getCantidad(), i.producto().producto().getPrecioVenta()))
                .reduce(BigDecimal.ZERO.setScale(Importes.ESCALA), BigDecimal::add);
        BigDecimal envio = modalidad == ModalidadEntrega.DELIVERY ? COSTO_DELIVERY : BigDecimal.ZERO.setScale(Importes.ESCALA);
        // El total (y con él el subtotal y cada línea) se guarda en NUMERIC(14,2): si no cabe, no se escribe nada.
        Importes.exigirMontoAdmitido(subtotal.add(envio), "total del pedido");
        Pedido p = new Pedido();
        p.setId(pedidos.siguienteId());
        p.setCodigo(String.format("PED-%d-%04d", FechasBolivia.hoy(ctx.reloj()).getYear(), p.getId()));
        p.setUsuarioId(clienteId);
        p.setTipo(tipo);
        p.setEstado(EstadoPedido.PENDIENTE);
        p.setFechaCreacion(ctx.ahora());
        p.setSubtotal(subtotal);
        p.setCostoEnvio(envio);
        p.setTotal(subtotal.add(envio));
        p.setClaveIdempotencia(clave);
        p.setObservacion(observacion);
        pedidos.insertar(p);
        return p;
    }

    /** Precio y descripción se copian del catálogo vigente; subtotal = round(cantidad x precio, 2) (R018). */
    private List<DetallePedido> insertarLineas(Pedido pedido, List<ItemCarritoVista> items) {
        List<DetallePedido> lineas = new ArrayList<>();
        for (ItemCarritoVista i : items) {
            Producto p = i.producto().producto();
            DetallePedido d = new DetallePedido();
            d.setPedidoId(pedido.getId());
            d.setProductoId(p.getId());
            d.setCantidad(i.item().getCantidad());
            d.setPrecioUnitario(p.getPrecioVenta());
            d.setSubtotal(Importes.subtotal(d.getCantidad(), d.getPrecioUnitario()));
            d.setDescripcionVenta(descripcionVenta(p));
            d.setId(pedidos.insertarLinea(d));
            lineas.add(d);
        }
        return lineas;
    }

    static String descripcionVenta(Producto p) {
        String texto = p.getNombre() + " - " + p.getPresentacion() + " (" + p.getUnidadMedida() + ")";
        return texto.length() <= 200 ? texto : texto.substring(0, 200);
    }

    /** R028: DELIVERY exige dirección; todo pedido lleva receptor y teléfono de contacto. */
    private static Entrega validarEntrega(SolicitudPedido s) {
        if (s.modalidad() == null) {
            throw NegocioException.regla("Indique la modalidad de entrega: RECOJO o DELIVERY.");
        }
        String telefono = Textos.obligatorio(s.telefonoContacto(), "teléfono de contacto");
        if (!TELEFONO.matcher(telefono).matches()) {
            throw NegocioException.regla("El teléfono de contacto solo admite dígitos, espacios, '+', '-' y paréntesis (6 a 20 caracteres).");
        }
        Entrega e = new Entrega();
        e.setModalidad(s.modalidad());
        e.setEstado(EstadoEntrega.PENDIENTE);
        e.setNombreReceptor(Textos.obligatorio(s.nombreReceptor(), "nombre del receptor"));
        e.setTelefonoContacto(telefono);
        if (s.modalidad() == ModalidadEntrega.DELIVERY) {
            e.setDireccion(Textos.obligatorio(s.direccion(), "dirección de entrega (obligatoria en DELIVERY)"));
            e.setReferencia(Textos.opcional(s.referencia()));
        }
        return e;
    }

    /**
     * La clave se deriva de los ítems convertidos (identidad, cantidad y momento en que se agregaron):
     * la misma selección del carrito produce siempre la misma clave y uq_pedido_clave_idempotencia
     * impide crear dos pedidos con ella. El reenvío de una confirmación ya aplicada no llega aquí: el
     * carrito ya no tiene esos ítems y se devuelve el pedido creado (pedidoDelReenvio).
     */
    private static String claveIdempotencia(TipoPedido tipo, long clienteId, List<ItemCarritoVista> items) {
        StringBuilder base = new StringBuilder(tipo.name()).append('|').append(clienteId);
        items.stream().sorted(Comparator.comparingLong(i -> i.item().getId())).forEach(i -> base.append('|')
                .append(i.item().getId()).append(':').append(i.item().getCantidad().stripTrailingZeros().toPlainString())
                .append(':').append(i.item().getFechaAgregado().toInstant().toEpochMilli()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(base.toString().getBytes(StandardCharsets.UTF_8));
            return "CAR-" + HexFormat.of().formatHex(hash).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible en esta JVM", e);
        }
    }

    private void notificarCreacion(Pedido pedido, OffsetDateTime vence) {
        String mensaje = pedido.getTipo() == TipoPedido.INMEDIATO
                ? "El pedido " + pedido.getCodigo() + " está pendiente de pago por Bs " + Importes.formatear(pedido.getTotal())
                + ". La reserva de sus productos vence el " + FechasBolivia.formatear(vence) + "."
                : "El encargo " + pedido.getCodigo() + " fue registrado. FEDER lo revisará y le informará una fecha estimada de disponibilidad.";
        ctx.notificaciones().sobrePedido(pedido.getUsuarioId(), pedido.getId(), TipoNotificacion.CAMBIO_ESTADO_PEDIDO,
                pedido.getTipo() == TipoPedido.INMEDIATO ? "Pedido registrado" : "Encargo registrado", mensaje);
    }
}
