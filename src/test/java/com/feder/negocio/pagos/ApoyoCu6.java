package com.feder.negocio.pagos;

import com.feder.negocio.ContextoNegocio;
import com.feder.negocio.comandos.ComandoDispatcher;
import com.feder.negocio.comandos.ModuloComandos;
import com.feder.negocio.comun.ResultadoOperacion;
import com.feder.negocio.entregas.ModuloEntregas;
import com.feder.negocio.inventario.ApoyoCu45;
import com.feder.negocio.inventario.ModuloInventario;
import com.feder.negocio.notificaciones.ModuloNotificaciones;
import com.feder.negocio.posventa.ModuloPosventa;
import com.feder.negocio.ventas.ModuloVentas;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Despachador real con los módulos de CU4 a CU6 y atajos para enviar comandos en las pruebas de CU6. */
public final class ApoyoCu6 {

    private ApoyoCu6() {
    }

    public static ComandoDispatcher despachador(ContextoNegocio ctx) {
        return ApoyoCu45.despachador(ctx, modulos(ctx));
    }

    public static ModuloComandos[] modulos(ContextoNegocio ctx) {
        return new ModuloComandos[] {new ModuloInventario(ctx), new ModuloVentas(ctx), new ModuloPagos(ctx), new ModuloEntregas(ctx),
                new ModuloPosventa(ctx), new ModuloNotificaciones(ctx)};
    }

    /** Comando de escritura: pide la confirmación, la confirma y devuelve el resultado de la ejecución. */
    public static ResultadoOperacion escribir(ComandoDispatcher d, String correo, String comando, String... valores) {
        return ApoyoCu45.confirmarComando(d, comando, List.of(valores), correo);
    }

    /** Como {@link #escribir}, exigiendo que la operación termine con éxito. */
    public static ResultadoOperacion exito(ComandoDispatcher d, String correo, String comando, String... valores) {
        ResultadoOperacion r = escribir(d, correo, comando, valores);
        assertTrue(r.esExito(), comando + ": " + r.titulo() + " - " + r.mensaje());
        return r;
    }

    /** Comando de consulta (o cualquier comando, sin confirmar): devuelve la primera respuesta. */
    public static ResultadoOperacion leer(ComandoDispatcher d, String correo, String comando, String... valores) {
        return d.despachar(comando, List.of(valores), correo).resultado;
    }
}
