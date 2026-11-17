package com.feder.negocio.comandos;

/**
 * Cada caso de uso declara sus comandos en una clase que implementa esta interfaz.
 * El punto de arranque registra todos los módulos en el {@link CatalogoComandos}.
 */
public interface ModuloComandos {

    void registrar(CatalogoComandos catalogo);
}
