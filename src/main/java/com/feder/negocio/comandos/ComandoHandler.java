package com.feder.negocio.comandos;

import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.comun.Solicitante;

/**
 * Ejecuta un comando ya validado. Cada handler delega en un servicio de negocio:
 * no abre sockets, no escribe SQL y no construye HTML.
 */
@FunctionalInterface
public interface ComandoHandler {

    ResultadoOperacion ejecutar(Parametros parametros, Solicitante solicitante);
}
