package com.feder.datos.seguridad;

import com.feder.datos.AccesoDatosException;
import com.feder.datos.RepositorioJdbc;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistencia de las confirmaciones de un solo uso.
 * En esta tabla solo se guarda el hash SHA-256 del código. El código en claro existe únicamente en la
 * respuesta enviada al usuario (y en su copia de la bandeja de salida hasta el envío); en el registro
 * de mensajes recibidos el asunto CONFIRMAR se guarda enmascarado. El código es de un solo uso y vence.
 */
public class ConfirmacionRepositorio extends RepositorioJdbc {

    /** Operación pendiente de confirmar, tal como se solicitó. */
    public static final class Pendiente {
        public final long id;
        public final String correo;
        public final Long usuarioId;
        public final String comando;
        public final OffsetDateTime expiraEn;
        public final OffsetDateTime usadaEn;

        Pendiente(long id, String correo, Long usuarioId, String comando, OffsetDateTime expiraEn, OffsetDateTime usadaEn) {
            this.id = id;
            this.correo = correo;
            this.usuarioId = usuarioId;
            this.comando = comando;
            this.expiraEn = expiraEn;
            this.usadaEn = usadaEn;
        }
    }

    public long registrar(String codigoHash, String correo, Long usuarioId, String comando,
                          List<String> parametros, OffsetDateTime expiraEn) {
        // Los parámetros se guardan como arreglo JSON usando la conversión de PostgreSQL,
        // así no construimos JSON a mano ni concatenamos texto del usuario.
        Array arreglo = crearArregloTexto(parametros);
        try {
            return insertar("INSERT INTO confirmacion_operacion (codigo_hash, correo, usuario_id, comando, parametros, expira_en) "
                            + "VALUES (?, lower(?), ?, ?, to_jsonb(?::text[]), ?)",
                    codigoHash, correo, usuarioId, comando, arreglo, expiraEn);
        } finally {
            liberar(arreglo);
        }
    }

    /** Busca y bloquea la confirmación para que dos mensajes simultáneos no la usen dos veces. */
    public Optional<Pendiente> buscarParaUsar(String codigoHash) {
        return consultarUno("SELECT id, correo, usuario_id, comando, expira_en, usada_en FROM confirmacion_operacion "
                        + "WHERE codigo_hash = ? FOR UPDATE",
                fila -> new Pendiente(fila.getLong("id"), fila.getString("correo"), largoONulo(fila, "usuario_id"),
                        fila.getString("comando"), fechaHora(fila, "expira_en"), fechaHora(fila, "usada_en")),
                codigoHash);
    }

    public List<String> parametros(long id) {
        return consultar("SELECT jsonb_array_elements_text(parametros) AS valor FROM confirmacion_operacion WHERE id = ?",
                fila -> fila.getString("valor"), id);
    }

    /** Marca la confirmación como usada solo si nadie la usó antes (operación atómica). */
    public boolean marcarUsada(long id) {
        return actualizar("UPDATE confirmacion_operacion SET usada_en = now() WHERE id = ? AND usada_en IS NULL", id) == 1;
    }

    public int contarCreadasDesde(String correo, OffsetDateTime desde) {
        return consultarUno("SELECT count(*) AS n FROM confirmacion_operacion WHERE correo = lower(?) AND creada_en >= ?",
                fila -> fila.getInt("n"), correo, desde).orElse(0);
    }

    private Array crearArregloTexto(List<String> valores) {
        try {
            return conexion().createArrayOf("text", valores.toArray(new String[0]));
        } catch (SQLException e) {
            throw new AccesoDatosException("No se pudo preparar la lista de parámetros", e);
        }
    }

    private static void liberar(Array arreglo) {
        try {
            arreglo.free();
        } catch (SQLException ignorada) {
            // Liberar el arreglo es solo una optimización de memoria.
        }
    }
}
