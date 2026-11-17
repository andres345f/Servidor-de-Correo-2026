-- =====================================================================================
-- FEDER · INF513 (UAGRM) · Sistema dirigido por correo electrónico
-- 001_correo_saliente_notificacion.sql · Migración incremental del esquema
--
-- Qué hace: agrega a la tabla técnica correo_saliente la referencia a la notificación del canal
-- CORREO que cada envío entrega (R045):
--   - columna  correo_saliente.notificacion_id BIGINT NULL;
--   - clave    fk_correo_saliente_notificacion → notificacion (id) ON DELETE RESTRICT;
--   - índice   ix_correo_saliente_notificacion.
--
-- A quién se aplica: a las bases instaladas con una versión de db/01_schema.sql anterior a esa
-- columna. En una base instalada con el 01_schema.sql actual no cambia nada (los tres objetos ya
-- existen), y puede ejecutarse varias veces sin efecto adicional.
--
-- Garantías:
--   - No borra ni reescribe datos: solo agrega una columna que admite nulos, una clave foránea y un
--     índice. Los correos ya guardados quedan con notificacion_id en NULL.
--   - Se ejecuta en una sola transacción: si algo falla, no queda nada aplicado.
--   - Si el esquema base de FEDER no está instalado, se detiene con un error y no crea nada.
--
-- Ejecución recomendada: java -jar target/feder-email-driven.jar actualizar-esquema
-- (muestra el destino y lo pendiente; solo aplica con --confirmar=<nombre exacto de la base>).
-- =====================================================================================

SET client_encoding = 'UTF8';

BEGIN;

SET LOCAL search_path = public;
SET LOCAL client_min_messages = warning;

-- Guardia: la migración solo tiene sentido sobre el esquema base ya instalado.
DO $$
BEGIN
    IF to_regclass(format('%I.correo_saliente', current_schema())) IS NULL
       OR to_regclass(format('%I.notificacion', current_schema())) IS NULL THEN
        RAISE EXCEPTION 'La base % no tiene instalado el esquema base de FEDER (faltan correo_saliente o notificacion); no se modificó nada.', current_database()
            USING HINT = 'Instale primero el esquema con la acción instalar-esquema (db/01_schema.sql).';
    END IF;
END
$$;

ALTER TABLE correo_saliente ADD COLUMN IF NOT EXISTS notificacion_id BIGINT;

-- ADD CONSTRAINT no admite IF NOT EXISTS: se consulta el catálogo por el nombre de la restricción.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
         WHERE conname = 'fk_correo_saliente_notificacion'
           AND conrelid = to_regclass(format('%I.correo_saliente', current_schema()))) THEN
        ALTER TABLE correo_saliente
            ADD CONSTRAINT fk_correo_saliente_notificacion FOREIGN KEY (notificacion_id)
            REFERENCES notificacion (id) ON DELETE RESTRICT;
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS ix_correo_saliente_notificacion ON correo_saliente (notificacion_id);

COMMIT;
