package com.feder.negocio.comandos;

import com.feder.datos.AccesoDatosException;
import com.feder.negocio.comun.NegocioException;
import com.feder.negocio.comun.ResultadoOperacion;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Convierte errores en respuestas seguras para el remitente: explican qué pasó sin exponer
 * SQL, trazas ni estructura interna. El detalle técnico queda solo en error.log.
 */
final class TraductorErrores {

    private static final Logger LOG = Logger.getLogger("feder.error");

    ResultadoOperacion desdeNegocio(NegocioException e) {
        return ResultadoOperacion.error(titulo(e.codigo()), e.getMessage());
    }

    ResultadoOperacion desdeDatos(AccesoDatosException e) {
        if (e.esReglaDeBaseDeDatos() && e.mensajeServidor() != null) {
            // Mensajes redactados por nosotros en los triggers (RAISE EXCEPTION ... 'P0001').
            return ResultadoOperacion.error("Regla de negocio", e.mensajeServidor());
        }
        if (e.esViolacionUnica()) {
            return ResultadoOperacion.error("Registro duplicado",
                    "Ya existe un registro con ese valor único" + sufijo(e) + ". No se modificó la base de datos.");
        }
        if (e.esViolacionClaveForanea()) {
            return ResultadoOperacion.error("Referencia inválida",
                    "La operación hace referencia a un registro inexistente o tiene registros que dependen de él"
                            + sufijo(e) + ". No se modificó la base de datos.");
        }
        if (e.esViolacionCheck() || e.esViolacionNoNulo()) {
            return ResultadoOperacion.error("Datos no válidos",
                    "Los datos no cumplen una regla del modelo FEDER" + sufijo(e) + ". No se modificó la base de datos.");
        }
        LOG.log(Level.SEVERE, "Error de base de datos no clasificado (SQLSTATE " + e.sqlState() + ")", e);
        return ResultadoOperacion.error("Error interno",
                "No se pudo completar la operación por un problema técnico. No se modificó la base de datos.");
    }

    private static String sufijo(AccesoDatosException e) {
        return e.restriccion() == null ? "" : " (regla: " + e.restriccion() + ")";
    }

    private static String titulo(NegocioException.Codigo codigo) {
        switch (codigo) {
            case NO_ENCONTRADO:
                return "No encontrado";
            case DUPLICADO:
                return "Registro duplicado";
            case ESTADO_INVALIDO:
                return "Estado no permitido";
            case SIN_PERMISO:
                return "Acceso denegado";
            case NO_IDENTIFICADO:
                return "Remitente no identificado";
            case PARAMETRO_INVALIDO:
                return "Comando o parámetro inválido";
            case STOCK_INSUFICIENTE:
                return "Stock insuficiente";
            case CONFIRMACION_INVALIDA:
                return "Confirmación no válida";
            case LIMITE_EXCEDIDO:
                return "Límite de solicitudes";
            case REGLA_DE_NEGOCIO:
            default:
                return "Regla de negocio";
        }
    }
}
