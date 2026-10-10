-- ═══════════════════════════════════════════════════════════════════════════
-- EL MOTOR DE TRASLADOS
-- ═══════════════════════════════════════════════════════════════════════════
--
-- Un traslado tiene DOS mitades y no son simétricas:
--
--   · **Despachar** resta de las ubicaciones de origen y deja la mercancía
--     **en tránsito**. El total del producto NO cambia, porque el tránsito se suma
--     en `recalcular_total_producto`. Sin esa suma, el total bajaría durante el
--     viaje y los reportes mentirían: parecería una venta.
--   · **Recibir** suma en la ubicación de recepción del destino y **cierra** la fila
--     de tránsito. El orden importa: primero se suma y después se cierra. Al revés,
--     un fallo entre las dos dejaría la mercancía fuera de las dos sumas y el total
--     bajaría.
--
-- Y **cancelar un despachado** devuelve la mercancía a su ubicación de origen en la
-- misma transacción, para no dejarla en un limbo que ningún reporte explica.
--
-- Por qué vive aquí y no en la aplicación: son movimientos de inventario, y el
-- principio del proyecto es que **el saldo lo mueve el motor SQL**, dentro de la
-- transacción y con el bloqueo de fila de `aplicar_movimiento_a_ubicacion`. Un
-- `UPDATE stock_by_location` desde Kotlin sería la segunda ruta de escritura que la
-- guardia `InventoryWriteGuardTest` prohíbe.
--
-- Todo pasa por `registrar_movimiento`, que ya escribe el kardex con su saldo
-- posterior y recalcula el total. Aquí solo se añade lo que es del traslado: el
-- tránsito, el estado del documento y las dos vueltas que el kardex no conoce.

-- La ubicación de recepción de una bodega: donde llega un traslado y de donde sale
-- si la línea no dice otra cosa.
CREATE OR REPLACE FUNCTION ubicacion_recepcion(p_warehouse_id BIGINT)
RETURNS BIGINT AS $$
    SELECT id FROM locations
    WHERE warehouse_id = p_warehouse_id AND kind = 'RECEIVING' AND active
    ORDER BY id
    LIMIT 1;
$$ LANGUAGE sql STABLE;

COMMENT ON FUNCTION ubicacion_recepcion IS
    'La ubicación de recepción de una bodega. Devuelve NULL si no la tiene, y quien '
    'llama decide: un traslado sin destino donde llegar es un error de datos.';

-- ── Despachar ───────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION despachar_traslado(p_transfer_id BIGINT, p_user_id BIGINT)
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
        RAISE EXCEPTION 'El traslado % no existe', p_transfer_id USING ERRCODE = 'no_data_found';
    END IF;

    FOR r IN
        SELECT * FROM transfer_lines
        WHERE transfer_id = p_transfer_id
        ORDER BY id
    LOOP
        -- Sin ubicación en la línea, sale de la recepción de la bodega de origen.
        v_origen := COALESCE(r.from_location_id, ubicacion_recepcion(v_from));
        IF v_origen IS NULL THEN
            RAISE EXCEPTION 'La bodega % no tiene ubicación de recepción', v_from
                USING ERRCODE = 'no_data_found';
        END IF;

        -- Sale del origen. Si no hay saldo, `aplicar_movimiento_a_ubicacion` choca
        -- con el `CHECK stock_non_negative` y la transacción entera se revierte: no
        -- queda media salida.
        PERFORM registrar_movimiento(
            r.product_id, v_from, 'TRANSFER_OUT', r.quantity,
            v_origen, NULL, NULL, NULL, NULL, NULL, p_user_id, NULL, r.note
        );

        INSERT INTO stock_in_transit (
            product_id, from_warehouse_id, to_warehouse_id, from_location_id, quantity, transfer_id
        ) VALUES (
            r.product_id, v_from, v_to, v_origen, r.quantity, p_transfer_id
        );

        -- Y ahora sí: el tránsito ya cuenta, así que el total vuelve a ser el de antes.
        PERFORM recalcular_total_producto(r.product_id);
        v_lineas := v_lineas + 1;
    END LOOP;

    UPDATE transfers
    SET status = 'DISPATCHED', dispatched_by = p_user_id, dispatched_at = now(), updated_at = now()
    WHERE id = p_transfer_id;

    RETURN v_lineas;
END;
$$ LANGUAGE plpgsql;

-- ── Recibir ─────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION recibir_traslado(p_transfer_id BIGINT, p_user_id BIGINT)
RETURNS INT AS $$
DECLARE
    v_to       BIGINT;
    v_destino  BIGINT;
    r          RECORD;
    v_lineas   INT := 0;
BEGIN
    SELECT to_warehouse_id INTO v_to FROM transfers WHERE id = p_transfer_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'El traslado % no existe', p_transfer_id USING ERRCODE = 'no_data_found';
    END IF;

    v_destino := ubicacion_recepcion(v_to);
    IF v_destino IS NULL THEN
        RAISE EXCEPTION 'La bodega % no tiene ubicación de recepción', v_to
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
        PERFORM registrar_movimiento(
            r.product_id, v_to, 'TRANSFER_IN', r.quantity,
            NULL, v_destino, NULL, NULL, NULL, NULL, p_user_id, NULL, r.note
        );

        -- …y después se cierra la fila de tránsito.
        UPDATE stock_in_transit SET received_at = now()
        WHERE transfer_id = p_transfer_id AND product_id = r.product_id AND received_at IS NULL;

        PERFORM recalcular_total_producto(r.product_id);
        v_lineas := v_lineas + 1;
    END LOOP;

    UPDATE transfers
    SET status = 'RECEIVED', received_by = p_user_id, received_at = now(), updated_at = now()
    WHERE id = p_transfer_id;

    RETURN v_lineas;
END;
$$ LANGUAGE plpgsql;

-- ── Cancelar ────────────────────────────────────────────────────────────────
--
-- Desde `DRAFT` no hay nada que devolver. Desde `DISPATCHED`, la mercancía vuelve a
-- su ubicación de origen. Desde `RECEIVED` **no se llama**: lo recibido está en las
-- ubicaciones de destino y cancelarlo dejaría la mercancía contada en el sitio
-- equivocado; para deshacerlo se hace un traslado de vuelta, que deja rastro.
--
-- La vuelta al origen se registra como `TRANSFER_IN`: la mercancía **llega** desde el
-- tránsito, que es exactamente lo que pasó. No hay un tipo «cancelación» en el
-- catálogo de movimientos, y añadirlo obligaría a que todos los reportes lo conocieran.
CREATE OR REPLACE FUNCTION cancelar_traslado(p_transfer_id BIGINT, p_user_id BIGINT)
RETURNS INT AS $$
DECLARE
    v_status  transfer_status;
    r         RECORD;
    v_lineas  INT := 0;
BEGIN
    SELECT status INTO v_status FROM transfers WHERE id = p_transfer_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'El traslado % no existe', p_transfer_id USING ERRCODE = 'no_data_found';
    END IF;

    IF v_status = 'DISPATCHED' THEN
        FOR r IN
            SELECT * FROM stock_in_transit
            WHERE transfer_id = p_transfer_id AND received_at IS NULL
            ORDER BY id
        LOOP
            PERFORM registrar_movimiento(
                r.product_id, r.from_warehouse_id, 'TRANSFER_IN', r.quantity,
                NULL, r.from_location_id, NULL, NULL, NULL, NULL, p_user_id, NULL,
                'Traslado cancelado: la mercancía vuelve a su ubicación de origen'
            );
            UPDATE stock_in_transit SET received_at = now() WHERE id = r.id;
            PERFORM recalcular_total_producto(r.product_id);
            v_lineas := v_lineas + 1;
        END LOOP;
    END IF;

    UPDATE transfers SET status = 'CANCELLED', updated_at = now() WHERE id = p_transfer_id;
    RETURN v_lineas;
END;
$$ LANGUAGE plpgsql;

-- ── Permisos ────────────────────────────────────────────────────────────────
--
-- El rol de la aplicación no es dueño del esquema: sin el `GRANT` las funciones
-- existen y no se pueden llamar. V6 lo hace así y este archivo sigue el mismo patrón.
DO $$
DECLARE
    app_role TEXT := current_user;
BEGIN
    EXECUTE format('GRANT EXECUTE ON FUNCTION ubicacion_recepcion(BIGINT) TO %I', app_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION despachar_traslado(BIGINT,BIGINT) TO %I', app_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION recibir_traslado(BIGINT,BIGINT) TO %I', app_role);
    EXECUTE format('GRANT EXECUTE ON FUNCTION cancelar_traslado(BIGINT,BIGINT) TO %I', app_role);
END;
$$;
