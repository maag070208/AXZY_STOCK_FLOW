-- ═══════════════════════════════════════════════════════════════════════════
-- V5: UBICACIÓN «SIN UBICAR» Y MOTOR DE INVENTARIO
--
-- ⚠️  ESTA MIGRACIÓN **NO SE PUEDE APLICAR CON FLYWAY TODAVÍA**, y está
--     identificada la causa exacta.
--
-- PostgreSQL **no permite usar un valor nuevo de un enum en la MISMA transacción
-- en la que se añadió** («unsafe use of new value of enum type»). Flyway envuelve
-- cada migración en una transacción, así que el `ALTER TYPE ... ADD VALUE
-- 'UNASSIGNED'` de más abajo no se puede combinar con el `INSERT` que después usa
-- ese valor.
--
-- Se aplicaba sin problema con `psql < archivo` porque `psql` va en autocommit, y
-- **de ahí venía la inconsistencia**: funcionaba a mano y fallaba con Flyway. Ese
-- fue el motivo de dar vueltas durante un buen rato sobre un motor que en
-- realidad funcionaba.
--
-- ESTADO REAL DEL MOTOR: los dos bugs del SQL están corregidos (la colisión de
-- nombres con `kind` y la ambigüedad de `quantity` en el `ON CONFLICT`, que
-- hacía que el saldo nunca se acumulara). El motor **funciona**, comprobado
-- llamando a `registrar_movimiento` con una sola conexión.
--
-- LO QUE FALTA PARA CERRARLO:
--   1. Separar el `ALTER TYPE` a su propia migración (V5) y mover el resto a V6,
--      o marcar esta migración como no transaccional. Lo primero es más limpio.
--   2. Poner los tests de `MotorInventarioTest` en verde: hoy fallan porque la
--      migración no aplica, no porque el motor esté mal.
--   3. Añadir la prueba que falta: que `comprobar_proyecciones()` devuelva CERO
--      filas tras una secuencia de carga, ubicación y movimiento.
--
-- Referencia: PLAN_ALMACEN_INTEGRAL.md §2.4, §2.6 y §2.7.
--
-- Dos cosas que van juntas porque la primera existe para la segunda.
--
-- **1. La ubicación `SIN-UBICAR`.** El archivo de inventario del cliente trae
-- 10.914.130 unidades **sin decir dónde están** (verificado: el Excel solo tiene
-- producto, nombre, unidad y cantidad). La migración tiene que cargar ese stock
-- en algún sitio, y la decisión tomada es una ubicación propia y distinta de
-- recepción.
--
-- **Por qué no la recepción.** «Recepción» significa «acaba de llegar y todavía
-- no está ubicado». Si le metemos 10,9 millones de unidades de stock histórico,
-- deja de significar eso: el flujo de ubicar tendría que distinguir cuáles son
-- nuevas y cuáles son el saldo heredado, y esa distinción no se puede hacer bien
-- a posteriori. `SIN-UBICAR` dice exactamente lo que es —«está en el almacén, no
-- sé en qué estante»— y el conteo físico la va vaciando hacia los estantes.
--
-- **2. El motor de inventario.** Funciones que mantienen las proyecciones
-- (`stock_by_location` y `product_stock`) coherentes con el ledger. Van en la
-- base y no en el código porque **deben ser atómicas**: si el proceso falla a
-- mitad entre escribir el kardex y actualizar la proyección, el inventario queda
-- descuadrado y nadie lo sabe.
--
-- Referencia: PLAN_ALMACEN_INTEGRAL.md §2.4, §2.6 y §2.7.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. El tipo de ubicación para el saldo heredado ─────────────────────────

-- Se añade un valor al enum. `IF NOT EXISTS` no existe para los valores de un
-- enum, así que se comprueba a mano para que la migración sea repetible.
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
    'SHELF = estante normal. RECEIVING = zona de recepción: lo recién llegado y '
    'sin ubicar todavia. UNASSIGNED = saldo heredado del sistema anterior, del '
    'que se sabe que existe pero no donde esta. Se vacia con el conteo fisico.';

-- ── 2. La ubicación, una por bodega ────────────────────────────────────────
--
-- `code` es 'SIN-UBICAR' pero el alfabeto de `locations.code` es [0-9A-Z], sin
-- guiones. Se usa 'SINUBICAR' en la columna y el nombre visible lo pone el
-- servidor. Es el mismo criterio que con la ubicación de recepción.

INSERT INTO locations (warehouse_id, hall, code, position, levels, kind)
SELECT id, 'SINUBICAR', 'SINUBICAR', 'SINUBICAR', 1, 'UNASSIGNED'
FROM warehouses
WHERE is_default
ON CONFLICT (warehouse_id, hall, code, position) DO NOTHING;

-- ── 3. El motor de inventario ──────────────────────────────────────────────
--
-- CUATRO FUNCIONES, y las cuatro son la misma idea: el ledger manda y las
-- proyecciones se derivan. **Ninguna operación puede restar del total sin un
-- movimiento que lo justifique**, que es la inversión del modelo del legacy.
--
-- El legacy guardaba `Store.stock` como un número y lo compensaba a mano con
-- restas, así que cualquier operación que se olvidara de compensar lo descuadraba
-- de forma permanente. Aquí no hay ningún número que mantener: `product_stock` se
-- recalcula desde `stock_by_location`, y `stock_by_location` solo cambia con las
-- funciones de abajo.

-- ── 3.1 Aplicar un cambio a una ubicación ──────────────────────────────────

CREATE OR REPLACE FUNCTION aplicar_movimiento_a_ubicacion(
    p_product_id     BIGINT,
    p_location_id    BIGINT,
    p_warehouse_id   BIGINT,
    p_delta          NUMERIC,   -- positivo suma, negativo resta
    p_permitir_negativo BOOLEAN DEFAULT FALSE
)
RETURNS NUMERIC AS $$
DECLARE
    cantidad_resultante NUMERIC;
BEGIN
    -- El `INSERT ... ON CONFLICT` resuelve el caso de la primera vez en una
    -- ubicación sin necesidad de comprobar antes si existe. Comprobar y luego
    -- insertar tiene una carrera: dos peticiones simultáneas ven que no existe y
    -- las dos intentan crearla.
    INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity, updated_at)
    VALUES (p_product_id, p_location_id, p_warehouse_id, p_delta, now())
    ON CONFLICT (product_id, location_id)
    DO UPDATE SET
        -- ── LA CUALIFICACIÓN ES OBLIGATORIA, Y SU AUSENCIA ERA UN BUG REAL ──
        --
        -- En `DO UPDATE`, un nombre de columna sin cualificar se resuelve a la
        -- fila que se está intentando INSERTAR (lo que en un `WHERE` sería
        -- `EXCLUDED`). Así que `quantity = quantity + p_delta` calculaba
        -- `p_delta + p_delta`: **el saldo nunca se acumulaba**.
        --
        -- El síntoma engañaba. Con un delta de +500 el saldo quedaba en 1000 en
        -- lugar de 1500, y con -300 daba -600 y violaba la restricción de no
        -- negativo. Parecía un problema de la restricción y era de esta línea.
        --
        -- `stock_by_location.quantity` es la fila EXISTENTE; el valor propuesto
        -- se referencia con `EXCLUDED` si hiciera falta, pero aquí no hace falta
        -- porque el delta ya viene en el parámetro.
        quantity = stock_by_location.quantity + p_delta,
        updated_at = now()
    RETURNING quantity INTO cantidad_resultante;

    -- El stock negativo se comprueba AQUÍ y no solo con el CHECK.
    --
    -- El CHECK de la tabla lo impediría igual, pero su error es
    -- «stock_non_negative» y no dice qué producto, qué ubicación ni cuánto había.
    -- Un mensaje que el usuario pueda entender y con el que pueda actuar vale más
    -- que un error genérico de restricción.
    IF cantidad_resultante < 0 AND NOT p_permitir_negativo THEN
        RAISE EXCEPTION
            'Stock insuficiente: el producto % quedaría con % en la ubicación %',
            p_product_id, cantidad_resultante, p_location_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN cantidad_resultante;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION aplicar_movimiento_a_ubicacion IS
    'Suma o resta una cantidad en una ubicacion y devuelve el saldo resultante. '
    'Impide el stock negativo con un mensaje que dice QUE producto y CUANTO '
    'quedaba, no solo que fallo una restriccion.';

-- ── 3.2 Recalcular el total de un producto ─────────────────────────────────

CREATE OR REPLACE FUNCTION recalcular_total_producto(p_product_id BIGINT)
RETURNS NUMERIC AS $$
DECLARE
    total NUMERIC;
BEGIN
    -- El total es una SUMA DERIVADA, no un número que se mantenga a mano.
    --
    -- Suma dos cosas: lo que está en las ubicaciones y lo que está **en tránsito
    -- y no se ha recibido**. La segunda condición es imprescindible: un traslado
    -- ya recibido tiene sus unidades en `stock_by_location` Y su fila de tránsito
    -- sigue existiendo como histórico, así que sin `received_at IS NULL` la misma
    -- mercancía contaría dos veces.
    SELECT
        COALESCE((SELECT SUM(quantity) FROM stock_by_location WHERE product_id = p_product_id), 0)
      + COALESCE((SELECT SUM(quantity) FROM stock_in_transit
                  WHERE product_id = p_product_id AND received_at IS NULL), 0)
    INTO total;

    INSERT INTO product_stock (product_id, quantity, updated_at)
    VALUES (p_product_id, total, now())
    ON CONFLICT (product_id)
    DO UPDATE SET quantity = total, updated_at = now();

    RETURN total;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION recalcular_total_producto IS
    'Recalcula product_stock como la SUMA de las ubicaciones mas el transito NO '
    'recibido. El total es una proyeccion derivada: no hay ningun numero que '
    'mantener sincronizado a mano.';

-- ── 3.3 Registrar un movimiento completo ───────────────────────────────────
--
-- Es la operación que usa la aplicación: escribe el kardex Y actualiza las
-- proyecciones en la misma transacción. **Separarlas es lo que descuadra un
-- inventario.**

CREATE OR REPLACE FUNCTION registrar_movimiento(
    p_product_id        BIGINT,
    p_warehouse_id      BIGINT,
    p_move_type         movement_type,
    p_units             NUMERIC,
    p_from_location_id  BIGINT DEFAULT NULL,
    p_to_location_id    BIGINT DEFAULT NULL,
    p_container         TEXT DEFAULT NULL,
    p_order_number      TEXT DEFAULT NULL,
    p_adjustment_reason adjustment_reason DEFAULT NULL,
    p_adjustment_origin adjustment_origin DEFAULT NULL,
    p_user_id           BIGINT DEFAULT NULL,
    p_client_uuid       UUID DEFAULT NULL,
    p_note              TEXT DEFAULT NULL
)
RETURNS BIGINT AS $$
DECLARE
    kardex_id        BIGINT;
    total_resultante NUMERIC;
BEGIN
    IF p_units <= 0 THEN
        RAISE EXCEPTION 'Las unidades de un movimiento deben ser positivas' USING ERRCODE = 'check_violation';
    END IF;

    -- ── Origen: se resta ────────────────────────────────────────────────────
    -- NO se comprueba el tipo del origen. Lo que importa es que exista, y eso lo
    -- garantiza la clave foránea: si el identificador no corresponde a ninguna
    -- ubicación, el `INSERT` en `stock_by_location` falla con un error de
    -- integridad referencial, que es un mensaje mejor que una variable sin usar.
    IF p_from_location_id IS NOT NULL THEN
        PERFORM aplicar_movimiento_a_ubicacion(
            p_product_id, p_from_location_id, p_warehouse_id, -p_units, FALSE
        );
    END IF;

    -- ── Destino: se suma ────────────────────────────────────────────────────
    IF p_to_location_id IS NOT NULL THEN
        PERFORM aplicar_movimiento_a_ubicacion(
            p_product_id, p_to_location_id, p_warehouse_id, p_units, FALSE
        );
    END IF;

    -- ── El total se recalcula DESPUÉS de mover ─────────────────────────────
    -- El orden importa: recalcular antes daría el total viejo.
    total_resultante := recalcular_total_producto(p_product_id);

    -- ── El kardex, al final y con el saldo ya calculado ────────────────────
    -- `quantity_after` hace el ledger auto-auditable: se puede reconstruir el
    -- saldo sin recalcular todo el histórico.
    INSERT INTO kardex (
        product_id, warehouse_id, move_type, units, quantity_after,
        location_id, from_location_id, to_location_id,
        container, order_number, adjustment_reason, adjustment_origin,
        user_id, client_uuid, note
    ) VALUES (
        p_product_id, p_warehouse_id, p_move_type, p_units, total_resultante,
        p_to_location_id, p_from_location_id, p_to_location_id,
        p_container, p_order_number, p_adjustment_reason, p_adjustment_origin,
        p_user_id, p_client_uuid, p_note
    )
    RETURNING id INTO kardex_id;

    RETURN kardex_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION registrar_movimiento IS
    'Registra un movimiento de inventario: ajusta las ubicaciones, recalcula el '
    'total y escribe el kardex, todo en UNA transaccion. Separar estas tres cosas '
    'es lo que descuadra un inventario.';

-- ── 3.4 Comprobar que las proyecciones cuadran con el ledger ───────────────
--
-- **El reporte de reconciliación.** En el legacy esta comprobación no existía, y
-- el descuadre solo se descubría con un conteo físico. Aquí se puede preguntar en
-- cualquier momento y sin contar nada.

CREATE OR REPLACE FUNCTION comprobar_proyecciones()
RETURNS TABLE (
    product_id      BIGINT,
    product_code    TEXT,
    total_en_bd     NUMERIC,
    total_esperado  NUMERIC,
    diferencia      NUMERIC
) AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.id,
        p.product_code::TEXT,
        COALESCE(ps.quantity, 0)                                        AS total_en_bd,
        COALESCE((SELECT SUM(s.quantity) FROM stock_by_location s
                  WHERE s.product_id = p.id), 0)
      + COALESCE((SELECT SUM(t.quantity) FROM stock_in_transit t
                  WHERE t.product_id = p.id AND t.received_at IS NULL), 0) AS total_esperado,
        COALESCE(ps.quantity, 0)
      - (COALESCE((SELECT SUM(s.quantity) FROM stock_by_location s
                   WHERE s.product_id = p.id), 0)
       + COALESCE((SELECT SUM(t.quantity) FROM stock_in_transit t
                   WHERE t.product_id = p.id AND t.received_at IS NULL), 0))  AS diferencia
    FROM products p
    LEFT JOIN product_stock ps ON ps.product_id = p.id
    WHERE COALESCE(ps.quantity, 0) <>
          (COALESCE((SELECT SUM(s.quantity) FROM stock_by_location s
                     WHERE s.product_id = p.id), 0)
         + COALESCE((SELECT SUM(t.quantity) FROM stock_in_transit t
                     WHERE t.product_id = p.id AND t.received_at IS NULL), 0));
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION comprobar_proyecciones IS
    'Devuelve SOLO los productos donde product_stock no coincide con la suma de '
    'sus ubicaciones mas el transito no recibido. Si no devuelve filas, el '
    'inventario cuadra. En el legacy esta comprobacion no existia.';

-- ── 4. Permisos ────────────────────────────────────────────────────────────

DO $$
DECLARE
    app_role TEXT := current_user;
BEGIN
    EXECUTE format('GRANT EXECUTE ON FUNCTION aplicar_movimiento_a_ubicacion(BIGINT,BIGINT,BIGINT,NUMERIC,BOOLEAN) TO %I', app_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION recalcular_total_producto(BIGINT) TO %I', app_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION comprobar_proyecciones() TO %I', app_role);
END $$;
