package com.feder.negocio.mantenimiento;

import com.feder.datos.GestorTransacciones;
import com.feder.negocio.ContextoNegocio;
import com.feder.pruebas.BaseDatosPrueba;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R096: una invitación PENDIENTE vencida pasa a ANULADA sin autor ni revocado_por. */
class ServicioMantenimientoTest {

    @Test
    void anulaLasInvitacionesVencidasSinAutor() {
        ContextoNegocio contexto = BaseDatosPrueba.contexto();
        BaseDatosPrueba.enTransaccionRevertida(contexto, () -> {
            // La semilla tiene una invitación PENDIENTE (cuenta 4); la hacemos vencer ayer.
            long invitacion = escalar("SELECT id FROM usuario_rol WHERE usuario_id = 4 AND estado = 'PENDIENTE'");
            ejecutar("UPDATE usuario_rol SET fecha_asignacion = now() - interval '10 days', "
                    + "fecha_expiracion = now() - interval '1 day' WHERE id = " + invitacion);

            new ServicioMantenimiento(contexto).ejecutar();

            assertEquals(1, escalar("SELECT count(*) FROM usuario_rol WHERE id = " + invitacion
                    + " AND estado = 'ANULADA' AND revocado_por_id IS NULL AND fecha_revocacion IS NOT NULL"));
            assertTrue(escalar("SELECT count(*) FROM bitacora WHERE entidad = 'USUARIO_ROL' AND entidad_id = "
                    + invitacion + " AND usuario_id IS NULL") >= 1, "la anulación automática queda en bitácora sin autor");
        });
    }

    private static long escalar(String sql) {
        try (PreparedStatement s = GestorTransacciones.conexionActual().prepareStatement(sql);
             ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void ejecutar(String sql) {
        try (PreparedStatement s = GestorTransacciones.conexionActual().prepareStatement(sql)) {
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
