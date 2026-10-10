-- ═══════════════════════════════════════════════════════════════════════════
-- V8: LOS NOMBRES DE COLUMNA DE LA RECONCILIACIÓN, EN INGLÉS
--
-- **POR QUÉ HACE FALTA ESTA MIGRACIÓN.**
--
-- `comprobar_proyecciones()` (V6) devuelve tres columnas llamadas `total_en_bd`,
-- `total_esperado` y `diferencia`. Como `InventoryService.divergences()` ejecuta
-- `SELECT * FROM comprobar_proyecciones()`, el mapper las lee por nombre:
--
--     totalEnBd = getBigDecimal("total_en_bd").toPlainString()
--
-- Es decir, **esos nombres en español llegaban hasta el JSON de la respuesta**,
-- porque el DTO se serializa con los nombres de sus propiedades. El contrato
-- §9 publicaba `totalEnBd`, `totalEsperado` y `diferencia` como campos de la API.
--
-- Eso incumple la regla de idioma de `ARQUITECTURA.md` §5.1: **identificadores en
-- inglés, solo los comentarios en español.** Y el incumplimiento no era cosmético
-- —era un campo de API pública.
--
-- **POR QUÉ HAY QUE RECREAR LA FUNCIÓN Y NO SOLO RENOMBRAR.** En PostgreSQL, los
-- nombres de un `RETURNS TABLE` son parte de la **firma** de la función. No existe
-- `ALTER FUNCTION ... RENAME COLUMN` para ellos: un `CREATE OR REPLACE` con otros
-- nombres de salida **falla** con
--
--     ERROR: cannot change name of input parameter
--            HINT: Use DROP FUNCTION comprobar_proyecciones() first.
--
-- Así que se suelta y se vuelve a crear. El `DROP` es seguro aquí porque la función
-- **no tiene estado**: se recalcula entera en cada llamada y nadie la referencia
-- desde una vista, un índice o una columna generada. Se comprobó con
-- `\d+ comprobar_proyecciones` antes de escribir esta migración.
--
-- **Se conserva el nombre de la función en español** (`comprobar_proyecciones`)
-- aunque la norma pida inglés, y es una decisión consciente: renombrarla obligaría
-- a cambiar el `GRANT`, la llamada de `InventoryService` y el `COMMENT`, y **no
-- aporta nada al contrato HTTP**, que es donde el idioma se filtra al cliente. Los
-- nombres de las funciones PL/pgSQL del motor de inventario son un detalle interno
-- de la base y se dejan como están para no mezclar dos cambios en una migración.
--
-- **El cuerpo de la consulta NO cambia.** Es la misma lógica de V6, carácter por
-- carácter, con otros alias de salida. Si aquí se colara un cambio de cálculo, el
-- reporte de reconciliación pasaría a mentir en silencio, que es exactamente el
-- fallo que este endpoint existe para detectar.
-- ═══════════════════════════════════════════════════════════════════════════

DROP FUNCTION IF EXISTS comprobar_proyecciones();

CREATE OR REPLACE FUNCTION comprobar_proyecciones()
RETURNS TABLE (
    product_id       BIGINT,
    product_code     TEXT,
    projected_total  NUMERIC,
    ledger_total     NUMERIC,
    difference       NUMERIC
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.id,
        p.product_code::TEXT,
        COALESCE(ps.quantity, 0)                                        AS projected_total,
        COALESCE((SELECT SUM(s.quantity) FROM stock_by_location s
                  WHERE s.product_id = p.id), 0)
      + COALESCE((SELECT SUM(t.quantity) FROM stock_in_transit t
                  WHERE t.product_id = p.id AND t.received_at IS NULL), 0) AS ledger_total,
        COALESCE(ps.quantity, 0)
      - (COALESCE((SELECT SUM(s.quantity) FROM stock_by_location s
                   WHERE s.product_id = p.id), 0)
       + COALESCE((SELECT SUM(t.quantity) FROM stock_in_transit t
                   WHERE t.product_id = p.id AND t.received_at IS NULL), 0))  AS difference
    FROM products p
    LEFT JOIN product_stock ps ON ps.product_id = p.id
    WHERE COALESCE(ps.quantity, 0) <>
          (COALESCE((SELECT SUM(s.quantity) FROM stock_by_location s
                     WHERE s.product_id = p.id), 0)
         + COALESCE((SELECT SUM(t.quantity) FROM stock_in_transit t
                     WHERE t.product_id = p.id AND t.received_at IS NULL), 0));
END;
$$ LANGUAGE plpgsql;

-- El `COMMENT` se recrea con la función: al soltarla se pierde el de V6.
COMMENT ON FUNCTION comprobar_proyecciones IS
    'Devuelve SOLO los productos donde product_stock no coincide con la suma de '
    'sus ubicaciones mas el transito no recibido. Si no devuelve filas, el '
    'inventario cuadra. En el legacy esta comprobacion no existia. Los nombres de '
    'salida van en INGLES porque llegan al JSON de /v1/reports/kardex-vs-projection.';

-- El permiso se pierde con el DROP: hay que volver a concederlo, o la ruta empieza
-- a fallar con «permission denied for function» y el sintoma no apunta a la causa.
DO $$
DECLARE
    app_role TEXT := current_user;
BEGIN
    EXECUTE format('GRANT EXECUTE ON FUNCTION comprobar_proyecciones() TO %I', app_role);
END $$;
