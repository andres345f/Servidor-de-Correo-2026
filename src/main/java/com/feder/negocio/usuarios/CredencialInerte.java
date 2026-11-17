package com.feder.negocio.usuarios;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Valor que se guarda en password_hash de las cuentas activas.
 *
 * Este sistema no tiene acceso web con contraseña: las operaciones se autentican con códigos de
 * confirmación enviados por correo. Aun así el esquema exige un hash en toda cuenta activa
 * (ck_usuario_password_hash), así que guardamos un valor aleatorio que no deriva de ninguna
 * contraseña y que nunca coincide con una verificación de hash real.
 */
final class CredencialInerte {

    private static final String PREFIJO = "sin-acceso-web$";
    private static final SecureRandom ALEATORIO = new SecureRandom();

    private CredencialInerte() {
    }

    static String generar() {
        byte[] bytes = new byte[32];
        ALEATORIO.nextBytes(bytes);
        return PREFIJO + HexFormat.of().formatHex(bytes);
    }
}
