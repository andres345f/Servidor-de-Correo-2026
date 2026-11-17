-- =====================================================================================
-- FEDER · verificar_semilla.sql
-- Comprueba que los datos de demostración (db/02_seed.sql) cumplen las reglas del modelo que la base
-- no puede imponer por sí sola (saldos, totales, estados derivados, límites por suma) y las reglas de
-- los datos (dominio reservado, sin contraseñas nulas en cuentas activas, fechas de 2026).
-- Solo lee datos (crea dos funciones temporales de la sesión para contar filas). La consulta 1 debe
-- devolver CERO filas; la 2 y la 3 son informativas.
--
-- Ejecución:
--   psql -h 127.0.0.1 -p 55432 -U feder_admin -d feder_test -f db/verificaciones/verificar_semilla.sql
-- =====================================================================================

SET client_encoding = 'UTF8';

-- Funciones temporales de apoyo (desaparecen al cerrar la sesión).
CREATE OR REPLACE FUNCTION pg_temp.feder_contar(p_tabla text) RETURNS bigint
LANGUAGE plpgsql AS $$
DECLARE
    v_filas bigint;
BEGIN
    EXECUTE format('SELECT count(*) FROM public.%I', p_tabla) INTO v_filas;
    RETURN v_filas;
END;
$$;

-- Verdadero si la tabla tiene filas y su secuencia de identidad todavía no alcanzó el mayor id.
CREATE OR REPLACE FUNCTION pg_temp.feder_secuencia_atrasada(p_tabla text) RETURNS boolean
LANGUAGE plpgsql AS $$
DECLARE
    v_maximo bigint;
    v_ultimo bigint;
BEGIN
    EXECUTE format('SELECT max(id) FROM public.%I', p_tabla) INTO v_maximo;
    IF v_maximo IS NULL THEN
        RETURN false;
    END IF;
    EXECUTE format('SELECT last_value FROM %s', pg_get_serial_sequence(format('public.%I', p_tabla), 'id')) INTO v_ultimo;
    RETURN v_ultimo < v_maximo;
END;
$$;

-- -------------------------------------------------------------------------------------
-- 1. Incumplimientos (debe devolver CERO filas)
-- -------------------------------------------------------------------------------------
WITH saldo AS (
    SELECT lf.id,
           COALESCE(sum(CASE WHEN m.tipo IN ('ENTRADA_COMPRA', 'ENTRADA_RETORNO_ENTREGA', 'ENTRADA_DEVOLUCION', 'AJUSTE_POSITIVO')
                             THEN m.cantidad ELSE -m.cantidad END), 0) AS segun_movimientos,
           COALESCE(sum(m.cantidad) FILTER (WHERE m.tipo = 'ENTRADA_COMPRA'), 0)
             - COALESCE(sum(m.cantidad) FILTER (WHERE m.tipo = 'ANULACION_RECEPCION'), 0) AS aceptado_neto
      FROM lote_feder lf
      LEFT JOIN movimiento_inventario m ON m.lote_feder_id = lf.id
     GROUP BY lf.id
),
cobertura AS (
    SELECT dp.id, dp.pedido_id, dp.cantidad,
           COALESCE(sum(rs.cantidad) FILTER (WHERE rs.estado IN ('ACTIVA', 'CONSUMIDA')), 0) AS cubierta
      FROM detalle_pedido dp
      LEFT JOIN reserva_stock rs ON rs.detalle_pedido_id = dp.id
     GROUP BY dp.id
),
cobrado AS (
    SELECT p.id, COALESCE(sum(pg.monto) FILTER (WHERE pg.estado = 'PAGADO'), 0) AS pagado
      FROM pedido p
      LEFT JOIN pago pg ON pg.pedido_id = p.id
     GROUP BY p.id
),
historial AS (
    SELECT h.*,
           lag(h.estado_nuevo) OVER (PARTITION BY h.pedido_id ORDER BY h.fecha, h.id) AS estado_previo,
           row_number() OVER (PARTITION BY h.pedido_id ORDER BY h.fecha DESC, h.id DESC) AS desde_el_final
      FROM historial_pedido h
),
ocupacion_lote AS (
    SELECT lp.id, lp.estado, lp.cantidad_ofrecida,
           COALESCE(sum(dc.cantidad_comprada - dc.cantidad_cancelada), 0) AS ocupado
      FROM lote_produccion lp
      LEFT JOIN detalle_compra dc ON dc.lote_origen_id = lp.id
     GROUP BY lp.id
),
compra AS (
    SELECT c.id, c.estado, c.total, c.fecha_pago_productor,
           sum(round(dc.cantidad_comprada * dc.precio_unitario, 2)) AS total_calculado,
           sum(dc.cantidad_comprada - dc.cantidad_recibida - dc.cantidad_rechazada - dc.cantidad_cancelada) AS pendiente,
           sum(dc.cantidad_recibida) AS aceptado,
           sum(dc.cantidad_rechazada) AS rechazado,
           count(dc.id) AS lineas
      FROM compra_feder c
      LEFT JOIN detalle_compra dc ON dc.compra_id = c.id
     GROUP BY c.id
),
incumplimiento (regla, detalle) AS (
    -- LOTE_FEDER «Saldo coherente»
    SELECT 'LOTE_FEDER saldo = entradas - salidas', format('lote %s: saldo %s, movimientos %s', lf.id, lf.cantidad_disponible, s.segun_movimientos)
      FROM lote_feder lf JOIN saldo s ON s.id = lf.id
     WHERE lf.cantidad_disponible <> s.segun_movimientos
    UNION ALL
    SELECT 'LOTE_FEDER reservado = suma de reservas ACTIVA', format('lote %s', lf.id)
      FROM lote_feder lf
     WHERE lf.cantidad_reservada <> (SELECT COALESCE(sum(rs.cantidad), 0) FROM reserva_stock rs
                                      WHERE rs.lote_feder_id = lf.id AND rs.estado = 'ACTIVA')
    UNION ALL
    SELECT 'LOTE_FEDER nace con una ENTRADA_COMPRA', format('lote %s', lf.id)
      FROM lote_feder lf
     WHERE NOT EXISTS (SELECT 1 FROM movimiento_inventario m WHERE m.lote_feder_id = lf.id AND m.tipo = 'ENTRADA_COMPRA')
    UNION ALL
    -- DETALLE_COMPRA: lo aceptado es lo que entró al inventario; sin aceptación no hay lote
    SELECT 'DETALLE_COMPRA recibida = ENTRADA_COMPRA - ANULACION_RECEPCION', format('detalle %s', dc.id)
      FROM detalle_compra dc
      LEFT JOIN lote_feder lf ON lf.detalle_compra_id = dc.id
      LEFT JOIN saldo s ON s.id = lf.id
     WHERE dc.cantidad_recibida <> COALESCE(s.aceptado_neto, 0)
    UNION ALL
    SELECT 'DETALLE_COMPRA cada recepción o cancelación tiene su registro de bitácora', format('movimiento %s', m.id)
      FROM movimiento_inventario m
     WHERE m.tipo = 'ENTRADA_COMPRA'
       AND NOT EXISTS (SELECT 1 FROM bitacora b
                        WHERE b.entidad = 'DETALLE_COMPRA' AND b.detalle ->> 'clave_operacion' = m.clave_operacion
                          AND (b.detalle ->> 'cantidad_aceptada')::numeric = m.cantidad)
    UNION ALL
    -- COMPRA_FEDER: total acordado, estado derivado y pago solo de lo aceptado
    SELECT 'COMPRA_FEDER total = suma de líneas', format('compra %s', id) FROM compra WHERE total <> total_calculado OR lineas = 0
    UNION ALL
    SELECT 'COMPRA_FEDER «Estado coherente»', format('compra %s en %s', id, estado)
      FROM compra
     WHERE estado <> CASE
                         WHEN pendiente > 0 AND aceptado = 0 AND rechazado = 0 THEN 'REGISTRADA'
                         WHEN pendiente > 0 THEN 'RECIBIDA_PARCIAL'
                         WHEN aceptado > 0 THEN 'RECIBIDA'
                         ELSE 'CANCELADA'
                     END
    UNION ALL
    -- LOTE_PRODUCCION «Compra dentro de lo ofrecido»
    SELECT 'LOTE_PRODUCCION compra dentro de lo ofrecido', format('lote %s', id) FROM ocupacion_lote WHERE ocupado > cantidad_ofrecida
    UNION ALL
    SELECT 'LOTE_PRODUCCION AGOTADO si y solo si todo lo ofrecido fue comprado', format('lote %s', id)
      FROM ocupacion_lote
     WHERE estado <> 'RETIRADO' AND (estado = 'AGOTADO') <> (ocupado = cantidad_ofrecida)
    UNION ALL
    SELECT 'LOTE_PRODUCCION solo en producciones EN_CURSO o TERMINADA', format('lote %s', lp.id)
      FROM lote_produccion lp JOIN produccion pr ON pr.id = lp.produccion_id
     WHERE pr.estado NOT IN ('EN_CURSO', 'TERMINADA')
    UNION ALL
    -- PRODUCCION «Procedencia histórica»: instantánea igual a la comunidad de origen
    SELECT 'PRODUCCION instantánea de la comunidad de origen', format('producción %s', pr.id)
      FROM produccion pr JOIN comunidad_agricola c ON c.id = pr.comunidad_origen_id
     WHERE (pr.nombre_comunidad_origen, pr.municipio_origen, pr.departamento_origen) <> (c.nombre, c.municipio, c.departamento)
    UNION ALL
    SELECT 'PRODUCCION registrada por un productor aprobado', format('producción %s', pr.id)
      FROM produccion pr JOIN productor p ON p.id = pr.productor_id
     WHERE p.estado_solicitud <> 'APROBADA'
    UNION ALL
    -- PEDIDO: totales, estructura mínima e historial
    SELECT 'PEDIDO subtotal = suma de líneas', format('pedido %s', p.id)
      FROM pedido p
     WHERE p.subtotal <> (SELECT COALESCE(sum(dp.subtotal), -1) FROM detalle_pedido dp WHERE dp.pedido_id = p.id)
    UNION ALL
    SELECT 'PEDIDO con exactamente una entrega', format('pedido %s', p.id)
      FROM pedido p WHERE (SELECT count(*) FROM entrega e WHERE e.pedido_id = p.id) <> 1
    UNION ALL
    SELECT 'HISTORIAL_PEDIDO último registro = estado del pedido', format('pedido %s', p.id)
      FROM pedido p
     WHERE p.estado IS DISTINCT FROM (SELECT h.estado_nuevo FROM historial h WHERE h.pedido_id = p.id AND h.desde_el_final = 1)
    UNION ALL
    SELECT 'HISTORIAL_PEDIDO cadena continua de estados', format('historial %s', id)
      FROM historial WHERE estado_anterior IS DISTINCT FROM estado_previo
    UNION ALL
    SELECT 'HISTORIAL_PEDIDO transición propia del tipo de pedido', format('historial %s', h.id)
      FROM historial_pedido h JOIN pedido p ON p.id = h.pedido_id
     WHERE (p.tipo = 'INMEDIATO' AND 'ESPERANDO_ABASTECIMIENTO' IN (h.estado_anterior, h.estado_nuevo))
        OR (p.tipo = 'ENCARGO' AND h.estado_anterior = 'PENDIENTE' AND h.estado_nuevo = 'CONFIRMADO')
    UNION ALL
    -- Reservas y cobertura
    SELECT 'RESERVA_STOCK «Sin exceso»', format('línea %s', id) FROM cobertura WHERE cubierta > cantidad
    UNION ALL
    SELECT 'PEDIDO INMEDIATO vigente cubierto por reservas', format('línea %s', c.id)
      FROM cobertura c JOIN pedido p ON p.id = c.pedido_id
     WHERE p.tipo = 'INMEDIATO' AND p.estado <> 'CANCELADO' AND c.cubierta <> c.cantidad
    UNION ALL
    SELECT 'PEDIDO CANCELADO sin reservas ACTIVA', format('reserva %s', rs.id)
      FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id JOIN pedido p ON p.id = dp.pedido_id
     WHERE p.estado = 'CANCELADO' AND rs.estado = 'ACTIVA'
    UNION ALL
    SELECT 'RESERVA_STOCK CONSUMIDA solo desde el despacho', format('reserva %s', rs.id)
      FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id JOIN pedido p ON p.id = dp.pedido_id
     WHERE (rs.estado = 'CONSUMIDA') <> (p.estado IN ('DESPACHADO', 'ENTREGADO'))
       AND p.estado <> 'CANCELADO'
    UNION ALL
    SELECT 'RESERVA_STOCK CONSUMIDA si y solo si tiene su SALIDA_VENTA', format('reserva %s', rs.id)
      FROM reserva_stock rs
     WHERE (rs.estado = 'CONSUMIDA') <> EXISTS (SELECT 1 FROM movimiento_inventario m
                                               WHERE m.reserva_id = rs.id AND m.tipo = 'SALIDA_VENTA')
    UNION ALL
    SELECT 'RESERVA_STOCK plazo de pago solo en pedidos PENDIENTE', format('reserva %s', rs.id)
      FROM reserva_stock rs JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id JOIN pedido p ON p.id = dp.pedido_id
     WHERE rs.estado = 'ACTIVA' AND (rs.fecha_expiracion IS NOT NULL) <> (p.estado = 'PENDIENTE')
    UNION ALL
    -- Pagos, facturas y entregas
    SELECT 'PAGO suma PAGADO no supera el total', format('pedido %s', p.id)
      FROM pedido p JOIN cobrado c ON c.id = p.id WHERE c.pagado > p.total
    UNION ALL
    SELECT 'PEDIDO ENTREGADO con cobro completo y entrega ENTREGADA', format('pedido %s', p.id)
      FROM pedido p JOIN cobrado c ON c.id = p.id JOIN entrega e ON e.pedido_id = p.id
     WHERE (p.estado = 'ENTREGADO') <> (e.estado = 'ENTREGADA') OR (p.estado = 'ENTREGADO' AND c.pagado <> p.total)
    UNION ALL
    SELECT 'FACTURA emitida solo con el pago completo', format('factura %s', f.id)
      FROM factura f JOIN pedido p ON p.id = f.pedido_id JOIN cobrado c ON c.id = p.id
     WHERE c.pagado <> p.total
    UNION ALL
    SELECT 'ENTREGA en RECOJO sin costo de envío', format('pedido %s', p.id)
      FROM pedido p JOIN entrega e ON e.pedido_id = p.id WHERE e.modalidad = 'RECOJO' AND p.costo_envio <> 0
    UNION ALL
    SELECT 'ENTREGA coherente con el estado del pedido', format('pedido %s: %s / %s', p.id, p.estado, e.estado)
      FROM pedido p JOIN entrega e ON e.pedido_id = p.id
     WHERE (p.estado IN ('PENDIENTE', 'ESPERANDO_ABASTECIMIENTO', 'CONFIRMADO', 'EN_PREPARACION') AND e.estado <> 'PENDIENTE')
        OR (p.estado = 'DESPACHADO' AND e.estado NOT IN ('EN_CAMINO', 'LISTA_PARA_RECOJO', 'FALLIDA', 'RETORNADA', 'NO_RECUPERADA'))
        OR (p.estado = 'CANCELADO' AND e.estado NOT IN ('CANCELADA', 'RETORNADA', 'NO_RECUPERADA'))
    UNION ALL
    SELECT 'ENTREGA cada transición registrada en la bitácora', format('entrega %s en %s', e.id, e.estado)
      FROM entrega e
     WHERE e.estado <> 'PENDIENTE'
       AND NOT EXISTS (SELECT 1 FROM bitacora b
                        WHERE b.entidad = 'ENTREGA' AND b.entidad_id = e.id AND b.detalle ->> 'estado_nuevo' = e.estado)
    UNION ALL
    -- Devoluciones y reembolsos
    SELECT 'DEVOLUCION límites por despacho', format('despacho %s', m.id)
      FROM movimiento_inventario m
     WHERE m.tipo IN ('SALIDA_VENTA', 'SALIDA_REPOSICION')
       AND m.cantidad < (SELECT COALESCE(sum(CASE WHEN d.estado = 'SOLICITADA' THEN d.cantidad
                                                  WHEN d.estado IN ('APROBADA', 'COMPLETADA') THEN d.cantidad_aprobada
                                                  ELSE 0 END), 0)
                           FROM devolucion d WHERE d.movimiento_inventario_id = m.id)
    UNION ALL
    SELECT 'DEVOLUCION importe = round(subtotal × aprobada / cantidad, 2)', format('devolución %s', d.id)
      FROM devolucion d
      JOIN movimiento_inventario m ON m.id = d.movimiento_inventario_id
      JOIN reserva_stock rs ON rs.id = m.reserva_id
      JOIN detalle_pedido dp ON dp.id = rs.detalle_pedido_id
     WHERE d.importe_reembolsable IS NOT NULL
       AND d.importe_reembolsable <> round(dp.subtotal * d.cantidad_aprobada / dp.cantidad, 2)
    UNION ALL
    SELECT 'DEVOLUCION COMPLETADA con su compensación completa', format('devolución %s', d.id)
      FROM devolucion d
     WHERE d.estado = 'COMPLETADA'
       AND ((d.resolucion = 'REEMBOLSO'
             AND d.importe_reembolsable <> (SELECT COALESCE(sum(r.monto), 0) FROM reembolso r
                                             WHERE r.devolucion_id = d.id AND r.estado = 'EJECUTADO'))
            OR (d.resolucion = 'REPOSICION'
                AND d.cantidad_aprobada <> (SELECT COALESCE(sum(m.cantidad), 0) FROM movimiento_inventario m
                                             WHERE m.devolucion_id = d.id AND m.tipo = 'SALIDA_REPOSICION'))
            OR LEAST(COALESCE(d.cantidad_apta, 0), d.cantidad_aprobada)
               <> (SELECT COALESCE(sum(m.cantidad), 0) FROM movimiento_inventario m
                    WHERE m.devolucion_id = d.id AND m.tipo = 'ENTRADA_DEVOLUCION'))
    UNION ALL
    SELECT 'REEMBOLSO «Límite por pago»', format('pago %s', pg.id)
      FROM pago pg
     WHERE pg.monto < (SELECT COALESCE(sum(r.monto), 0) FROM reembolso r
                        WHERE r.pago_id = pg.id AND r.estado IN ('PENDIENTE', 'EJECUTADO'))
    UNION ALL
    -- Cuentas y concesiones
    SELECT 'USUARIO con al menos una concesión', format('usuario %s', u.id)
      FROM usuario u WHERE NOT EXISTS (SELECT 1 FROM usuario_rol ur WHERE ur.usuario_id = u.id)
    UNION ALL
    SELECT 'USUARIO activo con hash de contraseña', format('usuario %s', u.id)
      FROM usuario u WHERE u.activo AND u.password_hash IS NULL
    UNION ALL
    SELECT 'USUARIO sin contraseña solo si nunca se activó', format('usuario %s', u.id)
      FROM usuario u
     WHERE u.password_hash IS NULL
       AND (u.activo OR EXISTS (SELECT 1 FROM usuario_rol ur WHERE ur.usuario_id = u.id AND ur.fecha_activacion IS NOT NULL))
    UNION ALL
    SELECT 'USUARIO «Términos aceptados»', format('usuario %s', u.id)
      FROM usuario u
     WHERE u.fecha_aceptacion_terminos IS NULL
       AND EXISTS (SELECT 1 FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id
                    WHERE ur.usuario_id = u.id AND r.codigo = 'CLIENTE')
    UNION ALL
    SELECT 'USUARIO correo del dominio reservado feder.example', format('usuario %s', u.id)
      FROM usuario u WHERE u.correo NOT LIKE '%@feder.example'
    UNION ALL
    SELECT 'USUARIO_ROL «Concesión autorizada»: asignado_por según el rol', format('concesión %s', ur.id)
      FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id
     WHERE (r.codigo = 'CLIENTE' AND ur.asignado_por_id IS NOT NULL)
        OR (r.codigo IN ('PERSONAL_FEDER', 'PRODUCTOR') AND ur.asignado_por_id IS NULL)
        OR (ur.asignado_por_id IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM usuario_rol a JOIN rol ra ON ra.id = a.rol_id
                             WHERE a.usuario_id = ur.asignado_por_id AND ra.codigo = 'ADMINISTRADOR'
                               AND a.fecha_activacion <= ur.fecha_asignacion))
    UNION ALL
    SELECT 'USUARIO_ROL una sola concesión ADMINISTRADOR, la inicial', format('%s concesiones', count(*))
      FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id
     WHERE r.codigo = 'ADMINISTRADOR'
    HAVING count(*) <> 1 OR bool_or(ur.asignado_por_id IS NOT NULL)
    UNION ALL
    SELECT 'PRODUCTOR «Perfil con rol vigente»', format('productor %s', p.id)
      FROM productor p
     WHERE p.activo <> (p.estado_solicitud = 'APROBADA'
                        AND EXISTS (SELECT 1 FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id
                                     WHERE ur.usuario_id = p.usuario_id AND r.codigo = 'PRODUCTOR' AND ur.estado = 'VIGENTE'))
    UNION ALL
    -- Avisos
    SELECT 'NOTIFICACION el pedido referenciado es del destinatario', format('notificación %s', n.id)
      FROM notificacion n JOIN pedido p ON p.id = n.pedido_id WHERE p.usuario_id <> n.usuario_id
    UNION ALL
    SELECT 'NOTIFICACION ALERTA_STOCK solo a personal o administración vigente', format('notificación %s', n.id)
      FROM notificacion n
     WHERE n.tipo = 'ALERTA_STOCK'
       AND NOT EXISTS (SELECT 1 FROM usuario_rol ur JOIN rol r ON r.id = ur.rol_id
                        WHERE ur.usuario_id = n.usuario_id AND r.codigo IN ('PERSONAL_FEDER', 'ADMINISTRADOR')
                          AND ur.fecha_activacion <= n.fecha_creacion
                          AND (ur.fecha_revocacion IS NULL OR ur.fecha_revocacion > n.fecha_creacion))
    UNION ALL
    -- Fechas de los hechos dentro de 2026
    SELECT 'Fechas de los hechos dentro de 2026', format('%s %s', t.tabla, t.id)
      FROM (SELECT 'usuario' AS tabla, id, fecha_registro AS fecha FROM usuario
            UNION ALL SELECT 'bitacora', id, fecha FROM bitacora
            UNION ALL SELECT 'compra_feder', id, fecha_compra FROM compra_feder
            UNION ALL SELECT 'movimiento_inventario', id, fecha FROM movimiento_inventario
            UNION ALL SELECT 'pedido', id, fecha_creacion FROM pedido
            UNION ALL SELECT 'historial_pedido', id, fecha FROM historial_pedido
            UNION ALL SELECT 'pago', id, fecha_registro FROM pago
            UNION ALL SELECT 'factura', id, fecha_emision FROM factura
            UNION ALL SELECT 'devolucion', id, fecha_solicitud FROM devolucion
            UNION ALL SELECT 'reembolso', id, fecha_solicitud FROM reembolso
            UNION ALL SELECT 'notificacion', id, fecha_creacion FROM notificacion) AS t
     WHERE t.fecha < TIMESTAMPTZ '2026-01-01 00:00:00-04' OR t.fecha >= TIMESTAMPTZ '2026-11-16 00:00:00-04'
    UNION ALL
    -- Identidades: la próxima alta no colisiona con los identificadores cargados
    SELECT 'Secuencia de identidad por delante del mayor id', c.table_name::text
      FROM information_schema.columns c
     WHERE c.table_schema = 'public' AND c.column_name = 'id' AND c.is_identity = 'YES'
       AND pg_temp.feder_secuencia_atrasada(c.table_name::text)
)
SELECT regla, detalle FROM incumplimiento ORDER BY 1, 2;

-- -------------------------------------------------------------------------------------
-- 2. Filas por tabla (informativo)
-- -------------------------------------------------------------------------------------
SELECT c.relname AS tabla, pg_temp.feder_contar(c.relname::text) AS filas
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
 WHERE c.relkind = 'r'
 ORDER BY 1;

-- -------------------------------------------------------------------------------------
-- 3. Stock vendible por producto y alerta (informativo; PRODUCTO «Stock vendible derivado»)
-- -------------------------------------------------------------------------------------
SELECT p.codigo, p.nombre, p.unidad_medida,
       COALESCE(sum(lf.cantidad_disponible - lf.cantidad_reservada)
                FILTER (WHERE lf.estado = 'DISPONIBLE'
                          AND (lp.fecha_vencimiento IS NULL OR lp.fecha_vencimiento >= DATE '2026-11-15')), 0) AS stock_vendible,
       p.stock_minimo,
       CASE
           WHEN NOT p.activo THEN 'producto inactivo'
           WHEN COALESCE(sum(lf.cantidad_disponible - lf.cantidad_reservada)
                         FILTER (WHERE lf.estado = 'DISPONIBLE'
                                   AND (lp.fecha_vencimiento IS NULL OR lp.fecha_vencimiento >= DATE '2026-11-15')), 0) = 0 THEN 'agotado'
           WHEN COALESCE(sum(lf.cantidad_disponible - lf.cantidad_reservada)
                         FILTER (WHERE lf.estado = 'DISPONIBLE'
                                   AND (lp.fecha_vencimiento IS NULL OR lp.fecha_vencimiento >= DATE '2026-11-15')), 0) < p.stock_minimo THEN 'bajo mínimo'
           ELSE 'sin alerta'
       END AS alerta
  FROM producto p
  LEFT JOIN produccion pr ON pr.producto_id = p.id
  LEFT JOIN lote_produccion lp ON lp.produccion_id = pr.id
  LEFT JOIN detalle_compra dc ON dc.lote_origen_id = lp.id
  LEFT JOIN lote_feder lf ON lf.detalle_compra_id = dc.id
 GROUP BY p.id
 ORDER BY p.codigo;
