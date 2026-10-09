-- ═══════════════════════════════════════════════════════════════════════════
-- V9: LAS LÍNEAS DEL TRASLADO, Y EL VÍNCULO DEL TRÁNSITO CON SU TRASLADO
--
-- **POR QUÉ HACE FALTA.**
--
-- La migración V3 creó `transfers` con su cabecera y su enum de estados, y
-- `stock_in_transit` con el producto, las bodegas y la cantidad — pero **sin
-- `transfer_id`**—. El resultado era un esquema que no podía responder a la
-- pregunta que el propio contrato §7 exige:
--
--     GET /v1/transfers/in-transit → «traslados despachados y no recibidos,
--                                     con su responsable y los días transcurridos»
--
-- Sin líneas no se sabe **qué productos** van en el traslado (`lineCount` y
-- `totalUnits` de la respuesta), y sin `transfer_id` en el tránsito no se sabe a
-- qué traslado pertenece cada fila: se podría calcular el total en tránsito de un
-- producto, pero no cuál de los tres traslados abiertos lo lleva.
--
-- **Un traslado nunca recibido es una deuda visible, no un dato que se pierde.**
-- Esa frase del contrato es la razón de existir de esta migración: para que la
-- deuda sea visible hay que poder atribuirla.
--
-- ── POR QUÉ `stock_in_transit` LLEVA `transfer_id` Y NO AL REVÉS ───────────
--
-- El tránsito ya existe como tabla y ya lo suma `recalcular_total_producto`
-- (V6), que es lo que sostiene la invariante del contrato: **Σ ubicaciones +
-- Σ tránsito se conserva en cada transición**. La mercancía en el camión sigue
-- contando en el inventario. Añadir aquí la referencia es aditivo: **no cambia
-- el total** y por tanto no invalida ninguna proyección existente.
--
-- ── LA RESTRICCIÓN QUE HACE ÚTIL LA COLUMNA ────────────────────────────────
--
-- `transfer_transit_not_same_warehouse` reproduce en el tránsito la misma regla
-- que ya tiene la cabecera (`transfers_different_warehouses`). Sin ella, un
-- traslado de una bodega a sí misma podría despacharse y las existencias
-- quedarían "en tránsito" hacia el mismo sitio: un descuadre permanente que
-- nadie vería en un reporte porque **el total no cambia**.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── 1. Las líneas del traslado ─────────────────────────────────────────────

CREATE TABLE transfer_lines (
    id                BIGSERIAL PRIMARY KEY,
    transfer_id       BIGINT NOT NULL REFERENCES transfers(id) ON DELETE CASCADE,
    product_id        BIGINT NOT NULL REFERENCES products(id),
    from_location_id  BIGINT REFERENCES locations(id),
    quantity          NUMERIC(14,3) NOT NULL,
    note              TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Una línea de cero no es una línea: pedir 0 unidades de un producto no
    -- significa nada y ensuciaría el `lineCount` del reporte.
    CONSTRAINT transfer_lines_quantity_positive CHECK (quantity > 0)
);

-- El listado y el detalle leen SIEMPRE por traslado, y el `ON DELETE CASCADE`
-- borra las líneas con su cabecera: el índice hace esa ruta barata.
CREATE INDEX transfer_lines_transfer_idx ON transfer_lines (transfer_id);
CREATE INDEX transfer_lines_product_idx ON transfer_lines (product_id);

COMMENT ON TABLE transfer_lines IS
    'Productos que viajan en un traslado. Sin esta tabla no se puede responder '
    'que lleva un traslado: solo se sabia que habia mercancia en transito.';

-- ── 2. El vínculo del tránsito con su traslado ─────────────────────────────

ALTER TABLE stock_in_transit
    ADD COLUMN transfer_id BIGINT REFERENCES transfers(id);

-- Índice parcial: la mayoría de las filas de tránsito acabarán recibidas, y las
-- consultas del reporte de tránsito buscan **solo las abiertas**
-- (`received_at IS NULL`). Un índice completo sería más grande y más lento.
CREATE INDEX stock_in_transit_transfer_idx
    ON stock_in_transit (transfer_id)
    WHERE transfer_id IS NOT NULL;

COMMENT ON COLUMN stock_in_transit.transfer_id IS
    'Traslado al que pertenece esta mercancia en transito. Permite atribuir la '
    'deuda de un traslado nunca recibido a su responsable.';

-- ── 3. La coherencia que la columna nueva hace comprobable ─────────────────

ALTER TABLE stock_in_transit
    ADD CONSTRAINT transit_warehouses_differ
    CHECK (from_warehouse_id <> to_warehouse_id);

-- ── 4. Permisos ────────────────────────────────────────────────────────────
-- La tabla nueva necesita sus permisos para el rol de la aplicación. Se concede
-- con el rol ACTUAL (igual que hacen V6 y V8): el despliegue y el test corren
-- con usuarios distintos y no se puede fijar un nombre a mano.

DO $$
DECLARE
    app_role TEXT := current_user;
BEGIN
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON transfer_lines TO %I', app_role);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE transfer_lines_id_seq TO %I', app_role);
END $$;
