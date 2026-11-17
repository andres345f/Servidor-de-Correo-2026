-- =====================================================================================
-- FEDER · verificar_esquema.sql
-- Comprueba que la base cargada con db/01_schema.sql coincide con el modelo técnico aprobado.
-- Una instalación anterior debe tener aplicadas las migraciones de db/migraciones (acción
-- actualizar-esquema) para pasar esta verificación.
-- Solo lee el catálogo de PostgreSQL: no modifica datos ni estructura.
--
-- Las listas de valores esperados se generaron a partir de modelo_tecnico.json (clase, atributo,
-- multiplicidad y etiqueta «postgresql» de los 172 atributos; cardinalidades de las 40 asociaciones)
-- y de los nombres declarados en 01_schema.sql.
--
-- Cómo leer el resultado: las consultas 2 a 7 deben devolver CERO filas; el resumen final (8) debe
-- mostrar «OK» en todas sus líneas.
--
-- Ejecución:
--   psql -h 127.0.0.1 -p 55432 -U feder_admin -d feder_test -f db/verificaciones/verificar_esquema.sql
-- =====================================================================================

SET client_encoding = 'UTF8';

-- -------------------------------------------------------------------------------------
-- 1. Tablas presentes (25 del modelo + 3 técnicas del procesador de correo)
-- -------------------------------------------------------------------------------------
SELECT '1. tablas' AS verificacion,
       count(*) FILTER (WHERE table_name IN ('rol', 'usuario', 'usuario_rol', 'bitacora', 'comunidad_agricola', 'categoria', 'producto', 'productor', 'produccion', 'lote_produccion', 'compra_feder', 'detalle_compra', 'lote_feder', 'item_carrito', 'pedido', 'detalle_pedido', 'reserva_stock', 'movimiento_inventario', 'historial_pedido', 'pago', 'factura', 'entrega', 'notificacion', 'devolucion', 'reembolso')) AS tablas_modelo,
       count(*) FILTER (WHERE table_name IN ('mensaje_correo', 'correo_saliente', 'confirmacion_operacion')) AS tablas_tecnicas,
       count(*) AS tablas_en_public
  FROM information_schema.tables
 WHERE table_schema = 'public' AND table_type = 'BASE TABLE';

-- -------------------------------------------------------------------------------------
-- 2. Columnas frente al modelo: existencia, nulabilidad ([0..1] → NULL, [1] → NOT NULL), tipo y
--    valor por defecto de los 172 atributos, de las 40 claves foráneas y de los 25 identificadores;
--    además, que ninguna tabla del modelo tenga columnas no previstas. Debe devolver CERO filas.
-- -------------------------------------------------------------------------------------
-- [INICIO consulta_columnas_modelo]
WITH esperado (tabla, columna, origen, nullable_esperado, tipo_esperado, defecto_esperado) AS (VALUES
    ('rol', 'id', 'PK', 'NO', 'bigint', NULL),
    ('rol', 'codigo', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('rol', 'nombre', 'ATRIBUTO', 'NO', 'character varying(50)', NULL),
    ('rol', 'descripcion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('usuario', 'id', 'PK', 'NO', 'bigint', NULL),
    ('usuario', 'correo', 'ATRIBUTO', 'NO', 'character varying(120)', NULL),
    ('usuario', 'password_hash', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('usuario', 'nombres', 'ATRIBUTO', 'NO', 'character varying(80)', NULL),
    ('usuario', 'apellidos', 'ATRIBUTO', 'NO', 'character varying(80)', NULL),
    ('usuario', 'ci', 'ATRIBUTO', 'NO', 'integer', NULL),
    ('usuario', 'ci_complemento', 'ATRIBUTO', 'YES', 'character varying(2)', NULL),
    ('usuario', 'telefono', 'ATRIBUTO', 'NO', 'integer', NULL),
    ('usuario', 'activo', 'ATRIBUTO', 'NO', 'boolean', 'true'),
    ('usuario', 'fecha_registro', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('usuario', 'ultimo_acceso', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('usuario', 'fecha_aceptacion_terminos', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('usuario_rol', 'id', 'PK', 'NO', 'bigint', NULL),
    ('usuario_rol', 'usuario_id', 'FK', 'NO', 'bigint', NULL),
    ('usuario_rol', 'rol_id', 'FK', 'NO', 'bigint', NULL),
    ('usuario_rol', 'asignado_por_id', 'FK', 'YES', 'bigint', NULL),
    ('usuario_rol', 'revocado_por_id', 'FK', 'YES', 'bigint', NULL),
    ('usuario_rol', 'estado', 'ATRIBUTO', 'NO', 'character varying(10)', NULL),
    ('usuario_rol', 'fecha_asignacion', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('usuario_rol', 'fecha_activacion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('usuario_rol', 'fecha_expiracion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('usuario_rol', 'fecha_revocacion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('usuario_rol', 'motivo', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('usuario_rol', 'motivo_revocacion', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('bitacora', 'id', 'PK', 'NO', 'bigint', NULL),
    ('bitacora', 'usuario_id', 'FK', 'YES', 'bigint', NULL),
    ('bitacora', 'fecha', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('bitacora', 'accion', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('bitacora', 'entidad', 'ATRIBUTO', 'NO', 'character varying(60)', NULL),
    ('bitacora', 'entidad_id', 'ATRIBUTO', 'YES', 'bigint', NULL),
    ('bitacora', 'detalle', 'ATRIBUTO', 'YES', 'jsonb', NULL),
    ('bitacora', 'direccion_ip', 'ATRIBUTO', 'YES', 'inet', NULL),
    ('comunidad_agricola', 'id', 'PK', 'NO', 'bigint', NULL),
    ('comunidad_agricola', 'nombre', 'ATRIBUTO', 'NO', 'character varying(120)', NULL),
    ('comunidad_agricola', 'municipio', 'ATRIBUTO', 'NO', 'character varying(80)', NULL),
    ('comunidad_agricola', 'departamento', 'ATRIBUTO', 'NO', 'character varying(40)', '''Santa Cruz''::character varying'),
    ('comunidad_agricola', 'descripcion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('comunidad_agricola', 'activa', 'ATRIBUTO', 'NO', 'boolean', 'true'),
    ('categoria', 'id', 'PK', 'NO', 'bigint', NULL),
    ('categoria', 'nombre', 'ATRIBUTO', 'NO', 'character varying(60)', NULL),
    ('categoria', 'descripcion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('categoria', 'activa', 'ATRIBUTO', 'NO', 'boolean', 'true'),
    ('producto', 'id', 'PK', 'NO', 'bigint', NULL),
    ('producto', 'categoria_id', 'FK', 'NO', 'bigint', NULL),
    ('producto', 'codigo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('producto', 'nombre', 'ATRIBUTO', 'NO', 'character varying(120)', NULL),
    ('producto', 'descripcion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('producto', 'presentacion', 'ATRIBUTO', 'NO', 'character varying(60)', NULL),
    ('producto', 'unidad_medida', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('producto', 'precio_venta', 'ATRIBUTO', 'NO', 'numeric(12,2)', NULL),
    ('producto', 'stock_minimo', 'ATRIBUTO', 'NO', 'numeric(12,3)', '0'),
    ('producto', 'imagen_url', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('producto', 'permite_encargo', 'ATRIBUTO', 'NO', 'boolean', 'false'),
    ('producto', 'activo', 'ATRIBUTO', 'NO', 'boolean', 'true'),
    ('productor', 'id', 'PK', 'NO', 'bigint', NULL),
    ('productor', 'usuario_id', 'FK', 'NO', 'bigint', NULL),
    ('productor', 'comunidad_agricola_id', 'FK', 'NO', 'bigint', NULL),
    ('productor', 'codigo_productor', 'ATRIBUTO', 'YES', 'character varying(20)', NULL),
    ('productor', 'estado_solicitud', 'ATRIBUTO', 'NO', 'character varying(10)', '''PENDIENTE''::character varying'),
    ('productor', 'fecha_solicitud', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('productor', 'fecha_revision', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('productor', 'motivo_rechazo', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('productor', 'ubicacion_actividad', 'ATRIBUTO', 'NO', 'character varying(200)', NULL),
    ('productor', 'fecha_afiliacion', 'ATRIBUTO', 'YES', 'date', NULL),
    ('productor', 'activo', 'ATRIBUTO', 'NO', 'boolean', 'false'),
    ('produccion', 'id', 'PK', 'NO', 'bigint', NULL),
    ('produccion', 'productor_id', 'FK', 'NO', 'bigint', NULL),
    ('produccion', 'producto_id', 'FK', 'NO', 'bigint', NULL),
    ('produccion', 'comunidad_origen_id', 'FK', 'NO', 'bigint', NULL),
    ('produccion', 'codigo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('produccion', 'fecha_inicio', 'ATRIBUTO', 'YES', 'date', NULL),
    ('produccion', 'fecha_disponibilidad_estimada', 'ATRIBUTO', 'YES', 'date', NULL),
    ('produccion', 'cantidad_estimada', 'ATRIBUTO', 'YES', 'numeric(12,3)', NULL),
    ('produccion', 'ubicacion_produccion', 'ATRIBUTO', 'YES', 'character varying(200)', NULL),
    ('produccion', 'estado', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('produccion', 'observacion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('produccion', 'nombre_comunidad_origen', 'ATRIBUTO', 'NO', 'character varying(120)', NULL),
    ('produccion', 'municipio_origen', 'ATRIBUTO', 'NO', 'character varying(80)', NULL),
    ('produccion', 'departamento_origen', 'ATRIBUTO', 'NO', 'character varying(40)', NULL),
    ('lote_produccion', 'id', 'PK', 'NO', 'bigint', NULL),
    ('lote_produccion', 'produccion_id', 'FK', 'NO', 'bigint', NULL),
    ('lote_produccion', 'codigo_lote', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('lote_produccion', 'fecha_elaboracion', 'ATRIBUTO', 'YES', 'date', NULL),
    ('lote_produccion', 'fecha_vencimiento', 'ATRIBUTO', 'YES', 'date', NULL),
    ('lote_produccion', 'cantidad_producida', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('lote_produccion', 'cantidad_ofrecida', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('lote_produccion', 'precio_ofrecido', 'ATRIBUTO', 'YES', 'numeric(12,2)', NULL),
    ('lote_produccion', 'estado', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('compra_feder', 'id', 'PK', 'NO', 'bigint', NULL),
    ('compra_feder', 'productor_id', 'FK', 'NO', 'bigint', NULL),
    ('compra_feder', 'codigo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('compra_feder', 'fecha_compra', 'ATRIBUTO', 'NO', 'timestamp with time zone', NULL),
    ('compra_feder', 'estado', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('compra_feder', 'total', 'ATRIBUTO', 'NO', 'numeric(14,2)', NULL),
    ('compra_feder', 'fecha_pago_productor', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('compra_feder', 'observacion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('detalle_compra', 'id', 'PK', 'NO', 'bigint', NULL),
    ('detalle_compra', 'compra_id', 'FK', 'NO', 'bigint', NULL),
    ('detalle_compra', 'lote_origen_id', 'FK', 'NO', 'bigint', NULL),
    ('detalle_compra', 'cantidad_comprada', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('detalle_compra', 'precio_unitario', 'ATRIBUTO', 'NO', 'numeric(12,2)', NULL),
    ('detalle_compra', 'cantidad_recibida', 'ATRIBUTO', 'NO', 'numeric(12,3)', '0'),
    ('detalle_compra', 'cantidad_rechazada', 'ATRIBUTO', 'NO', 'numeric(12,3)', '0'),
    ('detalle_compra', 'cantidad_cancelada', 'ATRIBUTO', 'NO', 'numeric(12,3)', '0'),
    ('lote_feder', 'id', 'PK', 'NO', 'bigint', NULL),
    ('lote_feder', 'detalle_compra_id', 'FK', 'NO', 'bigint', NULL),
    ('lote_feder', 'codigo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('lote_feder', 'fecha_ingreso', 'ATRIBUTO', 'NO', 'timestamp with time zone', NULL),
    ('lote_feder', 'cantidad_disponible', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('lote_feder', 'cantidad_reservada', 'ATRIBUTO', 'NO', 'numeric(12,3)', '0'),
    ('lote_feder', 'ubicacion_almacen', 'ATRIBUTO', 'YES', 'character varying(60)', NULL),
    ('lote_feder', 'estado', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('item_carrito', 'id', 'PK', 'NO', 'bigint', NULL),
    ('item_carrito', 'cliente_id', 'FK', 'NO', 'bigint', NULL),
    ('item_carrito', 'producto_id', 'FK', 'NO', 'bigint', NULL),
    ('item_carrito', 'cantidad', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('item_carrito', 'fecha_agregado', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('pedido', 'id', 'PK', 'NO', 'bigint', NULL),
    ('pedido', 'usuario_id', 'FK', 'NO', 'bigint', NULL),
    ('pedido', 'codigo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('pedido', 'tipo', 'ATRIBUTO', 'NO', 'character varying(15)', NULL),
    ('pedido', 'estado', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('pedido', 'fecha_creacion', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('pedido', 'subtotal', 'ATRIBUTO', 'NO', 'numeric(14,2)', NULL),
    ('pedido', 'costo_envio', 'ATRIBUTO', 'NO', 'numeric(12,2)', '0'),
    ('pedido', 'total', 'ATRIBUTO', 'NO', 'numeric(14,2)', NULL),
    ('pedido', 'fecha_estimada_disponibilidad', 'ATRIBUTO', 'YES', 'date', NULL),
    ('pedido', 'clave_idempotencia', 'ATRIBUTO', 'NO', 'character varying(64)', NULL),
    ('pedido', 'observacion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('detalle_pedido', 'id', 'PK', 'NO', 'bigint', NULL),
    ('detalle_pedido', 'pedido_id', 'FK', 'NO', 'bigint', NULL),
    ('detalle_pedido', 'producto_id', 'FK', 'NO', 'bigint', NULL),
    ('detalle_pedido', 'produccion_id', 'FK', 'YES', 'bigint', NULL),
    ('detalle_pedido', 'cantidad', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('detalle_pedido', 'precio_unitario', 'ATRIBUTO', 'NO', 'numeric(12,2)', NULL),
    ('detalle_pedido', 'subtotal', 'ATRIBUTO', 'NO', 'numeric(14,2)', NULL),
    ('detalle_pedido', 'descripcion_venta', 'ATRIBUTO', 'NO', 'character varying(200)', NULL),
    ('reserva_stock', 'id', 'PK', 'NO', 'bigint', NULL),
    ('reserva_stock', 'lote_feder_id', 'FK', 'NO', 'bigint', NULL),
    ('reserva_stock', 'detalle_pedido_id', 'FK', 'NO', 'bigint', NULL),
    ('reserva_stock', 'cantidad', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('reserva_stock', 'estado', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('reserva_stock', 'fecha_creacion', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('reserva_stock', 'fecha_expiracion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('reserva_stock', 'fecha_cierre', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('movimiento_inventario', 'id', 'PK', 'NO', 'bigint', NULL),
    ('movimiento_inventario', 'lote_feder_id', 'FK', 'NO', 'bigint', NULL),
    ('movimiento_inventario', 'usuario_id', 'FK', 'NO', 'bigint', NULL),
    ('movimiento_inventario', 'reserva_id', 'FK', 'YES', 'bigint', NULL),
    ('movimiento_inventario', 'devolucion_id', 'FK', 'YES', 'bigint', NULL),
    ('movimiento_inventario', 'tipo', 'ATRIBUTO', 'NO', 'character varying(25)', NULL),
    ('movimiento_inventario', 'cantidad', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('movimiento_inventario', 'fecha', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('movimiento_inventario', 'motivo', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('movimiento_inventario', 'clave_operacion', 'ATRIBUTO', 'NO', 'character varying(64)', NULL),
    ('historial_pedido', 'id', 'PK', 'NO', 'bigint', NULL),
    ('historial_pedido', 'pedido_id', 'FK', 'NO', 'bigint', NULL),
    ('historial_pedido', 'usuario_id', 'FK', 'YES', 'bigint', NULL),
    ('historial_pedido', 'estado_anterior', 'ATRIBUTO', 'YES', 'character varying(30)', NULL),
    ('historial_pedido', 'estado_nuevo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('historial_pedido', 'fecha', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('historial_pedido', 'comentario', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('pago', 'id', 'PK', 'NO', 'bigint', NULL),
    ('pago', 'pedido_id', 'FK', 'NO', 'bigint', NULL),
    ('pago', 'metodo', 'ATRIBUTO', 'NO', 'character varying(15)', NULL),
    ('pago', 'monto', 'ATRIBUTO', 'NO', 'numeric(14,2)', NULL),
    ('pago', 'estado', 'ATRIBUTO', 'NO', 'character varying(15)', NULL),
    ('pago', 'fecha_registro', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('pago', 'fecha_pago', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('pago', 'referencia_externa', 'ATRIBUTO', 'YES', 'character varying(100)', NULL),
    ('pago', 'clave_idempotencia', 'ATRIBUTO', 'NO', 'character varying(64)', NULL),
    ('factura', 'id', 'PK', 'NO', 'bigint', NULL),
    ('factura', 'pedido_id', 'FK', 'NO', 'bigint', NULL),
    ('factura', 'numero', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('factura', 'fecha_emision', 'ATRIBUTO', 'NO', 'timestamp with time zone', NULL),
    ('factura', 'nit_ci', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('factura', 'razon_social', 'ATRIBUTO', 'NO', 'character varying(150)', NULL),
    ('factura', 'total', 'ATRIBUTO', 'NO', 'numeric(14,2)', NULL),
    ('factura', 'codigo_autorizacion', 'ATRIBUTO', 'YES', 'character varying(100)', NULL),
    ('factura', 'estado', 'ATRIBUTO', 'NO', 'character varying(10)', NULL),
    ('factura', 'fecha_anulacion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('entrega', 'id', 'PK', 'NO', 'bigint', NULL),
    ('entrega', 'pedido_id', 'FK', 'NO', 'bigint', NULL),
    ('entrega', 'modalidad', 'ATRIBUTO', 'NO', 'character varying(10)', NULL),
    ('entrega', 'estado', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('entrega', 'direccion', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('entrega', 'referencia', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('entrega', 'nombre_receptor', 'ATRIBUTO', 'NO', 'character varying(120)', NULL),
    ('entrega', 'telefono_contacto', 'ATRIBUTO', 'NO', 'character varying(20)', NULL),
    ('entrega', 'fecha_programada', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('entrega', 'fecha_entrega', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('entrega', 'observacion', 'ATRIBUTO', 'YES', 'text', NULL),
    ('notificacion', 'id', 'PK', 'NO', 'bigint', NULL),
    ('notificacion', 'usuario_id', 'FK', 'NO', 'bigint', NULL),
    ('notificacion', 'pedido_id', 'FK', 'YES', 'bigint', NULL),
    ('notificacion', 'producto_id', 'FK', 'YES', 'bigint', NULL),
    ('notificacion', 'tipo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('notificacion', 'canal', 'ATRIBUTO', 'NO', 'character varying(10)', NULL),
    ('notificacion', 'titulo', 'ATRIBUTO', 'NO', 'character varying(120)', NULL),
    ('notificacion', 'mensaje', 'ATRIBUTO', 'NO', 'text', NULL),
    ('notificacion', 'estado', 'ATRIBUTO', 'NO', 'character varying(10)', NULL),
    ('notificacion', 'fecha_creacion', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('notificacion', 'fecha_envio', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('notificacion', 'fecha_lectura', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('devolucion', 'id', 'PK', 'NO', 'bigint', NULL),
    ('devolucion', 'movimiento_inventario_id', 'FK', 'NO', 'bigint', NULL),
    ('devolucion', 'devolucion_anulada_id', 'FK', 'YES', 'bigint', NULL),
    ('devolucion', 'codigo', 'ATRIBUTO', 'NO', 'character varying(30)', NULL),
    ('devolucion', 'cantidad', 'ATRIBUTO', 'NO', 'numeric(12,3)', NULL),
    ('devolucion', 'motivo', 'ATRIBUTO', 'NO', 'character varying(255)', NULL),
    ('devolucion', 'estado', 'ATRIBUTO', 'NO', 'character varying(15)', NULL),
    ('devolucion', 'resolucion', 'ATRIBUTO', 'YES', 'character varying(15)', NULL),
    ('devolucion', 'cantidad_aprobada', 'ATRIBUTO', 'YES', 'numeric(12,3)', NULL),
    ('devolucion', 'cantidad_recibida', 'ATRIBUTO', 'YES', 'numeric(12,3)', NULL),
    ('devolucion', 'cantidad_apta', 'ATRIBUTO', 'YES', 'numeric(12,3)', NULL),
    ('devolucion', 'importe_reembolsable', 'ATRIBUTO', 'YES', 'numeric(14,2)', NULL),
    ('devolucion', 'fecha_solicitud', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('devolucion', 'fecha_recepcion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('devolucion', 'fecha_inspeccion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('devolucion', 'fecha_resolucion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('devolucion', 'fecha_cierre', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('devolucion', 'fecha_anulacion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('devolucion', 'motivo_anulacion', 'ATRIBUTO', 'YES', 'character varying(255)', NULL),
    ('reembolso', 'id', 'PK', 'NO', 'bigint', NULL),
    ('reembolso', 'pago_id', 'FK', 'NO', 'bigint', NULL),
    ('reembolso', 'devolucion_id', 'FK', 'YES', 'bigint', NULL),
    ('reembolso', 'monto', 'ATRIBUTO', 'NO', 'numeric(14,2)', NULL),
    ('reembolso', 'estado', 'ATRIBUTO', 'NO', 'character varying(15)', NULL),
    ('reembolso', 'motivo', 'ATRIBUTO', 'NO', 'character varying(255)', NULL),
    ('reembolso', 'fecha_solicitud', 'ATRIBUTO', 'NO', 'timestamp with time zone', 'now()'),
    ('reembolso', 'fecha_ejecucion', 'ATRIBUTO', 'YES', 'timestamp with time zone', NULL),
    ('reembolso', 'referencia_externa', 'ATRIBUTO', 'YES', 'character varying(100)', NULL),
    ('reembolso', 'clave_idempotencia', 'ATRIBUTO', 'NO', 'character varying(64)', NULL)
),
existente AS (
    SELECT c.relname::text AS tabla,
           a.attname::text AS columna,
           CASE WHEN a.attnotnull THEN 'NO' ELSE 'YES' END AS nullable_real,
           format_type(a.atttypid, a.atttypmod) AS tipo_real,
           pg_get_expr(d.adbin, d.adrelid) AS defecto_real
      FROM pg_class c
      JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
      JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
      LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
     WHERE c.relkind = 'r'
       AND c.relname IN (SELECT DISTINCT tabla FROM esperado)
)
SELECT COALESCE(e.tabla, x.tabla) AS tabla,
       COALESCE(e.columna, x.columna) AS columna,
       e.origen,
       CASE
           WHEN x.columna IS NULL THEN 'falta la columna'
           WHEN e.columna IS NULL THEN 'columna no prevista en el modelo'
           WHEN e.nullable_esperado <> x.nullable_real THEN 'nulabilidad distinta'
           WHEN e.tipo_esperado <> x.tipo_real THEN 'tipo distinto'
           ELSE 'valor por defecto distinto'
       END AS problema,
       e.nullable_esperado, x.nullable_real,
       e.tipo_esperado, x.tipo_real,
       e.defecto_esperado, x.defecto_real
  FROM esperado e
  FULL JOIN existente x ON x.tabla = e.tabla AND x.columna = e.columna
 WHERE x.columna IS NULL
    OR e.columna IS NULL
    OR e.nullable_esperado <> x.nullable_real
    OR e.tipo_esperado <> x.tipo_real
    OR e.defecto_esperado IS DISTINCT FROM x.defecto_real
 ORDER BY 1, 2;
-- [FIN consulta_columnas_modelo]

-- -------------------------------------------------------------------------------------
-- 3. Asociaciones → claves foráneas: cada una de las 40 asociaciones tiene su clave foránea de una
--    columna hacia la tabla correcta, con ON DELETE RESTRICT (CASCADE solo en el carrito), y es UNIQUE
--    cuando el extremo que la contiene tiene multiplicidad máxima 1. Debe devolver CERO filas.
-- -------------------------------------------------------------------------------------
WITH esperado (asociacion, tabla, columna, tabla_referida, nullable_esperado, unica_esperada) AS (VALUES
    ('A10', 'usuario_rol', 'usuario_id', 'usuario', 'NO', false),
    ('A11', 'usuario_rol', 'rol_id', 'rol', 'NO', false),
    ('A12', 'usuario_rol', 'asignado_por_id', 'usuario', 'YES', false),
    ('A13', 'bitacora', 'usuario_id', 'usuario', 'YES', false),
    ('A14', 'productor', 'usuario_id', 'usuario', 'NO', true),
    ('A15', 'productor', 'comunidad_agricola_id', 'comunidad_agricola', 'NO', false),
    ('A16', 'produccion', 'productor_id', 'productor', 'NO', false),
    ('A17', 'produccion', 'producto_id', 'producto', 'NO', false),
    ('A18', 'lote_produccion', 'produccion_id', 'produccion', 'NO', false),
    ('A19', 'producto', 'categoria_id', 'categoria', 'NO', false),
    ('A20', 'compra_feder', 'productor_id', 'productor', 'NO', false),
    ('A21', 'detalle_compra', 'compra_id', 'compra_feder', 'NO', false),
    ('A22', 'detalle_compra', 'lote_origen_id', 'lote_produccion', 'NO', false),
    ('A23', 'lote_feder', 'detalle_compra_id', 'detalle_compra', 'NO', true),
    ('A24', 'movimiento_inventario', 'lote_feder_id', 'lote_feder', 'NO', false),
    ('A25', 'movimiento_inventario', 'usuario_id', 'usuario', 'NO', false),
    ('A26', 'reserva_stock', 'lote_feder_id', 'lote_feder', 'NO', false),
    ('A27', 'reserva_stock', 'detalle_pedido_id', 'detalle_pedido', 'NO', false),
    ('A28', 'movimiento_inventario', 'reserva_id', 'reserva_stock', 'YES', false),
    ('A29', 'item_carrito', 'cliente_id', 'usuario', 'NO', false),
    ('A30', 'item_carrito', 'producto_id', 'producto', 'NO', false),
    ('A31', 'pedido', 'usuario_id', 'usuario', 'NO', false),
    ('A32', 'detalle_pedido', 'pedido_id', 'pedido', 'NO', false),
    ('A33', 'detalle_pedido', 'producto_id', 'producto', 'NO', false),
    ('A34', 'detalle_pedido', 'produccion_id', 'produccion', 'YES', false),
    ('A35', 'historial_pedido', 'pedido_id', 'pedido', 'NO', false),
    ('A36', 'historial_pedido', 'usuario_id', 'usuario', 'YES', false),
    ('A37', 'pago', 'pedido_id', 'pedido', 'NO', false),
    ('A38', 'factura', 'pedido_id', 'pedido', 'NO', true),
    ('A39', 'entrega', 'pedido_id', 'pedido', 'NO', true),
    ('A40', 'notificacion', 'usuario_id', 'usuario', 'NO', false),
    ('A41', 'notificacion', 'pedido_id', 'pedido', 'YES', false),
    ('A42', 'notificacion', 'producto_id', 'producto', 'YES', false),
    ('A43', 'devolucion', 'movimiento_inventario_id', 'movimiento_inventario', 'NO', false),
    ('A44', 'movimiento_inventario', 'devolucion_id', 'devolucion', 'YES', false),
    ('A45', 'produccion', 'comunidad_origen_id', 'comunidad_agricola', 'NO', false),
    ('A46', 'reembolso', 'pago_id', 'pago', 'NO', false),
    ('A47', 'reembolso', 'devolucion_id', 'devolucion', 'YES', false),
    ('A48', 'devolucion', 'devolucion_anulada_id', 'devolucion', 'YES', true),
    ('A49', 'usuario_rol', 'revocado_por_id', 'usuario', 'YES', false)
),
fk_real AS (
    SELECT cl.relname::text AS tabla, a.attname::text AS columna, cr.relname::text AS tabla_referida,
           con.confdeltype AS al_borrar
      FROM pg_constraint con
      JOIN pg_class cl ON cl.oid = con.conrelid
      JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
      JOIN pg_class cr ON cr.oid = con.confrelid
      JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = con.conkey[1]
     WHERE con.contype = 'f' AND cardinality(con.conkey) = 1
),
unica_real AS (
    SELECT cl.relname::text AS tabla, a.attname::text AS columna
      FROM pg_index i
      JOIN pg_class cl ON cl.oid = i.indrelid
      JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = i.indkey[0]
     WHERE i.indisunique AND i.indnkeyatts = 1 AND i.indpred IS NULL
)
SELECT e.asociacion, e.tabla, e.columna, e.tabla_referida,
       CASE
           WHEN f.columna IS NULL THEN 'falta la clave foránea'
           WHEN f.al_borrar NOT IN ('r', 'c') THEN 'acción ON DELETE no prevista'
           WHEN f.al_borrar = 'c' AND e.tabla <> 'item_carrito' THEN 'ON DELETE CASCADE no previsto'
           WHEN e.unica_esperada THEN 'debería ser UNIQUE'
           ELSE 'no debería ser UNIQUE'
       END AS problema
  FROM esperado e
  LEFT JOIN fk_real f ON f.tabla = e.tabla AND f.columna = e.columna AND f.tabla_referida = e.tabla_referida
  LEFT JOIN unica_real u ON u.tabla = e.tabla AND u.columna = e.columna
 WHERE f.columna IS NULL
    OR f.al_borrar NOT IN ('r', 'c')
    OR (f.al_borrar = 'c' AND e.tabla <> 'item_carrito')
    OR e.unica_esperada <> (u.columna IS NOT NULL)
 ORDER BY 1;

-- -------------------------------------------------------------------------------------
-- 4. Objetos con nombre declarados en 01_schema.sql (claves, restricciones, índices y disparadores)
--    que faltan en la base. Debe devolver CERO filas.
-- -------------------------------------------------------------------------------------
-- [INICIO consulta_objetos_declarados]
WITH esperado (clase, tabla, nombre) AS (VALUES
    ('PK', 'rol', 'pk_rol'),
    ('UQ', 'rol', 'uq_rol_codigo'),
    ('CK', 'rol', 'ck_rol_codigo'),
    ('PK', 'usuario', 'pk_usuario'),
    ('CK', 'usuario', 'ck_usuario_correo'),
    ('CK', 'usuario', 'ck_usuario_password_hash'),
    ('CK', 'usuario', 'ck_usuario_nombres'),
    ('CK', 'usuario', 'ck_usuario_apellidos'),
    ('CK', 'usuario', 'ck_usuario_ci'),
    ('CK', 'usuario', 'ck_usuario_ci_complemento'),
    ('CK', 'usuario', 'ck_usuario_telefono'),
    ('INDICE_UNICO', 'usuario', 'uq_usuario_correo'),
    ('INDICE_UNICO', 'usuario', 'uq_usuario_ci'),
    ('PK', 'usuario_rol', 'pk_usuario_rol'),
    ('FK', 'usuario_rol', 'fk_usuario_rol_usuario'),
    ('FK', 'usuario_rol', 'fk_usuario_rol_rol'),
    ('FK', 'usuario_rol', 'fk_usuario_rol_asignado_por'),
    ('FK', 'usuario_rol', 'fk_usuario_rol_revocado_por'),
    ('CK', 'usuario_rol', 'ck_usuario_rol_estado'),
    ('CK', 'usuario_rol', 'ck_usuario_rol_fecha_activacion'),
    ('CK', 'usuario_rol', 'ck_usuario_rol_fecha_expiracion'),
    ('CK', 'usuario_rol', 'ck_usuario_rol_fecha_revocacion'),
    ('CK', 'usuario_rol', 'ck_usuario_rol_coherencia_estado'),
    ('CK', 'usuario_rol', 'ck_usuario_rol_asignado_por_distinto'),
    ('INDICE_UNICO', 'usuario_rol', 'uq_usuario_rol_concesion_abierta'),
    ('PK', 'bitacora', 'pk_bitacora'),
    ('FK', 'bitacora', 'fk_bitacora_usuario'),
    ('CK', 'bitacora', 'ck_bitacora_accion'),
    ('CK', 'bitacora', 'ck_bitacora_operacion_detalle_compra'),
    ('INDICE_UNICO', 'bitacora', 'uq_bitacora_clave_operacion'),
    ('INDICE_UNICO', 'bitacora', 'uq_bitacora_documento_entrega'),
    ('PK', 'comunidad_agricola', 'pk_comunidad_agricola'),
    ('UQ', 'comunidad_agricola', 'uq_comunidad_agricola_nombre_municipio'),
    ('PK', 'categoria', 'pk_categoria'),
    ('UQ', 'categoria', 'uq_categoria_nombre'),
    ('PK', 'producto', 'pk_producto'),
    ('FK', 'producto', 'fk_producto_categoria'),
    ('UQ', 'producto', 'uq_producto_codigo'),
    ('CK', 'producto', 'ck_producto_unidad_medida'),
    ('CK', 'producto', 'ck_producto_precio_venta'),
    ('CK', 'producto', 'ck_producto_stock_minimo'),
    ('CK', 'producto', 'ck_producto_stock_minimo_entero'),
    ('PK', 'productor', 'pk_productor'),
    ('FK', 'productor', 'fk_productor_usuario'),
    ('FK', 'productor', 'fk_productor_comunidad_agricola'),
    ('UQ', 'productor', 'uq_productor_usuario'),
    ('UQ', 'productor', 'uq_productor_codigo_productor'),
    ('CK', 'productor', 'ck_productor_estado_solicitud'),
    ('CK', 'productor', 'ck_productor_fecha_revision'),
    ('CK', 'productor', 'ck_productor_ubicacion_actividad'),
    ('CK', 'productor', 'ck_productor_coherencia_solicitud'),
    ('CK', 'productor', 'ck_productor_activo_aprobado'),
    ('PK', 'produccion', 'pk_produccion'),
    ('FK', 'produccion', 'fk_produccion_productor'),
    ('FK', 'produccion', 'fk_produccion_producto'),
    ('FK', 'produccion', 'fk_produccion_comunidad_origen'),
    ('UQ', 'produccion', 'uq_produccion_codigo'),
    ('CK', 'produccion', 'ck_produccion_estado'),
    ('CK', 'produccion', 'ck_produccion_fecha_inicio'),
    ('CK', 'produccion', 'ck_produccion_fecha_disponibilidad_estimada'),
    ('CK', 'produccion', 'ck_produccion_cantidad_estimada'),
    ('CK', 'produccion', 'ck_produccion_ubicacion_produccion'),
    ('PK', 'lote_produccion', 'pk_lote_produccion'),
    ('FK', 'lote_produccion', 'fk_lote_produccion_produccion'),
    ('UQ', 'lote_produccion', 'uq_lote_produccion_codigo_lote'),
    ('CK', 'lote_produccion', 'ck_lote_produccion_estado'),
    ('CK', 'lote_produccion', 'ck_lote_produccion_fecha_vencimiento'),
    ('CK', 'lote_produccion', 'ck_lote_produccion_vencimiento_con_elaboracion'),
    ('CK', 'lote_produccion', 'ck_lote_produccion_cantidad_producida'),
    ('CK', 'lote_produccion', 'ck_lote_produccion_cantidad_ofrecida'),
    ('CK', 'lote_produccion', 'ck_lote_produccion_precio_ofrecido'),
    ('PK', 'compra_feder', 'pk_compra_feder'),
    ('FK', 'compra_feder', 'fk_compra_feder_productor'),
    ('UQ', 'compra_feder', 'uq_compra_feder_codigo'),
    ('CK', 'compra_feder', 'ck_compra_feder_estado'),
    ('CK', 'compra_feder', 'ck_compra_feder_total'),
    ('CK', 'compra_feder', 'ck_compra_feder_pago_solo_recibida'),
    ('PK', 'detalle_compra', 'pk_detalle_compra'),
    ('FK', 'detalle_compra', 'fk_detalle_compra_compra'),
    ('FK', 'detalle_compra', 'fk_detalle_compra_lote_origen'),
    ('UQ', 'detalle_compra', 'uq_detalle_compra_compra_lote_origen'),
    ('CK', 'detalle_compra', 'ck_detalle_compra_cantidad_comprada'),
    ('CK', 'detalle_compra', 'ck_detalle_compra_precio_unitario'),
    ('CK', 'detalle_compra', 'ck_detalle_compra_cantidad_recibida'),
    ('CK', 'detalle_compra', 'ck_detalle_compra_cantidad_rechazada'),
    ('CK', 'detalle_compra', 'ck_detalle_compra_cantidad_cancelada'),
    ('CK', 'detalle_compra', 'ck_detalle_compra_cantidades_resueltas'),
    ('PK', 'lote_feder', 'pk_lote_feder'),
    ('FK', 'lote_feder', 'fk_lote_feder_detalle_compra'),
    ('UQ', 'lote_feder', 'uq_lote_feder_detalle_compra'),
    ('UQ', 'lote_feder', 'uq_lote_feder_codigo'),
    ('CK', 'lote_feder', 'ck_lote_feder_estado'),
    ('CK', 'lote_feder', 'ck_lote_feder_cantidad_disponible'),
    ('CK', 'lote_feder', 'ck_lote_feder_cantidad_reservada'),
    ('CK', 'lote_feder', 'ck_lote_feder_agotado_sin_saldo'),
    ('PK', 'item_carrito', 'pk_item_carrito'),
    ('FK', 'item_carrito', 'fk_item_carrito_cliente'),
    ('FK', 'item_carrito', 'fk_item_carrito_producto'),
    ('UQ', 'item_carrito', 'uq_item_carrito_cliente_producto'),
    ('CK', 'item_carrito', 'ck_item_carrito_cantidad'),
    ('PK', 'pedido', 'pk_pedido'),
    ('FK', 'pedido', 'fk_pedido_usuario'),
    ('UQ', 'pedido', 'uq_pedido_codigo'),
    ('UQ', 'pedido', 'uq_pedido_clave_idempotencia'),
    ('UQ', 'pedido', 'uq_pedido_id_total'),
    ('CK', 'pedido', 'ck_pedido_tipo'),
    ('CK', 'pedido', 'ck_pedido_estado'),
    ('CK', 'pedido', 'ck_pedido_subtotal'),
    ('CK', 'pedido', 'ck_pedido_costo_envio'),
    ('CK', 'pedido', 'ck_pedido_total'),
    ('CK', 'pedido', 'ck_pedido_espera_solo_encargo'),
    ('CK', 'pedido', 'ck_pedido_fecha_estimada_solo_encargo'),
    ('PK', 'detalle_pedido', 'pk_detalle_pedido'),
    ('FK', 'detalle_pedido', 'fk_detalle_pedido_pedido'),
    ('FK', 'detalle_pedido', 'fk_detalle_pedido_producto'),
    ('FK', 'detalle_pedido', 'fk_detalle_pedido_produccion'),
    ('UQ', 'detalle_pedido', 'uq_detalle_pedido_pedido_producto'),
    ('CK', 'detalle_pedido', 'ck_detalle_pedido_cantidad'),
    ('CK', 'detalle_pedido', 'ck_detalle_pedido_precio_unitario'),
    ('CK', 'detalle_pedido', 'ck_detalle_pedido_subtotal'),
    ('PK', 'reserva_stock', 'pk_reserva_stock'),
    ('FK', 'reserva_stock', 'fk_reserva_stock_lote_feder'),
    ('FK', 'reserva_stock', 'fk_reserva_stock_detalle_pedido'),
    ('UQ', 'reserva_stock', 'uq_reserva_stock_id_lote_feder'),
    ('CK', 'reserva_stock', 'ck_reserva_stock_cantidad'),
    ('CK', 'reserva_stock', 'ck_reserva_stock_estado'),
    ('CK', 'reserva_stock', 'ck_reserva_stock_fecha_cierre'),
    ('PK', 'movimiento_inventario', 'pk_movimiento_inventario'),
    ('FK', 'movimiento_inventario', 'fk_movimiento_inventario_lote_feder'),
    ('FK', 'movimiento_inventario', 'fk_movimiento_inventario_usuario'),
    ('FK', 'movimiento_inventario', 'fk_movimiento_inventario_reserva'),
    ('FK', 'movimiento_inventario', 'fk_movimiento_inventario_reserva_lote'),
    ('UQ', 'movimiento_inventario', 'uq_movimiento_inventario_clave_operacion'),
    ('CK', 'movimiento_inventario', 'ck_movimiento_inventario_tipo'),
    ('CK', 'movimiento_inventario', 'ck_movimiento_inventario_cantidad'),
    ('CK', 'movimiento_inventario', 'ck_movimiento_inventario_reserva_segun_tipo'),
    ('CK', 'movimiento_inventario', 'ck_movimiento_inventario_devolucion_segun_tipo'),
    ('CK', 'movimiento_inventario', 'ck_movimiento_inventario_motivo_segun_tipo'),
    ('INDICE_UNICO', 'movimiento_inventario', 'uq_movimiento_inventario_salida_venta_reserva'),
    ('PK', 'historial_pedido', 'pk_historial_pedido'),
    ('FK', 'historial_pedido', 'fk_historial_pedido_pedido'),
    ('FK', 'historial_pedido', 'fk_historial_pedido_usuario'),
    ('CK', 'historial_pedido', 'ck_historial_pedido_estado_anterior'),
    ('CK', 'historial_pedido', 'ck_historial_pedido_estado_nuevo'),
    ('CK', 'historial_pedido', 'ck_historial_pedido_transicion'),
    ('CK', 'historial_pedido', 'ck_historial_pedido_comentario_cancelacion'),
    ('PK', 'pago', 'pk_pago'),
    ('FK', 'pago', 'fk_pago_pedido'),
    ('UQ', 'pago', 'uq_pago_referencia_externa'),
    ('UQ', 'pago', 'uq_pago_clave_idempotencia'),
    ('CK', 'pago', 'ck_pago_metodo'),
    ('CK', 'pago', 'ck_pago_monto'),
    ('CK', 'pago', 'ck_pago_estado'),
    ('CK', 'pago', 'ck_pago_fecha_pago'),
    ('INDICE_UNICO', 'pago', 'uq_pago_pendiente_por_pedido'),
    ('PK', 'factura', 'pk_factura'),
    ('FK', 'factura', 'fk_factura_pedido'),
    ('FK', 'factura', 'fk_factura_pedido_total'),
    ('UQ', 'factura', 'uq_factura_pedido'),
    ('UQ', 'factura', 'uq_factura_numero'),
    ('CK', 'factura', 'ck_factura_estado'),
    ('CK', 'factura', 'ck_factura_fecha_anulacion'),
    ('PK', 'entrega', 'pk_entrega'),
    ('FK', 'entrega', 'fk_entrega_pedido'),
    ('UQ', 'entrega', 'uq_entrega_pedido'),
    ('CK', 'entrega', 'ck_entrega_modalidad'),
    ('CK', 'entrega', 'ck_entrega_estado'),
    ('CK', 'entrega', 'ck_entrega_fecha_entrega'),
    ('CK', 'entrega', 'ck_entrega_direccion_delivery'),
    ('CK', 'entrega', 'ck_entrega_estado_segun_modalidad'),
    ('PK', 'notificacion', 'pk_notificacion'),
    ('FK', 'notificacion', 'fk_notificacion_usuario'),
    ('FK', 'notificacion', 'fk_notificacion_pedido'),
    ('FK', 'notificacion', 'fk_notificacion_producto'),
    ('CK', 'notificacion', 'ck_notificacion_tipo'),
    ('CK', 'notificacion', 'ck_notificacion_canal'),
    ('CK', 'notificacion', 'ck_notificacion_estado'),
    ('CK', 'notificacion', 'ck_notificacion_referencia_segun_tipo'),
    ('CK', 'notificacion', 'ck_notificacion_fecha_envio'),
    ('PK', 'devolucion', 'pk_devolucion'),
    ('FK', 'devolucion', 'fk_devolucion_movimiento_inventario'),
    ('FK', 'devolucion', 'fk_devolucion_devolucion_anulada'),
    ('UQ', 'devolucion', 'uq_devolucion_id_movimiento_inventario'),
    ('FK', 'devolucion', 'fk_devolucion_devolucion_anulada_despacho'),
    ('UQ', 'devolucion', 'uq_devolucion_devolucion_anulada'),
    ('UQ', 'devolucion', 'uq_devolucion_codigo'),
    ('CK', 'devolucion', 'ck_devolucion_devolucion_anulada_distinta'),
    ('CK', 'devolucion', 'ck_devolucion_cantidad'),
    ('CK', 'devolucion', 'ck_devolucion_estado'),
    ('CK', 'devolucion', 'ck_devolucion_resolucion'),
    ('CK', 'devolucion', 'ck_devolucion_cantidad_aprobada'),
    ('CK', 'devolucion', 'ck_devolucion_cantidad_recibida'),
    ('CK', 'devolucion', 'ck_devolucion_cantidad_apta'),
    ('CK', 'devolucion', 'ck_devolucion_apta_requiere_recibida'),
    ('CK', 'devolucion', 'ck_devolucion_importe_reembolsable'),
    ('CK', 'devolucion', 'ck_devolucion_fecha_inspeccion'),
    ('CK', 'devolucion', 'ck_devolucion_fecha_recepcion'),
    ('CK', 'devolucion', 'ck_devolucion_resolucion_desde_aprobada'),
    ('CK', 'devolucion', 'ck_devolucion_aprobada_desde_aprobada'),
    ('CK', 'devolucion', 'ck_devolucion_importe_segun_resolucion'),
    ('CK', 'devolucion', 'ck_devolucion_fecha_resolucion'),
    ('CK', 'devolucion', 'ck_devolucion_fecha_cierre'),
    ('CK', 'devolucion', 'ck_devolucion_fecha_anulacion'),
    ('CK', 'devolucion', 'ck_devolucion_motivo_anulacion'),
    ('FK', 'movimiento_inventario', 'fk_movimiento_inventario_devolucion'),
    ('PK', 'reembolso', 'pk_reembolso'),
    ('FK', 'reembolso', 'fk_reembolso_pago'),
    ('FK', 'reembolso', 'fk_reembolso_devolucion'),
    ('UQ', 'reembolso', 'uq_reembolso_referencia_externa'),
    ('UQ', 'reembolso', 'uq_reembolso_clave_idempotencia'),
    ('CK', 'reembolso', 'ck_reembolso_monto'),
    ('CK', 'reembolso', 'ck_reembolso_estado'),
    ('CK', 'reembolso', 'ck_reembolso_fecha_ejecucion'),
    ('INDICE_UNICO', 'reembolso', 'uq_reembolso_pendiente_por_pago_devolucion'),
    ('INDICE', 'usuario_rol', 'ix_usuario_rol_usuario'),
    ('INDICE', 'usuario_rol', 'ix_usuario_rol_rol'),
    ('INDICE', 'usuario_rol', 'ix_usuario_rol_asignado_por'),
    ('INDICE', 'usuario_rol', 'ix_usuario_rol_revocado_por'),
    ('INDICE', 'usuario_rol', 'ix_usuario_rol_estado'),
    ('INDICE', 'bitacora', 'ix_bitacora_usuario'),
    ('INDICE', 'bitacora', 'ix_bitacora_entidad'),
    ('INDICE', 'bitacora', 'ix_bitacora_fecha'),
    ('INDICE', 'bitacora', 'ix_bitacora_accion'),
    ('INDICE', 'producto', 'ix_producto_categoria'),
    ('INDICE', 'producto', 'ix_producto_activo'),
    ('INDICE', 'productor', 'ix_productor_comunidad_agricola'),
    ('INDICE', 'productor', 'ix_productor_estado_solicitud'),
    ('INDICE', 'produccion', 'ix_produccion_productor'),
    ('INDICE', 'produccion', 'ix_produccion_producto'),
    ('INDICE', 'produccion', 'ix_produccion_comunidad_origen'),
    ('INDICE', 'produccion', 'ix_produccion_estado'),
    ('INDICE', 'lote_produccion', 'ix_lote_produccion_produccion'),
    ('INDICE', 'lote_produccion', 'ix_lote_produccion_estado'),
    ('INDICE', 'lote_produccion', 'ix_lote_produccion_fecha_vencimiento'),
    ('INDICE', 'compra_feder', 'ix_compra_feder_productor'),
    ('INDICE', 'compra_feder', 'ix_compra_feder_estado'),
    ('INDICE', 'compra_feder', 'ix_compra_feder_fecha_compra'),
    ('INDICE', 'detalle_compra', 'ix_detalle_compra_lote_origen'),
    ('INDICE', 'lote_feder', 'ix_lote_feder_estado'),
    ('INDICE', 'lote_feder', 'ix_lote_feder_fecha_ingreso'),
    ('INDICE', 'item_carrito', 'ix_item_carrito_producto'),
    ('INDICE', 'pedido', 'ix_pedido_usuario'),
    ('INDICE', 'pedido', 'ix_pedido_estado'),
    ('INDICE', 'pedido', 'ix_pedido_fecha_creacion'),
    ('INDICE', 'detalle_pedido', 'ix_detalle_pedido_producto'),
    ('INDICE', 'detalle_pedido', 'ix_detalle_pedido_produccion'),
    ('INDICE', 'reserva_stock', 'ix_reserva_stock_lote_feder'),
    ('INDICE', 'reserva_stock', 'ix_reserva_stock_detalle_pedido'),
    ('INDICE', 'reserva_stock', 'ix_reserva_stock_estado'),
    ('INDICE', 'reserva_stock', 'ix_reserva_stock_expiracion_activa'),
    ('INDICE', 'movimiento_inventario', 'ix_movimiento_inventario_lote_feder'),
    ('INDICE', 'movimiento_inventario', 'ix_movimiento_inventario_usuario'),
    ('INDICE', 'movimiento_inventario', 'ix_movimiento_inventario_reserva'),
    ('INDICE', 'movimiento_inventario', 'ix_movimiento_inventario_devolucion'),
    ('INDICE', 'movimiento_inventario', 'ix_movimiento_inventario_tipo'),
    ('INDICE', 'movimiento_inventario', 'ix_movimiento_inventario_fecha'),
    ('INDICE', 'historial_pedido', 'ix_historial_pedido_pedido'),
    ('INDICE', 'historial_pedido', 'ix_historial_pedido_usuario'),
    ('INDICE', 'historial_pedido', 'ix_historial_pedido_fecha'),
    ('INDICE', 'pago', 'ix_pago_pedido'),
    ('INDICE', 'pago', 'ix_pago_estado'),
    ('INDICE', 'pago', 'ix_pago_fecha_pago'),
    ('INDICE', 'factura', 'ix_factura_estado'),
    ('INDICE', 'factura', 'ix_factura_fecha_emision'),
    ('INDICE', 'entrega', 'ix_entrega_estado'),
    ('INDICE', 'entrega', 'ix_entrega_fecha_entrega'),
    ('INDICE', 'notificacion', 'ix_notificacion_usuario'),
    ('INDICE', 'notificacion', 'ix_notificacion_pedido'),
    ('INDICE', 'notificacion', 'ix_notificacion_producto'),
    ('INDICE', 'notificacion', 'ix_notificacion_estado'),
    ('INDICE', 'devolucion', 'ix_devolucion_movimiento_inventario'),
    ('INDICE', 'devolucion', 'ix_devolucion_estado'),
    ('INDICE', 'devolucion', 'ix_devolucion_fecha_solicitud'),
    ('INDICE', 'reembolso', 'ix_reembolso_pago'),
    ('INDICE', 'reembolso', 'ix_reembolso_devolucion'),
    ('INDICE', 'reembolso', 'ix_reembolso_estado'),
    ('DISPARADOR', 'bitacora', 'tg_bitacora_solo_insercion'),
    ('DISPARADOR', 'movimiento_inventario', 'tg_movimiento_inventario_inmutable'),
    ('DISPARADOR', 'rol', 'tg_rol_catalogo_cerrado'),
    ('DISPARADOR', 'producto', 'tg_producto_unidad_inmutable'),
    ('DISPARADOR', 'produccion', 'tg_produccion_integridad'),
    ('DISPARADOR', 'lote_produccion', 'tg_lote_produccion_integridad'),
    ('DISPARADOR', 'compra_feder', 'tg_compra_feder_productor_fijo'),
    ('DISPARADOR', 'detalle_compra', 'tg_detalle_compra_integridad'),
    ('DISPARADOR', 'lote_feder', 'tg_lote_feder_integridad'),
    ('DISPARADOR', 'item_carrito', 'tg_item_carrito_cantidad_entera'),
    ('DISPARADOR', 'pedido', 'tg_pedido_tipo_fijo'),
    ('DISPARADOR', 'detalle_pedido', 'tg_detalle_pedido_integridad'),
    ('DISPARADOR', 'reserva_stock', 'tg_reserva_stock_integridad'),
    ('DISPARADOR', 'movimiento_inventario', 'tg_movimiento_inventario_validar'),
    ('DISPARADOR', 'devolucion', 'tg_devolucion_integridad'),
    ('DISPARADOR', 'pago', 'tg_pago_pedido_fijo'),
    ('DISPARADOR', 'reembolso', 'tg_reembolso_integridad'),
    ('PK', 'mensaje_correo', 'pk_mensaje_correo'),
    ('UQ', 'mensaje_correo', 'uq_mensaje_correo_buzon_uidl'),
    ('CK', 'mensaje_correo', 'ck_mensaje_correo_estado'),
    ('CK', 'mensaje_correo', 'ck_mensaje_correo_resultado'),
    ('INDICE', 'mensaje_correo', 'ix_mensaje_correo_remitente_recibido'),
    ('PK', 'correo_saliente', 'pk_correo_saliente'),
    ('FK', 'correo_saliente', 'fk_correo_saliente_mensaje_correo'),
    ('FK', 'correo_saliente', 'fk_correo_saliente_notificacion'),
    ('UQ', 'correo_saliente', 'uq_correo_saliente_message_id'),
    ('CK', 'correo_saliente', 'ck_correo_saliente_estado'),
    ('CK', 'correo_saliente', 'ck_correo_saliente_adjunto'),
    ('INDICE', 'correo_saliente', 'ix_correo_saliente_mensaje_correo'),
    ('INDICE', 'correo_saliente', 'ix_correo_saliente_notificacion'),
    ('INDICE', 'correo_saliente', 'ix_correo_saliente_pendiente'),
    ('PK', 'confirmacion_operacion', 'pk_confirmacion_operacion'),
    ('FK', 'confirmacion_operacion', 'fk_confirmacion_operacion_usuario'),
    ('UQ', 'confirmacion_operacion', 'uq_confirmacion_operacion_codigo_hash'),
    ('CK', 'confirmacion_operacion', 'ck_confirmacion_operacion_vigencia'),
    ('INDICE', 'confirmacion_operacion', 'ix_confirmacion_operacion_correo_creada'),
    ('INDICE', 'confirmacion_operacion', 'ix_confirmacion_operacion_usuario')
),
existente AS (
    SELECT CASE con.contype WHEN 'p' THEN 'PK' WHEN 'u' THEN 'UQ' WHEN 'c' THEN 'CK' WHEN 'f' THEN 'FK' END AS clase,
           cl.relname::text AS tabla, con.conname::text AS nombre
      FROM pg_constraint con
      JOIN pg_class cl ON cl.oid = con.conrelid
      JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
     WHERE con.contype IN ('p', 'u', 'c', 'f')
    UNION ALL
    SELECT CASE WHEN i.indisunique THEN 'INDICE_UNICO' ELSE 'INDICE' END, ct.relname::text, ci.relname::text
      FROM pg_index i
      JOIN pg_class ci ON ci.oid = i.indexrelid
      JOIN pg_class ct ON ct.oid = i.indrelid
      JOIN pg_namespace n ON n.oid = ct.relnamespace AND n.nspname = 'public'
     WHERE NOT EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.indexrelid)
    UNION ALL
    SELECT 'DISPARADOR', cl.relname::text, t.tgname::text
      FROM pg_trigger t
      JOIN pg_class cl ON cl.oid = t.tgrelid
      JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
     WHERE NOT t.tgisinternal
)
SELECT COALESCE(e.clase, x.clase) AS clase,
       COALESCE(e.tabla, x.tabla) AS tabla,
       COALESCE(e.nombre, x.nombre) AS nombre,
       CASE WHEN x.nombre IS NULL THEN 'declarado en el script y ausente en la base'
            ELSE 'presente en la base y no declarado en el script' END AS problema
  FROM esperado e
  FULL JOIN existente x ON x.clase = e.clase AND x.tabla = e.tabla AND x.nombre = e.nombre
 WHERE e.nombre IS NULL OR x.nombre IS NULL
 ORDER BY 1, 2, 3;
-- [FIN consulta_objetos_declarados]

-- -------------------------------------------------------------------------------------
-- 5. Funciones de apoyo de los disparadores que faltan. Debe devolver CERO filas.
-- -------------------------------------------------------------------------------------
WITH esperado (funcion) AS (VALUES
    ('feder_rechazar'),
    ('feder_exigir_cantidad_entera'),
    ('feder_producto_de_lote_produccion'),
    ('feder_producto_de_lote_feder'),
    ('feder_pedido_de_movimiento'),
    ('tgf_bitacora_solo_insercion'),
    ('tgf_movimiento_inventario_inmutable'),
    ('tgf_rol_catalogo_cerrado'),
    ('tgf_producto_unidad_inmutable'),
    ('tgf_produccion_integridad'),
    ('tgf_lote_produccion_integridad'),
    ('tgf_compra_feder_productor_fijo'),
    ('tgf_detalle_compra_integridad'),
    ('tgf_lote_feder_integridad'),
    ('tgf_item_carrito_cantidad_entera'),
    ('tgf_pedido_tipo_fijo'),
    ('tgf_detalle_pedido_integridad'),
    ('tgf_reserva_stock_integridad'),
    ('tgf_movimiento_inventario_validar'),
    ('tgf_devolucion_integridad'),
    ('tgf_pago_pedido_fijo'),
    ('tgf_reembolso_integridad')
)
SELECT e.funcion AS funcion_ausente
  FROM esperado e
 WHERE NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                    WHERE n.nspname = 'public' AND p.proname = e.funcion)
 ORDER BY 1;

-- -------------------------------------------------------------------------------------
-- 6. Claves foráneas sin índice de apoyo (alguna columna de la clave debe encabezar un índice
--    completo de su tabla). Debe devolver CERO filas.
-- -------------------------------------------------------------------------------------
SELECT cl.relname AS tabla, con.conname AS clave_foranea
  FROM pg_constraint con
  JOIN pg_class cl ON cl.oid = con.conrelid
  JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
 WHERE con.contype = 'f'
   AND NOT EXISTS (
       SELECT 1 FROM pg_index i
        WHERE i.indrelid = con.conrelid AND i.indpred IS NULL
          AND i.indkey[0] = ANY (con.conkey))
 ORDER BY 1, 2;

-- -------------------------------------------------------------------------------------
-- 7. Catálogo cerrado de roles: filas que no corresponden a los cuatro literales de TipoRol, o
--    literales sin fila. Debe devolver CERO filas.
-- -------------------------------------------------------------------------------------
SELECT COALESCE(e.codigo, r.codigo) AS codigo,
       CASE WHEN r.codigo IS NULL THEN 'falta el rol' ELSE 'rol no previsto' END AS problema
  FROM (VALUES ('ADMINISTRADOR'), ('PERSONAL_FEDER'), ('PRODUCTOR'), ('CLIENTE')) AS e (codigo)
  FULL JOIN rol r ON r.codigo = e.codigo
 WHERE e.codigo IS NULL OR r.codigo IS NULL;

-- -------------------------------------------------------------------------------------
-- 8. Resumen de conteos (esperado frente a obtenido)
-- -------------------------------------------------------------------------------------
WITH restricciones AS (
    SELECT con.contype, cl.relname
      FROM pg_constraint con
      JOIN pg_class cl ON cl.oid = con.conrelid
      JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public'
),
indices AS (
    SELECT i.indisunique, EXISTS (SELECT 1 FROM pg_constraint con WHERE con.conindid = i.indexrelid) AS de_restriccion
      FROM pg_index i
      JOIN pg_class ct ON ct.oid = i.indrelid
      JOIN pg_namespace n ON n.oid = ct.relnamespace AND n.nspname = 'public'
),
conteo (orden, concepto, esperado, obtenido) AS (
    SELECT 1, 'tablas del modelo UML', 25,
           (SELECT count(*) FROM information_schema.tables
             WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name IN ('rol', 'usuario', 'usuario_rol', 'bitacora', 'comunidad_agricola', 'categoria', 'producto', 'productor', 'produccion', 'lote_produccion', 'compra_feder', 'detalle_compra', 'lote_feder', 'item_carrito', 'pedido', 'detalle_pedido', 'reserva_stock', 'movimiento_inventario', 'historial_pedido', 'pago', 'factura', 'entrega', 'notificacion', 'devolucion', 'reembolso'))
    UNION ALL
    SELECT 2, 'tablas técnicas del procesador de correo', 3,
           (SELECT count(*) FROM information_schema.tables
             WHERE table_schema = 'public' AND table_type = 'BASE TABLE' AND table_name IN ('mensaje_correo', 'correo_saliente', 'confirmacion_operacion'))
    UNION ALL
    SELECT 3, 'tablas en el esquema public', 28,
           (SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE')
    UNION ALL
    SELECT 4, 'columnas de las tablas del modelo (25 id + 40 FK + 172 atributos)', 237,
           (SELECT count(*) FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name IN ('rol', 'usuario', 'usuario_rol', 'bitacora', 'comunidad_agricola', 'categoria', 'producto', 'productor', 'produccion', 'lote_produccion', 'compra_feder', 'detalle_compra', 'lote_feder', 'item_carrito', 'pedido', 'detalle_pedido', 'reserva_stock', 'movimiento_inventario', 'historial_pedido', 'pago', 'factura', 'entrega', 'notificacion', 'devolucion', 'reembolso'))
    UNION ALL
    SELECT 5, 'columnas de las tablas técnicas', 40,
           (SELECT count(*) FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name IN ('mensaje_correo', 'correo_saliente', 'confirmacion_operacion'))
    UNION ALL
    SELECT 6, 'claves primarias', 28, (SELECT count(*) FROM restricciones WHERE contype = 'p')
    UNION ALL
    SELECT 7, 'claves foráneas (40 asociaciones + 3 de coherencia + 3 técnicas)', 46,
           (SELECT count(*) FROM restricciones WHERE contype = 'f')
    UNION ALL
    SELECT 8, 'restricciones UNIQUE', 32, (SELECT count(*) FROM restricciones WHERE contype = 'u')
    UNION ALL
    SELECT 9, 'restricciones CHECK', 114, (SELECT count(*) FROM restricciones WHERE contype = 'c')
    UNION ALL
    SELECT 10, 'índices únicos adicionales (parciales o de expresión)', 8,
           (SELECT count(*) FROM indices WHERE indisunique AND NOT de_restriccion)
    UNION ALL
    SELECT 11, 'índices de apoyo no únicos', 68,
           (SELECT count(*) FROM indices WHERE NOT indisunique AND NOT de_restriccion)
    UNION ALL
    SELECT 12, 'disparadores', 17,
           (SELECT count(*) FROM pg_trigger t JOIN pg_class cl ON cl.oid = t.tgrelid
              JOIN pg_namespace n ON n.oid = cl.relnamespace AND n.nspname = 'public' WHERE NOT t.tgisinternal)
    UNION ALL
    SELECT 13, 'funciones (apoyo y de disparador)', 22,
           (SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'public')
    UNION ALL
    SELECT 14, 'filas del catálogo de roles', 4, (SELECT count(*) FROM rol)
)
SELECT concepto, esperado, obtenido,
       CASE WHEN esperado = obtenido THEN 'OK' ELSE 'REVISAR' END AS resultado
  FROM conteo
 ORDER BY orden;
