package com.feder.negocio.indicadores;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Conciliaciones de control de CU7 a una fecha de corte, con valores acumulados desde el primer registro:
 * cobros confirmados - reembolsos ejecutados = ingresos reconocidos + envío cobrado + anticipos, e inventario
 * inicial (0) + entradas - salidas, a costo, = valor del inventario. Se comparan ya redondeados a 2 decimales,
 * que es como se presentan.
 */
public record ConciliacionControl(LocalDate corte, BigDecimal cobros, BigDecimal reintegros, BigDecimal ingresos,
                                  BigDecimal envio, BigDecimal anticipos, BigDecimal entradas, BigDecimal salidas,
                                  BigDecimal valorInventario) {

    public BigDecimal caja() {
        return Formatos.redondear(cobros.subtract(reintegros));
    }

    public BigDecimal ventasMasAnticipos() {
        return Formatos.redondear(ingresos.add(envio).add(anticipos));
    }

    public BigDecimal diferenciaCaja() {
        return caja().subtract(ventasMasAnticipos());
    }

    public boolean cajaConcilia() {
        return diferenciaCaja().signum() == 0;
    }

    public BigDecimal inventarioCalculado() {
        return Formatos.redondear(entradas.subtract(salidas));
    }

    public BigDecimal diferenciaInventario() {
        return inventarioCalculado().subtract(Formatos.redondear(valorInventario));
    }

    public boolean inventarioConcilia() {
        return diferenciaInventario().signum() == 0;
    }
}
