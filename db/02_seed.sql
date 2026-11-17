-- =====================================================================================
-- FEDER · INF513 (UAGRM) · Sistema dirigido por correo electrónico
-- 02_seed.sql · Datos de demostración
--
-- AVISO: estos datos son de demostración académica. NO son registros comerciales reales de FEDER.
--   - Las personas, cédulas, teléfonos, direcciones, códigos y referencias de pago son inventados.
--     Las cédulas (99000001…) y los teléfonos (70000101…) son secuencias ficticias.
--   - Todos los correos usan el dominio reservado «feder.example» (RFC 2606): no existe ni recibe correo.
--   - Ninguna cuenta tiene una contraseña conocida. Las cuentas activas llevan en password_hash un valor
--     aleatorio e irreversible generado al cargar el script (el modelo exige que una cuenta activa
--     tenga hash); nadie conoce ni puede derivar una contraseña de él. Este proyecto autentica las
--     operaciones por correo con códigos de confirmación de un solo uso; el acceso web queda fuera
--     de su alcance.
--   - «Administración FEDER» es una cuenta operativa neutra, no una persona. Existe porque el modelo
--     exige que las concesiones PERSONAL_FEDER y PRODUCTOR las otorgue un ADMINISTRADOR vigente
--     distinto del titular; su concesión es la inicial (asignado_por nulo). Como su correo pertenece
--     al dominio reservado, nadie puede recibir sus códigos de confirmación.
--
-- Requiere una base recién creada con 01_schema.sql (que ya contiene los cuatro roles).
-- Fechas dentro de 2026, en hora de Bolivia (-04). Fecha de referencia de los datos: 2026-11-15.
-- =====================================================================================

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL search_path = public;

-- El catálogo de roles lo crea 01_schema.sql; aquí solo se comprueba que está completo.
DO $$
BEGIN
    IF (SELECT count(*) FROM rol
         WHERE (id, codigo) IN ((1, 'ADMINISTRADOR'), (2, 'PERSONAL_FEDER'), (3, 'PRODUCTOR'), (4, 'CLIENTE'))) <> 4 THEN
        RAISE EXCEPTION 'Falta el catálogo de roles: cargue antes db/01_schema.sql';
    END IF;
END;
$$;

-- -------------------------------------------------------------------------------------
-- CU1 · Cuentas y concesiones de rol
-- -------------------------------------------------------------------------------------
-- 1 cuenta operativa de administración, 2 de personal, 1 persona invitada que aún no activó su cuenta,
-- 1 expersonal sin rol vigente, 5 productores o solicitantes (también clientes) y 4 clientes.
INSERT INTO usuario (id, correo, password_hash, nombres, apellidos, ci, ci_complemento, telefono, activo,
                     fecha_registro, ultimo_acceso, fecha_aceptacion_terminos)
SELECT v.id, v.correo,
       CASE WHEN v.activo
            THEN 'pbkdf2_sha256$600000$' || md5(random()::text || clock_timestamp()::text) || '$' || md5(random()::text)
       END,
       v.nombres, v.apellidos, v.ci, v.ci_complemento, v.telefono, v.activo,
       v.fecha_registro::timestamptz, v.ultimo_acceso::timestamptz, v.fecha_aceptacion_terminos::timestamptz
  FROM (VALUES
    ( 1, 'administracion@feder.example',            'Administración', 'FEDER',              99000001, NULL, 70000101, TRUE,  '2026-01-12 09:00:00-04', '2026-11-14 08:05:00-04', NULL),
    ( 2, 'almacen.carla.mendez@feder.example',      'Carla',          'Méndez Roca',        99000002, NULL, 70000102, TRUE,  '2026-01-13 10:00:00-04', '2026-11-14 08:40:00-04', NULL),
    ( 3, 'ventas.jorge.suarez@feder.example',       'Jorge',          'Suárez Vaca',        99000003, NULL, 70000103, TRUE,  '2026-01-13 10:10:00-04', '2026-11-14 17:30:00-04', '2026-05-02 20:15:00-04'),
    ( 4, 'logistica.lucia.fernandez@feder.example', 'Lucía',          'Fernández Ortiz',    99000004, NULL, 70000104, FALSE, '2026-10-20 09:00:00-04', NULL,                     NULL),
    ( 5, 'hugo.paniagua@feder.example',             'Hugo',           'Paniagua Rivero',    99000005, NULL, 70000105, TRUE,  '2026-01-13 10:20:00-04', '2026-06-29 16:10:00-04', NULL),
    ( 6, 'maria.quispe@feder.example',              'María',          'Quispe Mamani',      99000006, NULL, 70000106, TRUE,  '2026-01-20 19:30:00-04', '2026-11-10 14:20:00-04', '2026-01-20 19:30:00-04'),
    ( 7, 'pedro.chuve@feder.example',               'Pedro',          'Chuvé Peña',         99000007, NULL, 70000107, TRUE,  '2026-02-02 18:40:00-04', '2026-10-30 09:15:00-04', '2026-02-02 18:40:00-04'),
    ( 8, 'rosa.tomicha@feder.example',              'Rosa',           'Tomichá Surubí',     99000008, NULL, 70000108, TRUE,  '2026-03-10 20:05:00-04', '2026-11-12 19:00:00-04', '2026-03-10 20:05:00-04'),
    ( 9, 'elmer.rojas@feder.example',               'Elmer',          'Rojas Cuéllar',      99000009, NULL, 70000109, TRUE,  '2026-11-03 21:10:00-04', '2026-11-11 12:00:00-04', '2026-11-03 21:10:00-04'),
    (10, 'silvia.anez@feder.example',               'Silvia',         'Añez Paz',           99000010, NULL, 70000110, TRUE,  '2026-09-01 10:30:00-04', '2026-09-09 08:20:00-04', '2026-09-01 10:30:00-04'),
    (11, 'daniela.flores@feder.example',            'Daniela',        'Flores Justiniano',  99000011, NULL, 70000111, TRUE,  '2026-03-20 21:15:00-04', '2026-11-14 20:10:00-04', '2026-03-20 21:15:00-04'),
    (12, 'marco.antelo@feder.example',              'Marco',          'Antelo Salvatierra', 99000012, '1A', 70000112, TRUE,  '2026-04-05 11:45:00-04', '2026-11-12 13:15:00-04', '2026-04-05 11:45:00-04'),
    (13, 'patricia.vargas@feder.example',           'Patricia',       'Vargas Lino',        99000013, NULL, 70000113, TRUE,  '2026-06-18 19:20:00-04', '2026-11-14 21:00:00-04', '2026-06-18 19:20:00-04'),
    (14, 'andres.molina@feder.example',             'Andrés',         'Molina Soliz',       99000014, NULL, 70000114, TRUE,  '2026-09-22 08:50:00-04', '2026-11-15 08:25:00-04', '2026-09-22 08:50:00-04')
  ) AS v (id, correo, nombres, apellidos, ci, ci_complemento, telefono, activo,
          fecha_registro, ultimo_acceso, fecha_aceptacion_terminos);

-- Concesiones. Roles: 1 ADMINISTRADOR, 2 PERSONAL_FEDER, 3 PRODUCTOR, 4 CLIENTE.
--   · La 1 es la concesión inicial de ADMINISTRADOR (asignado_por nulo).
--   · PERSONAL_FEDER llega por invitación (con plazo) aceptada por la persona.
--   · PRODUCTOR se concede directamente al aprobar la solicitud; CLIENTE, en el registro público.
--   · Las cuentas 3, 6, 7 y 8 acumulan dos roles vigentes.
INSERT INTO usuario_rol (id, usuario_id, rol_id, asignado_por_id, revocado_por_id, estado, fecha_asignacion,
                         fecha_activacion, fecha_expiracion, fecha_revocacion, motivo, motivo_revocacion) VALUES
    ( 1,  1, 1, NULL, NULL, 'VIGENTE',   '2026-01-12 09:00:00-04', '2026-01-12 09:00:00-04', NULL, NULL,
        'Concesión inicial creada por el procedimiento de instalación', NULL),
    ( 2,  2, 2, 1,    NULL, 'VIGENTE',   '2026-01-13 10:00:00-04', '2026-01-14 08:30:00-04', '2026-01-20 10:00:00-04', NULL,
        'Incorporación como responsable de almacén', NULL),
    ( 3,  3, 2, 1,    NULL, 'VIGENTE',   '2026-01-13 10:10:00-04', '2026-01-13 18:45:00-04', '2026-01-20 10:10:00-04', NULL,
        'Incorporación como responsable de ventas y entregas', NULL),
    ( 4,  5, 2, 1,    1,    'REVOCADA',  '2026-01-13 10:20:00-04', '2026-01-15 09:00:00-04', '2026-01-20 10:20:00-04', '2026-06-30 17:00:00-04',
        'Incorporación como auxiliar de almacén', 'Finalización de su vínculo laboral con FEDER'),
    ( 5,  4, 2, 1,    NULL, 'ANULADA',   '2026-10-20 09:00:00-04', NULL, '2026-10-27 09:00:00-04', '2026-10-27 09:00:00-04',
        'Incorporación como responsable de logística', NULL),
    ( 6,  4, 2, 1,    NULL, 'PENDIENTE', '2026-11-12 09:30:00-04', NULL, '2026-11-19 09:30:00-04', NULL,
        'Segunda invitación como responsable de logística', NULL),
    ( 7,  3, 4, NULL, NULL, 'VIGENTE',   '2026-05-02 20:15:00-04', '2026-05-02 20:15:00-04', NULL, NULL,
        'Activación voluntaria del rol de cliente', NULL),
    ( 8,  6, 4, NULL, NULL, 'VIGENTE',   '2026-01-20 19:30:00-04', '2026-01-20 19:30:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    ( 9,  6, 3, 1,    NULL, 'VIGENTE',   '2026-01-26 11:00:00-04', '2026-01-26 11:00:00-04', NULL, NULL, 'Aprobación de la solicitud de productor', NULL),
    (10,  7, 4, NULL, NULL, 'VIGENTE',   '2026-02-02 18:40:00-04', '2026-02-02 18:40:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    (11,  7, 3, 1,    NULL, 'VIGENTE',   '2026-02-06 10:30:00-04', '2026-02-06 10:30:00-04', NULL, NULL, 'Aprobación de la solicitud de productor', NULL),
    (12,  8, 4, NULL, NULL, 'VIGENTE',   '2026-03-10 20:05:00-04', '2026-03-10 20:05:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    (13,  8, 3, 1,    NULL, 'VIGENTE',   '2026-03-16 09:45:00-04', '2026-03-16 09:45:00-04', NULL, NULL, 'Aprobación de la solicitud de productor', NULL),
    (14,  9, 4, NULL, NULL, 'VIGENTE',   '2026-11-03 21:10:00-04', '2026-11-03 21:10:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    (15, 10, 4, NULL, NULL, 'VIGENTE',   '2026-09-01 10:30:00-04', '2026-09-01 10:30:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    (16, 11, 4, NULL, NULL, 'VIGENTE',   '2026-03-20 21:15:00-04', '2026-03-20 21:15:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    (17, 12, 4, NULL, NULL, 'VIGENTE',   '2026-04-05 11:45:00-04', '2026-04-05 11:45:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    (18, 13, 4, NULL, NULL, 'VIGENTE',   '2026-06-18 19:20:00-04', '2026-06-18 19:20:00-04', NULL, NULL, 'Registro público en la tienda', NULL),
    (19, 14, 4, NULL, NULL, 'VIGENTE',   '2026-09-22 08:50:00-04', '2026-09-22 08:50:00-04', NULL, NULL, 'Registro público en la tienda', NULL);

-- -------------------------------------------------------------------------------------
-- CU2 · Comunidades y productores
-- -------------------------------------------------------------------------------------
-- Municipios reales del departamento de Santa Cruz; las comunidades son ficticias.
INSERT INTO comunidad_agricola (id, nombre, municipio, departamento, descripcion, activa) VALUES
    (1, 'Comunidad Totaizal de San Juan',  'San José de Chiquitos', 'Santa Cruz',
        'Familias que recolectan el fruto de la palmera totaí y elaboran aceite y jabón artesanal.', TRUE),
    (2, 'Comunidad Villa Colmenar',        'Buena Vista',           'Santa Cruz',
        'Apicultores y pequeños caficultores organizados alrededor de un apiario comunitario.', TRUE),
    (3, 'Comunidad El Palmar del Surutú',  'Yapacaní',              'Santa Cruz',
        'Productoras de harina de plátano, chocolate artesanal y conservas de fruta.', TRUE),
    (4, 'Comunidad Nueva Aurora',          'Portachuelo',           'Santa Cruz',
        'Comunidad registrada durante una visita de promoción; sin productores afiliados.', FALSE);

INSERT INTO productor (id, usuario_id, comunidad_agricola_id, codigo_productor, estado_solicitud, fecha_solicitud,
                       fecha_revision, motivo_rechazo, ubicacion_actividad, fecha_afiliacion, activo) VALUES
    (1,  6, 1, 'PROD-0001', 'APROBADA',  '2026-01-22 09:00:00-04', '2026-01-26 11:00:00-04', NULL,
        'Taller familiar de extracción de aceite, zona norte de la comunidad', '2026-01-26', TRUE),
    (2,  7, 2, 'PROD-0002', 'APROBADA',  '2026-02-03 08:15:00-04', '2026-02-06 10:30:00-04', NULL,
        'Apiario comunitario y tostaduría artesanal de Villa Colmenar', '2026-02-06', TRUE),
    (3,  8, 3, 'PROD-0003', 'APROBADA',  '2026-03-11 10:20:00-04', '2026-03-16 09:45:00-04', NULL,
        'Centro comunitario de transformación de alimentos de El Palmar', '2026-03-16', TRUE),
    (4,  9, 2, NULL,        'PENDIENTE', '2026-11-10 16:20:00-04', NULL, NULL,
        'Chaco familiar con colmenas, camino al río Surutú', NULL, FALSE),
    (5, 10, 3, NULL,        'RECHAZADA', '2026-09-02 09:10:00-04', '2026-09-08 15:00:00-04',
        'No se pudo verificar la ubicación de la actividad productiva declarada; puede corregir sus datos y volver a solicitar.',
        'Vivienda en la zona urbana de Yapacaní', NULL, FALSE);

-- -------------------------------------------------------------------------------------
-- CU4 · Catálogo
-- -------------------------------------------------------------------------------------
INSERT INTO categoria (id, nombre, descripcion, activa) VALUES
    (1, 'Aceites naturales',      'Aceites vegetales artesanales de palmeras y semillas nativas.', TRUE),
    (2, 'Cuidado personal',       'Jabones y productos de cuidado personal elaborados a mano.', TRUE),
    (3, 'Miel y derivados',       'Miel de abeja de apiarios comunitarios.', TRUE),
    (4, 'Harinas',                'Harinas de frutos y tubérculos de la región.', TRUE),
    (5, 'Café y cacao',           'Café tostado y chocolate artesanal.', TRUE),
    (6, 'Mermeladas y conservas', 'Mermeladas y conservas de fruta de temporada.', TRUE),
    (7, 'Bebidas artesanales',    'Categoría reservada para una línea de bebidas todavía no incorporada.', FALSE);

-- Precios en bolivianos (Bs), impuestos incluidos. Las cantidades de un producto por UNIDAD son enteras.
INSERT INTO producto (id, categoria_id, codigo, nombre, descripcion, presentacion, unidad_medida, precio_venta,
                      stock_minimo, imagen_url, permite_encargo, activo) VALUES
    ( 1, 1, 'FED-ACE-020', 'Aceite de totaí 20 ml',
        'Aceite de totaí extraído en frío, para el cuidado del cabello y la piel.',
        'Botella de vidrio de 20 ml', 'UNIDAD', 25.00, 20, '/imagenes/productos/aceite-totai-20ml.jpg', TRUE, TRUE),
    ( 2, 1, 'FED-ACE-100', 'Aceite de totaí 100 ml',
        'Aceite de totaí extraído en frío, presentación familiar.',
        'Botella de vidrio de 100 ml', 'UNIDAD', 90.00, 10, '/imagenes/productos/aceite-totai-100ml.jpg', TRUE, TRUE),
    ( 3, 2, 'FED-JAB-090', 'Jabón artesanal de totaí',
        'Jabón de aceite de totaí elaborado en frío, sin colorantes.',
        'Barra de 90 g', 'UNIDAD', 18.00, 30, '/imagenes/productos/jabon-totai-90g.jpg', FALSE, TRUE),
    ( 4, 3, 'FED-MIE-500', 'Miel de abeja multifloral 500 g',
        'Miel pura de abeja de floración silvestre.',
        'Frasco de vidrio de 500 g', 'UNIDAD', 45.00, 15, '/imagenes/productos/miel-500g.jpg', TRUE, TRUE),
    ( 5, 3, 'FED-MIE-GRA', 'Miel de abeja a granel',
        'Miel pura de abeja vendida por peso; el cliente puede llevar su envase.',
        'A granel, por kilogramo', 'KILOGRAMO', 70.00, 10.000, '/imagenes/productos/miel-granel.jpg', FALSE, TRUE),
    ( 6, 4, 'FED-HAR-PLA', 'Harina de plátano',
        'Harina de plátano verde secado al sol, sin gluten.',
        'Bolsa de 1 kg', 'UNIDAD', 22.00, 20, '/imagenes/productos/harina-platano-1kg.jpg', FALSE, TRUE),
    ( 7, 5, 'FED-CAF-250', 'Café tostado molido 250 g',
        'Café de altura tostado y molido de forma artesanal.',
        'Bolsa de 250 g', 'UNIDAD', 38.00, 12, '/imagenes/productos/cafe-molido-250g.jpg', FALSE, TRUE),
    ( 8, 5, 'FED-CAF-GRA', 'Café tostado en grano a granel',
        'Café tostado en grano vendido por peso.',
        'A granel, por libra', 'LIBRA', 26.00, 5.000, '/imagenes/productos/cafe-grano-granel.jpg', TRUE, TRUE),
    ( 9, 5, 'FED-CHO-100', 'Chocolate artesanal 70 % cacao',
        'Chocolate amargo elaborado con cacao silvestre.',
        'Tableta de 100 g', 'UNIDAD', 28.00, 80, '/imagenes/productos/chocolate-70-100g.jpg', TRUE, TRUE),
    (10, 6, 'FED-MER-GUA', 'Mermelada de guayaba',
        'Mermelada de guayaba con trozos de fruta, sin conservantes.',
        'Frasco de 250 g', 'UNIDAD', 24.00, 12, '/imagenes/productos/mermelada-guayaba-250g.jpg', FALSE, TRUE),
    (11, 6, 'FED-MER-MAN', 'Mermelada de mango',
        'Mermelada de mango de temporada; retirada del catálogo hasta la próxima cosecha.',
        'Frasco de 250 g', 'UNIDAD', 24.00, 0, NULL, FALSE, FALSE);

-- -------------------------------------------------------------------------------------
-- CU3 · Producciones y lotes de origen (todavía en poder del productor)
-- -------------------------------------------------------------------------------------
-- La comunidad de origen es la del productor al registrar; su nombre y ubicación se copian como instantánea.
INSERT INTO produccion (id, productor_id, producto_id, comunidad_origen_id, codigo, fecha_inicio,
                        fecha_disponibilidad_estimada, cantidad_estimada, ubicacion_produccion, estado, observacion,
                        nombre_comunidad_origen, municipio_origen, departamento_origen)
SELECT v.id, v.productor_id, v.producto_id, c.id, v.codigo, v.fecha_inicio::date, v.fecha_disponibilidad::date,
       v.cantidad_estimada, v.ubicacion, v.estado, v.observacion, c.nombre, c.municipio, c.departamento
  FROM (VALUES
    ( 1, 1,  1, 'PRD-2026-001', '2026-02-10', '2026-03-05', 300,    'Taller familiar de extracción de aceite', 'TERMINADA',
        'Extracción en frío del fruto de totaí recolectado por la familia.'),
    ( 2, 1,  3, 'PRD-2026-002', '2026-03-01', '2026-03-22', 400,    'Taller familiar de extracción de aceite', 'TERMINADA', NULL),
    ( 3, 2,  4, 'PRD-2026-003', '2026-03-15', '2026-04-12', 200,    'Sala de envasado del apiario comunitario', 'TERMINADA', NULL),
    ( 4, 2,  5, 'PRD-2026-004', NULL,         NULL,         NULL,   'Sala de envasado del apiario comunitario', 'TERMINADA',
        'Miel ya cosechada, registrada directamente como terminada.'),
    ( 5, 2,  7, 'PRD-2026-005', '2026-04-25', '2026-05-18', 150,    'Tostaduría artesanal de la comunidad', 'TERMINADA', NULL),
    ( 6, 3,  6, 'PRD-2026-006', '2026-05-20', '2026-06-05', 250,    'Centro comunitario de secado y molienda', 'TERMINADA', NULL),
    ( 7, 3,  9, 'PRD-2026-007', '2026-06-25', '2026-07-15', 180,    'Cocina comunitaria de transformación de cacao', 'TERMINADA', NULL),
    ( 8, 3, 10, 'PRD-2026-008', '2026-07-25', '2026-08-08', 160,    'Cocina comunitaria de conservas', 'TERMINADA', NULL),
    ( 9, 2,  8, 'PRD-2026-009', '2026-08-01', '2026-09-15', 40.000, 'Tostaduría artesanal de la comunidad', 'CANCELADA',
        'Cancelada: el exceso de lluvias dañó la cosecha de café.'),
    (10, 3,  6, 'PRD-2026-010', '2026-09-25', '2026-10-12', 120,    'Centro comunitario de secado y molienda', 'TERMINADA', NULL),
    (11, 1,  2, 'PRD-2026-011', '2026-10-20', '2026-12-05', 80,     'Taller familiar de extracción de aceite', 'EN_CURSO',
        'Primera producción de la presentación de 100 ml.'),
    (12, 3,  9, 'PRD-2026-012', '2026-11-25', '2026-12-20', 200,    'Cocina comunitaria de transformación de cacao', 'PLANIFICADA',
        'Producción prevista para la campaña de fin de año.')
  ) AS v (id, productor_id, producto_id, codigo, fecha_inicio, fecha_disponibilidad, cantidad_estimada, ubicacion, estado, observacion)
  JOIN productor p ON p.id = v.productor_id
  JOIN comunidad_agricola c ON c.id = p.comunidad_agricola_id;

-- Estado del lote: AGOTADO si todo lo ofrecido ya fue comprado; RETIRADO si el productor dejó de ofrecerlo.
INSERT INTO lote_produccion (id, produccion_id, codigo_lote, fecha_elaboracion, fecha_vencimiento, cantidad_producida,
                             cantidad_ofrecida, precio_ofrecido, estado) VALUES
    ( 1,  1, 'LP-2026-001', '2026-03-02', '2027-03-02', 300,    250,    15.00, 'DISPONIBLE'),
    ( 2,  2, 'LP-2026-002', '2026-03-20', NULL,         400,    300,     9.00, 'DISPONIBLE'),
    ( 3,  3, 'LP-2026-003', '2026-04-10', '2028-04-10', 200,    150,    28.00, 'AGOTADO'),
    ( 4,  4, 'LP-2026-004', '2026-04-12', '2028-04-12', 85.500, 60.000, 42.00, 'DISPONIBLE'),
    ( 5,  5, 'LP-2026-005', '2026-05-15', '2027-05-15', 150,    120,    24.00, 'DISPONIBLE'),
    ( 6,  5, 'LP-2026-006', '2026-05-16', '2027-05-16', 60,     60,     24.00, 'RETIRADO'),
    ( 7,  6, 'LP-2026-007', '2026-06-01', '2026-12-01', 250,    150,    13.00, 'AGOTADO'),
    ( 8,  7, 'LP-2026-008', '2026-07-10', '2027-01-10', 180,    150,    17.00, 'DISPONIBLE'),
    ( 9,  8, 'LP-2026-009', '2026-08-05', '2027-08-05', 160,    120,    14.00, 'DISPONIBLE'),
    (10, 10, 'LP-2026-010', '2026-10-10', '2027-04-10', 120,    60,     13.50, 'DISPONIBLE'),
    (11, 11, 'LP-2026-011', '2026-11-05', '2027-11-05', 40,     40,     60.00, 'DISPONIBLE');

-- -------------------------------------------------------------------------------------
-- CU4 · Compras de FEDER a productores, lotes de FEDER e inventario
-- -------------------------------------------------------------------------------------
-- total = suma de round(cantidad_comprada × precio_unitario, 2); no se recalcula por rechazos ni cancelaciones.
--   1 RECIBIDA y pagada (con 5 unidades rechazadas) · 2 RECIBIDA y pagada · 3 RECIBIDA_PARCIAL (10 rechazadas,
--   30 pendientes) · 4 CANCELADA (línea cancelada por completo) · 5 REGISTRADA (nada recibido todavía).
INSERT INTO compra_feder (id, productor_id, codigo, fecha_compra, estado, total, fecha_pago_productor, observacion) VALUES
    (1, 1, 'CMP-2026-001', '2026-03-23 10:00:00-04', 'RECIBIDA',         5250.00, '2026-04-02 15:00:00-04',
        'Primera compra a la productora de la comunidad Totaizal de San Juan.'),
    (2, 2, 'CMP-2026-002', '2026-05-20 09:00:00-04', 'RECIBIDA',         8700.00, '2026-06-05 11:00:00-04',
        'Miel y café entregados en dos viajes.'),
    (3, 3, 'CMP-2026-003', '2026-08-12 11:00:00-04', 'RECIBIDA_PARCIAL', 5390.00, NULL,
        'Quedan pendientes 30 tabletas de chocolate.'),
    (4, 3, 'CMP-2026-004', '2026-10-15 10:00:00-04', 'CANCELADA',         810.00, NULL,
        'La productora no pudo entregar el lote.'),
    (5, 1, 'CMP-2026-005', '2026-11-10 15:00:00-04', 'REGISTRADA',       1800.00, NULL,
        'Compra acordada del primer lote de aceite de 100 ml; entrega prevista para fines de noviembre.');

INSERT INTO detalle_compra (id, compra_id, lote_origen_id, cantidad_comprada, precio_unitario, cantidad_recibida,
                            cantidad_rechazada, cantidad_cancelada) VALUES
    ( 1, 1,  1, 200,    15.00, 200,    0,  0),
    ( 2, 1,  2, 250,     9.00, 245,    5,  0),
    ( 3, 2,  3, 150,    28.00, 150,    0,  0),
    ( 4, 2,  4, 50.000, 42.00, 50.000, 0,  0),
    ( 5, 2,  5, 100,    24.00, 100,    0,  0),
    ( 6, 3,  7, 150,    13.00, 150,    0,  0),
    ( 7, 3,  8, 120,    17.00, 80,     10, 0),
    ( 8, 3,  9, 100,    14.00, 100,    0,  0),
    ( 9, 4, 10, 60,     13.50, 0,      0,  60),
    (10, 5, 11, 30,     60.00, 0,      0,  0);

-- Solo la mercancía aceptada tiene lote de FEDER. Saldos al 2026-11-15, coherentes con los movimientos
-- (cantidad_disponible) y con las reservas ACTIVA (cantidad_reservada).
INSERT INTO lote_feder (id, detalle_compra_id, codigo, fecha_ingreso, cantidad_disponible, cantidad_reservada,
                        ubicacion_almacen, estado) VALUES
    (1, 1, 'LF-2026-001', '2026-03-26 10:15:00-04', 183,    2, 'Estante A-01', 'DISPONIBLE'),
    (2, 2, 'LF-2026-002', '2026-03-26 10:15:00-04', 234,    0, 'Estante A-02', 'DISPONIBLE'),
    (3, 3, 'LF-2026-003', '2026-05-23 09:40:00-04', 147,    2, 'Estante B-01', 'DISPONIBLE'),
    (4, 4, 'LF-2026-004', '2026-05-23 09:40:00-04', 45.900, 0, 'Estante B-02, bidones', 'DISPONIBLE'),
    (5, 5, 'LF-2026-005', '2026-05-23 09:40:00-04', 95,     1, 'Estante B-03', 'DISPONIBLE'),
    (6, 6, 'LF-2026-006', '2026-08-15 10:30:00-04', 141,    0, 'Estante C-01', 'DISPONIBLE'),
    (7, 7, 'LF-2026-007', '2026-08-15 10:30:00-04', 76,     6, 'Estante C-02, ambiente fresco', 'DISPONIBLE'),
    (8, 8, 'LF-2026-008', '2026-08-15 10:30:00-04', 95,     0, 'Estante C-03', 'DISPONIBLE');

-- -------------------------------------------------------------------------------------
-- CU5 · Pedidos
-- -------------------------------------------------------------------------------------
--  1 ENTREGADO (delivery, QR)            7 ENTREGADO (recojo, QR; con una devolución solicitada)
--  2 ENTREGADO (recojo, efectivo)        8 ENCARGO en ESPERANDO_ABASTECIMIENTO (sin stock, con producción de respaldo)
--  3 CANCELADO por el cliente (reembolso) 9 DESPACHADO (delivery en camino)
--  4 ENTREGADO (con devolución y reembolso) 10 CONFIRMADO (pagado, pendiente de preparar)
--  5 CANCELADO por reserva expirada      11 PENDIENTE (reservas con plazo de pago)
--  6 ENTREGADO (delivery, QR)
INSERT INTO pedido (id, usuario_id, codigo, tipo, estado, fecha_creacion, subtotal, costo_envio, total,
                    fecha_estimada_disponibilidad, clave_idempotencia, observacion) VALUES
    ( 1, 11, 'PED-2026-0001', 'INMEDIATO', 'ENTREGADO',  '2026-04-06 10:20:00-04', 204.00, 15.00, 219.00, NULL,
        'conf-20260406-11-7c1e4a', 'Entregar por la tarde.'),
    ( 2, 12, 'PED-2026-0002', 'INMEDIATO', 'ENTREGADO',  '2026-06-10 11:05:00-04', 386.00,  0.00, 386.00, NULL,
        'conf-20260610-12-2b9f03', 'Pago en efectivo al recoger.'),
    ( 3, 12, 'PED-2026-0003', 'INMEDIATO', 'CANCELADO',  '2026-07-03 14:10:00-04',  76.00,  0.00,  76.00, NULL,
        'conf-20260703-12-9d27c5', NULL),
    ( 4, 13, 'PED-2026-0004', 'INMEDIATO', 'ENTREGADO',  '2026-08-20 18:00:00-04', 294.00, 20.00, 314.00, NULL,
        'conf-20260820-13-51ae8b', NULL),
    ( 5,  3, 'PED-2026-0005', 'INMEDIATO', 'CANCELADO',  '2026-09-14 19:00:00-04',  48.00,  0.00,  48.00, NULL,
        'conf-20260914-03-e03b71', NULL),
    ( 6, 14, 'PED-2026-0006', 'INMEDIATO', 'ENTREGADO',  '2026-10-05 12:30:00-04', 364.00, 15.00, 379.00, NULL,
        'conf-20261005-14-0a6d92', 'Regalo: sin la nota de precios.'),
    ( 7, 11, 'PED-2026-0007', 'INMEDIATO', 'ENTREGADO',  '2026-11-06 17:45:00-04', 135.50,  0.00, 135.50, NULL,
        'conf-20261106-11-c84f16', 'Llevaré mi propio envase para la miel.'),
    ( 8, 11, 'PED-2026-0008', 'ENCARGO',   'ESPERANDO_ABASTECIMIENTO', '2026-11-08 10:00:00-04', 1080.00, 0.00, 1080.00, '2026-12-12',
        'conf-20261108-11-6f5b2d', 'Encargo para regalos de fin de año.'),
    ( 9, 12, 'PED-2026-0009', 'INMEDIATO', 'DESPACHADO', '2026-11-12 13:20:00-04', 178.00, 15.00, 193.00, NULL,
        'conf-20261112-12-b7d340', NULL),
    (10, 13, 'PED-2026-0010', 'INMEDIATO', 'CONFIRMADO', '2026-11-13 16:05:00-04', 218.00,  0.00, 218.00, NULL,
        'conf-20261113-13-3e9a58', NULL),
    (11, 14, 'PED-2026-0011', 'INMEDIATO', 'PENDIENTE',  '2026-11-15 08:40:00-04', 128.00, 15.00, 143.00, NULL,
        'conf-20261115-14-d16c7f', NULL);

-- precio_unitario y descripcion_venta son copias del catálogo en el momento de confirmar. El aceite de 20 ml
-- costaba Bs 24,00 hasta el 2026-09-01 (pedido 1) y Bs 25,00 después (pedidos 6 y 10).
INSERT INTO detalle_pedido (id, pedido_id, producto_id, produccion_id, cantidad, precio_unitario, subtotal, descripcion_venta) VALUES
    ( 1,  1,  1, NULL, 4,     24.00,   96.00, 'Aceite de totaí 20 ml - Botella de vidrio de 20 ml (UNIDAD)'),
    ( 2,  1,  3, NULL, 6,     18.00,  108.00, 'Jabón artesanal de totaí - Barra de 90 g (UNIDAD)'),
    ( 3,  2,  4, NULL, 3,     45.00,  135.00, 'Miel de abeja multifloral 500 g - Frasco de vidrio de 500 g (UNIDAD)'),
    ( 4,  2,  5, NULL, 2.500, 70.00,  175.00, 'Miel de abeja a granel - A granel, por kilogramo (KILOGRAMO)'),
    ( 5,  2,  7, NULL, 2,     38.00,   76.00, 'Café tostado molido 250 g - Bolsa de 250 g (UNIDAD)'),
    ( 6,  3,  7, NULL, 2,     38.00,   76.00, 'Café tostado molido 250 g - Bolsa de 250 g (UNIDAD)'),
    ( 7,  4,  6, NULL, 5,     22.00,  110.00, 'Harina de plátano - Bolsa de 1 kg (UNIDAD)'),
    ( 8,  4,  9, NULL, 4,     28.00,  112.00, 'Chocolate artesanal 70 % cacao - Tableta de 100 g (UNIDAD)'),
    ( 9,  4, 10, NULL, 3,     24.00,   72.00, 'Mermelada de guayaba - Frasco de 250 g (UNIDAD)'),
    (10,  5, 10, NULL, 2,     24.00,   48.00, 'Mermelada de guayaba - Frasco de 250 g (UNIDAD)'),
    (11,  6,  1, NULL, 10,    25.00,  250.00, 'Aceite de totaí 20 ml - Botella de vidrio de 20 ml (UNIDAD)'),
    (12,  6,  7, NULL, 3,     38.00,  114.00, 'Café tostado molido 250 g - Bolsa de 250 g (UNIDAD)'),
    (13,  7,  5, NULL, 1.250, 70.00,   87.50, 'Miel de abeja a granel - A granel, por kilogramo (KILOGRAMO)'),
    (14,  7, 10, NULL, 2,     24.00,   48.00, 'Mermelada de guayaba - Frasco de 250 g (UNIDAD)'),
    (15,  8,  2, 11,   12,    90.00, 1080.00, 'Aceite de totaí 100 ml - Botella de vidrio de 100 ml (UNIDAD)'),
    (16,  9,  6, NULL, 4,     22.00,   88.00, 'Harina de plátano - Bolsa de 1 kg (UNIDAD)'),
    (17,  9,  3, NULL, 5,     18.00,   90.00, 'Jabón artesanal de totaí - Barra de 90 g (UNIDAD)'),
    (18, 10,  9, NULL, 6,     28.00,  168.00, 'Chocolate artesanal 70 % cacao - Tableta de 100 g (UNIDAD)'),
    (19, 10,  1, NULL, 2,     25.00,   50.00, 'Aceite de totaí 20 ml - Botella de vidrio de 20 ml (UNIDAD)'),
    (20, 11,  4, NULL, 2,     45.00,   90.00, 'Miel de abeja multifloral 500 g - Frasco de vidrio de 500 g (UNIDAD)'),
    (21, 11,  7, NULL, 1,     38.00,   38.00, 'Café tostado molido 250 g - Bolsa de 250 g (UNIDAD)');

-- Reservas: CONSUMIDA al despachar, LIBERADA al cancelar, EXPIRADA al vencer el plazo de pago, ACTIVA si
-- el pedido sigue vigente. El plazo (fecha_expiracion) se anula con la confirmación comercial.
INSERT INTO reserva_stock (id, lote_feder_id, detalle_pedido_id, cantidad, estado, fecha_creacion, fecha_expiracion, fecha_cierre) VALUES
    ( 1, 1,  1, 4,     'CONSUMIDA', '2026-04-06 10:20:00-04', NULL, '2026-04-07 09:00:00-04'),
    ( 2, 2,  2, 6,     'CONSUMIDA', '2026-04-06 10:20:00-04', NULL, '2026-04-07 09:00:00-04'),
    ( 3, 3,  3, 3,     'CONSUMIDA', '2026-06-10 11:05:00-04', NULL, '2026-06-11 09:30:00-04'),
    ( 4, 4,  4, 2.500, 'CONSUMIDA', '2026-06-10 11:05:00-04', NULL, '2026-06-11 09:30:00-04'),
    ( 5, 5,  5, 2,     'CONSUMIDA', '2026-06-10 11:05:00-04', NULL, '2026-06-11 09:30:00-04'),
    ( 6, 5,  6, 2,     'LIBERADA',  '2026-07-03 14:10:00-04', NULL, '2026-07-04 09:20:00-04'),
    ( 7, 6,  7, 5,     'CONSUMIDA', '2026-08-20 18:00:00-04', NULL, '2026-08-21 10:00:00-04'),
    ( 8, 7,  8, 4,     'CONSUMIDA', '2026-08-20 18:00:00-04', NULL, '2026-08-21 10:00:00-04'),
    ( 9, 8,  9, 3,     'CONSUMIDA', '2026-08-20 18:00:00-04', NULL, '2026-08-21 10:00:00-04'),
    (10, 8, 10, 2,     'EXPIRADA',  '2026-09-14 19:00:00-04', '2026-09-15 07:00:00-04', '2026-09-15 07:05:00-04'),
    (11, 1, 11, 10,    'CONSUMIDA', '2026-10-05 12:30:00-04', NULL, '2026-10-06 09:15:00-04'),
    (12, 5, 12, 3,     'CONSUMIDA', '2026-10-05 12:30:00-04', NULL, '2026-10-06 09:15:00-04'),
    (13, 4, 13, 1.250, 'CONSUMIDA', '2026-11-06 17:45:00-04', NULL, '2026-11-07 10:30:00-04'),
    (14, 8, 14, 2,     'CONSUMIDA', '2026-11-06 17:45:00-04', NULL, '2026-11-07 10:30:00-04'),
    (15, 6, 16, 4,     'CONSUMIDA', '2026-11-12 13:20:00-04', NULL, '2026-11-14 09:00:00-04'),
    (16, 2, 17, 5,     'CONSUMIDA', '2026-11-12 13:20:00-04', NULL, '2026-11-14 09:00:00-04'),
    (17, 7, 18, 6,     'ACTIVA',    '2026-11-13 16:05:00-04', NULL, NULL),
    (18, 1, 19, 2,     'ACTIVA',    '2026-11-13 16:05:00-04', NULL, NULL),
    (19, 3, 20, 2,     'ACTIVA',    '2026-11-15 08:40:00-04', '2026-11-15 20:40:00-04', NULL),
    (20, 5, 21, 1,     'ACTIVA',    '2026-11-15 08:40:00-04', '2026-11-15 20:40:00-04', NULL);

-- Movimientos de inventario (primera parte: los que no dependen de una devolución).
-- Las ENTRADA_COMPRA comparten su clave de operación con el registro de recepción de la bitácora.
INSERT INTO movimiento_inventario (id, lote_feder_id, usuario_id, reserva_id, devolucion_id, tipo, cantidad, fecha, motivo, clave_operacion) VALUES
    ( 1, 1, 2, NULL, NULL, 'ENTRADA_COMPRA',  200,    '2026-03-26 10:15:00-04', NULL, 'op-rec-cmp001-dc01-01'),
    ( 2, 2, 2, NULL, NULL, 'ENTRADA_COMPRA',  245,    '2026-03-26 10:15:00-04', NULL, 'op-rec-cmp001-dc02-01'),
    ( 3, 3, 2, NULL, NULL, 'ENTRADA_COMPRA',  100,    '2026-05-23 09:40:00-04', NULL, 'op-rec-cmp002-dc03-01'),
    ( 4, 4, 2, NULL, NULL, 'ENTRADA_COMPRA',  50.000, '2026-05-23 09:40:00-04', NULL, 'op-rec-cmp002-dc04-01'),
    ( 5, 5, 2, NULL, NULL, 'ENTRADA_COMPRA',  100,    '2026-05-23 09:40:00-04', NULL, 'op-rec-cmp002-dc05-01'),
    ( 6, 3, 2, NULL, NULL, 'ENTRADA_COMPRA',  50,     '2026-05-30 10:05:00-04', NULL, 'op-rec-cmp002-dc03-02'),
    ( 7, 6, 2, NULL, NULL, 'ENTRADA_COMPRA',  150,    '2026-08-15 10:30:00-04', NULL, 'op-rec-cmp003-dc06-01'),
    ( 8, 7, 2, NULL, NULL, 'ENTRADA_COMPRA',  80,     '2026-08-15 10:30:00-04', NULL, 'op-rec-cmp003-dc07-01'),
    ( 9, 8, 2, NULL, NULL, 'ENTRADA_COMPRA',  100,    '2026-08-15 10:30:00-04', NULL, 'op-rec-cmp003-dc08-01'),
    (10, 1, 2, 1,    NULL, 'SALIDA_VENTA',    4,      '2026-04-07 09:00:00-04', NULL, 'op-desp-ped0001-res01'),
    (11, 2, 2, 2,    NULL, 'SALIDA_VENTA',    6,      '2026-04-07 09:00:00-04', NULL, 'op-desp-ped0001-res02'),
    (12, 3, 2, 3,    NULL, 'SALIDA_VENTA',    3,      '2026-06-11 09:30:00-04', NULL, 'op-desp-ped0002-res03'),
    (13, 4, 2, 4,    NULL, 'SALIDA_VENTA',    2.500,  '2026-06-11 09:30:00-04', NULL, 'op-desp-ped0002-res04'),
    (14, 5, 2, 5,    NULL, 'SALIDA_VENTA',    2,      '2026-06-11 09:30:00-04', NULL, 'op-desp-ped0002-res05'),
    (17, 1, 2, NULL, NULL, 'BAJA_DANADO',     3,      '2026-07-15 16:30:00-04',
        'Tres botellas rotas al reordenar el estante A-01', 'op-baja-lf001-20260715'),
    (18, 6, 2, 7,    NULL, 'SALIDA_VENTA',    5,      '2026-08-21 10:00:00-04', NULL, 'op-desp-ped0004-res07'),
    (19, 7, 2, 8,    NULL, 'SALIDA_VENTA',    4,      '2026-08-21 10:00:00-04', NULL, 'op-desp-ped0004-res08'),
    (20, 8, 2, 9,    NULL, 'SALIDA_VENTA',    3,      '2026-08-21 10:00:00-04', NULL, 'op-desp-ped0004-res09'),
    (21, 4, 2, NULL, NULL, 'AJUSTE_NEGATIVO', 0.350,  '2026-09-30 17:00:00-04',
        'Diferencia en el conteo mensual: merma por fraccionamiento de la miel a granel', 'op-ajuste-lf004-20260930'),
    (22, 1, 2, 11,   NULL, 'SALIDA_VENTA',    10,     '2026-10-06 09:15:00-04', NULL, 'op-desp-ped0006-res11'),
    (23, 5, 2, 12,   NULL, 'SALIDA_VENTA',    3,      '2026-10-06 09:15:00-04', NULL, 'op-desp-ped0006-res12'),
    (24, 4, 2, 13,   NULL, 'SALIDA_VENTA',    1.250,  '2026-11-07 10:30:00-04', NULL, 'op-desp-ped0007-res13'),
    (25, 8, 2, 14,   NULL, 'SALIDA_VENTA',    2,      '2026-11-07 10:30:00-04', NULL, 'op-desp-ped0007-res14'),
    (26, 6, 2, 15,   NULL, 'SALIDA_VENTA',    4,      '2026-11-14 09:00:00-04', NULL, 'op-desp-ped0009-res15'),
    (27, 2, 2, 16,   NULL, 'SALIDA_VENTA',    5,      '2026-11-14 09:00:00-04', NULL, 'op-desp-ped0009-res16');

INSERT INTO historial_pedido (id, pedido_id, usuario_id, estado_anterior, estado_nuevo, fecha, comentario) VALUES
    ( 1,  1, 11,   NULL,             'PENDIENTE',      '2026-04-06 10:20:00-04', 'Pedido creado por el cliente'),
    ( 2,  1, NULL, 'PENDIENTE',      'CONFIRMADO',     '2026-04-06 10:32:00-04', 'Pago por QR confirmado por la pasarela'),
    ( 3,  1, 2,    'CONFIRMADO',     'EN_PREPARACION', '2026-04-06 15:00:00-04', NULL),
    ( 4,  1, 2,    'EN_PREPARACION', 'DESPACHADO',     '2026-04-07 09:00:00-04', 'Enviado a domicilio'),
    ( 5,  1, 3,    'DESPACHADO',     'ENTREGADO',      '2026-04-07 16:40:00-04', NULL),
    ( 6,  2, 12,   NULL,             'PENDIENTE',      '2026-06-10 11:05:00-04', 'Pedido creado por el cliente'),
    ( 7,  2, 3,    'PENDIENTE',      'CONFIRMADO',     '2026-06-10 11:40:00-04', 'Aceptado para pago en efectivo contra entrega'),
    ( 8,  2, 2,    'CONFIRMADO',     'EN_PREPARACION', '2026-06-10 16:00:00-04', NULL),
    ( 9,  2, 2,    'EN_PREPARACION', 'DESPACHADO',     '2026-06-11 09:30:00-04', 'Listo para recojo en FEDER'),
    (10,  2, 3,    'DESPACHADO',     'ENTREGADO',      '2026-06-12 10:15:00-04', 'Entregado y cobrado en efectivo'),
    (11,  3, 12,   NULL,             'PENDIENTE',      '2026-07-03 14:10:00-04', 'Pedido creado por el cliente'),
    (12,  3, NULL, 'PENDIENTE',      'CONFIRMADO',     '2026-07-03 14:20:00-04', 'Pago por QR confirmado por la pasarela'),
    (13,  3, 12,   'CONFIRMADO',     'CANCELADO',      '2026-07-04 09:20:00-04', 'El cliente canceló el pedido antes de la preparación'),
    (14,  4, 13,   NULL,             'PENDIENTE',      '2026-08-20 18:00:00-04', 'Pedido creado por el cliente'),
    (15,  4, NULL, 'PENDIENTE',      'CONFIRMADO',     '2026-08-20 18:12:00-04', 'Pago con tarjeta confirmado por la pasarela'),
    (16,  4, 2,    'CONFIRMADO',     'EN_PREPARACION', '2026-08-21 08:30:00-04', NULL),
    (17,  4, 2,    'EN_PREPARACION', 'DESPACHADO',     '2026-08-21 10:00:00-04', 'Enviado a domicilio'),
    (18,  4, 3,    'DESPACHADO',     'ENTREGADO',      '2026-08-22 11:20:00-04', NULL),
    (19,  5, 3,    NULL,             'PENDIENTE',      '2026-09-14 19:00:00-04', 'Pedido creado por el cliente'),
    (20,  5, NULL, 'PENDIENTE',      'CANCELADO',      '2026-09-15 07:05:00-04', 'reserva expirada'),
    (21,  6, 14,   NULL,             'PENDIENTE',      '2026-10-05 12:30:00-04', 'Pedido creado por el cliente'),
    (22,  6, NULL, 'PENDIENTE',      'CONFIRMADO',     '2026-10-05 12:40:00-04', 'Pago por QR confirmado por la pasarela'),
    (23,  6, 2,    'CONFIRMADO',     'EN_PREPARACION', '2026-10-05 16:00:00-04', NULL),
    (24,  6, 2,    'EN_PREPARACION', 'DESPACHADO',     '2026-10-06 09:15:00-04', 'Enviado a domicilio'),
    (25,  6, 3,    'DESPACHADO',     'ENTREGADO',      '2026-10-07 15:10:00-04', NULL),
    (26,  7, 11,   NULL,             'PENDIENTE',      '2026-11-06 17:45:00-04', 'Pedido creado por el cliente'),
    (27,  7, NULL, 'PENDIENTE',      'CONFIRMADO',     '2026-11-06 17:52:00-04', 'Pago por QR confirmado por la pasarela'),
    (28,  7, 2,    'CONFIRMADO',     'EN_PREPARACION', '2026-11-07 08:45:00-04', NULL),
    (29,  7, 2,    'EN_PREPARACION', 'DESPACHADO',     '2026-11-07 10:30:00-04', 'Listo para recojo en FEDER'),
    (30,  7, 3,    'DESPACHADO',     'ENTREGADO',      '2026-11-09 09:50:00-04', NULL),
    (31,  8, 11,   NULL,             'PENDIENTE',      '2026-11-08 10:00:00-04', 'Encargo creado por el cliente'),
    (32,  8, 3,    'PENDIENTE',      'ESPERANDO_ABASTECIMIENTO', '2026-11-08 11:15:00-04',
        'Encargo aceptado; respaldado por la producción PRD-2026-011'),
    (33,  9, 12,   NULL,             'PENDIENTE',      '2026-11-12 13:20:00-04', 'Pedido creado por el cliente'),
    (34,  9, NULL, 'PENDIENTE',      'CONFIRMADO',     '2026-11-12 13:24:00-04', 'Pago con tarjeta confirmado por la pasarela'),
    (35,  9, 2,    'CONFIRMADO',     'EN_PREPARACION', '2026-11-13 09:00:00-04', NULL),
    (36,  9, 2,    'EN_PREPARACION', 'DESPACHADO',     '2026-11-14 09:00:00-04', 'Enviado a domicilio'),
    (37, 10, 13,   NULL,             'PENDIENTE',      '2026-11-13 16:05:00-04', 'Pedido creado por el cliente'),
    (38, 10, NULL, 'PENDIENTE',      'CONFIRMADO',     '2026-11-13 16:15:00-04', 'Pago por QR confirmado por la pasarela'),
    (39, 11, 14,   NULL,             'PENDIENTE',      '2026-11-15 08:40:00-04', 'Pedido creado por el cliente');

-- -------------------------------------------------------------------------------------
-- CU6 · Pagos, facturas, entregas y posventa
-- -------------------------------------------------------------------------------------
-- Cada reintento es un pago nuevo (pedido 4: tarjeta rechazada y después aprobada). El efectivo del
-- pedido 2 se cobró en la entrega. El pedido 8 (encargo) todavía no tiene pagos.
INSERT INTO pago (id, pedido_id, metodo, monto, estado, fecha_registro, fecha_pago, referencia_externa, clave_idempotencia) VALUES
    ( 1,  1, 'QR',       219.00, 'PAGADO',    '2026-04-06 10:21:00-04', '2026-04-06 10:32:00-04', 'QR-20260406-000184',  'pago-ped0001-01'),
    ( 2,  2, 'EFECTIVO', 386.00, 'PAGADO',    '2026-06-10 11:40:00-04', '2026-06-12 10:15:00-04', NULL,                  'pago-ped0002-01'),
    ( 3,  3, 'QR',        76.00, 'PAGADO',    '2026-07-03 14:11:00-04', '2026-07-03 14:20:00-04', 'QR-20260703-000412',  'pago-ped0003-01'),
    ( 4,  4, 'TARJETA',  314.00, 'RECHAZADO', '2026-08-20 18:01:00-04', NULL,                     'TRJ-20260820-004411', 'pago-ped0004-01'),
    ( 5,  4, 'TARJETA',  314.00, 'PAGADO',    '2026-08-20 18:08:00-04', '2026-08-20 18:12:00-04', 'TRJ-20260820-004418', 'pago-ped0004-02'),
    ( 6,  5, 'QR',        48.00, 'RECHAZADO', '2026-09-14 19:01:00-04', NULL,                     'QR-20260914-000733',  'pago-ped0005-01'),
    ( 7,  6, 'QR',       379.00, 'PAGADO',    '2026-10-05 12:31:00-04', '2026-10-05 12:40:00-04', 'QR-20261005-000958',  'pago-ped0006-01'),
    ( 8,  7, 'QR',       135.50, 'PAGADO',    '2026-11-06 17:46:00-04', '2026-11-06 17:52:00-04', 'QR-20261106-001120',  'pago-ped0007-01'),
    ( 9,  9, 'TARJETA',  193.00, 'PAGADO',    '2026-11-12 13:21:00-04', '2026-11-12 13:24:00-04', 'TRJ-20261112-005902', 'pago-ped0009-01'),
    (10, 10, 'QR',       218.00, 'PAGADO',    '2026-11-13 16:06:00-04', '2026-11-13 16:15:00-04', 'QR-20261113-001187',  'pago-ped0010-01'),
    (11, 11, 'QR',       143.00, 'PENDIENTE', '2026-11-15 08:41:00-04', NULL,                     NULL,                  'pago-ped0011-01');

-- Una factura por pedido con el pago completo; su total es el del pedido. La 3 se anuló tras la cancelación.
INSERT INTO factura (id, pedido_id, numero, fecha_emision, nit_ci, razon_social, total, codigo_autorizacion, estado, fecha_anulacion) VALUES
    (1,  1, 'FAC-2026-000001', '2026-04-06 10:32:00-04', '99000011', 'Daniela Flores Justiniano',  219.00, NULL, 'VIGENTE', NULL),
    (2,  2, 'FAC-2026-000002', '2026-06-12 10:15:00-04', '99000012', 'Marco Antelo Salvatierra',   386.00, NULL, 'VIGENTE', NULL),
    (3,  3, 'FAC-2026-000003', '2026-07-03 14:20:00-04', '99000012', 'Marco Antelo Salvatierra',    76.00, NULL, 'ANULADA', '2026-07-06 10:00:00-04'),
    (4,  4, 'FAC-2026-000004', '2026-08-20 18:12:00-04', '99000013', 'Patricia Vargas Lino',       314.00, NULL, 'VIGENTE', NULL),
    (5,  6, 'FAC-2026-000005', '2026-10-05 12:40:00-04', '99000014', 'Andrés Molina Soliz',        379.00, NULL, 'VIGENTE', NULL),
    (6,  7, 'FAC-2026-000006', '2026-11-06 17:52:00-04', '99000011', 'Daniela Flores Justiniano',  135.50, NULL, 'VIGENTE', NULL),
    (7,  9, 'FAC-2026-000007', '2026-11-12 13:24:00-04', '99000012', 'Marco Antelo Salvatierra',   193.00, NULL, 'VIGENTE', NULL),
    (8, 10, 'FAC-2026-000008', '2026-11-13 16:15:00-04', '99000013', 'Patricia Vargas Lino',       218.00, NULL, 'VIGENTE', NULL);

-- Todo pedido tiene exactamente una entrega. En RECOJO el costo de envío es 0 y no se usa EN_CAMINO.
INSERT INTO entrega (id, pedido_id, modalidad, estado, direccion, referencia, nombre_receptor, telefono_contacto,
                     fecha_programada, fecha_entrega, observacion) VALUES
    ( 1,  1, 'DELIVERY', 'ENTREGADA', 'Barrio Los Tajibos, calle 4 N.º 120, Santa Cruz de la Sierra', 'Casa de rejas verdes, frente a la plazuela',
        'Daniela Flores Justiniano', '70000111', '2026-04-07 15:00:00-04', '2026-04-07 16:40:00-04', NULL),
    ( 2,  2, 'RECOJO',   'ENTREGADA', NULL, NULL,
        'Marco Antelo Salvatierra', '70000112', '2026-06-12 10:00:00-04', '2026-06-12 10:15:00-04', 'Pagó en efectivo al recoger.'),
    ( 3,  3, 'RECOJO',   'CANCELADA', NULL, NULL,
        'Marco Antelo Salvatierra', '70000112', NULL, NULL, 'Cancelada junto con el pedido, antes del despacho.'),
    ( 4,  4, 'DELIVERY', 'ENTREGADA', 'Urbanización El Trompillo, avenida Los Cusis N.º 58, Santa Cruz de la Sierra', 'Portón negro, tocar el timbre',
        'Patricia Vargas Lino', '70000113', '2026-08-22 10:00:00-04', '2026-08-22 11:20:00-04', NULL),
    ( 5,  5, 'RECOJO',   'CANCELADA', NULL, NULL,
        'Jorge Suárez Vaca', '70000103', NULL, NULL, 'Cancelada por expiración de la reserva.'),
    ( 6,  6, 'DELIVERY', 'ENTREGADA', 'Barrio Las Palmeras, calle Los Mangales N.º 301, Santa Cruz de la Sierra', 'Edificio de tres pisos, departamento 2B',
        'Andrés Molina Soliz', '70000114', '2026-10-07 14:00:00-04', '2026-10-07 15:10:00-04', NULL),
    ( 7,  7, 'RECOJO',   'ENTREGADA', NULL, NULL,
        'Daniela Flores Justiniano', '70000111', '2026-11-09 09:30:00-04', '2026-11-09 09:50:00-04', NULL),
    ( 8,  8, 'RECOJO',   'PENDIENTE', NULL, NULL,
        'Daniela Flores Justiniano', '70000111', NULL, NULL, NULL),
    ( 9,  9, 'DELIVERY', 'EN_CAMINO', 'Barrio Villa Fátima, calle Los Pinos N.º 77, Santa Cruz de la Sierra', 'Tienda de barrio en la esquina',
        'Marco Antelo Salvatierra', '70000112', '2026-11-16 10:00:00-04', NULL, NULL),
    (10, 10, 'RECOJO',   'PENDIENTE', NULL, NULL,
        'Patricia Vargas Lino', '70000113', NULL, NULL, NULL),
    (11, 11, 'DELIVERY', 'PENDIENTE', 'Barrio Las Palmeras, calle Los Mangales N.º 301, Santa Cruz de la Sierra', 'Edificio de tres pisos, departamento 2B',
        'Andrés Molina Soliz', '70000114', NULL, NULL, NULL);

-- Devoluciones (cada una sobre un despacho concreto):
--   1 REPOSICIÓN completada: 1 frasco de miel del pedido 2; la unidad devuelta resultó apta y se reincorporó.
--   2 REEMBOLSO completado: 2 tabletas de chocolate del pedido 4, no aptas; importe = round(112,00 × 2 / 4, 2).
--   3 SOLICITADA: 1 frasco de mermelada del pedido 7, pendiente de decisión.
INSERT INTO devolucion (id, movimiento_inventario_id, devolucion_anulada_id, codigo, cantidad, motivo, estado, resolucion,
                        cantidad_aprobada, cantidad_recibida, cantidad_apta, importe_reembolsable, fecha_solicitud,
                        fecha_recepcion, fecha_inspeccion, fecha_resolucion, fecha_cierre, fecha_anulacion, motivo_anulacion) VALUES
    (1, 12, NULL, 'DEV-2026-0001', 1, 'El frasco llegó con la tapa aparentemente floja', 'COMPLETADA', 'REPOSICION',
        1, 1, 1, NULL,  '2026-06-15 09:00:00-04', '2026-06-18 10:30:00-04', '2026-06-18 11:00:00-04', '2026-06-18 11:15:00-04',
        '2026-06-22 10:00:00-04', NULL, NULL),
    (2, 19, NULL, 'DEV-2026-0002', 2, 'Dos tabletas llegaron derretidas y con el empaque abierto', 'COMPLETADA', 'REEMBOLSO',
        2, 2, 0, 56.00, '2026-08-24 09:10:00-04', '2026-08-25 10:00:00-04', '2026-08-25 10:30:00-04', '2026-08-25 11:00:00-04',
        '2026-08-26 09:45:00-04', NULL, NULL),
    (3, 25, NULL, 'DEV-2026-0003', 1, 'Un frasco de mermelada llegó con la tapa abollada', 'SOLICITADA', NULL,
        NULL, NULL, NULL, NULL, '2026-11-14 18:30:00-04', NULL, NULL, NULL, NULL, NULL, NULL);

-- Movimientos generados por la devolución 1: reingreso de la unidad apta en el lote del despacho devuelto
-- y salida de la reposición, entregada al cliente por recojo en FEDER.
INSERT INTO movimiento_inventario (id, lote_feder_id, usuario_id, reserva_id, devolucion_id, tipo, cantidad, fecha, motivo, clave_operacion) VALUES
    (15, 3, 2, NULL, 1, 'ENTRADA_DEVOLUCION', 1, '2026-06-18 11:30:00-04',
        'Reingreso de la unidad apta de la devolución DEV-2026-0001', 'op-dev0001-reingreso-01'),
    (16, 3, 3, NULL, 1, 'SALIDA_REPOSICION',  1, '2026-06-22 10:00:00-04',
        'Reposición de la devolución DEV-2026-0001 entregada al cliente', 'op-dev0001-reposicion-01');

-- Reintegros: 1 por la cancelación del pedido 3 (sin devolución); 2 por la devolución 2.
INSERT INTO reembolso (id, pago_id, devolucion_id, monto, estado, motivo, fecha_solicitud, fecha_ejecucion, referencia_externa, clave_idempotencia) VALUES
    (1, 3, NULL, 76.00, 'EJECUTADO', 'Cancelación del pedido PED-2026-0003 por el cliente antes de la preparación',
        '2026-07-04 09:20:00-04', '2026-07-06 09:30:00-04', 'RQR-20260706-000052', 'reembolso-pago03-01'),
    (2, 5, 2,    56.00, 'EJECUTADO', 'Devolución DEV-2026-0002: dos tabletas de chocolate en mal estado',
        '2026-08-25 11:00:00-04', '2026-08-26 09:45:00-04', 'RTJ-20260826-000317', 'reembolso-pago05-dev02-01');

-- Avisos a los usuarios. La alerta de stock del chocolate (bajo mínimo desde la reserva del pedido 4) va al
-- personal y a la administración; los demás avisos referencian su pedido.
INSERT INTO notificacion (id, usuario_id, pedido_id, producto_id, tipo, canal, titulo, mensaje, estado, fecha_creacion, fecha_envio, fecha_lectura) VALUES
    ( 1, 11,  1, NULL, 'PAGO_CONFIRMADO',      'CORREO',  'Pago confirmado',
        'Recibimos el pago de tu pedido PED-2026-0001 por Bs 219,00.', 'ENVIADA', '2026-04-06 10:32:00-04', '2026-04-06 10:33:00-04', NULL),
    ( 2, 11,  1, NULL, 'CAMBIO_ESTADO_PEDIDO', 'SISTEMA', 'Tu pedido fue despachado',
        'El pedido PED-2026-0001 salió de FEDER y va camino a tu domicilio.', 'ENVIADA', '2026-04-07 09:00:00-04', '2026-04-07 09:00:00-04', '2026-04-07 09:20:00-04'),
    ( 3, 11,  1, NULL, 'CAMBIO_ESTADO_PEDIDO', 'CORREO',  'Tu pedido fue entregado',
        'El pedido PED-2026-0001 fue entregado. Gracias por apoyar a las comunidades productoras.', 'ENVIADA', '2026-04-07 16:40:00-04', '2026-04-07 16:41:00-04', NULL),
    ( 4, 12,  2, NULL, 'CAMBIO_ESTADO_PEDIDO', 'CORREO',  'Tu pedido está listo para recojo',
        'El pedido PED-2026-0002 está listo. Puedes recogerlo en FEDER y pagar en efectivo.', 'ENVIADA', '2026-06-11 09:30:00-04', '2026-06-11 09:31:00-04', NULL),
    ( 5, 12,  2, NULL, 'DEVOLUCION_RESUELTA',  'CORREO',  'Devolución aprobada',
        'Aprobamos la devolución DEV-2026-0001 del pedido PED-2026-0002: te entregaremos un frasco de reposición en FEDER.', 'ENVIADA', '2026-06-18 11:15:00-04', '2026-06-18 11:16:00-04', NULL),
    ( 6, 12,  3, NULL, 'PAGO_CONFIRMADO',      'CORREO',  'Pago confirmado',
        'Recibimos el pago de tu pedido PED-2026-0003 por Bs 76,00.', 'ENVIADA', '2026-07-03 14:20:00-04', '2026-07-03 14:21:00-04', NULL),
    ( 7, 12,  3, NULL, 'CAMBIO_ESTADO_PEDIDO', 'CORREO',  'Pedido cancelado',
        'El pedido PED-2026-0003 fue cancelado a tu solicitud. Te reintegraremos el importe pagado.', 'ENVIADA', '2026-07-04 09:20:00-04', '2026-07-04 09:21:00-04', NULL),
    ( 8, 12,  3, NULL, 'REEMBOLSO_EJECUTADO',  'CORREO',  'Reembolso ejecutado',
        'Devolvimos Bs 76,00 del pedido PED-2026-0003 al medio de pago original.', 'ENVIADA', '2026-07-06 09:30:00-04', '2026-07-06 09:31:00-04', NULL),
    ( 9, 13,  4, NULL, 'PAGO_RECHAZADO',       'SISTEMA', 'Pago rechazado',
        'El pago con tarjeta del pedido PED-2026-0004 fue rechazado por el emisor. Puedes intentarlo de nuevo.', 'ENVIADA', '2026-08-20 18:05:00-04', '2026-08-20 18:05:00-04', '2026-08-20 18:06:00-04'),
    (10, 13,  4, NULL, 'PAGO_CONFIRMADO',      'CORREO',  'Pago confirmado',
        'Recibimos el pago de tu pedido PED-2026-0004 por Bs 314,00.', 'ENVIADA', '2026-08-20 18:12:00-04', '2026-08-20 18:13:00-04', NULL),
    (11,  2, NULL,  9, 'ALERTA_STOCK',         'SISTEMA', 'Stock bajo mínimo',
        'Chocolate artesanal 70 % cacao: el stock vendible (76) quedó bajo el mínimo (80).', 'ENVIADA', '2026-08-20 18:00:00-04', '2026-08-20 18:00:00-04', '2026-08-21 08:10:00-04'),
    (12,  3, NULL,  9, 'ALERTA_STOCK',         'SISTEMA', 'Stock bajo mínimo',
        'Chocolate artesanal 70 % cacao: el stock vendible (76) quedó bajo el mínimo (80).', 'ENVIADA', '2026-08-20 18:00:00-04', '2026-08-20 18:00:00-04', '2026-08-21 08:25:00-04'),
    (13,  1, NULL,  9, 'ALERTA_STOCK',         'SISTEMA', 'Stock bajo mínimo',
        'Chocolate artesanal 70 % cacao: el stock vendible (76) quedó bajo el mínimo (80).', 'ENVIADA', '2026-08-20 18:00:00-04', '2026-08-20 18:00:00-04', NULL),
    (14, 13,  4, NULL, 'DEVOLUCION_RESUELTA',  'CORREO',  'Devolución aprobada',
        'Aprobamos la devolución DEV-2026-0002 del pedido PED-2026-0004: te reembolsaremos Bs 56,00.', 'ENVIADA', '2026-08-25 11:00:00-04', '2026-08-25 11:01:00-04', NULL),
    (15, 13,  4, NULL, 'REEMBOLSO_EJECUTADO',  'CORREO',  'Reembolso ejecutado',
        'Devolvimos Bs 56,00 del pedido PED-2026-0004 a tu tarjeta.', 'ENVIADA', '2026-08-26 09:45:00-04', '2026-08-26 09:46:00-04', NULL),
    (16,  3,  5, NULL, 'CAMBIO_ESTADO_PEDIDO', 'CORREO',  'Pedido cancelado por falta de pago',
        'El pedido PED-2026-0005 se canceló porque venció el plazo de pago de la reserva.', 'ENVIADA', '2026-09-15 07:05:00-04', '2026-09-15 07:06:00-04', NULL),
    (17, 14,  6, NULL, 'CAMBIO_ESTADO_PEDIDO', 'CORREO',  'Tu pedido fue entregado',
        'El pedido PED-2026-0006 fue entregado. Gracias por apoyar a las comunidades productoras.', 'ENVIADA', '2026-10-07 15:10:00-04', '2026-10-07 15:11:00-04', NULL),
    (18, 11,  8, NULL, 'FECHA_ESTIMADA_ACTUALIZADA', 'CORREO', 'Fecha estimada de tu encargo',
        'El encargo PED-2026-0008 tiene como fecha estimada de disponibilidad el 12/12/2026. Es una estimación, no una fecha garantizada.', 'ENVIADA', '2026-11-08 11:15:00-04', '2026-11-08 11:16:00-04', NULL),
    (19, 12,  9, NULL, 'CAMBIO_ESTADO_PEDIDO', 'CORREO',  'Tu pedido está en camino',
        'El pedido PED-2026-0009 salió de FEDER y va camino a tu domicilio.', 'ENVIADA', '2026-11-14 09:00:00-04', '2026-11-14 09:01:00-04', NULL),
    (20, 13, 10, NULL, 'PAGO_CONFIRMADO',      'CORREO',  'Pago confirmado',
        'Recibimos el pago de tu pedido PED-2026-0010 por Bs 218,00.', 'FALLIDA', '2026-11-13 16:15:00-04', NULL, NULL),
    (21, 14, 11, NULL, 'CAMBIO_ESTADO_PEDIDO', 'SISTEMA', 'Pedido registrado',
        'El pedido PED-2026-0011 está pendiente de pago. La reserva de tus productos vence hoy a las 20:40.', 'PENDIENTE', '2026-11-15 08:40:00-04', NULL, NULL);

-- Carritos vigentes (no reservan stock).
INSERT INTO item_carrito (id, cliente_id, producto_id, cantidad, fecha_agregado) VALUES
    (1, 11, 3, 2,     '2026-11-14 20:10:00-04'),
    (2, 13, 5, 0.750, '2026-11-14 21:00:00-04'),
    (3, 14, 9, 3,     '2026-11-15 08:30:00-04'),
    (4,  9, 6, 2,     '2026-11-11 12:00:00-04'),
    (5,  3, 7, 1,     '2026-11-10 19:40:00-04');

-- -------------------------------------------------------------------------------------
-- Bitácora (solo inserción). Direcciones IP del bloque reservado para documentación 192.0.2.0/24.
-- -------------------------------------------------------------------------------------

-- CU1 · Inicialización, invitaciones, concesiones y solicitudes de productor.
INSERT INTO bitacora (id, usuario_id, fecha, accion, entidad, entidad_id, detalle, direccion_ip) VALUES
    ( 1, NULL, '2026-01-12 09:00:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 1,
        '{"rol": "ADMINISTRADOR", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Concesión inicial creada por el procedimiento de instalación"}', NULL),
    ( 2, 1,    '2026-01-13 10:00:00-04', 'INVITAR',     'USUARIO_ROL', 2,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": null, "estado_nuevo": "PENDIENTE", "justificacion": "Incorporación como responsable de almacén"}', '192.0.2.10'),
    ( 3, 1,    '2026-01-13 10:10:00-04', 'INVITAR',     'USUARIO_ROL', 3,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": null, "estado_nuevo": "PENDIENTE", "justificacion": "Incorporación como responsable de ventas y entregas"}', '192.0.2.10'),
    ( 4, 1,    '2026-01-13 10:20:00-04', 'INVITAR',     'USUARIO_ROL', 4,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": null, "estado_nuevo": "PENDIENTE", "justificacion": "Incorporación como auxiliar de almacén"}', '192.0.2.10'),
    ( 5, 3,    '2026-01-13 18:45:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 3,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": "PENDIENTE", "estado_nuevo": "VIGENTE", "justificacion": "Invitación aceptada por la persona invitada"}', '192.0.2.21'),
    ( 6, 2,    '2026-01-14 08:30:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 2,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": "PENDIENTE", "estado_nuevo": "VIGENTE", "justificacion": "Invitación aceptada por la persona invitada"}', '192.0.2.22'),
    ( 7, 5,    '2026-01-15 09:00:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 4,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": "PENDIENTE", "estado_nuevo": "VIGENTE", "justificacion": "Invitación aceptada por la persona invitada"}', '192.0.2.23'),
    ( 8, 6,    '2026-01-20 19:30:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 8,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.31'),
    ( 9, 6,    '2026-01-22 09:00:00-04', 'CREAR',       'PRODUCTOR',   1,
        '{"estado_anterior": null, "estado_nuevo": "PENDIENTE", "comunidad_id": 1, "justificacion": "Solicitud de alta como productora de FEDER"}', '192.0.2.31'),
    (10, 1,    '2026-01-26 11:00:00-04', 'APROBAR_SOLICITUD', 'PRODUCTOR', 1,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "APROBADA", "codigo_productor": "PROD-0001", "justificacion": "Identidad verificada con el documento y actividad comprobada en la visita a la comunidad"}', '192.0.2.10'),
    (11, 1,    '2026-01-26 11:00:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 9,
        '{"rol": "PRODUCTOR", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Aprobación de la solicitud de productor"}', '192.0.2.10'),
    (12, 7,    '2026-02-02 18:40:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 10,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.32'),
    (13, 7,    '2026-02-03 08:15:00-04', 'CREAR',       'PRODUCTOR',   2,
        '{"estado_anterior": null, "estado_nuevo": "PENDIENTE", "comunidad_id": 2, "justificacion": "Solicitud de alta como productor de FEDER"}', '192.0.2.32'),
    (14, 1,    '2026-02-06 10:30:00-04', 'APROBAR_SOLICITUD', 'PRODUCTOR', 2,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "APROBADA", "codigo_productor": "PROD-0002", "justificacion": "Identidad verificada con el documento y apiario comprobado en la visita"}', '192.0.2.10'),
    (15, 1,    '2026-02-06 10:30:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 11,
        '{"rol": "PRODUCTOR", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Aprobación de la solicitud de productor"}', '192.0.2.10'),
    (16, 8,    '2026-03-10 20:05:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 12,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.33'),
    (17, 8,    '2026-03-11 10:20:00-04', 'CREAR',       'PRODUCTOR',   3,
        '{"estado_anterior": null, "estado_nuevo": "PENDIENTE", "comunidad_id": 3, "justificacion": "Solicitud de alta como productora de FEDER"}', '192.0.2.33'),
    (18, 1,    '2026-03-16 09:45:00-04', 'APROBAR_SOLICITUD', 'PRODUCTOR', 3,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "APROBADA", "codigo_productor": "PROD-0003", "justificacion": "Identidad verificada con el documento y centro de transformación comprobado en la visita"}', '192.0.2.10'),
    (19, 1,    '2026-03-16 09:45:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 13,
        '{"rol": "PRODUCTOR", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Aprobación de la solicitud de productor"}', '192.0.2.10'),
    (20, 11,   '2026-03-20 21:15:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 16,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.41'),
    (21, 12,   '2026-04-05 11:45:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 17,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.42'),
    (22, 3,    '2026-05-02 20:15:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 7,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Activación voluntaria del rol de cliente con aceptación de los términos"}', '192.0.2.21'),
    (23, 13,   '2026-06-18 19:20:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 18,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.43'),
    (24, 1,    '2026-06-30 17:00:00-04', 'REVOCAR_ROL', 'USUARIO_ROL', 4,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": "VIGENTE", "estado_nuevo": "REVOCADA", "justificacion": "Finalización de su vínculo laboral con FEDER"}', '192.0.2.10'),
    (25, 10,   '2026-09-01 10:30:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 15,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.44'),
    (26, 10,   '2026-09-02 09:10:00-04', 'CREAR',       'PRODUCTOR',   5,
        '{"estado_anterior": null, "estado_nuevo": "PENDIENTE", "comunidad_id": 3, "justificacion": "Solicitud de alta como productora de FEDER"}', '192.0.2.44'),
    (27, 1,    '2026-09-08 15:00:00-04', 'RECHAZAR_SOLICITUD', 'PRODUCTOR', 5,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "RECHAZADA", "justificacion": "No se pudo verificar la ubicación de la actividad productiva declarada"}', '192.0.2.10'),
    (28, 14,   '2026-09-22 08:50:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 19,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.45'),
    (29, 1,    '2026-10-20 09:00:00-04', 'INVITAR',     'USUARIO_ROL', 5,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": null, "estado_nuevo": "PENDIENTE", "justificacion": "Incorporación como responsable de logística"}', '192.0.2.10'),
    (30, NULL, '2026-10-27 09:00:00-04', 'REVOCAR_ROL', 'USUARIO_ROL', 5,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": "PENDIENTE", "estado_nuevo": "ANULADA", "justificacion": "Invitación vencida sin respuesta"}', NULL),
    (31, 9,    '2026-11-03 21:10:00-04', 'ASIGNAR_ROL', 'USUARIO_ROL', 14,
        '{"rol": "CLIENTE", "estado_anterior": null, "estado_nuevo": "VIGENTE", "justificacion": "Registro público en la tienda"}', '192.0.2.46'),
    (32, 9,    '2026-11-10 16:20:00-04', 'CREAR',       'PRODUCTOR',   4,
        '{"estado_anterior": null, "estado_nuevo": "PENDIENTE", "comunidad_id": 2, "justificacion": "Solicitud de alta como productor de FEDER"}', '192.0.2.46'),
    (33, 1,    '2026-11-12 09:30:00-04', 'INVITAR',     'USUARIO_ROL', 6,
        '{"rol": "PERSONAL_FEDER", "estado_anterior": null, "estado_nuevo": "PENDIENTE", "justificacion": "Segunda invitación como responsable de logística"}', '192.0.2.10'),
    (34, 1,    '2026-11-14 08:05:00-04', 'INICIO_SESION', 'USUARIO', 1,  '{"canal": "correo"}', '192.0.2.10'),
    (35, 2,    '2026-11-14 08:40:00-04', 'INICIO_SESION', 'USUARIO', 2,  '{"canal": "correo"}', '192.0.2.22'),
    (36, 3,    '2026-11-14 17:30:00-04', 'INICIO_SESION', 'USUARIO', 3,  '{"canal": "correo"}', '192.0.2.21'),
    (37, 14,   '2026-11-15 08:25:00-04', 'INICIO_SESION', 'USUARIO', 14, '{"canal": "correo"}', '192.0.2.45');

-- CU2 y CU4 · Altas y cambios de comunidades, categorías y productos.
INSERT INTO bitacora (id, usuario_id, fecha, accion, entidad, entidad_id, detalle) VALUES
    (41, 1, '2026-01-13 11:00:00-04', 'CREAR',     'COMUNIDAD_AGRICOLA', 1, '{"nombre": "Comunidad Totaizal de San Juan", "municipio": "San José de Chiquitos"}'),
    (42, 1, '2026-01-13 11:05:00-04', 'CREAR',     'COMUNIDAD_AGRICOLA', 2, '{"nombre": "Comunidad Villa Colmenar", "municipio": "Buena Vista"}'),
    (43, 2, '2026-02-10 09:20:00-04', 'CREAR',     'COMUNIDAD_AGRICOLA', 4, '{"nombre": "Comunidad Nueva Aurora", "municipio": "Portachuelo"}'),
    (44, 2, '2026-03-05 10:00:00-04', 'CREAR',     'COMUNIDAD_AGRICOLA', 3, '{"nombre": "Comunidad El Palmar del Surutú", "municipio": "Yapacaní"}'),
    (45, 1, '2026-07-01 09:00:00-04', 'MODIFICAR', 'COMUNIDAD_AGRICOLA', 4,
        '{"activa": {"anterior": true, "nuevo": false}, "motivo": "Sin productores afiliados ni solicitudes desde su registro"}'),
    (46, 3, '2026-01-27 09:00:00-04', 'CREAR',     'CATEGORIA', 1, '{"nombre": "Aceites naturales"}'),
    (47, 3, '2026-01-27 09:02:00-04', 'CREAR',     'CATEGORIA', 2, '{"nombre": "Cuidado personal"}'),
    (48, 3, '2026-02-09 09:00:00-04', 'CREAR',     'CATEGORIA', 3, '{"nombre": "Miel y derivados"}'),
    (49, 3, '2026-02-09 09:02:00-04', 'CREAR',     'CATEGORIA', 5, '{"nombre": "Café y cacao"}'),
    (50, 3, '2026-03-18 09:00:00-04', 'CREAR',     'CATEGORIA', 4, '{"nombre": "Harinas"}'),
    (51, 3, '2026-03-18 09:02:00-04', 'CREAR',     'CATEGORIA', 6, '{"nombre": "Mermeladas y conservas"}'),
    (52, 3, '2026-04-15 11:00:00-04', 'CREAR',     'CATEGORIA', 7, '{"nombre": "Bebidas artesanales", "activa": false}'),
    (53, 3, '2026-01-28 10:00:00-04', 'CREAR',     'PRODUCTO',  1, '{"codigo": "FED-ACE-020", "precio_venta": 24.00, "unidad_medida": "UNIDAD"}'),
    (54, 3, '2026-01-28 10:10:00-04', 'CREAR',     'PRODUCTO',  3, '{"codigo": "FED-JAB-090", "precio_venta": 18.00, "unidad_medida": "UNIDAD"}'),
    (55, 3, '2026-02-09 10:00:00-04', 'CREAR',     'PRODUCTO',  4, '{"codigo": "FED-MIE-500", "precio_venta": 42.00, "unidad_medida": "UNIDAD"}'),
    (56, 3, '2026-02-09 10:10:00-04', 'CREAR',     'PRODUCTO',  5, '{"codigo": "FED-MIE-GRA", "precio_venta": 70.00, "unidad_medida": "KILOGRAMO"}'),
    (57, 3, '2026-02-09 10:20:00-04', 'CREAR',     'PRODUCTO',  7, '{"codigo": "FED-CAF-250", "precio_venta": 38.00, "unidad_medida": "UNIDAD"}'),
    (58, 3, '2026-03-18 10:00:00-04', 'CREAR',     'PRODUCTO',  6, '{"codigo": "FED-HAR-PLA", "precio_venta": 22.00, "unidad_medida": "UNIDAD"}'),
    (59, 3, '2026-03-18 10:10:00-04', 'CREAR',     'PRODUCTO',  9, '{"codigo": "FED-CHO-100", "precio_venta": 28.00, "unidad_medida": "UNIDAD"}'),
    (60, 3, '2026-03-18 10:20:00-04', 'CREAR',     'PRODUCTO', 10, '{"codigo": "FED-MER-GUA", "precio_venta": 24.00, "unidad_medida": "UNIDAD"}'),
    (61, 3, '2026-03-18 10:30:00-04', 'CREAR',     'PRODUCTO', 11, '{"codigo": "FED-MER-MAN", "precio_venta": 24.00, "unidad_medida": "UNIDAD"}'),
    (62, 3, '2026-06-01 09:00:00-04', 'CAMBIO_PRECIO', 'PRODUCTO', 4,
        '{"precio_anterior": 42.00, "precio_nuevo": 45.00, "motivo": "Ajuste por el aumento del costo de adquisición"}'),
    (63, 3, '2026-07-20 10:00:00-04', 'CREAR',     'PRODUCTO',  8, '{"codigo": "FED-CAF-GRA", "precio_venta": 26.00, "unidad_medida": "LIBRA"}'),
    (64, 3, '2026-09-01 09:00:00-04', 'CAMBIO_PRECIO', 'PRODUCTO', 1,
        '{"precio_anterior": 24.00, "precio_nuevo": 25.00, "motivo": "Actualización anual de precios"}'),
    (65, 3, '2026-09-10 09:30:00-04', 'MODIFICAR', 'PRODUCTO', 11,
        '{"activo": {"anterior": true, "nuevo": false}, "motivo": "Fuera de temporada: sin producción hasta la próxima cosecha de mango"}'),
    (66, 3, '2026-10-18 09:30:00-04', 'CREAR',     'PRODUCTO',  2, '{"codigo": "FED-ACE-100", "precio_venta": 90.00, "unidad_medida": "UNIDAD"}'),
    (67, 2, '2026-08-20 08:00:00-04', 'MODIFICAR', 'PRODUCTO',  9,
        '{"stock_minimo": {"anterior": 15, "nuevo": 80}, "motivo": "Mayor demanda prevista para la campaña de fin de año"}');

-- CU3 · Altas de producciones y de lotes de origen, registradas por la cuenta de cada productor.
INSERT INTO bitacora (id, usuario_id, fecha, accion, entidad, entidad_id, detalle)
SELECT 70 + pr.id, p.usuario_id, v.fecha::timestamptz, 'CREAR', 'PRODUCCION', pr.id,
       jsonb_build_object('codigo', pr.codigo, 'estado_inicial', v.estado_inicial, 'comunidad_origen_id', pr.comunidad_origen_id,
                          'origen', 'Comunidad vigente del productor al registrar la producción')
  FROM (VALUES
    ( 1, '2026-02-10 08:00:00-04', 'PLANIFICADA'), ( 2, '2026-03-01 08:00:00-04', 'PLANIFICADA'),
    ( 3, '2026-03-15 08:30:00-04', 'PLANIFICADA'), ( 4, '2026-04-13 09:00:00-04', 'TERMINADA'),
    ( 5, '2026-04-25 08:30:00-04', 'PLANIFICADA'), ( 6, '2026-05-20 08:00:00-04', 'PLANIFICADA'),
    ( 7, '2026-06-25 08:00:00-04', 'PLANIFICADA'), ( 8, '2026-07-25 08:00:00-04', 'PLANIFICADA'),
    ( 9, '2026-08-01 08:30:00-04', 'PLANIFICADA'), (10, '2026-09-25 08:00:00-04', 'PLANIFICADA'),
    (11, '2026-10-20 08:00:00-04', 'PLANIFICADA'), (12, '2026-11-12 19:00:00-04', 'PLANIFICADA')
  ) AS v (produccion_id, fecha, estado_inicial)
  JOIN produccion pr ON pr.id = v.produccion_id
  JOIN productor p ON p.id = pr.productor_id;

INSERT INTO bitacora (id, usuario_id, fecha, accion, entidad, entidad_id, detalle)
SELECT 85 + lp.id, p.usuario_id, v.fecha::timestamptz, 'CREAR', 'LOTE_PRODUCCION', lp.id,
       jsonb_build_object('codigo_lote', lp.codigo_lote, 'produccion_id', lp.produccion_id,
                          'cantidad_producida', lp.cantidad_producida, 'cantidad_ofrecida', lp.cantidad_ofrecida)
  FROM (VALUES
    ( 1, '2026-03-03 09:00:00-04'), ( 2, '2026-03-21 09:00:00-04'), ( 3, '2026-04-11 09:00:00-04'),
    ( 4, '2026-04-13 09:05:00-04'), ( 5, '2026-05-16 09:00:00-04'), ( 6, '2026-05-17 09:00:00-04'),
    ( 7, '2026-06-02 09:00:00-04'), ( 8, '2026-07-11 09:00:00-04'), ( 9, '2026-08-06 09:00:00-04'),
    (10, '2026-10-11 09:00:00-04'), (11, '2026-11-06 09:00:00-04')
  ) AS v (lote_id, fecha)
  JOIN lote_produccion lp ON lp.id = v.lote_id
  JOIN produccion pr ON pr.id = lp.produccion_id
  JOIN productor p ON p.id = pr.productor_id;

-- CU4 · Compras, recepciones idempotentes, cancelación, pagos al productor y salidas no comerciales.
-- Cada recepción o cancelación de línea lleva su clave de operación (la misma del movimiento ENTRADA_COMPRA),
-- la compra, las cantidades y, si existe, el documento de entrega.
INSERT INTO bitacora (id, usuario_id, fecha, accion, entidad, entidad_id, detalle) VALUES
    (101, 3, '2026-03-23 10:00:00-04', 'CREAR', 'COMPRA_FEDER', 1, '{"codigo": "CMP-2026-001", "productor_id": 1, "total": 5250.00}'),
    (102, 2, '2026-03-26 10:15:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 1,
        '{"clave_operacion": "op-rec-cmp001-dc01-01", "operacion": "RECEPCION", "compra_id": 1, "cantidad_aceptada": 200, "cantidad_rechazada": 0, "motivo": "Recepción conforme", "documento_entrega": "NE-MQ-0001"}'),
    (103, 2, '2026-03-26 10:15:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 2,
        '{"clave_operacion": "op-rec-cmp001-dc02-01", "operacion": "RECEPCION", "compra_id": 1, "cantidad_aceptada": 245, "cantidad_rechazada": 5, "motivo": "Cinco barras rechazadas por tener el empaque roto", "documento_entrega": "NE-MQ-0001"}'),
    (104, 3, '2026-04-02 15:00:00-04', 'MODIFICAR', 'COMPRA_FEDER', 1,
        '{"operacion": "PAGO_PRODUCTOR", "importe_pagado": 5205.00, "detalle": "Se paga lo aceptado: 200 × 15,00 + 245 × 9,00"}'),
    (105, 3, '2026-05-20 09:00:00-04', 'CREAR', 'COMPRA_FEDER', 2, '{"codigo": "CMP-2026-002", "productor_id": 2, "total": 8700.00}'),
    (106, 2, '2026-05-23 09:40:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 3,
        '{"clave_operacion": "op-rec-cmp002-dc03-01", "operacion": "RECEPCION", "compra_id": 2, "cantidad_aceptada": 100, "cantidad_rechazada": 0, "motivo": "Primera entrega parcial", "documento_entrega": "NE-PC-0007"}'),
    (107, 2, '2026-05-23 09:40:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 4,
        '{"clave_operacion": "op-rec-cmp002-dc04-01", "operacion": "RECEPCION", "compra_id": 2, "cantidad_aceptada": 50.000, "cantidad_rechazada": 0, "motivo": "Recepción conforme", "documento_entrega": "NE-PC-0007"}'),
    (108, 2, '2026-05-23 09:40:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 5,
        '{"clave_operacion": "op-rec-cmp002-dc05-01", "operacion": "RECEPCION", "compra_id": 2, "cantidad_aceptada": 100, "cantidad_rechazada": 0, "motivo": "Recepción conforme", "documento_entrega": "NE-PC-0007"}'),
    (109, 2, '2026-05-30 10:05:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 3,
        '{"clave_operacion": "op-rec-cmp002-dc03-02", "operacion": "RECEPCION", "compra_id": 2, "cantidad_aceptada": 50, "cantidad_rechazada": 0, "motivo": "Segunda entrega: completa la línea", "documento_entrega": "NE-PC-0009"}'),
    (110, 3, '2026-06-05 11:00:00-04', 'MODIFICAR', 'COMPRA_FEDER', 2,
        '{"operacion": "PAGO_PRODUCTOR", "importe_pagado": 8700.00, "detalle": "Todo lo acordado fue aceptado"}'),
    (111, 2, '2026-07-15 16:30:00-04', 'CREAR', 'MOVIMIENTO_INVENTARIO', 17,
        '{"tipo": "BAJA_DANADO", "lote_feder_id": 1, "cantidad": 3, "motivo": "Tres botellas rotas al reordenar el estante A-01"}'),
    (112, 3, '2026-08-12 11:00:00-04', 'CREAR', 'COMPRA_FEDER', 3, '{"codigo": "CMP-2026-003", "productor_id": 3, "total": 5390.00}'),
    (113, 2, '2026-08-15 10:30:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 6,
        '{"clave_operacion": "op-rec-cmp003-dc06-01", "operacion": "RECEPCION", "compra_id": 3, "cantidad_aceptada": 150, "cantidad_rechazada": 0, "motivo": "Recepción conforme", "documento_entrega": "NE-RT-0003"}'),
    (114, 2, '2026-08-15 10:30:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 7,
        '{"clave_operacion": "op-rec-cmp003-dc07-01", "operacion": "RECEPCION", "compra_id": 3, "cantidad_aceptada": 80, "cantidad_rechazada": 10, "motivo": "Diez tabletas rechazadas: llegaron derretidas por el traslado; quedan 30 pendientes de entrega", "documento_entrega": "NE-RT-0003"}'),
    (115, 2, '2026-08-15 10:30:00-04', 'MODIFICAR', 'DETALLE_COMPRA', 8,
        '{"clave_operacion": "op-rec-cmp003-dc08-01", "operacion": "RECEPCION", "compra_id": 3, "cantidad_aceptada": 100, "cantidad_rechazada": 0, "motivo": "Recepción conforme", "documento_entrega": "NE-RT-0003"}'),
    (116, 2, '2026-09-30 17:00:00-04', 'CREAR', 'MOVIMIENTO_INVENTARIO', 21,
        '{"tipo": "AJUSTE_NEGATIVO", "lote_feder_id": 4, "cantidad": 0.350, "motivo": "Diferencia en el conteo mensual: merma por fraccionamiento de la miel a granel"}'),
    (117, 3, '2026-10-15 10:00:00-04', 'CREAR', 'COMPRA_FEDER', 4, '{"codigo": "CMP-2026-004", "productor_id": 3, "total": 810.00}'),
    (118, 3, '2026-10-28 09:00:00-04', 'ANULAR', 'DETALLE_COMPRA', 9,
        '{"clave_operacion": "op-can-cmp004-dc09-01", "operacion": "CANCELACION", "compra_id": 4, "cantidad_cancelada": 60, "motivo": "La productora no pudo completar el secado por las lluvias y no entregará el lote"}'),
    (119, 3, '2026-11-10 15:00:00-04', 'CREAR', 'COMPRA_FEDER', 5, '{"codigo": "CMP-2026-005", "productor_id": 1, "total": 1800.00}');

-- CU5 y CU6 · Transiciones de las entregas (estado anterior y nuevo), verificación de pagos, encargo,
-- devoluciones, reintegros y anulación de factura. El autor es nulo cuando actúa el sistema.
INSERT INTO bitacora (id, usuario_id, fecha, accion, entidad, entidad_id, detalle) VALUES
    (131, NULL, '2026-04-06 10:32:00-04', 'MODIFICAR', 'PAGO', 1,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Cobro confirmado por la pasarela", "referencia": "QR-20260406-000184"}'),
    (132, 2,    '2026-04-07 09:00:00-04', 'MODIFICAR', 'ENTREGA', 1, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "EN_CAMINO"}'),
    (133, 3,    '2026-04-07 16:40:00-04', 'MODIFICAR', 'ENTREGA', 1, '{"estado_anterior": "EN_CAMINO", "estado_nuevo": "ENTREGADA"}'),
    (134, 2,    '2026-06-11 09:30:00-04', 'MODIFICAR', 'ENTREGA', 2, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "LISTA_PARA_RECOJO"}'),
    (135, 3,    '2026-06-12 10:15:00-04', 'MODIFICAR', 'ENTREGA', 2, '{"estado_anterior": "LISTA_PARA_RECOJO", "estado_nuevo": "ENTREGADA"}'),
    (136, 3,    '2026-06-12 10:15:00-04', 'MODIFICAR', 'PAGO', 2,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Efectivo recibido en la entrega", "referencia": null}'),
    (137, 12,   '2026-06-15 09:00:00-04', 'CREAR', 'DEVOLUCION', 1, '{"codigo": "DEV-2026-0001", "cantidad": 1, "despacho_id": 12}'),
    (138, 3,    '2026-06-18 11:15:00-04', 'MODIFICAR', 'DEVOLUCION', 1,
        '{"estado_anterior": "SOLICITADA", "estado_nuevo": "APROBADA", "resolucion": "REPOSICION", "cantidad_aprobada": 1, "justificacion": "Reclamo atendido; la inspección comprobó el sello íntegro y la unidad se reincorpora"}'),
    (139, 3,    '2026-06-22 10:00:00-04', 'MODIFICAR', 'DEVOLUCION', 1,
        '{"estado_anterior": "APROBADA", "estado_nuevo": "COMPLETADA", "justificacion": "Reposición entregada al cliente en FEDER"}'),
    (140, NULL, '2026-07-03 14:20:00-04', 'MODIFICAR', 'PAGO', 3,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Cobro confirmado por la pasarela", "referencia": "QR-20260703-000412"}'),
    (141, 12,   '2026-07-04 09:20:00-04', 'MODIFICAR', 'ENTREGA', 3, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "CANCELADA"}'),
    (142, 3,    '2026-07-04 09:25:00-04', 'CREAR', 'REEMBOLSO', 1, '{"pago_id": 3, "monto": 76.00, "motivo": "Cancelación del pedido PED-2026-0003"}'),
    (143, 1,    '2026-07-06 10:00:00-04', 'ANULAR', 'FACTURA', 3,
        '{"estado_anterior": "VIGENTE", "estado_nuevo": "ANULADA", "motivo": "Anulación fiscal tras la cancelación del pedido y el reintegro del pago"}'),
    (144, NULL, '2026-08-20 18:05:00-04', 'MODIFICAR', 'PAGO', 4,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "RECHAZADO", "resultado": "Tarjeta rechazada por el emisor", "referencia": "TRJ-20260820-004411"}'),
    (145, NULL, '2026-08-20 18:12:00-04', 'MODIFICAR', 'PAGO', 5,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Cobro confirmado por la pasarela", "referencia": "TRJ-20260820-004418"}'),
    (146, 2,    '2026-08-21 10:00:00-04', 'MODIFICAR', 'ENTREGA', 4, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "EN_CAMINO"}'),
    (147, 3,    '2026-08-22 11:20:00-04', 'MODIFICAR', 'ENTREGA', 4, '{"estado_anterior": "EN_CAMINO", "estado_nuevo": "ENTREGADA"}'),
    (148, 13,   '2026-08-24 09:10:00-04', 'CREAR', 'DEVOLUCION', 2, '{"codigo": "DEV-2026-0002", "cantidad": 2, "despacho_id": 19}'),
    (149, 3,    '2026-08-25 11:00:00-04', 'MODIFICAR', 'DEVOLUCION', 2,
        '{"estado_anterior": "SOLICITADA", "estado_nuevo": "APROBADA", "resolucion": "REEMBOLSO", "cantidad_aprobada": 2, "importe_reembolsable": 56.00, "justificacion": "Las dos tabletas recibidas no son aptas para la venta"}'),
    (150, 3,    '2026-08-25 11:00:00-04', 'CREAR', 'REEMBOLSO', 2, '{"pago_id": 5, "devolucion_id": 2, "monto": 56.00}'),
    (151, 3,    '2026-08-26 09:45:00-04', 'MODIFICAR', 'DEVOLUCION', 2,
        '{"estado_anterior": "APROBADA", "estado_nuevo": "COMPLETADA", "justificacion": "Reembolso ejecutado por el importe atribuido"}'),
    (152, NULL, '2026-09-15 07:05:00-04', 'MODIFICAR', 'PAGO', 6,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "RECHAZADO", "resultado": "Código QR vencido sin cobro, conciliado con la pasarela", "referencia": "QR-20260914-000733"}'),
    (153, NULL, '2026-09-15 07:05:00-04', 'MODIFICAR', 'ENTREGA', 5, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "CANCELADA"}'),
    (154, NULL, '2026-10-05 12:40:00-04', 'MODIFICAR', 'PAGO', 7,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Cobro confirmado por la pasarela", "referencia": "QR-20261005-000958"}'),
    (155, 2,    '2026-10-06 09:15:00-04', 'MODIFICAR', 'ENTREGA', 6, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "EN_CAMINO"}'),
    (156, 3,    '2026-10-07 15:10:00-04', 'MODIFICAR', 'ENTREGA', 6, '{"estado_anterior": "EN_CAMINO", "estado_nuevo": "ENTREGADA"}'),
    (157, NULL, '2026-11-06 17:52:00-04', 'MODIFICAR', 'PAGO', 8,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Cobro confirmado por la pasarela", "referencia": "QR-20261106-001120"}'),
    (158, 2,    '2026-11-07 10:30:00-04', 'MODIFICAR', 'ENTREGA', 7, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "LISTA_PARA_RECOJO"}'),
    (159, 3,    '2026-11-08 11:15:00-04', 'MODIFICAR', 'PEDIDO', 8,
        '{"fecha_estimada_anterior": null, "fecha_estimada_nueva": "2026-12-12", "motivo": "Aceptación del encargo", "fundamento": "Disponibilidad estimada de la producción PRD-2026-011 (2026-12-05) más recepción y preparación"}'),
    (160, 3,    '2026-11-09 09:50:00-04', 'MODIFICAR', 'ENTREGA', 7, '{"estado_anterior": "LISTA_PARA_RECOJO", "estado_nuevo": "ENTREGADA"}'),
    (161, NULL, '2026-11-12 13:24:00-04', 'MODIFICAR', 'PAGO', 9,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Cobro confirmado por la pasarela", "referencia": "TRJ-20261112-005902"}'),
    (162, NULL, '2026-11-13 16:15:00-04', 'MODIFICAR', 'PAGO', 10,
        '{"estado_anterior": "PENDIENTE", "estado_nuevo": "PAGADO", "resultado": "Cobro confirmado por la pasarela", "referencia": "QR-20261113-001187"}'),
    (163, 2,    '2026-11-14 09:00:00-04', 'MODIFICAR', 'ENTREGA', 9, '{"estado_anterior": "PENDIENTE", "estado_nuevo": "EN_CAMINO"}'),
    (164, 11,   '2026-11-14 18:30:00-04', 'CREAR', 'DEVOLUCION', 3, '{"codigo": "DEV-2026-0003", "cantidad": 1, "despacho_id": 25}');

-- -------------------------------------------------------------------------------------
-- Identidades: se ajusta cada secuencia al mayor id cargado, para que las altas posteriores no colisionen.
-- -------------------------------------------------------------------------------------
DO $$
DECLARE
    v_tabla  text;
    v_maximo bigint;
BEGIN
    FOR v_tabla IN
        SELECT c.table_name
          FROM information_schema.columns c
         WHERE c.table_schema = 'public' AND c.column_name = 'id' AND c.is_identity = 'YES'
         ORDER BY c.table_name
    LOOP
        EXECUTE format('SELECT COALESCE(max(id), 0) FROM public.%I', v_tabla) INTO v_maximo;
        PERFORM setval(pg_get_serial_sequence(format('public.%I', v_tabla), 'id'), GREATEST(v_maximo, 1), v_maximo > 0);
    END LOOP;
END;
$$;

COMMIT;
