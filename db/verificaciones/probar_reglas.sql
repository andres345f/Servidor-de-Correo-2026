-- =====================================================================================
-- FEDER · probar_reglas.sql
-- Batería de casos que comprueba, sobre los datos cargados por 02_seed.sql, que la base acepta o
-- rechaza cada operación con el SQLSTATE y el nombre de restricción (ck_, uq_, fk_) o de regla (rg_)
-- esperados. Cubre los CHECK, las unicidades, las claves foráneas y todos los disparadores.
--
-- Requiere una base recién cargada con 01_schema.sql y 02_seed.sql (usa sus identificadores).
-- Todo se ejecuta dentro de una transacción que termina en ROLLBACK: no deja ningún cambio.
-- Resultado esperado: la columna «fallidos» en 0 y cero filas en el listado final.
--
-- Nota: en PostgreSQL 18 el borrado impedido por ON DELETE RESTRICT devuelve SQLSTATE 23001;
-- en versiones anteriores devuelve 23503. Los tres casos de borrado restringido esperan 23001.
--
-- Ejecución:
--   psql -h 127.0.0.1 -p 55432 -U feder_admin -d feder_test -f db/verificaciones/probar_reglas.sql
-- =====================================================================================

SET client_encoding = 'UTF8';

BEGIN;

CREATE TEMP TABLE resultado (n serial, caso text, esperado text, obtenido text, ok boolean) ON COMMIT DROP;

CREATE FUNCTION pg_temp.probar(p_caso text, p_sql text, p_estado text, p_restriccion text DEFAULT NULL)
RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    v_estado text; v_restriccion text; v_mensaje text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        v_estado := 'OK';
    EXCEPTION WHEN OTHERS THEN
        GET STACKED DIAGNOSTICS v_estado = RETURNED_SQLSTATE, v_restriccion = CONSTRAINT_NAME, v_mensaje = MESSAGE_TEXT;
    END;
    INSERT INTO resultado (caso, esperado, obtenido, ok)
    VALUES (p_caso, p_estado || COALESCE(' ' || p_restriccion, ''),
            v_estado || COALESCE(' ' || NULLIF(v_restriccion, ''), '') || COALESCE(' :: ' || v_mensaje, ''),
            v_estado = p_estado AND (p_restriccion IS NULL OR v_restriccion = p_restriccion));
END $$;

-- Las pruebas que deben tener éxito se revierten solas con un savepoint propio.
CREATE FUNCTION pg_temp.probar_ok(p_caso text, p_sql text) RETURNS void LANGUAGE plpgsql AS $$
DECLARE v_estado text := 'OK'; v_mensaje text;
BEGIN
    BEGIN
        EXECUTE p_sql;
        RAISE EXCEPTION 'revertir' USING ERRCODE = 'ZZ999';
    EXCEPTION WHEN SQLSTATE 'ZZ999' THEN NULL;
              WHEN OTHERS THEN GET STACKED DIAGNOSTICS v_estado = RETURNED_SQLSTATE, v_mensaje = MESSAGE_TEXT;
    END;
    INSERT INTO resultado (caso, esperado, obtenido, ok) VALUES (p_caso, 'OK', v_estado || COALESCE(' :: ' || v_mensaje, ''), v_estado = 'OK');
END $$;

DO $casos$
BEGIN
    PERFORM pg_temp.probar('bitacora update', $$UPDATE bitacora SET entidad = 'X' WHERE id = 1$$, 'P0001', 'rg_bitacora_solo_insercion');
    PERFORM pg_temp.probar('bitacora delete', $$DELETE FROM bitacora WHERE id = 1$$, 'P0001', 'rg_bitacora_solo_insercion');
    PERFORM pg_temp.probar('movimiento update', $$UPDATE movimiento_inventario SET cantidad = 1 WHERE id = 1$$, 'P0001', 'rg_movimiento_inventario_inmutable');
    PERFORM pg_temp.probar('movimiento delete', $$DELETE FROM movimiento_inventario WHERE id = 27$$, 'P0001', 'rg_movimiento_inventario_inmutable');
    PERFORM pg_temp.probar('rol delete', $$DELETE FROM rol WHERE codigo = 'CLIENTE'$$, 'P0001', 'rg_rol_catalogo_cerrado');
    PERFORM pg_temp.probar('rol recodificar', $$UPDATE rol SET codigo = 'PRODUCTOR' WHERE codigo = 'CLIENTE'$$, 'P0001', 'rg_rol_catalogo_cerrado');
    PERFORM pg_temp.probar_ok('rol cambiar nombre', $$UPDATE rol SET nombre = 'Clientela' WHERE codigo = 'CLIENTE'$$);
    PERFORM pg_temp.probar('rol duplicado', $$INSERT INTO rol (codigo, nombre) VALUES ('CLIENTE', 'x')$$, '23505', 'uq_rol_codigo');
    PERFORM pg_temp.probar('rol quinto literal', $$INSERT INTO rol (codigo, nombre) VALUES ('AUDITOR', 'x')$$, '23514', 'ck_rol_codigo');

    PERFORM pg_temp.probar('producto unidad con referencias', $$UPDATE producto SET unidad_medida = 'KILOGRAMO' WHERE id = 1$$, 'P0001', 'rg_producto_unidad_inmutable');
    PERFORM pg_temp.probar('producto presentacion con referencias', $$UPDATE producto SET presentacion = 'Otra' WHERE id = 2$$, 'P0001', 'rg_producto_unidad_inmutable');
    PERFORM pg_temp.probar_ok('producto presentacion sin referencias', $$UPDATE producto SET presentacion = 'Frasco de 300 g' WHERE id = 11$$);
    PERFORM pg_temp.probar_ok('producto precio con referencias', $$UPDATE producto SET precio_venta = 26 WHERE id = 1$$);
    PERFORM pg_temp.probar('producto a UNIDAD con carrito decimal', $$
    WITH p AS (INSERT INTO producto (categoria_id, codigo, nombre, presentacion, unidad_medida, precio_venta) VALUES (1, 'X-KG', 'x', 'granel', 'KILOGRAMO', 5) RETURNING id),
         i AS (INSERT INTO item_carrito (cliente_id, producto_id, cantidad) SELECT 12, id, 0.5 FROM p RETURNING producto_id)
    SELECT 1 FROM i; UPDATE producto SET unidad_medida = 'UNIDAD' WHERE codigo = 'X-KG'$$, 'P0001', 'rg_producto_unidad_inmutable');
    PERFORM pg_temp.probar('producto stock_minimo decimal UNIDAD', $$UPDATE producto SET stock_minimo = 2.5 WHERE id = 1$$, '23514', 'ck_producto_stock_minimo_entero');
    PERFORM pg_temp.probar_ok('producto stock_minimo decimal KILOGRAMO', $$UPDATE producto SET stock_minimo = 2.5 WHERE id = 5$$);

    PERFORM pg_temp.probar('produccion fecha_inicio a nulo', $$UPDATE produccion SET fecha_inicio = NULL, fecha_disponibilidad_estimada = NULL WHERE id = 1$$, 'P0001', 'rg_produccion_fecha_inicio_no_vacia');
    PERFORM pg_temp.probar('produccion comunidad origen', $$UPDATE produccion SET comunidad_origen_id = 2 WHERE id = 1$$, 'P0001', 'rg_produccion_comunidad_origen_fija');
    PERFORM pg_temp.probar('produccion producto con lotes', $$UPDATE produccion SET producto_id = 2 WHERE id = 1$$, 'P0001', 'rg_produccion_productor_producto_fijos');
    PERFORM pg_temp.probar('produccion productor con respaldo', $$UPDATE produccion SET productor_id = 2 WHERE id = 11$$, 'P0001', 'rg_produccion_productor_producto_fijos');
    PERFORM pg_temp.probar_ok('produccion producto sin lotes ni respaldos', $$UPDATE produccion SET producto_id = 1 WHERE id = 12$$);
    PERFORM pg_temp.probar('produccion inicio posterior a elaboracion', $$UPDATE produccion SET fecha_inicio = '2026-03-03' WHERE id = 1$$, 'P0001', 'rg_lote_produccion_elaboracion_desde_inicio');
    PERFORM pg_temp.probar('produccion cantidad decimal UNIDAD', $$UPDATE produccion SET cantidad_estimada = 10.5 WHERE id = 12$$, 'P0001', 'rg_produccion_cantidad_entera');
    PERFORM pg_temp.probar_ok('produccion cantidad decimal LIBRA', $$UPDATE produccion SET cantidad_estimada = 10.5 WHERE id = 9$$);
    PERFORM pg_temp.probar('produccion sin inicio y no terminada', $$INSERT INTO produccion (productor_id, producto_id, comunidad_origen_id, codigo, estado, nombre_comunidad_origen, municipio_origen, departamento_origen) VALUES (1, 1, 1, 'X1', 'PLANIFICADA', 'a', 'b', 'c')$$, '23514', 'ck_produccion_fecha_inicio');

    PERFORM pg_temp.probar('lote elaboracion anterior al inicio', $$INSERT INTO lote_produccion (produccion_id, codigo_lote, fecha_elaboracion, cantidad_producida, cantidad_ofrecida, estado) VALUES (11, 'X-L1', '2026-10-01', 10, 10, 'DISPONIBLE')$$, 'P0001', 'rg_lote_produccion_elaboracion_desde_inicio');
    PERFORM pg_temp.probar('lote reasignar produccion', $$UPDATE lote_produccion SET produccion_id = 2 WHERE id = 1$$, 'P0001', 'rg_lote_produccion_produccion_fija');
    PERFORM pg_temp.probar('lote cambiar codigo', $$UPDATE lote_produccion SET codigo_lote = 'OTRO' WHERE id = 1$$, 'P0001', 'rg_lote_produccion_codigo_fijo');
    PERFORM pg_temp.probar('lote cantidad decimal UNIDAD', $$UPDATE lote_produccion SET cantidad_ofrecida = 249.5 WHERE id = 1$$, 'P0001', 'rg_lote_produccion_cantidad_entera');
    PERFORM pg_temp.probar_ok('lote cantidad decimal KILOGRAMO', $$UPDATE lote_produccion SET cantidad_ofrecida = 60.5 WHERE id = 4$$);
    PERFORM pg_temp.probar('lote vencimiento sin elaboracion', $$INSERT INTO lote_produccion (produccion_id, codigo_lote, fecha_vencimiento, cantidad_producida, cantidad_ofrecida, estado) VALUES (11, 'X-L2', '2027-01-01', 10, 10, 'DISPONIBLE')$$, '23514', 'ck_lote_produccion_vencimiento_con_elaboracion');
    PERFORM pg_temp.probar('lote ofrecida mayor que producida', $$UPDATE lote_produccion SET cantidad_ofrecida = 500 WHERE id = 1$$, '23514', 'ck_lote_produccion_cantidad_ofrecida');

    PERFORM pg_temp.probar('compra cambiar productor', $$UPDATE compra_feder SET productor_id = 2 WHERE id = 5$$, 'P0001', 'rg_compra_feder_productor_fijo');
    PERFORM pg_temp.probar('compra pago sin estar RECIBIDA', $$UPDATE compra_feder SET fecha_pago_productor = now() WHERE id = 3$$, '23514', 'ck_compra_feder_pago_solo_recibida');
    PERFORM pg_temp.probar('detalle compra de otro productor', $$INSERT INTO detalle_compra (compra_id, lote_origen_id, cantidad_comprada, precio_unitario) VALUES (5, 5, 10, 20)$$, 'P0001', 'rg_detalle_compra_un_solo_productor');
    PERFORM pg_temp.probar('detalle compra lote repetido', $$INSERT INTO detalle_compra (compra_id, lote_origen_id, cantidad_comprada, precio_unitario) VALUES (5, 11, 5, 60)$$, '23505', 'uq_detalle_compra_compra_lote_origen');
    PERFORM pg_temp.probar('detalle compra cambiar lote', $$UPDATE detalle_compra SET lote_origen_id = 1 WHERE id = 10$$, 'P0001', 'rg_detalle_compra_origen_fijo');
    PERFORM pg_temp.probar('detalle compra precio tras pago', $$UPDATE detalle_compra SET precio_unitario = 16 WHERE id = 1$$, 'P0001', 'rg_detalle_compra_precio_fijo');
    PERFORM pg_temp.probar('detalle compra precio con lote', $$UPDATE detalle_compra SET precio_unitario = 18 WHERE id = 7$$, 'P0001', 'rg_detalle_compra_precio_fijo');
    PERFORM pg_temp.probar_ok('detalle compra precio sin aceptacion ni pago', $$UPDATE detalle_compra SET precio_unitario = 61 WHERE id = 10$$);
    PERFORM pg_temp.probar('detalle compra precio con recepcion en bitacora', $$
    INSERT INTO bitacora (accion, entidad, entidad_id, detalle) VALUES ('MODIFICAR', 'DETALLE_COMPRA', 10, '{"clave_operacion": "k-x1", "operacion": "RECEPCION", "compra_id": 5, "cantidad_aceptada": 3}');
    UPDATE detalle_compra SET precio_unitario = 61 WHERE id = 10$$, 'P0001', 'rg_detalle_compra_precio_fijo');
    PERFORM pg_temp.probar('detalle compra cantidades tras pago', $$UPDATE detalle_compra SET cantidad_recibida = 199, cantidad_rechazada = 1 WHERE id = 1$$, 'P0001', 'rg_detalle_compra_cantidades_fijas_tras_pago');
    PERFORM pg_temp.probar('detalle compra cantidad decimal UNIDAD', $$UPDATE detalle_compra SET cantidad_recibida = 80.5 WHERE id = 7$$, 'P0001', 'rg_detalle_compra_cantidad_entera');
    PERFORM pg_temp.probar('detalle compra excede lo comprado', $$UPDATE detalle_compra SET cantidad_recibida = 111 WHERE id = 7$$, '23514', 'ck_detalle_compra_cantidades_resueltas');
    PERFORM pg_temp.probar_ok('detalle compra recepcion condicional', $$UPDATE detalle_compra SET cantidad_recibida = cantidad_recibida + 30 WHERE id = 7 AND cantidad_recibida + cantidad_rechazada + cantidad_cancelada + 30 <= cantidad_comprada$$);

    PERFORM pg_temp.probar('lote feder cambiar detalle', $$UPDATE lote_feder SET detalle_compra_id = 9 WHERE id = 1$$, 'P0001', 'rg_lote_feder_detalle_compra_fijo');
    PERFORM pg_temp.probar('lote feder segundo lote del detalle', $$INSERT INTO lote_feder (detalle_compra_id, codigo, fecha_ingreso, cantidad_disponible, estado) VALUES (1, 'X-LF', now(), 1, 'DISPONIBLE')$$, '23505', 'uq_lote_feder_detalle_compra');
    PERFORM pg_temp.probar('lote feder cantidad decimal UNIDAD', $$UPDATE lote_feder SET cantidad_disponible = 182.5 WHERE id = 1$$, 'P0001', 'rg_lote_feder_cantidad_entera');
    PERFORM pg_temp.probar_ok('lote feder cantidad decimal KILOGRAMO', $$UPDATE lote_feder SET cantidad_disponible = 45.5 WHERE id = 4$$);
    PERFORM pg_temp.probar('lote feder saldo 0 sin AGOTADO', $$UPDATE lote_feder SET cantidad_disponible = 0 WHERE id = 2$$, '23514', 'ck_lote_feder_agotado_sin_saldo');
    PERFORM pg_temp.probar_ok('lote feder saldo 0 con AGOTADO', $$UPDATE lote_feder SET cantidad_disponible = 0, estado = 'AGOTADO' WHERE id = 2$$);
    PERFORM pg_temp.probar('lote feder AGOTADO con saldo', $$UPDATE lote_feder SET estado = 'AGOTADO' WHERE id = 2$$, '23514', 'ck_lote_feder_agotado_sin_saldo');
    PERFORM pg_temp.probar_ok('lote feder BLOQUEADO con saldo', $$UPDATE lote_feder SET estado = 'BLOQUEADO' WHERE id = 2$$);
    PERFORM pg_temp.probar('lote feder reservado mayor que saldo', $$UPDATE lote_feder SET cantidad_reservada = 500 WHERE id = 2$$, '23514', 'ck_lote_feder_cantidad_reservada');
    PERFORM pg_temp.probar_ok('lote feder reserva atomica', $$UPDATE lote_feder SET cantidad_reservada = cantidad_reservada + 5 WHERE id = 2 AND estado = 'DISPONIBLE' AND cantidad_disponible - cantidad_reservada >= 5$$);

    PERFORM pg_temp.probar('carrito cantidad decimal UNIDAD', $$INSERT INTO item_carrito (cliente_id, producto_id, cantidad) VALUES (12, 1, 1.5)$$, 'P0001', 'rg_item_carrito_cantidad_entera');
    PERFORM pg_temp.probar_ok('carrito cantidad decimal KILOGRAMO', $$INSERT INTO item_carrito (cliente_id, producto_id, cantidad) VALUES (12, 5, 1.5)$$);
    PERFORM pg_temp.probar('carrito producto repetido', $$INSERT INTO item_carrito (cliente_id, producto_id, cantidad) VALUES (11, 3, 1)$$, '23505', 'uq_item_carrito_cliente_producto');
    PERFORM pg_temp.probar('carrito update decimal UNIDAD', $$UPDATE item_carrito SET cantidad = 2.5 WHERE id = 1$$, 'P0001', 'rg_item_carrito_cantidad_entera');

    PERFORM pg_temp.probar('pedido cambiar tipo', $$UPDATE pedido SET tipo = 'ENCARGO' WHERE id = 11$$, 'P0001', 'rg_pedido_tipo_fijo');
    PERFORM pg_temp.probar('pedido espera en INMEDIATO', $$UPDATE pedido SET estado = 'ESPERANDO_ABASTECIMIENTO' WHERE id = 11$$, '23514', 'ck_pedido_espera_solo_encargo');
    PERFORM pg_temp.probar('pedido fecha estimada en INMEDIATO', $$UPDATE pedido SET fecha_estimada_disponibilidad = '2026-12-01' WHERE id = 11$$, '23514', 'ck_pedido_fecha_estimada_solo_encargo');
    PERFORM pg_temp.probar('pedido total incoherente', $$UPDATE pedido SET total = 150 WHERE id = 11$$, '23514', 'ck_pedido_total');
    PERFORM pg_temp.probar('pedido clave repetida', $$INSERT INTO pedido (usuario_id, codigo, tipo, estado, subtotal, total, clave_idempotencia) VALUES (11, 'X-P', 'INMEDIATO', 'PENDIENTE', 1, 1, 'conf-20260406-11-7c1e4a')$$, '23505', 'uq_pedido_clave_idempotencia');
    PERFORM pg_temp.probar('pedido facturado cambia total', $$UPDATE pedido SET costo_envio = 20, total = 224 WHERE id = 1$$, '23503', 'fk_factura_pedido_total');
    PERFORM pg_temp.probar_ok('pedido cambiar estado', $$UPDATE pedido SET estado = 'EN_PREPARACION' WHERE id = 10$$);

    PERFORM pg_temp.probar('linea modificar cantidad', $$UPDATE detalle_pedido SET cantidad = 5, subtotal = 120 WHERE id = 1$$, 'P0001', 'rg_detalle_pedido_datos_fijos');
    PERFORM pg_temp.probar('linea modificar descripcion', $$UPDATE detalle_pedido SET descripcion_venta = 'x' WHERE id = 1$$, 'P0001', 'rg_detalle_pedido_datos_fijos');
    PERFORM pg_temp.probar('linea subtotal incoherente', $$INSERT INTO detalle_pedido (pedido_id, producto_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES (11, 1, 2, 25, 49.99, 'x')$$, '23514', 'ck_detalle_pedido_subtotal');
    PERFORM pg_temp.probar('linea cantidad decimal UNIDAD', $$INSERT INTO detalle_pedido (pedido_id, producto_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES (11, 1, 1.5, 25, 37.50, 'x')$$, 'P0001', 'rg_detalle_pedido_cantidad_entera');
    PERFORM pg_temp.probar_ok('linea redondeo half-up', $$INSERT INTO detalle_pedido (pedido_id, producto_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES (11, 5, 0.375, 70.02, 26.26, 'x')$$);
    PERFORM pg_temp.probar('linea producto repetido', $$INSERT INTO detalle_pedido (pedido_id, producto_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES (11, 4, 1, 45, 45, 'x')$$, '23505', 'uq_detalle_pedido_pedido_producto');
    PERFORM pg_temp.probar('linea respaldo en INMEDIATO', $$INSERT INTO detalle_pedido (pedido_id, producto_id, produccion_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES (11, 2, 11, 1, 90, 90, 'x')$$, 'P0001', 'rg_detalle_pedido_respaldo_solo_encargo');
    PERFORM pg_temp.probar('linea respaldo de otro producto', $$INSERT INTO detalle_pedido (pedido_id, producto_id, produccion_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES (8, 9, 11, 1, 28, 28, 'x')$$, 'P0001', 'rg_detalle_pedido_respaldo_mismo_producto');
    PERFORM pg_temp.probar_ok('linea encargo con respaldo valido', $$INSERT INTO detalle_pedido (pedido_id, producto_id, produccion_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES (8, 9, 12, 1, 28, 28, 'x')$$);

    PERFORM pg_temp.probar('reserva de otro producto', $$INSERT INTO reserva_stock (lote_feder_id, detalle_pedido_id, cantidad, estado) VALUES (1, 20, 1, 'ACTIVA')$$, 'P0001', 'rg_reserva_stock_mismo_producto');
    PERFORM pg_temp.probar('reserva cambiar cantidad', $$UPDATE reserva_stock SET cantidad = 3 WHERE id = 17$$, 'P0001', 'rg_reserva_stock_datos_fijos');
    PERFORM pg_temp.probar('reserva cerrar sin fecha', $$UPDATE reserva_stock SET estado = 'LIBERADA' WHERE id = 17$$, '23514', 'ck_reserva_stock_fecha_cierre');
    PERFORM pg_temp.probar_ok('reserva liberacion unica', $$UPDATE reserva_stock SET estado = 'LIBERADA', fecha_cierre = now() WHERE id = 17 AND estado = 'ACTIVA'$$);
    PERFORM pg_temp.probar('reserva decimal UNIDAD', $$INSERT INTO reserva_stock (lote_feder_id, detalle_pedido_id, cantidad, estado) VALUES (3, 20, 0.5, 'ACTIVA')$$, 'P0001', 'rg_reserva_stock_cantidad_entera');

    PERFORM pg_temp.probar('mov cantidad cero', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'ENTRADA_COMPRA', 0, 'k1')$$, '23514', 'ck_movimiento_inventario_cantidad');
    PERFORM pg_temp.probar('mov cantidad negativa', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'ENTRADA_COMPRA', -3, 'k1')$$, '23514', 'ck_movimiento_inventario_cantidad');
    PERFORM pg_temp.probar('mov SALIDA_VENTA sin reserva', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'SALIDA_VENTA', 1, 'k2')$$, '23514', 'ck_movimiento_inventario_reserva_segun_tipo');
    PERFORM pg_temp.probar('mov SALIDA_VENTA otra cantidad', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, clave_operacion) VALUES (7, 2, 17, 'SALIDA_VENTA', 5, 'k3')$$, 'P0001', 'rg_movimiento_inventario_salida_venta_cantidad');
    PERFORM pg_temp.probar('mov SALIDA_VENTA otro lote', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 17, 'SALIDA_VENTA', 6, 'k4')$$, '23503', 'fk_movimiento_inventario_reserva_lote');
    PERFORM pg_temp.probar_ok('mov SALIDA_VENTA valida', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, clave_operacion) VALUES (7, 2, 17, 'SALIDA_VENTA', 6, 'k4b')$$);
    PERFORM pg_temp.probar('mov segunda SALIDA_VENTA de la reserva', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 1, 'SALIDA_VENTA', 4, 'k5')$$, '23505', 'uq_movimiento_inventario_salida_venta_reserva');
    PERFORM pg_temp.probar('mov SALIDA_VENTA reserva liberada', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, clave_operacion) VALUES (5, 2, 6, 'SALIDA_VENTA', 2, 'k6')$$, 'P0001', 'rg_movimiento_inventario_salida_venta_reserva');
    PERFORM pg_temp.probar('mov retorno con reserva activa', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, clave_operacion) VALUES (7, 2, 17, 'ENTRADA_RETORNO_ENTREGA', 1, 'k7')$$, 'P0001', 'rg_movimiento_inventario_retorno_reserva_consumida');
    PERFORM pg_temp.probar_ok('mov retorno con reserva consumida', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, reserva_id, tipo, cantidad, clave_operacion) VALUES (6, 2, 15, 'ENTRADA_RETORNO_ENTREGA', 4, 'k7b')$$);
    PERFORM pg_temp.probar('mov ENTRADA_DEVOLUCION otro lote', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, devolucion_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 2, 'ENTRADA_DEVOLUCION', 1, 'k8')$$, 'P0001', 'rg_movimiento_inventario_devolucion_mismo_lote');
    PERFORM pg_temp.probar('mov ENTRADA_DEVOLUCION sin devolucion', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'ENTRADA_DEVOLUCION', 1, 'k9')$$, '23514', 'ck_movimiento_inventario_devolucion_segun_tipo');
    PERFORM pg_temp.probar('mov ENTRADA_COMPRA con devolucion', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, devolucion_id, tipo, cantidad, clave_operacion) VALUES (7, 2, 2, 'ENTRADA_COMPRA', 1, 'k10')$$, '23514', 'ck_movimiento_inventario_devolucion_segun_tipo');
    PERFORM pg_temp.probar('mov ajuste sin motivo', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'AJUSTE_NEGATIVO', 1, 'k11')$$, '23514', 'ck_movimiento_inventario_motivo_segun_tipo');
    PERFORM pg_temp.probar('mov baja con motivo en blanco', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (1, 2, 'BAJA_DANADO', 1, '  ', 'k12')$$, '23514', 'ck_movimiento_inventario_motivo_segun_tipo');
    PERFORM pg_temp.probar('mov clave repetida', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'ENTRADA_COMPRA', 1, 'op-rec-cmp001-dc01-01')$$, '23505', 'uq_movimiento_inventario_clave_operacion');
    PERFORM pg_temp.probar('mov decimal en lote por UNIDAD', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'ENTRADA_COMPRA', 1.5, 'k13')$$, 'P0001', 'rg_movimiento_inventario_cantidad_entera');
    PERFORM pg_temp.probar_ok('mov decimal en lote por KILOGRAMO', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (4, 2, 'AJUSTE_NEGATIVO', 0.125, 'merma', 'k14')$$);
    PERFORM pg_temp.probar('mov tipo inexistente', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, clave_operacion) VALUES (1, 2, 'REGALO', 1, 'k15')$$, '23514', NULL);
    PERFORM pg_temp.probar('mov AJUSTE_POSITIVO por personal', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (1, 2, 'AJUSTE_POSITIVO', 1, 'conteo', 'k16')$$, 'P0001', 'rg_movimiento_inventario_ajuste_positivo_autor');
    PERFORM pg_temp.probar('mov AJUSTE_POSITIVO con compra pendiente', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (7, 1, 'AJUSTE_POSITIVO', 1, 'conteo', 'k17')$$, 'P0001', 'rg_movimiento_inventario_ajuste_positivo_pendiente');
    PERFORM pg_temp.probar('mov AJUSTE_POSITIVO sobre el limite', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (1, 1, 'AJUSTE_POSITIVO', 4, 'conteo', 'k18')$$, 'P0001', 'rg_movimiento_inventario_ajuste_positivo_limite');
    PERFORM pg_temp.probar_ok('mov AJUSTE_POSITIVO dentro del limite', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (1, 1, 'AJUSTE_POSITIVO', 3, 'conteo', 'k19')$$);
    PERFORM pg_temp.probar('mov AJUSTE_POSITIVO acumulado sobre el limite', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (1, 1, 'AJUSTE_POSITIVO', 2, 'conteo', 'k20'); INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (1, 1, 'AJUSTE_POSITIVO', 2, 'conteo', 'k21')$$, 'P0001', 'rg_movimiento_inventario_ajuste_positivo_limite');
    PERFORM pg_temp.probar('mov AJUSTE_POSITIVO sin baja previa', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, tipo, cantidad, motivo, clave_operacion) VALUES (2, 1, 'AJUSTE_POSITIVO', 1, 'conteo', 'k22')$$, 'P0001', 'rg_movimiento_inventario_ajuste_positivo_limite');

    PERFORM pg_temp.probar('historial transicion invalida', $$INSERT INTO historial_pedido (pedido_id, estado_anterior, estado_nuevo) VALUES (1, 'ENTREGADO', 'PENDIENTE')$$, '23514', 'ck_historial_pedido_transicion');
    PERFORM pg_temp.probar('historial creacion distinta de PENDIENTE', $$INSERT INTO historial_pedido (pedido_id, estado_anterior, estado_nuevo) VALUES (1, NULL, 'CONFIRMADO')$$, '23514', 'ck_historial_pedido_transicion');
    PERFORM pg_temp.probar('historial cancelacion sin comentario', $$INSERT INTO historial_pedido (pedido_id, estado_anterior, estado_nuevo) VALUES (10, 'CONFIRMADO', 'CANCELADO')$$, '23514', 'ck_historial_pedido_comentario_cancelacion');
    PERFORM pg_temp.probar_ok('historial transicion valida', $$INSERT INTO historial_pedido (pedido_id, usuario_id, estado_anterior, estado_nuevo) VALUES (10, 2, 'CONFIRMADO', 'EN_PREPARACION')$$);

    PERFORM pg_temp.probar('pago segundo pendiente', $$INSERT INTO pago (pedido_id, metodo, monto, estado, clave_idempotencia) VALUES (11, 'QR', 143, 'PENDIENTE', 'kp1')$$, '23505', 'uq_pago_pendiente_por_pedido');
    PERFORM pg_temp.probar('pago PAGADO sin fecha', $$UPDATE pago SET estado = 'PAGADO' WHERE id = 11$$, '23514', 'ck_pago_fecha_pago');
    PERFORM pg_temp.probar('pago RECHAZADO con fecha', $$UPDATE pago SET estado = 'RECHAZADO', fecha_pago = now() WHERE id = 11$$, '23514', 'ck_pago_fecha_pago');
    PERFORM pg_temp.probar_ok('pago transicion condicional', $$UPDATE pago SET estado = 'PAGADO', fecha_pago = now(), referencia_externa = 'QR-X' WHERE id = 11 AND estado = 'PENDIENTE'$$);
    PERFORM pg_temp.probar('pago referencia repetida', $$UPDATE pago SET referencia_externa = 'QR-20260406-000184' WHERE id = 11$$, '23505', 'uq_pago_referencia_externa');
    PERFORM pg_temp.probar('pago monto cero', $$INSERT INTO pago (pedido_id, metodo, monto, estado, clave_idempotencia) VALUES (8, 'QR', 0, 'PENDIENTE', 'kp2')$$, '23514', 'ck_pago_monto');

    PERFORM pg_temp.probar('factura total distinto del pedido', $$INSERT INTO factura (pedido_id, numero, fecha_emision, nit_ci, razon_social, total, estado) VALUES (11, 'X-F1', now(), '1', 'x', 100, 'VIGENTE')$$, '23503', 'fk_factura_pedido_total');
    PERFORM pg_temp.probar('factura segunda del pedido', $$INSERT INTO factura (pedido_id, numero, fecha_emision, nit_ci, razon_social, total, estado) VALUES (1, 'X-F2', now(), '1', 'x', 219, 'VIGENTE')$$, '23505', 'uq_factura_pedido');
    PERFORM pg_temp.probar('factura ANULADA sin fecha', $$UPDATE factura SET estado = 'ANULADA' WHERE id = 1$$, '23514', 'ck_factura_fecha_anulacion');
    PERFORM pg_temp.probar_ok('factura valida', $$INSERT INTO factura (pedido_id, numero, fecha_emision, nit_ci, razon_social, total, estado) VALUES (11, 'X-F3', now(), '1', 'x', 143.00, 'VIGENTE')$$);

    PERFORM pg_temp.probar('entrega segunda del pedido', $$INSERT INTO entrega (pedido_id, modalidad, estado, nombre_receptor, telefono_contacto) VALUES (1, 'RECOJO', 'PENDIENTE', 'x', '1')$$, '23505', 'uq_entrega_pedido');
    PERFORM pg_temp.probar('entrega delivery sin direccion', $$UPDATE entrega SET modalidad = 'DELIVERY' WHERE id = 10$$, '23514', 'ck_entrega_direccion_delivery');
    PERFORM pg_temp.probar('entrega recojo en camino', $$UPDATE entrega SET estado = 'EN_CAMINO' WHERE id = 10$$, '23514', 'ck_entrega_estado_segun_modalidad');
    PERFORM pg_temp.probar('entrega ENTREGADA sin fecha', $$UPDATE entrega SET estado = 'ENTREGADA' WHERE id = 9$$, '23514', 'ck_entrega_fecha_entrega');
    PERFORM pg_temp.probar_ok('entrega transicion condicional', $$UPDATE entrega SET estado = 'ENTREGADA', fecha_entrega = now() WHERE id = 9 AND estado = 'EN_CAMINO'$$);

    PERFORM pg_temp.probar('aviso alerta sin producto', $$INSERT INTO notificacion (usuario_id, tipo, canal, titulo, mensaje, estado) VALUES (2, 'ALERTA_STOCK', 'SISTEMA', 't', 'm', 'PENDIENTE')$$, '23514', 'ck_notificacion_referencia_segun_tipo');
    PERFORM pg_temp.probar('aviso de pedido sin pedido', $$INSERT INTO notificacion (usuario_id, tipo, canal, titulo, mensaje, estado) VALUES (11, 'PAGO_CONFIRMADO', 'CORREO', 't', 'm', 'PENDIENTE')$$, '23514', 'ck_notificacion_referencia_segun_tipo');
    PERFORM pg_temp.probar('aviso ENVIADA sin fecha', $$UPDATE notificacion SET estado = 'ENVIADA' WHERE id = 21$$, '23514', 'ck_notificacion_fecha_envio');

    PERFORM pg_temp.probar('devolucion sobre una entrada', $$INSERT INTO devolucion (movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (1, 'X-D1', 1, 'm', 'SOLICITADA')$$, 'P0001', 'rg_devolucion_despacho_valido');
    PERFORM pg_temp.probar('devolucion de pedido no entregado', $$INSERT INTO devolucion (movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (26, 'X-D2', 1, 'm', 'SOLICITADA')$$, 'P0001', 'rg_devolucion_pedido_entregado');
    PERFORM pg_temp.probar('devolucion decimal UNIDAD', $$INSERT INTO devolucion (movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (20, 'X-D3', 0.5, 'm', 'SOLICITADA')$$, 'P0001', 'rg_devolucion_cantidad_entera');
    PERFORM pg_temp.probar_ok('devolucion decimal KILOGRAMO', $$INSERT INTO devolucion (movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (24, 'X-D4', 0.5, 'm', 'SOLICITADA')$$);
    PERFORM pg_temp.probar_ok('devolucion sobre una reposicion', $$INSERT INTO devolucion (movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (16, 'X-D5', 1, 'm', 'SOLICITADA')$$);
    PERFORM pg_temp.probar('devolucion codigo repetido', $$INSERT INTO devolucion (movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (20, 'DEV-2026-0001', 1, 'm', 'SOLICITADA')$$, '23505', 'uq_devolucion_codigo');
    PERFORM pg_temp.probar('devolucion aprobar sin datos', $$UPDATE devolucion SET estado = 'APROBADA' WHERE id = 3$$, '23514', NULL);
    PERFORM pg_temp.probar_ok('devolucion aprobar con reembolso', $$UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REEMBOLSO', cantidad_aprobada = 1, importe_reembolsable = 24.00, fecha_resolucion = now() WHERE id = 3 AND estado = 'SOLICITADA'$$);
    PERFORM pg_temp.probar('devolucion reposicion con importe', $$UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REPOSICION', cantidad_aprobada = 1, importe_reembolsable = 24.00, fecha_resolucion = now() WHERE id = 3$$, '23514', 'ck_devolucion_importe_segun_resolucion');
    PERFORM pg_temp.probar('devolucion cambiar aprobada tras aprobar', $$UPDATE devolucion SET cantidad_aprobada = 1 WHERE id = 2$$, 'P0001', 'rg_devolucion_datos_fijos_desde_aprobacion');
    PERFORM pg_temp.probar('devolucion cambiar importe tras aprobar', $$UPDATE devolucion SET importe_reembolsable = 50 WHERE id = 2$$, 'P0001', 'rg_devolucion_importe_fijo');
    PERFORM pg_temp.probar('devolucion apta sin recibida', $$UPDATE devolucion SET cantidad_apta = 1, fecha_inspeccion = now() WHERE id = 3$$, '23514', 'ck_devolucion_apta_requiere_recibida');
    PERFORM pg_temp.probar('devolucion apta sin fecha inspeccion', $$UPDATE devolucion SET cantidad_recibida = 1, fecha_recepcion = now(), cantidad_apta = 1 WHERE id = 3$$, '23514', 'ck_devolucion_fecha_inspeccion');
    PERFORM pg_temp.probar('devolucion rectifica una no anulada', $$INSERT INTO devolucion (movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado) VALUES (19, 2, 'X-D6', 1, 'm', 'SOLICITADA')$$, 'P0001', 'rg_devolucion_rectifica_anulada');
    -- Flujo completo de rectificación: anular una aprobada y vincular la devolución correcta.
    PERFORM pg_temp.probar_ok('devolucion rectificacion completa', $$
    INSERT INTO devolucion (id, movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (900, 20, 'X-D7', 2, 'm', 'SOLICITADA');
    UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REPOSICION', cantidad_aprobada = 2, fecha_resolucion = now() WHERE id = 900;
    UPDATE devolucion SET estado = 'ANULADA', fecha_anulacion = now(), motivo_anulacion = 'aprobada por error' WHERE id = 900;
    INSERT INTO devolucion (id, movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado) VALUES (901, 20, 900, 'X-D8', 1, 'm', 'SOLICITADA')$$);
    PERFORM pg_temp.probar('devolucion segunda rectificacion', $$
    INSERT INTO devolucion (id, movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (900, 20, 'X-D7', 2, 'm', 'SOLICITADA');
    UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REPOSICION', cantidad_aprobada = 2, fecha_resolucion = now() WHERE id = 900;
    UPDATE devolucion SET estado = 'ANULADA', fecha_anulacion = now(), motivo_anulacion = 'aprobada por error' WHERE id = 900;
    INSERT INTO devolucion (id, movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado) VALUES (901, 20, 900, 'X-D8', 1, 'm', 'SOLICITADA');
    INSERT INTO devolucion (id, movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado) VALUES (902, 20, 900, 'X-D9', 1, 'm', 'SOLICITADA')$$, '23505', 'uq_devolucion_devolucion_anulada');
    PERFORM pg_temp.probar('devolucion rectificacion en otro despacho', $$
    INSERT INTO devolucion (id, movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (900, 20, 'X-D7', 2, 'm', 'SOLICITADA');
    UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REPOSICION', cantidad_aprobada = 2, fecha_resolucion = now() WHERE id = 900;
    UPDATE devolucion SET estado = 'ANULADA', fecha_anulacion = now(), motivo_anulacion = 'aprobada por error' WHERE id = 900;
    INSERT INTO devolucion (id, movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado) VALUES (901, 18, 900, 'X-D8', 1, 'm', 'SOLICITADA')$$, '23503', 'fk_devolucion_devolucion_anulada_despacho');
    PERFORM pg_temp.probar('devolucion anular sin motivo', $$
    INSERT INTO devolucion (id, movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (900, 20, 'X-D7', 2, 'm', 'SOLICITADA');
    UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REPOSICION', cantidad_aprobada = 2, fecha_resolucion = now() WHERE id = 900;
    UPDATE devolucion SET estado = 'ANULADA', fecha_anulacion = now() WHERE id = 900$$, '23514', 'ck_devolucion_motivo_anulacion');

    PERFORM pg_temp.probar('reembolso de pago pendiente', $$INSERT INTO reembolso (pago_id, monto, estado, motivo, clave_idempotencia) VALUES (11, 10, 'PENDIENTE', 'm', 'kr1')$$, 'P0001', 'rg_reembolso_pago_pagado');
    PERFORM pg_temp.probar('reembolso sin devolucion y pedido vigente', $$INSERT INTO reembolso (pago_id, monto, estado, motivo, clave_idempotencia) VALUES (1, 10, 'PENDIENTE', 'm', 'kr2')$$, 'P0001', 'rg_reembolso_sin_devolucion_pedido_cancelado');
    PERFORM pg_temp.probar('reembolso con devolucion de otro pedido', $$INSERT INTO reembolso (pago_id, devolucion_id, monto, estado, motivo, clave_idempotencia) VALUES (1, 2, 10, 'PENDIENTE', 'm', 'kr3')$$, 'P0001', 'rg_reembolso_mismo_pedido');
    PERFORM pg_temp.probar('reembolso con devolucion de reposicion', $$INSERT INTO reembolso (pago_id, devolucion_id, monto, estado, motivo, clave_idempotencia) VALUES (2, 1, 10, 'PENDIENTE', 'm', 'kr4')$$, 'P0001', 'rg_reembolso_devolucion_con_reembolso');
    PERFORM pg_temp.probar('reembolso cambiar monto', $$UPDATE reembolso SET monto = 1 WHERE id = 1$$, 'P0001', 'rg_reembolso_datos_fijos');
    PERFORM pg_temp.probar_ok('reembolso cambiar estado', $$UPDATE reembolso SET estado = 'FALLIDO', fecha_ejecucion = NULL WHERE id = 1$$);
    PERFORM pg_temp.probar('reembolso EJECUTADO sin fecha', $$INSERT INTO reembolso (pago_id, monto, estado, motivo, clave_idempotencia) VALUES (3, 1, 'EJECUTADO', 'm', 'kr5')$$, '23514', 'ck_reembolso_fecha_ejecucion');
    PERFORM pg_temp.probar('reembolso dos pendientes por pareja', $$INSERT INTO reembolso (pago_id, monto, estado, motivo, clave_idempotencia) VALUES (3, 1, 'PENDIENTE', 'm', 'kr6'), (3, 1, 'PENDIENTE', 'm', 'kr7')$$, '23505', 'uq_reembolso_pendiente_por_pago_devolucion');
    PERFORM pg_temp.probar_ok('reembolso con devolucion valido', $$INSERT INTO reembolso (pago_id, devolucion_id, monto, estado, motivo, clave_idempotencia) VALUES (5, 2, 1, 'PENDIENTE', 'm', 'kr8')$$);

    PERFORM pg_temp.probar('usuario correo repetido sin distinguir mayusculas', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, telefono) VALUES ('Daniela.Flores@FEDER.example', 'h', 'a', 'b', 88000001, 60000001)$$, '23505', 'uq_usuario_correo');
    PERFORM pg_temp.probar('usuario ci repetida sin complemento', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, telefono) VALUES ('z1@feder.example', 'h', 'a', 'b', 99000011, 60000001)$$, '23505', 'uq_usuario_ci');
    PERFORM pg_temp.probar_ok('usuario misma ci con otro complemento', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, ci_complemento, telefono) VALUES ('z2@feder.example', 'h', 'a', 'b', 99000011, '2B', 60000001)$$);
    PERFORM pg_temp.probar('usuario activo sin hash', $$INSERT INTO usuario (correo, nombres, apellidos, ci, telefono) VALUES ('z3@feder.example', 'a', 'b', 88000003, 60000001)$$, '23514', 'ck_usuario_password_hash');
    PERFORM pg_temp.probar_ok('usuario inactivo sin hash', $$INSERT INTO usuario (correo, nombres, apellidos, ci, telefono, activo) VALUES ('z4@feder.example', 'a', 'b', 88000004, 60000001, false)$$);
    PERFORM pg_temp.probar('usuario correo invalido', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, telefono) VALUES ('sin-arroba', 'h', 'a', 'b', 88000005, 60000001)$$, '23514', 'ck_usuario_correo');
    PERFORM pg_temp.probar('usuario ci fuera de rango', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, telefono) VALUES ('z6@feder.example', 'h', 'a', 'b', 9999, 60000001)$$, '23514', 'ck_usuario_ci');
    PERFORM pg_temp.probar('usuario telefono fuera de rango', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, telefono) VALUES ('z7@feder.example', 'h', 'a', 'b', 88000007, 80000000)$$, '23514', 'ck_usuario_telefono');
    PERFORM pg_temp.probar('usuario complemento invalido', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, ci_complemento, telefono) VALUES ('z8@feder.example', 'h', 'a', 'b', 88000008, 'a1', 60000001)$$, '23514', 'ck_usuario_ci_complemento');
    PERFORM pg_temp.probar('usuario nombres sin letras', $$INSERT INTO usuario (correo, password_hash, nombres, apellidos, ci, telefono) VALUES ('z9@feder.example', 'h', '123', 'b', 88000009, 60000001)$$, '23514', 'ck_usuario_nombres');
    PERFORM pg_temp.probar('usuario borrar con historial', $$DELETE FROM usuario WHERE id = 11$$, '23001', NULL);
    PERFORM pg_temp.probar_ok('usuario borrar con solo carrito (cascada)', $$
    INSERT INTO usuario (id, correo, nombres, apellidos, ci, telefono, activo) VALUES (900, 'z10@feder.example', 'a', 'b', 88000010, 60000001, false);
    INSERT INTO item_carrito (cliente_id, producto_id, cantidad) VALUES (900, 1, 1);
    DELETE FROM usuario WHERE id = 900$$);

    PERFORM pg_temp.probar('concesion vigente duplicada', $$INSERT INTO usuario_rol (usuario_id, rol_id, estado, fecha_activacion) VALUES (11, 4, 'VIGENTE', now())$$, '23505', 'uq_usuario_rol_concesion_abierta');
    PERFORM pg_temp.probar('concesion VIGENTE sin activacion', $$INSERT INTO usuario_rol (usuario_id, rol_id, asignado_por_id, estado) VALUES (11, 2, 1, 'VIGENTE')$$, '23514', 'ck_usuario_rol_coherencia_estado');
    PERFORM pg_temp.probar('concesion PENDIENTE sin plazo', $$INSERT INTO usuario_rol (usuario_id, rol_id, asignado_por_id, estado) VALUES (11, 2, 1, 'PENDIENTE')$$, '23514', 'ck_usuario_rol_coherencia_estado');
    PERFORM pg_temp.probar('concesion a si mismo', $$INSERT INTO usuario_rol (usuario_id, rol_id, asignado_por_id, estado, fecha_expiracion) VALUES (11, 2, 11, 'PENDIENTE', now() + interval '7 days')$$, '23514', 'ck_usuario_rol_asignado_por_distinto');
    PERFORM pg_temp.probar('concesion revocar sin autor', $$UPDATE usuario_rol SET estado = 'REVOCADA', fecha_revocacion = now(), motivo_revocacion = 'm' WHERE id = 2$$, '23514', 'ck_usuario_rol_coherencia_estado');
    PERFORM pg_temp.probar_ok('concesion revocar completa', $$UPDATE usuario_rol SET estado = 'REVOCADA', fecha_revocacion = now(), revocado_por_id = 1, motivo_revocacion = 'm' WHERE id = 2$$);
    PERFORM pg_temp.probar_ok('concesion aceptar invitacion', $$UPDATE usuario_rol SET estado = 'VIGENTE', fecha_activacion = '2026-11-15 10:00:00-04' WHERE id = 6 AND estado = 'PENDIENTE'$$);
    PERFORM pg_temp.probar('concesion aceptar invitacion vencida', $$UPDATE usuario_rol SET estado = 'VIGENTE', fecha_activacion = '2026-11-20 10:00:00-04' WHERE id = 6$$, '23514', 'ck_usuario_rol_fecha_activacion');

    PERFORM pg_temp.probar('productor segundo perfil', $$INSERT INTO productor (usuario_id, comunidad_agricola_id, ubicacion_actividad) VALUES (6, 1, 'taller')$$, '23505', 'uq_productor_usuario');
    PERFORM pg_temp.probar('productor activo sin aprobar', $$UPDATE productor SET activo = true WHERE id = 4$$, '23514', 'ck_productor_activo_aprobado');
    PERFORM pg_temp.probar('productor aprobar sin codigo', $$UPDATE productor SET estado_solicitud = 'APROBADA', fecha_revision = now() WHERE id = 4$$, '23514', 'ck_productor_coherencia_solicitud');
    PERFORM pg_temp.probar_ok('productor aprobar completo', $$UPDATE productor SET estado_solicitud = 'APROBADA', fecha_revision = '2026-11-15 09:00:00-04', codigo_productor = 'PROD-0004', fecha_afiliacion = '2026-11-15', activo = true WHERE id = 4$$);
    PERFORM pg_temp.probar('productor rechazar sin motivo', $$UPDATE productor SET estado_solicitud = 'RECHAZADA', fecha_revision = '2026-11-15 09:00:00-04' WHERE id = 4$$, '23514', 'ck_productor_coherencia_solicitud');
    PERFORM pg_temp.probar('productor ubicacion sin contenido', $$UPDATE productor SET ubicacion_actividad = ' -- ' WHERE id = 4$$, '23514', 'ck_productor_ubicacion_actividad');

    PERFORM pg_temp.probar('categoria nombre repetido', $$INSERT INTO categoria (nombre) VALUES ('Harinas')$$, '23505', 'uq_categoria_nombre');
    PERFORM pg_temp.probar('comunidad repetida en el municipio', $$INSERT INTO comunidad_agricola (nombre, municipio) VALUES ('Comunidad Villa Colmenar', 'Buena Vista')$$, '23505', 'uq_comunidad_agricola_nombre_municipio');
    PERFORM pg_temp.probar('comunidad borrar con productores', $$DELETE FROM comunidad_agricola WHERE id = 1$$, '23001', 'fk_productor_comunidad_agricola');
    PERFORM pg_temp.probar('categoria borrar con productos', $$DELETE FROM categoria WHERE id = 1$$, '23001', 'fk_producto_categoria');

    PERFORM pg_temp.probar('bitacora recepcion sin clave', $$INSERT INTO bitacora (accion, entidad, entidad_id, detalle) VALUES ('MODIFICAR', 'DETALLE_COMPRA', 10, '{"operacion": "RECEPCION", "compra_id": 5, "cantidad_aceptada": 1}')$$, '23514', 'ck_bitacora_operacion_detalle_compra');
    PERFORM pg_temp.probar('bitacora recepcion sin cantidad aceptada', $$INSERT INTO bitacora (accion, entidad, entidad_id, detalle) VALUES ('MODIFICAR', 'DETALLE_COMPRA', 10, '{"clave_operacion": "k-b1", "operacion": "RECEPCION", "compra_id": 5}')$$, '23514', 'ck_bitacora_operacion_detalle_compra');
    PERFORM pg_temp.probar('bitacora clave de operacion repetida', $$INSERT INTO bitacora (accion, entidad, entidad_id, detalle) VALUES ('MODIFICAR', 'DETALLE_COMPRA', 1, '{"clave_operacion": "op-rec-cmp001-dc01-01", "operacion": "RECEPCION", "compra_id": 1, "cantidad_aceptada": 1}')$$, '23505', 'uq_bitacora_clave_operacion');
    PERFORM pg_temp.probar('bitacora guia repetida en la linea', $$INSERT INTO bitacora (accion, entidad, entidad_id, detalle) VALUES ('MODIFICAR', 'DETALLE_COMPRA', 1, '{"clave_operacion": "k-b2", "operacion": "RECEPCION", "compra_id": 1, "cantidad_aceptada": 0, "documento_entrega": "NE-MQ-0001"}')$$, '23505', 'uq_bitacora_documento_entrega');
    PERFORM pg_temp.probar_ok('bitacora correccion de precio sin clave', $$INSERT INTO bitacora (usuario_id, accion, entidad, entidad_id, detalle) VALUES (3, 'MODIFICAR', 'DETALLE_COMPRA', 10, '{"operacion": "CORRECCION_PRECIO", "compra_id": 5, "precio_anterior": 60, "precio_nuevo": 61, "motivo": "error de registro"}')$$);
    PERFORM pg_temp.probar('bitacora accion inexistente', $$INSERT INTO bitacora (accion, entidad) VALUES ('BORRAR', 'X')$$, '23514', 'ck_bitacora_accion');

    PERFORM pg_temp.probar('correo entrante repetido', $$INSERT INTO mensaje_correo (buzon, uidl, remitente, estado) VALUES ('b', 'u1', 'r@feder.example', 'PROCESANDO'), ('b', 'u1', 'r@feder.example', 'PROCESANDO')$$, '23505', 'uq_mensaje_correo_buzon_uidl');
    PERFORM pg_temp.probar('correo entrante estado invalido', $$INSERT INTO mensaje_correo (buzon, uidl, remitente, estado) VALUES ('b', 'u2', 'r@feder.example', 'LEIDO')$$, '23514', 'ck_mensaje_correo_estado');
    PERFORM pg_temp.probar('correo saliente estado invalido', $$INSERT INTO correo_saliente (destinatario, asunto, cuerpo_texto, message_id, estado) VALUES ('d@feder.example', 'a', 'c', '<m1@feder.example>', 'ENVIANDO')$$, '23514', 'ck_correo_saliente_estado');
    PERFORM pg_temp.probar_ok('correo saliente por defecto', $$INSERT INTO correo_saliente (destinatario, asunto, cuerpo_texto, message_id) VALUES ('d@feder.example', 'a', 'c', '<m2@feder.example>')$$);
    PERFORM pg_temp.probar('confirmacion vigencia invalida', $$INSERT INTO confirmacion_operacion (codigo_hash, correo, comando, parametros, expira_en) VALUES (repeat('a', 64), 'x@feder.example', 'REGISTRAR', '{}', now() - interval '1 minute')$$, '23514', 'ck_confirmacion_operacion_vigencia');
    PERFORM pg_temp.probar_ok('confirmacion sin usuario', $$INSERT INTO confirmacion_operacion (codigo_hash, correo, comando, parametros, expira_en) VALUES (repeat('b', 64), 'x@feder.example', 'REGISTRAR', '{"a": 1}', now() + interval '10 minutes')$$);
    PERFORM pg_temp.probar('confirmacion hash repetido', $$INSERT INTO confirmacion_operacion (codigo_hash, correo, comando, parametros, expira_en) VALUES (repeat('c', 64), 'x@feder.example', 'R', '{}', now() + interval '10 minutes'), (repeat('c', 64), 'y@feder.example', 'R', '{}', now() + interval '10 minutes')$$, '23505', 'uq_confirmacion_operacion_codigo_hash');

    PERFORM pg_temp.probar('mov reposicion de devolucion con reembolso', $$INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, devolucion_id, tipo, cantidad, clave_operacion) VALUES (7, 3, 2, 'SALIDA_REPOSICION', 1, 'k30')$$, 'P0001', 'rg_movimiento_inventario_reposicion_resolucion');
    PERFORM pg_temp.probar_ok('mov reposicion de devolucion con reposicion', $$INSERT INTO devolucion (id, movimiento_inventario_id, codigo, cantidad, motivo, estado) VALUES (910, 20, 'X-D10', 1, 'm', 'SOLICITADA');
    UPDATE devolucion SET estado = 'APROBADA', resolucion = 'REPOSICION', cantidad_aprobada = 1, fecha_resolucion = now() WHERE id = 910;
    INSERT INTO movimiento_inventario (lote_feder_id, usuario_id, devolucion_id, tipo, cantidad, clave_operacion) VALUES (8, 3, 910, 'SALIDA_REPOSICION', 1, 'k31')$$);
    PERFORM pg_temp.probar('pago cambiar de pedido', $$UPDATE pago SET pedido_id = 2 WHERE id = 1$$, 'P0001', 'rg_pago_pedido_fijo');
    PERFORM pg_temp.probar('historial creacion distinta de PENDIENTE (bis)', $$INSERT INTO historial_pedido (pedido_id, estado_anterior, estado_nuevo) VALUES (1, NULL, 'ENTREGADO')$$, '23514', 'ck_historial_pedido_transicion');
    PERFORM pg_temp.probar('bitacora recepcion con clave nula', $$INSERT INTO bitacora (accion, entidad, entidad_id, detalle) VALUES ('MODIFICAR', 'DETALLE_COMPRA', 10, '{"clave_operacion": null, "operacion": "RECEPCION", "compra_id": 5, "cantidad_aceptada": 1}')$$, '23514', 'ck_bitacora_operacion_detalle_compra');
END;
$casos$;

SELECT count(*) AS casos, count(*) FILTER (WHERE ok) AS correctos, count(*) FILTER (WHERE NOT ok) AS fallidos FROM resultado;
SELECT n, caso, esperado, obtenido FROM resultado WHERE NOT ok ORDER BY n;

ROLLBACK;
