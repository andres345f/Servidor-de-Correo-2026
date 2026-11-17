package com.feder.negocio.comandos;

/** Tipos de dato admitidos en los parámetros de un comando. Se validan antes de llegar a SQL. */
public enum TipoParametro {
    /** Texto libre (con longitud máxima). */
    TEXTO("texto"),
    /** Código de negocio: letras, dígitos, '-', '_' y '.' (p. ej. PROD-ACT-250). */
    CODIGO("código"),
    /** Entero con signo de hasta 18 dígitos. */
    ENTERO("número entero"),
    /** Decimal con punto como separador (p. ej. 35.50). */
    DECIMAL("número decimal"),
    /** Fecha AAAA-MM-DD. */
    FECHA("fecha AAAA-MM-DD"),
    /** Fecha y hora AAAA-MM-DD HH:MM (hora de Bolivia). */
    FECHA_HORA("fecha y hora AAAA-MM-DD HH:MM"),
    /** true o false. */
    BOOLEANO("true o false"),
    /** Dirección de correo electrónico. */
    CORREO("correo electrónico"),
    /** Uno de los valores de una enumeración del modelo. */
    ENUMERACION("valor de la lista");

    private final String descripcion;

    TipoParametro(String descripcion) {
        this.descripcion = descripcion;
    }

    public String descripcion() {
        return descripcion;
    }
}
