package com.feder.negocio.indicadores;

import com.feder.datos.GestorTransacciones;
import com.feder.datos.auditoria.BitacoraRepositorio;
import com.feder.datos.notificaciones.NotificacionRepositorio;
import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.auditoria.ServicioBitacora;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.notificaciones.ServicioNotificaciones;
import com.feder.pruebas.BaseDatosPrueba;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** Apoyo de las pruebas de CU7 y CU8: contexto con reloj fijo y búsqueda de tablas, filas y detalles. */
public final class ResultadosPrueba {

    private ResultadosPrueba() {
    }

    /** Contexto con el reloj fijo en el 2026-11-15 a mediodía de Bolivia, fecha de referencia de la semilla. */
    public static ContextoNegocio contextoConRelojFijo() {
        Clock reloj = Clock.fixed(Instant.parse("2026-11-15T16:00:00Z"), ZoneOffset.UTC);
        return new ContextoNegocio(new GestorTransacciones(BaseDatosPrueba.conexion()), reloj,
                new ServicioBitacora(new BitacoraRepositorio()), new ServicioNotificaciones(new NotificacionRepositorio(), reloj));
    }

    public static ResultadoOperacion.Tabla tabla(ResultadoOperacion r, String prefijo) {
        Optional<ResultadoOperacion.Tabla> tabla = r.tablas().stream().filter(t -> t.titulo().startsWith(prefijo)).findFirst();
        return tabla.orElseThrow(() -> new AssertionError("No hay una tabla que empiece con «" + prefijo + "»"));
    }

    public static boolean hayTabla(ResultadoOperacion r, String prefijo) {
        return r.tablas().stream().anyMatch(t -> t.titulo().startsWith(prefijo));
    }

    /** Primera fila cuyas primeras celdas coinciden con las indicadas. */
    public static List<String> fila(ResultadoOperacion.Tabla tabla, String... inicio) {
        return tabla.filas().stream()
                .filter(f -> f.size() >= inicio.length && f.subList(0, inicio.length).equals(List.of(inicio)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("La tabla «" + tabla.titulo() + "» no tiene la fila " + List.of(inicio)
                        + "; filas: " + tabla.filas()));
    }

    public static String detalle(ResultadoOperacion r, String clave) {
        return r.detalles().stream().filter(d -> d[0].equals(clave)).map(d -> d[1]).findFirst()
                .orElseThrow(() -> new AssertionError("No hay el detalle «" + clave + "»"));
    }
}
