package com.feder.datos.ventas;

import com.feder.datos.RepositorioJdbc;
import com.feder.modelo.Entrega;
import com.feder.modelo.EstadoEntrega;
import com.feder.modelo.ModalidadEntrega;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** Acceso a ENTREGA: todo pedido tiene exactamente una, creada con él (A30). */
public class EntregaRepositorio extends RepositorioJdbc {

    private static final String COLUMNAS = "SELECT id, pedido_id, modalidad, estado, direccion, referencia, nombre_receptor, "
            + "telefono_contacto, fecha_programada, fecha_entrega, observacion FROM entrega ";

    public long insertar(Entrega e) {
        return insertar("INSERT INTO entrega (pedido_id, modalidad, estado, direccion, referencia, nombre_receptor, "
                        + "telefono_contacto, observacion) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                e.getPedidoId(), e.getModalidad(), e.getEstado(), e.getDireccion(), e.getReferencia(), e.getNombreReceptor(),
                e.getTelefonoContacto(), e.getObservacion());
    }

    public Optional<Entrega> dePedido(long pedidoId) {
        return consultarUno(COLUMNAS + "WHERE pedido_id = ?", EntregaRepositorio::mapear, pedidoId);
    }

    /** R027: cada transición de la entrega es condicional sobre su estado anterior. */
    public boolean cambiarEstado(long entregaId, EstadoEntrega desde, EstadoEntrega hacia) {
        return actualizar("UPDATE entrega SET estado = ? WHERE id = ? AND estado = ?", hacia, entregaId, desde) == 1;
    }

    /** R027: ENTREGADA exige fecha_entrega; ambos se fijan en la misma sentencia (ck_entrega_fecha_entrega). */
    public boolean marcarEntregada(long entregaId, EstadoEntrega desde, OffsetDateTime fecha) {
        return actualizar("UPDATE entrega SET estado = 'ENTREGADA', fecha_entrega = ? WHERE id = ? AND estado = ?",
                fecha, entregaId, desde) == 1;
    }

    public void programar(long entregaId, OffsetDateTime fechaProgramada) {
        actualizar("UPDATE entrega SET fecha_programada = ? WHERE id = ?", fechaProgramada, entregaId);
    }

    /** Agrega una incidencia a la observación de la entrega, sin perder las anteriores. */
    public void anotar(long entregaId, String incidencia) {
        actualizar("UPDATE entrega SET observacion = CASE WHEN observacion IS NULL OR observacion = '' THEN ? "
                + "ELSE observacion || E'\\n' || ? END WHERE id = ?", incidencia, incidencia, entregaId);
    }

    /** Entrega con el código y el cliente de su pedido, para LISENT. */
    public record EntregaVista(Entrega entrega, String codigoPedido, long clienteId) {
    }

    /** @param estado nulo para todos; @param clienteId nulo para todos los clientes. */
    public List<EntregaVista> listar(EstadoEntrega estado, Long clienteId, int limite) {
        return consultar("SELECT e.id, e.pedido_id, e.modalidad, e.estado, e.direccion, e.referencia, e.nombre_receptor, "
                        + "e.telefono_contacto, e.fecha_programada, e.fecha_entrega, e.observacion, p.codigo, p.usuario_id "
                        + "FROM entrega e JOIN pedido p ON p.id = e.pedido_id "
                        + "WHERE (?::text IS NULL OR e.estado = ?) AND (?::bigint IS NULL OR p.usuario_id = ?) "
                        + "ORDER BY p.fecha_creacion DESC, e.id DESC LIMIT ?",
                fila -> new EntregaVista(mapear(fila), fila.getString("codigo"), fila.getLong("usuario_id")),
                estado, estado, clienteId, clienteId, limite);
    }

    private static Entrega mapear(ResultSet fila) throws SQLException {
        Entrega e = new Entrega();
        e.setId(fila.getLong("id"));
        e.setPedidoId(fila.getLong("pedido_id"));
        e.setModalidad(enumeracion(fila, "modalidad", ModalidadEntrega.class));
        e.setEstado(enumeracion(fila, "estado", EstadoEntrega.class));
        e.setDireccion(fila.getString("direccion"));
        e.setReferencia(fila.getString("referencia"));
        e.setNombreReceptor(fila.getString("nombre_receptor"));
        e.setTelefonoContacto(fila.getString("telefono_contacto"));
        e.setFechaProgramada(fechaHora(fila, "fecha_programada"));
        e.setFechaEntrega(fechaHora(fila, "fecha_entrega"));
        e.setObservacion(fila.getString("observacion"));
        return e;
    }
}
