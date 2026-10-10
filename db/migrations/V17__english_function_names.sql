-- ═══════════════════════════════════════════════════════════════════════════
-- V17 — Function names and messages in English (ADR-0018).
--
-- Las funciones del motor (traslados, ubicaciones, movimientos, proyecciones) tenían nombres en
-- español. Se crean con su nombre en inglés, con el mismo cuerpo y los mismos argumentos, y se borran
-- las versiones en español. Los cuerpos se llaman entre sí, así que se reescriben aquí mismo.
--
-- Los mensajes de `RAISE EXCEPTION` llegan a la API y a los logs: se traducen. La API no compara por
-- texto (los errores de constraint se reconocen por el nombre del índice), así que no cambia la lógica.
-- ═══════════════════════════════════════════════════════════════════════════

-- ubicacion_recepcion -> receiving_location (definition from V13__transfer_engine.sql)
CREATE OR REPLACE FUNCTION receiving_location(p_warehouse_id BIGINT)
RETURNS BIGINT AS $$
    SELECT id FROM locations
    WHERE warehouse_id = p_warehouse_id AND kind = 'RECEIVING' AND active
    ORDER BY id
    LIMIT 1;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION receiving_location IS
    'La ubicación de recepción de una bodega. Devuelve NULL si no la tiene, y quien '
    'llama decide: un traslado sin destino donde llegar es un error de datos.';

-- despachar_traslado -> dispatch_transfer (definition from V13__transfer_engine.sql)
CREATE OR REPLACE FUNCTION dispatch_transfer(p_transfer_id BIGINT, p_user_id BIGINT)
RETURNS INT AS $$
DECLARE
    v_from     BIGINT;
    v_to       BIGINT;
    v_origen   BIGINT;
    r          RECORD;
    v_lineas   INT := 0;
BEGIN
    SELECT from_warehouse_id, to_warehouse_id INTO v_from, v_to
    FROM transfers WHERE id = p_transfer_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Transfer % does not exist', p_transfer_id USING ERRCODE = 'no_data_found';
    END IF;

    FOR r IN
        SELECT * FROM transfer_lines
        WHERE transfer_id = p_transfer_id
        ORDER BY id
    LOOP
        -- Sin ubicación en la línea, sale de la recepción de la bodega de origen.
        v_origen := COALESCE(r.from_location_id, receiving_location(v_from));
        IF v_origen IS NULL THEN
            RAISE EXCEPTION 'Warehouse % has no receiving location', v_from
                USING ERRCODE = 'no_data_found';
        END IF;

        -- Sale del origen. Si no hay saldo, `apply_location_movement` choca
        -- con el `CHECK stock_non_negative` y la transacción entera se revierte: no
        -- queda media salida.
        PERFORM record_movement(
            r.product_id, v_from, 'TRANSFER_OUT', r.quantity,
            v_origen, NULL, NULL, NULL, NULL, NULL, p_user_id, NULL, r.note
        );

        INSERT INTO stock_in_transit (
            product_id, from_warehouse_id, to_warehouse_id, from_location_id, quantity, transfer_id
        ) VALUES (
            r.product_id, v_from, v_to, v_origen, r.quantity, p_transfer_id
        );

        -- Y ahora sí: el tránsito ya cuenta, así que el total vuelve a ser el de antes.
        PERFORM recalculate_product_total(r.product_id);
        v_lineas := v_lineas + 1;
    END LOOP;

    UPDATE transfers
    SET status = 'DISPATCHED', dispatched_by = p_user_id, dispatched_at = now(), updated_at = now()
    WHERE id = p_transfer_id;

    RETURN v_lineas;
END;
$$ LANGUAGE plpgsql;

-- recibir_traslado -> receive_transfer (definition from V13__transfer_engine.sql)
CREATE OR REPLACE FUNCTION receive_transfer(p_transfer_id BIGINT, p_user_id BIGINT)
RETURNS INT AS $$
DECLARE
    v_to       BIGINT;
    v_destino  BIGINT;
    r          RECORD;
    v_lineas   INT := 0;
BEGIN
    SELECT to_warehouse_id INTO v_to FROM transfers WHERE id = p_transfer_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Transfer % does not exist', p_transfer_id USING ERRCODE = 'no_data_found';
    END IF;

    v_destino := receiving_location(v_to);
    IF v_destino IS NULL THEN
        RAISE EXCEPTION 'Warehouse % has no receiving location', v_to
            USING ERRCODE = 'no_data_found';
    END IF;

    FOR r IN
        SELECT tl.id, tl.product_id, tl.quantity, tl.note
        FROM transfer_lines tl
        JOIN stock_in_transit t
          ON t.transfer_id = tl.transfer_id
         AND t.product_id = tl.product_id
         AND t.received_at IS NULL
        WHERE tl.transfer_id = p_transfer_id
        ORDER BY tl.id
    LOOP
        -- Primero se suma al destino…
        PERFORM record_movement(
            r.product_id, v_to, 'TRANSFER_IN', r.quantity,
            NULL, v_destino, NULL, NULL, NULL, NULL, p_user_id, NULL, r.note
        );

        -- …y después se cierra la fila de tránsito.
        UPDATE stock_in_transit SET received_at = now()
        WHERE transfer_id = p_transfer_id AND product_id = r.product_id AND received_at IS NULL;

        PERFORM recalculate_product_total(r.product_id);
        v_lineas := v_lineas + 1;
    END LOOP;

    UPDATE transfers
    SET status = 'RECEIVED', received_by = p_user_id, received_at = now(), updated_at = now()
    WHERE id = p_transfer_id;

    RETURN v_lineas;
END;
$$ LANGUAGE plpgsql;

-- cancelar_traslado -> cancel_transfer (definition from V13__transfer_engine.sql)
CREATE OR REPLACE FUNCTION cancel_transfer(p_transfer_id BIGINT, p_user_id BIGINT)
RETURNS INT AS $$
DECLARE
    v_status  transfer_status;
    r         RECORD;
    v_lineas  INT := 0;
BEGIN
    SELECT status INTO v_status FROM transfers WHERE id = p_transfer_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Transfer % does not exist', p_transfer_id USING ERRCODE = 'no_data_found';
    END IF;

    IF v_status = 'DISPATCHED' THEN
        FOR r IN
            SELECT * FROM stock_in_transit
            WHERE transfer_id = p_transfer_id AND received_at IS NULL
            ORDER BY id
        LOOP
            PERFORM record_movement(
                r.product_id, r.from_warehouse_id, 'TRANSFER_IN', r.quantity,
                NULL, r.from_location_id, NULL, NULL, NULL, NULL, p_user_id, NULL,
                'Traslado cancelado: la mercancía vuelve a su ubicación de origen'
            );
            UPDATE stock_in_transit SET received_at = now() WHERE id = r.id;
            PERFORM recalculate_product_total(r.product_id);
            v_lineas := v_lineas + 1;
        END LOOP;
    END IF;

    UPDATE transfers SET status = 'CANCELLED', updated_at = now() WHERE id = p_transfer_id;
    RETURN v_lineas;
END;
$$ LANGUAGE plpgsql;

-- aplicar_movimiento_a_ubicacion -> apply_location_movement (definition from V6__unassigned_location_and_inventory_engine.sql)
CREATE OR REPLACE FUNCTION apply_location_movement(
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
            'Insufficient stock: product % would be left with % in location %',
            p_product_id, cantidad_resultante, p_location_id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN cantidad_resultante;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION apply_location_movement IS
    'Suma o resta una cantidad en una ubicacion y devuelve el saldo resultante. '
    'Impide el stock negativo con un mensaje que dice QUE producto y CUANTO '
    'quedaba, no solo que fallo una restriccion.';

-- recalcular_total_producto -> recalculate_product_total (definition from V10__recalcular_total_con_bloqueo.sql)
CREATE OR REPLACE FUNCTION recalculate_product_total(p_product_id BIGINT)
RETURNS NUMERIC AS $$
DECLARE
    total NUMERIC;
BEGIN
    -- Serializa los recálculos del mismo producto. Sin esta línea el SUM de abajo no ve
    -- las escrituras concurrentes sin confirmar, y el total se pierde (ver cabecera).
    PERFORM 1 FROM products WHERE id = p_product_id FOR UPDATE;

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

COMMENT ON FUNCTION recalculate_product_total IS
    'Recalcula product_stock como la SUMA de las ubicaciones mas el transito NO '
    'recibido. El total es una proyeccion derivada: no hay ningun numero que '
    'mantener sincronizado a mano.';

COMMENT ON FUNCTION recalculate_product_total IS
    'Recalcula product_stock como la SUMA de las ubicaciones mas el transito NO '
    'recibido, con el producto bloqueado para que dos recalculos concurrentes '
    'no pierdan la escritura del otro.';

-- registrar_movimiento -> record_movement (definition from V6__unassigned_location_and_inventory_engine.sql)
CREATE OR REPLACE FUNCTION record_movement(
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
        RAISE EXCEPTION 'The units of a movement must be positive' USING ERRCODE = 'check_violation';
    END IF;

    -- ── Origen: se resta ────────────────────────────────────────────────────
    -- NO se comprueba el tipo del origen. Lo que importa es que exista, y eso lo
    -- garantiza la clave foránea: si el identificador no corresponde a ninguna
    -- ubicación, el `INSERT` en `stock_by_location` falla con un error de
    -- integridad referencial, que es un mensaje mejor que una variable sin usar.
    IF p_from_location_id IS NOT NULL THEN
        PERFORM apply_location_movement(
            p_product_id, p_from_location_id, p_warehouse_id, -p_units, FALSE
        );
    END IF;

    -- ── Destino: se suma ────────────────────────────────────────────────────
    IF p_to_location_id IS NOT NULL THEN
        PERFORM apply_location_movement(
            p_product_id, p_to_location_id, p_warehouse_id, p_units, FALSE
        );
    END IF;

    -- ── El total se recalcula DESPUÉS de mover ─────────────────────────────
    -- El orden importa: recalcular antes daría el total viejo.
    total_resultante := recalculate_product_total(p_product_id);

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

COMMENT ON FUNCTION record_movement IS
    'Registra un movimiento de inventario: ajusta las ubicaciones, recalcula el '
    'total y escribe el kardex, todo en UNA transaccion. Separar estas tres cosas '
    'es lo que descuadra un inventario.';

-- comprobar_proyecciones -> check_projections (definition from V8__english_reconciliation_columns.sql)
CREATE OR REPLACE FUNCTION check_projections()
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

COMMENT ON FUNCTION check_projections IS
    'Devuelve SOLO los productos donde product_stock no coincide con la suma de '
    'sus ubicaciones mas el transito no recibido. Si no devuelve filas, el '
    'inventario cuadra. En el legacy esta comprobacion no existia.';

COMMENT ON FUNCTION check_projections IS
    'Devuelve SOLO los productos donde product_stock no coincide con la suma de '
    'sus ubicaciones mas el transito no recibido. Si no devuelve filas, el '
    'inventario cuadra. En el legacy esta comprobacion no existia. Los nombres de '
    'salida van en INGLES porque llegan al JSON de /v1/reports/kardex-vs-projection.';

-- ── Borrado de las versiones en español ──────────────────────────────────────
-- Se borran todas las firmas (`DROP FUNCTION` exige los tipos y hay dos con valores por defecto).
DO $$
DECLARE
    old_name TEXT;
    sig      TEXT;
BEGIN
    FOREACH old_name IN ARRAY ARRAY[
        'ubicacion_recepcion', 'despachar_traslado', 'recibir_traslado', 'cancelar_traslado',
        'aplicar_movimiento_a_ubicacion', 'recalcular_total_producto', 'registrar_movimiento',
        'comprobar_proyecciones'
    ] LOOP
        FOR sig IN
            SELECT p.oid::regprocedure::TEXT
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'public' AND p.proname = old_name
        LOOP
            EXECUTE 'DROP FUNCTION ' || sig;
        END LOOP;
    END LOOP;
END;
$$;

-- ── Permisos: la API ejecuta las funciones nuevas (todas sus firmas) ─────────
-- La API las llama directamente y también desde otras funciones del motor: PostgreSQL revisa el
-- permiso de quien invoca cada una, así que todas necesitan `EXECUTE`.
DO $$
DECLARE
    new_name TEXT;
    sig      TEXT;
BEGIN
    FOREACH new_name IN ARRAY ARRAY[
        'receiving_location', 'dispatch_transfer', 'receive_transfer', 'cancel_transfer',
        'apply_location_movement', 'recalculate_product_total', 'record_movement', 'check_projections'
    ] LOOP
        FOR sig IN
            SELECT p.oid::regprocedure::TEXT
              FROM pg_proc p
              JOIN pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = 'public' AND p.proname = new_name
        LOOP
            EXECUTE 'GRANT EXECUTE ON FUNCTION ' || sig || ' TO stock_api';
        END LOOP;
    END LOOP;
END;
$$;
