package com.feder.negocio.comandos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lista blanca de comandos admitidos. Un comando que no esté aquí no se ejecuta:
 * el correo nunca se convierte en una consola de SQL arbitrario.
 */
public final class CatalogoComandos {

    private final Map<String, ComandoSpec> comandos = new LinkedHashMap<>();

    public void registrar(ComandoSpec comando) {
        if (comandos.putIfAbsent(comando.nombre(), comando) != null) {
            throw new IllegalStateException("Comando duplicado en el catálogo: " + comando.nombre());
        }
    }

    public void registrarModulo(ModuloComandos modulo) {
        modulo.registrar(this);
    }

    public Optional<ComandoSpec> buscar(String nombre) {
        return Optional.ofNullable(comandos.get(nombre.toUpperCase(Locale.ROOT)));
    }

    public Collection<ComandoSpec> todos() {
        return Collections.unmodifiableCollection(comandos.values());
    }

    public List<ComandoSpec> delCasoDeUso(String casoDeUso) {
        List<ComandoSpec> lista = new ArrayList<>();
        for (ComandoSpec c : comandos.values()) {
            if (c.casoDeUso().equalsIgnoreCase(casoDeUso)) {
                lista.add(c);
            }
        }
        return lista;
    }
}
