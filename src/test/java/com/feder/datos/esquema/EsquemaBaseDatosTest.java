package com.feder.datos.esquema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Comprueba contra una base PostgreSQL real que el esquema de db/01_schema.sql coincide con el modelo
 * técnico y que rechaza las violaciones con el SQLSTATE y el nombre de restricción esperados.
 *
 * Usa la base indicada en las propiedades de sistema feder.test.db.url y feder.test.db.user (el clúster
 * local de scripts/pg-local.ps1). Si no hay conexión, las pruebas se omiten de forma explícita. Cada
 * prueba crea sus propios datos dentro de una transacción que siempre se revierte, por lo que no
 * depende de los datos de demostración ni deja rastro.
 */
class EsquemaBaseDatosTest {

    private static final String CHECK = "23514";
    private static final String UNICO = "23505";
    private static final String REGLA = "P0001";

    private static final Set<String> TABLAS_ESPERADAS = Set.of(
            "usuario", "rol", "usuario_rol", "bitacora", "comunidad_agricola", "productor", "produccion",
            "lote_produccion", "categoria", "producto", "compra_feder", "detalle_compra", "lote_feder",
            "movimiento_inventario", "reserva_stock", "item_carrito", "pedido", "detalle_pedido",
            "historial_pedido", "pago", "factura", "entrega", "notificacion", "devolucion", "reembolso",
            "mensaje_correo", "correo_saliente", "confirmacion_operacion");

    /** Bloque de trabajo que recibe una conexión con la transacción abierta. */
    @FunctionalInterface
    private interface Operacion {
        void ejecutar(Connection conexion) throws SQLException;
    }

    // ------------------------------------------------------------------ estructura

    @Test
    @DisplayName("Existen las 28 tablas: 25 del modelo y 3 técnicas del procesador de correo")
    void existenLasVeintiochoTablas() {
        enTransaccionRevertida(conexion -> {
            Set<String> tablas = new TreeSet<>();
            String sql = "SELECT table_name FROM information_schema.tables"
                    + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE'";
            try (Statement sentencia = conexion.createStatement(); ResultSet filas = sentencia.executeQuery(sql)) {
                while (filas.next()) {
                    tablas.add(filas.getString(1));
                }
            }
            assertEquals(28, TABLAS_ESPERADAS.size());
            assertEquals(new TreeSet<>(TABLAS_ESPERADAS), tablas, "Tablas del esquema public");
        });
    }

    @Test
    @DisplayName("Columnas, nulabilidad, tipos y valores por defecto coinciden con el modelo (cero diferencias)")
    void columnasCoincidenConElModelo() {
        String consulta = consultaDeVerificacion("consulta_columnas_modelo");
        enTransaccionRevertida(conexion -> assertSinFilas(conexion, consulta));
    }

    @Test
    @DisplayName("Existen todas las claves, restricciones, índices y disparadores declarados (cero diferencias)")
    void objetosDeclaradosExisten() {
        String consulta = consultaDeVerificacion("consulta_objetos_declarados");
        enTransaccionRevertida(conexion -> assertSinFilas(conexion, consulta));
    }

    @Test
    @DisplayName("El catálogo de roles tiene los cuatro literales y es cerrado")
    void catalogoDeRolesCerrado() {
        enTransaccionRevertida(conexion -> {
            Set<String> codigos = new TreeSet<>();
            try (Statement sentencia = conexion.createStatement();
                 ResultSet filas = sentencia.executeQuery("SELECT codigo FROM rol")) {
                while (filas.next()) {
                    codigos.add(filas.getString(1));
                }
            }
            assertEquals(Set.of("ADMINISTRADOR", "CLIENTE", "PERSONAL_FEDER", "PRODUCTOR"), codigos);

            assertRechazo(conexion, REGLA, "rg_rol_catalogo_cerrado", "DELETE FROM rol WHERE codigo = 'CLIENTE'");
            assertRechazo(conexion, CHECK, "ck_rol_codigo", "INSERT INTO rol (codigo, nombre) VALUES ('AUDITOR', 'Auditor')");
            assertRechazo(conexion, UNICO, "uq_rol_codigo", "INSERT INTO rol (codigo, nombre) VALUES ('CLIENTE', 'Cliente')");
        });
    }

    // ------------------------------------------------------------------ unicidad

    @Test
    @DisplayName("Dos categorías con el mismo nombre → 23505 uq_categoria_nombre")
    void categoriaNombreDuplicado() {
        enTransaccionRevertida(conexion -> {
            String nombre = "Categoría " + sufijo();
            insertar(conexion, "INSERT INTO categoria (nombre) VALUES (?) RETURNING id", nombre);
            assertRechazo(conexion, UNICO, "uq_categoria_nombre", "INSERT INTO categoria (nombre) VALUES (?)", nombre);
        });
    }

    @Test
    @DisplayName("El correo es único sin distinguir mayúsculas → 23505 uq_usuario_correo")
    void correoUnicoSinDistinguirMayusculas() {
        enTransaccionRevertida(conexion -> {
            String correo = "esquema." + sufijo() + "@feder.example";
            insertarUsuario(conexion, correo);
            assertRechazo(conexion, UNICO, "uq_usuario_correo",
                    "INSERT INTO usuario (correo, nombres, apellidos, ci, telefono, activo) VALUES (?, 'Ana', 'Rojas', ?, 60000001, false)",
                    correo.toUpperCase(), ciAleatoria());
        });
    }

    @Test
    @DisplayName("Una cuenta activa sin hash de contraseña → 23514 ck_usuario_password_hash")
    void cuentaActivaExigeHash() {
        enTransaccionRevertida(conexion -> assertRechazo(conexion, CHECK, "ck_usuario_password_hash",
                "INSERT INTO usuario (correo, nombres, apellidos, ci, telefono, activo) VALUES (?, 'Ana', 'Rojas', ?, 60000001, true)",
                "esquema." + sufijo() + "@feder.example", ciAleatoria()));
    }

    @Test
    @DisplayName("Segunda concesión abierta del mismo rol → 23505 uq_usuario_rol_concesion_abierta")
    void concesionVigenteUnica() {
        enTransaccionRevertida(conexion -> {
            long usuario = insertarUsuario(conexion, "esquema." + sufijo() + "@feder.example");
            String sql = "INSERT INTO usuario_rol (usuario_id, rol_id, estado, fecha_activacion)"
                    + " SELECT ?, id, 'VIGENTE', now() FROM rol WHERE codigo = 'CLIENTE'";
            ejecutar(conexion, sql, usuario);
            assertRechazo(conexion, UNICO, "uq_usuario_rol_concesion_abierta", sql, usuario);
            // Una concesión VIGENTE sin fecha de activación es incoherente con su estado.
            assertRechazo(conexion, CHECK, "ck_usuario_rol_coherencia_estado",
                    "INSERT INTO usuario_rol (usuario_id, rol_id, estado) SELECT ?, id, 'VIGENTE' FROM rol WHERE codigo = 'PRODUCTOR'",
                    usuario);
        });
    }

    // ------------------------------------------------------------------ CHECK y disparadores

    @Test
    @DisplayName("Subtotal de línea distinto de round(cantidad × precio, 2) → 23514 ck_detalle_pedido_subtotal")
    void subtotalDeLineaIncoherente() {
        enTransaccionRevertida(conexion -> {
            long usuario = insertarUsuario(conexion, "esquema." + sufijo() + "@feder.example");
            long producto = insertarProducto(conexion, "KILOGRAMO");
            long pedido = insertarPedido(conexion, usuario);
            String sql = "INSERT INTO detalle_pedido (pedido_id, producto_id, cantidad, precio_unitario, subtotal, descripcion_venta)"
                    + " VALUES (?, ?, ?, ?, ?, 'Línea de la prueba de esquema')";
            assertRechazo(conexion, CHECK, "ck_detalle_pedido_subtotal", sql,
                    pedido, producto, new BigDecimal("0.375"), new BigDecimal("70.02"), new BigDecimal("26.25"));
            // 0,375 × 70,02 = 26,2575 → 26,26 (redondeo half-up).
            ejecutar(conexion, sql, pedido, producto, new BigDecimal("0.375"), new BigDecimal("70.02"), new BigDecimal("26.26"));
            // Desde la confirmación, la línea no cambia.
            assertRechazo(conexion, REGLA, "rg_detalle_pedido_datos_fijos",
                    "UPDATE detalle_pedido SET descripcion_venta = 'Otra' WHERE pedido_id = ?", pedido);
        });
    }

    @Test
    @DisplayName("La bitácora solo admite inserciones: UPDATE y DELETE → P0001")
    void bitacoraSoloInsercion() {
        enTransaccionRevertida(conexion -> {
            long registro = insertar(conexion,
                    "INSERT INTO bitacora (accion, entidad, detalle) VALUES ('CREAR', 'CATEGORIA', '{\"origen\": \"esquema\"}'::jsonb) RETURNING id");
            assertRechazo(conexion, REGLA, "rg_bitacora_solo_insercion",
                    "UPDATE bitacora SET entidad = 'PRODUCTO' WHERE id = ?", registro);
            assertRechazo(conexion, REGLA, "rg_bitacora_solo_insercion", "DELETE FROM bitacora WHERE id = ?", registro);
        });
    }

    @Test
    @DisplayName("Cantidad con decimales en un producto por UNIDAD → P0001; en KILOGRAMO se acepta")
    void cantidadDecimalSegunUnidad() {
        enTransaccionRevertida(conexion -> {
            long usuario = insertarUsuario(conexion, "esquema." + sufijo() + "@feder.example");
            long porUnidad = insertarProducto(conexion, "UNIDAD");
            long porKilogramo = insertarProducto(conexion, "KILOGRAMO");
            String sql = "INSERT INTO item_carrito (cliente_id, producto_id, cantidad) VALUES (?, ?, ?)";
            assertRechazo(conexion, REGLA, "rg_item_carrito_cantidad_entera", sql, usuario, porUnidad, new BigDecimal("1.5"));
            ejecutar(conexion, sql, usuario, porUnidad, new BigDecimal("2"));
            ejecutar(conexion, sql, usuario, porKilogramo, new BigDecimal("1.5"));
            // La unidad de un producto no puede pasar a UNIDAD mientras haya carritos con decimales.
            assertRechazo(conexion, REGLA, "rg_producto_unidad_inmutable",
                    "UPDATE producto SET unidad_medida = 'UNIDAD' WHERE id = ?", porKilogramo);

            long pedido = insertarPedido(conexion, usuario);
            assertRechazo(conexion, REGLA, "rg_detalle_pedido_cantidad_entera",
                    "INSERT INTO detalle_pedido (pedido_id, producto_id, cantidad, precio_unitario, subtotal, descripcion_venta)"
                            + " VALUES (?, ?, 1.5, 10.00, 15.00, 'Línea de la prueba de esquema')",
                    pedido, porUnidad);
        });
    }

    @Test
    @DisplayName("Movimiento con cantidad <= 0 → 23514; un movimiento registrado es inmutable → P0001")
    void movimientoDeInventario() {
        enTransaccionRevertida(conexion -> {
            long usuario = insertarUsuario(conexion, "esquema." + sufijo() + "@feder.example");
            long lote = insertarLoteFeder(conexion, usuario, "UNIDAD");
            String sql = "INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion)"
                    + " VALUES (?, ?, 'ENTRADA_COMPRA', ?, ?) RETURNING id";
            assertRechazo(conexion, CHECK, "ck_movimiento_inventario_cantidad", sql, lote, usuario, BigDecimal.ZERO, "esq-" + sufijo());
            assertRechazo(conexion, CHECK, "ck_movimiento_inventario_cantidad", sql, lote, usuario, new BigDecimal("-3"), "esq-" + sufijo());
            assertRechazo(conexion, REGLA, "rg_movimiento_inventario_cantidad_entera", sql, lote, usuario, new BigDecimal("2.5"), "esq-" + sufijo());

            String clave = "esq-" + sufijo();
            long movimiento = insertar(conexion, sql, lote, usuario, new BigDecimal("10"), clave);
            assertRechazo(conexion, UNICO, "uq_movimiento_inventario_clave_operacion", sql, lote, usuario, new BigDecimal("10"), clave);
            assertRechazo(conexion, REGLA, "rg_movimiento_inventario_inmutable",
                    "UPDATE movimiento_inventario SET cantidad = 11 WHERE id = ?", movimiento);
            assertRechazo(conexion, REGLA, "rg_movimiento_inventario_inmutable",
                    "DELETE FROM movimiento_inventario WHERE id = ?", movimiento);
            // Una salida por venta exige su reserva.
            assertRechazo(conexion, CHECK, "ck_movimiento_inventario_reserva_segun_tipo",
                    "INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion)"
                            + " VALUES (?, ?, 'SALIDA_VENTA', 1, ?)",
                    lote, usuario, "esq-" + sufijo());
        });
    }

    @Test
    @DisplayName("El saldo y el estado del lote cambian juntos; lo reservado no supera el saldo")
    void saldoDelLoteDeFeder() {
        enTransaccionRevertida(conexion -> {
            long usuario = insertarUsuario(conexion, "esquema." + sufijo() + "@feder.example");
            long lote = insertarLoteFeder(conexion, usuario, "UNIDAD");
            assertRechazo(conexion, CHECK, "ck_lote_feder_agotado_sin_saldo",
                    "UPDATE lote_feder SET cantidad_disponible = 0 WHERE id = ?", lote);
            assertRechazo(conexion, CHECK, "ck_lote_feder_cantidad_reservada",
                    "UPDATE lote_feder SET cantidad_reservada = cantidad_disponible + 1 WHERE id = ?", lote);
            ejecutar(conexion, "UPDATE lote_feder SET cantidad_disponible = 0, estado = 'AGOTADO' WHERE id = ?", lote);
        });
    }

    @Test
    @DisplayName("No se borra un registro referenciado: ON DELETE RESTRICT (23001 en PostgreSQL 18, 23503 en versiones anteriores)")
    void borradoRestringido() {
        enTransaccionRevertida(conexion -> {
            long categoria = insertar(conexion, "INSERT INTO categoria (nombre) VALUES (?) RETURNING id", "Categoría " + sufijo());
            insertar(conexion,
                    "INSERT INTO producto (categoria_id, codigo, nombre, presentacion, unidad_medida, precio_venta)"
                            + " VALUES (?, ?, 'Producto de la prueba de esquema', 'Bolsa', 'UNIDAD', 10.00) RETURNING id",
                    categoria, "ESQ-" + sufijo());
            SQLException error = rechazo(conexion, "DELETE FROM categoria WHERE id = ?", categoria);
            assertTrue(Set.of("23001", "23503").contains(error.getSQLState()),
                    "SQLSTATE de borrado restringido: " + error.getSQLState());
            assertEquals("fk_producto_categoria", restriccion(error));
        });
    }

    @Test
    @DisplayName("Tablas técnicas: un mensaje se procesa una vez y una confirmación puede no tener usuario")
    void infraestructuraDeCorreo() {
        enTransaccionRevertida(conexion -> {
            String uidl = "uidl-" + sufijo();
            String sql = "INSERT INTO mensaje_correo (buzon, uidl, remitente, estado) VALUES ('buzon@feder.example', ?, 'cliente@feder.example', 'PROCESANDO')";
            ejecutar(conexion, sql, uidl);
            assertRechazo(conexion, UNICO, "uq_mensaje_correo_buzon_uidl", sql, uidl);

            ejecutar(conexion,
                    "INSERT INTO confirmacion_operacion (codigo_hash, correo, comando, parametros, expira_en)"
                            + " VALUES (?, 'cliente@feder.example', 'REGISTRAR', '{}'::jsonb, now() + interval '10 minutes')",
                    (sufijo() + sufijo() + sufijo() + sufijo() + sufijo() + sufijo() + sufijo() + sufijo()).substring(0, 64));
            assertRechazo(conexion, CHECK, "ck_confirmacion_operacion_vigencia",
                    "INSERT INTO confirmacion_operacion (codigo_hash, correo, comando, parametros, creada_en, expira_en)"
                            + " VALUES (?, 'cliente@feder.example', 'REGISTRAR', '{}'::jsonb, now(), now() - interval '1 minute')",
                    (sufijo() + sufijo() + sufijo() + sufijo() + sufijo() + sufijo() + sufijo() + sufijo()).substring(0, 64));
        });
    }

    // ------------------------------------------------------------------ conexión y transacción

    /**
     * Abre una conexión, ejecuta el bloque con la transacción abierta y siempre la revierte.
     * Si la base de pruebas no está disponible, la prueba se omite (no falla).
     */
    private static void enTransaccionRevertida(Operacion operacion) {
        String url = System.getProperty("feder.test.db.url");
        String usuario = System.getProperty("feder.test.db.user");
        Assumptions.assumeTrue(url != null && !url.isBlank() && usuario != null && !usuario.isBlank(),
                "Sin base de pruebas: defina feder.test.db.url y feder.test.db.user");
        // Las pruebas de reglas escriben (en transacciones revertidas): solo contra el clúster local.
        com.feder.pruebas.DestinoPruebas.exigirLocal(url);

        Properties propiedades = new Properties();
        propiedades.setProperty("user", usuario);
        propiedades.setProperty("connectTimeout", "5");
        propiedades.setProperty("ApplicationName", "feder-prueba-esquema");

        Connection abierta;
        try {
            abierta = DriverManager.getConnection(url, propiedades);
        } catch (SQLException e) {
            Assumptions.abort("Base de pruebas no disponible (" + e.getSQLState() + "): inicie el clúster con scripts/pg-local.ps1");
            return;
        }
        try (Connection conexion = abierta) {
            conexion.setAutoCommit(false);
            try {
                operacion.ejecutar(conexion);
            } finally {
                conexion.rollback();
            }
        } catch (SQLException e) {
            fail("Error SQL inesperado (" + e.getSQLState() + "): " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ aserciones

    /** Ejecuta la sentencia esperando que la base la rechace con ese SQLSTATE y esa restricción o regla. */
    private static void assertRechazo(Connection conexion, String sqlState, String restriccion, String sql, Object... parametros)
            throws SQLException {
        SQLException error = rechazo(conexion, sql, parametros);
        assertEquals(sqlState, error.getSQLState(), "SQLSTATE de: " + sql + " → " + error.getMessage());
        assertEquals(restriccion, restriccion(error), "Restricción de: " + sql + " → " + error.getMessage());
    }

    /** Ejecuta la sentencia dentro de un punto de guardado y devuelve el error con que fue rechazada. */
    private static SQLException rechazo(Connection conexion, String sql, Object... parametros) throws SQLException {
        Savepoint punto = conexion.setSavepoint();
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            asignar(sentencia, parametros);
            sentencia.execute();
        } catch (SQLException e) {
            conexion.rollback(punto);
            return e;
        }
        conexion.rollback(punto);
        return fail("La base aceptó una sentencia que debía rechazar: " + sql);
    }

    private static String restriccion(SQLException error) {
        if (error instanceof PSQLException) {
            ServerErrorMessage detalle = ((PSQLException) error).getServerErrorMessage();
            return detalle == null ? null : detalle.getConstraint();
        }
        return null;
    }

    private static void assertSinFilas(Connection conexion, String consulta) throws SQLException {
        List<String> diferencias = new ArrayList<>();
        try (Statement sentencia = conexion.createStatement(); ResultSet filas = sentencia.executeQuery(consulta)) {
            ResultSetMetaData columnas = filas.getMetaData();
            while (filas.next()) {
                StringBuilder fila = new StringBuilder();
                for (int i = 1; i <= columnas.getColumnCount(); i++) {
                    fila.append(i == 1 ? "" : " | ").append(columnas.getColumnLabel(i)).append('=').append(filas.getString(i));
                }
                diferencias.add(fila.toString());
            }
        }
        assertTrue(diferencias.isEmpty(),
                "Se esperaban cero diferencias y hay " + diferencias.size() + ":\n" + String.join("\n", diferencias));
    }

    /**
     * Lee de db/verificaciones/verificar_esquema.sql la consulta delimitada por las marcas
     * «-- [INICIO nombre]» y «-- [FIN nombre]», para no duplicar aquí la lista generada del modelo.
     */
    private static String consultaDeVerificacion(String nombre) {
        Path archivo = Path.of(System.getProperty("user.dir"), "db", "verificaciones", "verificar_esquema.sql");
        String contenido;
        try {
            contenido = Files.readString(archivo, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return fail("No se pudo leer " + archivo + " (las pruebas se ejecutan desde la raíz del proyecto)", e);
        }
        String inicio = "-- [INICIO " + nombre + "]";
        String fin = "-- [FIN " + nombre + "]";
        int desde = contenido.indexOf(inicio);
        int hasta = contenido.indexOf(fin);
        assertTrue(desde >= 0 && hasta > desde, "No se encontró el bloque «" + nombre + "» en " + archivo);
        return contenido.substring(desde + inicio.length(), hasta).trim();
    }

    // ------------------------------------------------------------------ datos de apoyo

    private static long insertarUsuario(Connection conexion, String correo) throws SQLException {
        return insertar(conexion,
                "INSERT INTO usuario (correo, nombres, apellidos, ci, telefono, activo)"
                        + " VALUES (?, 'Ana', 'Rojas', ?, 60000001, false) RETURNING id",
                correo, ciAleatoria());
    }

    private static long insertarProducto(Connection conexion, String unidad) throws SQLException {
        long categoria = insertar(conexion, "INSERT INTO categoria (nombre) VALUES (?) RETURNING id", "Categoría " + sufijo());
        return insertar(conexion,
                "INSERT INTO producto (categoria_id, codigo, nombre, presentacion, unidad_medida, precio_venta)"
                        + " VALUES (?, ?, 'Producto de la prueba de esquema', 'Presentación única', ?, 10.00) RETURNING id",
                categoria, "ESQ-" + sufijo(), unidad);
    }

    private static long insertarPedido(Connection conexion, long usuario) throws SQLException {
        return insertar(conexion,
                "INSERT INTO pedido (usuario_id, codigo, tipo, estado, subtotal, costo_envio, total, clave_idempotencia)"
                        + " VALUES (?, ?, 'INMEDIATO', 'PENDIENTE', 0, 0, 0, ?) RETURNING id",
                usuario, "ESQ-" + sufijo(), "esq-" + sufijo());
    }

    /** Crea la cadena comunidad → productor → producción → lote de origen → compra → detalle → lote de FEDER. */
    private static long insertarLoteFeder(Connection conexion, long usuario, String unidad) throws SQLException {
        long producto = insertarProducto(conexion, unidad);
        long comunidad = insertar(conexion,
                "INSERT INTO comunidad_agricola (nombre, municipio) VALUES (?, 'Municipio de la prueba') RETURNING id",
                "Comunidad " + sufijo());
        long productor = insertar(conexion,
                "INSERT INTO productor (usuario_id, comunidad_agricola_id, ubicacion_actividad) VALUES (?, ?, 'Taller comunitario') RETURNING id",
                usuario, comunidad);
        long produccion = insertar(conexion,
                "INSERT INTO produccion (productor_id, producto_id, comunidad_origen_id, codigo, estado,"
                        + " nombre_comunidad_origen, municipio_origen, departamento_origen)"
                        + " VALUES (?, ?, ?, ?, 'TERMINADA', 'Comunidad', 'Municipio', 'Santa Cruz') RETURNING id",
                productor, producto, comunidad, "ESQ-" + sufijo());
        long loteOrigen = insertar(conexion,
                "INSERT INTO lote_produccion (produccion_id, codigo_lote, cantidad_producida, cantidad_ofrecida, estado)"
                        + " VALUES (?, ?, 100, 100, 'DISPONIBLE') RETURNING id",
                produccion, "ESQ-" + sufijo());
        long compra = insertar(conexion,
                "INSERT INTO compra_feder (productor_id, codigo, fecha_compra, estado, total)"
                        + " VALUES (?, ?, now(), 'RECIBIDA_PARCIAL', 500.00) RETURNING id",
                productor, "ESQ-" + sufijo());
        long detalle = insertar(conexion,
                "INSERT INTO detalle_compra (compra_id, lote_origen_id, cantidad_comprada, precio_unitario, cantidad_recibida)"
                        + " VALUES (?, ?, 50, 10.00, 10) RETURNING id",
                compra, loteOrigen);
        return insertar(conexion,
                "INSERT INTO lote_feder (detalle_compra_id, codigo, fecha_ingreso, cantidad_disponible, estado)"
                        + " VALUES (?, ?, now(), 10, 'DISPONIBLE') RETURNING id",
                detalle, "ESQ-" + sufijo());
    }

    private static long insertar(Connection conexion, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            asignar(sentencia, parametros);
            try (ResultSet fila = sentencia.executeQuery()) {
                assertTrue(fila.next(), "El INSERT no devolvió el identificador: " + sql);
                return fila.getLong(1);
            }
        }
    }

    private static void ejecutar(Connection conexion, String sql, Object... parametros) throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            asignar(sentencia, parametros);
            sentencia.executeUpdate();
        }
    }

    private static void asignar(PreparedStatement sentencia, Object... parametros) throws SQLException {
        for (int i = 0; i < parametros.length; i++) {
            sentencia.setObject(i + 1, parametros[i]);
        }
    }

    private static String sufijo() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** Cédula ficticia de 6 cifras: fuera del rango que usan los datos de demostración. */
    private static int ciAleatoria() {
        return ThreadLocalRandom.current().nextInt(100_000, 900_000);
    }
}
