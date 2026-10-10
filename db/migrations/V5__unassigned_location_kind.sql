-- ═══════════════════════════════════════════════════════════════════════════
-- V5: EL TIPO DE UBICACIÓN «SIN UBICAR»
--
-- **ESTA MIGRACIÓN TIENE QUE IR SOLA, Y ES OBLIGATORIO.**
--
-- PostgreSQL **no permite usar un valor nuevo de un enum en la MISMA transacción
-- en la que se añadió** («unsafe use of new value of enum type»). Flyway envuelve
-- cada migración en una transacción, así que el `ALTER TYPE` y cualquier
-- `INSERT` que use el valor nuevo no pueden convivir en el mismo archivo.
--
-- Aplicarla con `psql < archivo` **sí** funcionaba, porque `psql` va en
-- autocommit — y de ahí venía una inconsistencia que costó un buen rato: el motor
-- funcionaba a mano y fallaba con Flyway, y el error apuntaba al SQL del motor en
-- lugar de a esta limitación.
--
-- Por eso esta migración **solo añade el valor** y no toca nada más. La ubicación
-- y el motor de inventario van en V6.
-- ═══════════════════════════════════════════════════════════════════════════

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_enum e
        JOIN pg_type t ON t.oid = e.enumtypid
        WHERE t.typname = 'location_kind' AND e.enumlabel = 'UNASSIGNED'
    ) THEN
        ALTER TYPE location_kind ADD VALUE 'UNASSIGNED';
    END IF;
END $$;

COMMENT ON TYPE location_kind IS
    'SHELF = estante normal. RECEIVING = zona de recepcion: lo recien llegado y '
    'sin ubicar todavia. UNASSIGNED = saldo heredado del sistema anterior, del que '
    'se sabe que existe pero no donde esta. Se vacia con el conteo fisico.';
