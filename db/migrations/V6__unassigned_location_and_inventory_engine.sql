-- ═══════════════════════════════════════════════════════════════════════════
-- V6: UBICACIÓN «SIN UBICAR» Y MOTOR DE INVENTARIO
--
-- Va SEPARADA de V5 por una limitación de PostgreSQL, no por gusto:
-- **no se puede usar un valor nuevo de un enum en la misma transacción en la que
-- se añadió**. El `ALTER TYPE ... ADD VALUE 'UNASSIGNED'` está en V5, y aquí ya se
-- puede usar porque es otra transacción.
--
-- Ver el comentario de V5 para el detalle completo y el porqué del despiste.
--
-- DOS BUGS REALES CORREGIDOS EN ESTE SQL, encontrados ejecutándolo:
--
--  1. `SELECT kind INTO tipo_origen FROM locations` — la variable `kind` chocaba
--     con la columna `kind`. Se eliminó la comprobación: la clave foránea ya
--     garantiza que la ubicación exista, y su error es mejor que una variable sin
--     usar.
--
--  2. **El grave:** en `ON CONFLICT DO UPDATE`, un nombre de columna sin
--     cualificar se resuelve a la fila que se intentaba INSERTAR. Así que
--     `quantity = quantity + p_delta` calculaba `p_delta + p_delta` y **el saldo
--     nunca se acumulaba**. El síntoma engañaba: con +500 el saldo quedaba en 1000
--     en lugar de 1500, y con -300 daba -600 y violaba `stock_non_negative`.
--     Parecía un problema de la restricción y era de esa línea.
--
-- Referencia: PLAN_ALMACEN_INTEGRAL.md §2.4, §2.6 y §2.7.
-- ═══════════════════════════════════════════════════════════════════════════

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
    -- ══════════════════════════════════════════════════════════════════════
    -- POR QUÉ NO SE USA `INSERT ... ON CONFLICT` — y costó encontrarlo
    -- ══════════════════════════════════════════════════════════════════════
    --
    -- La versión anterior proponía la fila con el delta y confiaba en
    -- `ON CONFLICT DO UPDATE` para sumarlo al saldo existente. **No se puede.**
    --
    -- PostgreSQL valida los `CHECK` de la tabla sobre la fila **PROPUESTA**
    -- **antes** de resolver el conflicto. Así que proponer un valor negativo falla
    -- siempre, aunque el conflicto lo fuera a convertir en positivo
    -- (1000 + (−300) = 700 pasaría, pero nunca se llega a calcular).
    --
    -- El síntoma era desconcertante y me llevó por el camino equivocado: con
    -- `+1000` funcionaba y con `−300` no, y el error señalaba `stock_non_negative`.
    -- Parecía un problema del saldo y era de la mecánica del `ON CONFLICT`.
    --
    -- La forma correcta: **intentar el UPDATE primero.** Si afecta a una fila, ya
    -- está; si no afecta a ninguna, es la primera vez en esa ubicación y se hace
    -- el INSERT con el valor completo.
    -- ══════════════════════════════════════════════════════════════════════

    UPDATE stock_by_location
    SET quantity = quantity + p_delta,
        updated_at = now()
    WHERE product_id = p_product_id
      AND location_id = p_location_id
    RETURNING quantity INTO cantidad_resultante;

    IF NOT FOUND THEN
        -- Primera vez en esta ubicación. Aquí el valor propuesto ES el saldo, así
        -- que un delta negativo solo llega si de verdad se está sacando de una
        -- ubicación vacía — y entonces el CHECK **debe** fallar. Es correcto.
        INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity, updated_at)
        VALUES (p_product_id, p_location_id, p_warehouse_id, p_delta, now())
        RETURNING quantity INTO cantidad_resultante;
    END IF;

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
