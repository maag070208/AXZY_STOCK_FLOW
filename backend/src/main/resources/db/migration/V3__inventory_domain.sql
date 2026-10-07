-- ═══════════════════════════════════════════════════════════════════════════
-- V3: DOMINIO DE INVENTARIO — el baseline
--
-- Aquí vive el modelo que corrige el defecto estructural del legacy. La
-- diferencia fundamental, y la razón por la que el bug del doble descuento al
-- ubicar deja de ser expresable:
--
--   LEGACY:  Store.stock = UN número total  →  se deduce la ubicación a
--            posteriori compensando con restas  →  descuadra
--
--   NUEVO:   stock_by_location (la verdad)  →  product_stock = SUMA
--            →  no puede descuadrar
--
-- **Ninguna operación resta del total.** El total solo cambia por una recepción
-- o un ajuste, y el ajuste exige motivo. Todo lo demás es un traslado entre
-- ubicaciones o hacia el tránsito.
--
-- Referencia: PLAN_ALMACEN_INTEGRAL.md §2.4, §2.6 y §2.7.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Enums del dominio ──────────────────────────────────────────────────────

CREATE TYPE movement_type AS ENUM (
    'INITIAL',              -- carga inicial del catálogo
    'RECEIPT_IN',           -- entrada por recepción de factura
    'ADJUSTMENT_IN',        -- ajuste positivo autorizado
    'FULFILLMENT_OUT',      -- salida por surtido de una orden
    'ADJUSTMENT_OUT',       -- merma: exige motivo
    'LOCATE',               -- ubicar: traslado recepción -> estante
    'MOVE_WITHIN_WAREHOUSE',-- traslado entre estantes
    'TRANSFER_OUT',         -- salida a otra bodega: entra en tránsito
    'TRANSFER_IN',          -- llegada desde el tránsito
    'PENDING_OUT'           -- intención de salida: NO cambia el inventario
);

CREATE TYPE location_kind AS ENUM ('SHELF', 'RECEIVING');

CREATE TYPE transfer_status AS ENUM ('DRAFT', 'DISPATCHED', 'IN_TRANSIT', 'RECEIVED', 'CANCELLED');

CREATE TYPE adjustment_reason AS ENUM (
    'SHORTAGE', 'DAMAGE', 'THEFT', 'SUPPLIER_ERROR',
    'COUNT_ERROR', 'RETURN_TO_SUPPLIER', 'EXPIRED', 'OTHER'
);

CREATE TYPE adjustment_origin AS ENUM (
    'INVOICE_DISCREPANCY', 'FULFILLMENT_SHORTAGE', 'MANUAL_COUNT', 'DAMAGE_REPORT'
);

CREATE TYPE invoice_type AS ENUM ('NATIONAL', 'INTERNATIONAL');

CREATE TYPE invoice_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'PENDING_TO_LOCATE', 'COMPLETED', 'CANCELLED'
);

CREATE TYPE invoice_line_status AS ENUM (
    'PENDING', 'CAPTURED', 'DISCREPANCY', 'PENDING_APPROVAL',
    'APPROVED', 'ASSIGNED', 'LOCATED', 'CANCELLED'
);

CREATE TYPE order_status AS ENUM (
    'PENDING', 'IN_PROGRESS', 'PACKAGED', 'PENDING_TO_OUT', 'COMPLETED', 'CANCELLED'
);

CREATE TYPE order_line_status AS ENUM (
    'PENDING', 'ASSIGNED', 'SUPPLIED', 'VERIFIED', 'CANCELLED'
);

CREATE TYPE print_status AS ENUM ('PENDING', 'SENT', 'PRINTED', 'FAILED');

-- ═══════════════════════════════════════════════════════════════════════════
-- ALMACÉN FÍSICO — multi-bodega con una sola por defecto
--
-- El esquema está preparado para N bodegas desde el inicio. Operar con una es
-- el caso trivial, y añadir la segunda NO requiere migración.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE warehouses (
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(20) NOT NULL UNIQUE,
    name       VARCHAR(120) NOT NULL,
    address    TEXT,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE warehouses IS
    'Bodegas. Se siembra UNA con is_default = true: operar con una sola bodega no '
    'cambia nada, y añadir la segunda no requiere migración.';

CREATE UNIQUE INDEX warehouses_one_default_idx ON warehouses (is_default)
    WHERE is_default;

-- La ubicación de RECEPCIÓN es la antigua pseudo-ubicación 'ADMIN', que el
-- legacy devolvía hardcodeada con un id ficticio (hallazgo R-69). Es la
-- ubicación temporal de la que hablabas: ahí vive lo recibido y no ubicado.
CREATE TABLE locations (
    id           BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL REFERENCES warehouses(id),
    hall         VARCHAR(20) NOT NULL,
    code         VARCHAR(20) NOT NULL,
    position     VARCHAR(20) NOT NULL,
    levels       INT NOT NULL DEFAULT 1,
    kind         location_kind NOT NULL DEFAULT 'SHELF',
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT locations_levels_positive CHECK (levels >= 1),
    CONSTRAINT locations_code_canonical CHECK (
        hall = upper(hall) AND code = upper(code) AND position = upper(position)
    ),
    UNIQUE (warehouse_id, hall, code, position)
);

COMMENT ON COLUMN locations.kind IS
    'SHELF = estante normal. RECEIVING = zona de recepción: ahí vive lo recibido '
    'y no ubicado. Es la antigua ubicación ADMIN, ahora real con id real.';

CREATE INDEX locations_kind_idx ON locations (warehouse_id, kind) WHERE active;

-- ═══════════════════════════════════════════════════════════════════════════
-- CATÁLOGO
--
-- `product_code` es la clave de identidad de negocio Y el código de barras. No
-- hay campo separado porque es lo que está impreso en las etiquetas y en los
-- estantes; añadir un `barcode` distinto obligaría a reetiquetar el almacén.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE units_of_measure (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(8) NOT NULL UNIQUE,
    name            VARCHAR(40) NOT NULL,
    allows_fraction BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON TABLE units_of_measure IS
    'Catálogo cerrado de unidades. Evita el clásico KG/Kg/KGS como tres unidades '
    'distintas, que es lo que pasa con un campo de texto libre.';

CREATE TABLE products (
    id              BIGSERIAL PRIMARY KEY,
    product_code    VARCHAR(20) NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    description     TEXT,
    unit_measure_id BIGINT NOT NULL REFERENCES units_of_measure(id),
    -- El identificador del ERP, conservado para referencia cruzada. Es NULL
    -- cuando el producto se crea directamente en la API nueva.
    external_id     BIGINT,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- El alfabeto real verificado contra el Excel: 0-9 y A-Z, sin Ñ ni O ni W.
    -- Un código en minúsculas indica que algo cambió en el origen y hay que
    -- mirarlo, no normalizarlo en silencio.
    CONSTRAINT products_code_format CHECK (product_code ~ '^[0-9A-Z]+$')
);

COMMENT ON COLUMN products.description IS
    'Opcional a propósito. En el archivo real el nombre y la descripción eran '
    'idénticos en las 1.398 filas: era una columna duplicada mantenida a mano.';

CREATE UNIQUE INDEX products_external_id_idx ON products (external_id)
    WHERE external_id IS NOT NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- INVENTARIO — el ledger y sus proyecciones
-- ═══════════════════════════════════════════════════════════════════════════

-- La proyección por ubicación. ES LA FUENTE DE VERDAD.
CREATE TABLE stock_by_location (
    id           BIGSERIAL PRIMARY KEY,
    product_id   BIGINT NOT NULL REFERENCES products(id),
    location_id  BIGINT NOT NULL REFERENCES locations(id),
    -- Desnormalizado a propósito: los reportes por bodega se resuelven sin un
    -- solo join, y son la mayoría de las consultas de inventario.
    warehouse_id BIGINT NOT NULL REFERENCES warehouses(id),
    quantity     NUMERIC(14,3) NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (product_id, location_id),
    -- La base de datos impide el stock negativo. No depende de que el código
    -- se acuerde de validarlo (invariante I2).
    CONSTRAINT stock_non_negative CHECK (quantity >= 0)
);

COMMENT ON CONSTRAINT stock_non_negative ON stock_by_location IS
    'Invariante I2: una ubicación física no puede tener stock negativo. Se '
    'impone en la base de datos, no solo en el código.';

-- El total por producto es una SUMA DERIVADA, no un número que haya que
-- mantener sincronizado. Es la inversión del modelo del legacy.
CREATE TABLE product_stock (
    product_id BIGINT PRIMARY KEY REFERENCES products(id),
    quantity   NUMERIC(14,3) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT product_stock_non_negative CHECK (quantity >= 0)
);

COMMENT ON TABLE product_stock IS
    'Total por producto, PROYECCIÓN de stock_by_location + stock_in_transit. '
    'Es el antiguo Store.stock, pero derivado: en el legacy era un número que se '
    'intentaba mantener a mano y por eso se descuadraba.';

-- Mercancía que salió de una bodega y todavía no entró en la otra.
CREATE TABLE stock_in_transit (
    id                BIGSERIAL PRIMARY KEY,
    product_id        BIGINT NOT NULL REFERENCES products(id),
    from_warehouse_id BIGINT NOT NULL REFERENCES warehouses(id),
    to_warehouse_id   BIGINT NOT NULL REFERENCES warehouses(id),
    from_location_id  BIGINT REFERENCES locations(id),
    quantity          NUMERIC(14,3) NOT NULL,
    dispatched_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    received_at       TIMESTAMPTZ,
    CONSTRAINT transit_positive CHECK (quantity > 0),
    CONSTRAINT transit_different_warehouses CHECK (from_warehouse_id <> to_warehouse_id)
);

COMMENT ON TABLE stock_in_transit IS
    'Mercancía en tránsito entre bodegas. SIGUE contando en el inventario total: '
    'si se modelara como dos movimientos instantáneos, el total bajaría durante '
    'el viaje y los reportes mentirían.';

CREATE TABLE transfers (
    id                BIGSERIAL PRIMARY KEY,
    code              VARCHAR(40) NOT NULL UNIQUE,
    from_warehouse_id BIGINT NOT NULL REFERENCES warehouses(id),
    to_warehouse_id   BIGINT NOT NULL REFERENCES warehouses(id),
    status            transfer_status NOT NULL DEFAULT 'DRAFT',
    note              TEXT,
    dispatched_by     BIGINT REFERENCES users(id),
    dispatched_at     TIMESTAMPTZ,
    received_by       BIGINT REFERENCES users(id),
    received_at       TIMESTAMPTZ,
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transfers_different_warehouses CHECK (from_warehouse_id <> to_warehouse_id)
);

-- ═══════════════════════════════════════════════════════════════════════════
-- EL LEDGER — append-only
--
-- Es el libro de inventario. **Ninguna operación lo modifica**: las
-- correcciones son movimientos nuevos. El legacy hacía `kardex.update` en
-- `removeUnitsFromKardex`, y con ello destruía la trazabilidad (hallazgo R-02).
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE kardex (
    id                 BIGSERIAL PRIMARY KEY,
    product_id         BIGINT NOT NULL REFERENCES products(id),
    warehouse_id       BIGINT REFERENCES warehouses(id),
    move_type          movement_type NOT NULL,
    units              NUMERIC(14,3) NOT NULL,
    -- Cantidad total del producto DESPUÉS de este movimiento. Hace el ledger
    -- auto-auditable: se puede reconstruir el saldo sin recalcular todo.
    quantity_after     NUMERIC(14,3) NOT NULL,
    container          VARCHAR(40),
    order_number       VARCHAR(40),
    location_id        BIGINT REFERENCES locations(id),
    from_location_id   BIGINT REFERENCES locations(id),
    to_location_id     BIGINT REFERENCES locations(id),
    transfer_id        BIGINT REFERENCES transfers(id),
    -- Motivo obligatorio en los ajustes: un ajuste sin motivo es un agujero de
    -- auditoría (invariante I6, decisión B2).
    adjustment_reason  adjustment_reason,
    adjustment_origin  adjustment_origin,
    approved_by        BIGINT REFERENCES users(id),
    approved_at        TIMESTAMPTZ,
    -- FK al usuario, no un nombre libre como en el legacy (hallazgo R-23).
    user_id            BIGINT REFERENCES users(id),
    -- Idempotencia del móvil offline: repetir la clave no duplica el movimiento.
    client_uuid        UUID,
    moved_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    note               TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT kardex_units_positive CHECK (units > 0),
    -- Todo ajuste exige motivo, sin excepción.
    CONSTRAINT kardex_adjustment_requires_reason CHECK (
        move_type NOT IN ('ADJUSTMENT_IN', 'ADJUSTMENT_OUT')
        OR adjustment_reason IS NOT NULL
    ),
    -- Ubicar requiere origen y destino, y que sean distintos: si no, no es un
    -- traslado. En el legacy esto no se validaba.
    CONSTRAINT kardex_locate_requires_locations CHECK (
        move_type <> 'LOCATE'
        OR (from_location_id IS NOT NULL AND to_location_id IS NOT NULL
            AND from_location_id <> to_location_id)
    ),
    CONSTRAINT kardex_transfer_out_requires_warehouse CHECK (
        move_type <> 'TRANSFER_OUT' OR to_location_id IS NULL
    )
);

COMMENT ON TABLE kardex IS
    'Ledger de inventario, APPEND-ONLY. Ninguna operación lo modifica: las '
    'correcciones son movimientos nuevos. En el legacy, removeUnitsFromKardex '
    'hacía kardex.update y destruía la trazabilidad.';

COMMENT ON COLUMN kardex.location_id IS
    'Obsoleto para movimientos nuevos: se usan from_location_id/to_location_id. '
    'Se conserva para poder migrar el histórico del legacy sin reinterpretarlo.';

CREATE INDEX kardex_product_idx ON kardex (product_id, moved_at DESC);
CREATE INDEX kardex_location_idx ON kardex (location_id, moved_at DESC);
CREATE INDEX kardex_container_idx ON kardex (container) WHERE container IS NOT NULL;
CREATE INDEX kardex_order_idx ON kardex (order_number) WHERE order_number <> '';
CREATE INDEX kardex_transfer_idx ON kardex (transfer_id) WHERE transfer_id IS NOT NULL;
CREATE UNIQUE INDEX kardex_client_uuid_idx ON kardex (client_uuid)
    WHERE client_uuid IS NOT NULL;

-- Inmutabilidad del ledger, aplicada con el mismo criterio que la auditoría:
-- permisos revocados más un trigger que lo refuerza.
CREATE OR REPLACE FUNCTION kardex_block_mutations()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'kardex es append-only: % no está permitido. Un ledger que se puede '
        'editar no es un ledger. Para corregir un movimiento se registra uno '
        'nuevo que lo compensa.', TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER kardex_no_update
    BEFORE UPDATE ON kardex
    FOR EACH ROW EXECUTE FUNCTION kardex_block_mutations();

CREATE TRIGGER kardex_no_delete
    BEFORE DELETE ON kardex
    FOR EACH ROW EXECUTE FUNCTION kardex_block_mutations();

-- ═══════════════════════════════════════════════════════════════════════════
-- RECEPCIÓN
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE invoices (
    id             BIGSERIAL PRIMARY KEY,
    invoice_number VARCHAR(60) NOT NULL UNIQUE,
    receipt_date   DATE NOT NULL,
    invoice_type   invoice_type NOT NULL DEFAULT 'NATIONAL',
    status         invoice_status NOT NULL DEFAULT 'PENDING',
    customer_code  VARCHAR(40),
    concept        VARCHAR(80),
    sent           BOOLEAN NOT NULL DEFAULT FALSE,
    reviewed_by    BIGINT REFERENCES users(id),
    reviewed_at    TIMESTAMPTZ,
    -- Borrado LÓGICO de verdad, con quién y cuándo. El esquema del legacy
    -- declaraba `deleted` pero el código hacía DELETE físico y nunca escribía el
    -- flag (hallazgo B-1 / R-57).
    deleted        BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by     BIGINT REFERENCES users(id),
    deleted_at     TIMESTAMPTZ,
    deleted_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT invoices_deleted_coherent CHECK (
        (deleted = FALSE AND deleted_at IS NULL)
        OR (deleted = TRUE AND deleted_at IS NOT NULL
            AND deleted_reason IS NOT NULL AND btrim(deleted_reason) <> '')
    )
);

COMMENT ON CONSTRAINT invoices_deleted_coherent ON invoices IS
    'Un documento no se borra sin dejar rastro: si está borrado, consta cuándo y '
    'por qué. El legacy ponía el flag pero nunca lo escribía, y borraba en físico.';

CREATE INDEX invoices_status_idx ON invoices (status, receipt_date DESC)
    WHERE NOT deleted;

CREATE TABLE invoice_lines (
    id                    BIGSERIAL PRIMARY KEY,
    invoice_id            BIGINT NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    product_id            BIGINT NOT NULL REFERENCES products(id),
    container             VARCHAR(40),
    unit_measure_id       BIGINT NOT NULL REFERENCES units_of_measure(id),
    quantity              NUMERIC(14,3) NOT NULL,
    box_quantity          NUMERIC(14,3) NOT NULL DEFAULT 0,
    units_per_box         NUMERIC(14,3),
    physical_quantity     NUMERIC(14,3),
    physical_box_quantity NUMERIC(14,3),
    located_quantity      NUMERIC(14,3) NOT NULL DEFAULT 0,
    -- COLUMNA GENERADA: el legacy declaraba `difference` en modelo, DTO, mapper
    -- y respuesta, y siempre escribía 0 (hallazgo R-58 / B-2). Aquí es imposible
    -- desincronizarla porque no se almacena, se calcula.
    difference            NUMERIC(14,3)
        GENERATED ALWAYS AS (physical_quantity - quantity) STORED,
    status                invoice_line_status NOT NULL DEFAULT 'PENDING',
    assigned_user_id      BIGINT REFERENCES users(id),
    location_id           BIGINT REFERENCES locations(id),
    -- Quién capturó: lo necesita la segregación de funciones (decisión R-74).
    captured_by           BIGINT REFERENCES users(id),
    captured_at           TIMESTAMPTZ,
    approved_by           BIGINT REFERENCES users(id),
    approved_at           TIMESTAMPTZ,
    adjustment_reason     adjustment_reason,
    deleted               BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT invoice_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT invoice_lines_units_per_box_positive CHECK (
        units_per_box IS NULL OR units_per_box > 0
    ),
    CONSTRAINT invoice_lines_located_not_exceeds CHECK (
        located_quantity >= 0
            AND (physical_quantity IS NULL OR located_quantity <= physical_quantity)
    ),
    -- Regla B1: una línea aprobada TIENE aprobador. Hace imposible cerrar una
    -- discrepancia sin que alguien la haya firmado.
    CONSTRAINT invoice_lines_approved_has_approver CHECK (
        status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL)
    ),
    -- Regla B2: una discrepancia resuelta tiene motivo.
    CONSTRAINT invoice_lines_approved_has_reason CHECK (
        status <> 'APPROVED' OR adjustment_reason IS NOT NULL
    )
);

COMMENT ON CONSTRAINT invoice_lines_approved_has_approver ON invoice_lines IS
    'Regla B1 y decisión R-74: ninguna línea llega a APPROVED sin aprobador. Es '
    'el control interno que impide que alguien firme su propio error de conteo.';

COMMENT ON COLUMN invoice_lines.difference IS
    'Columna generada: physical_quantity - quantity. El legacy la declaraba en '
    'cuatro sitios y siempre escribía 0.';

-- ═══════════════════════════════════════════════════════════════════════════
-- SURTIDO
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE orders (
    id              BIGSERIAL PRIMARY KEY,
    order_number    VARCHAR(40) NOT NULL UNIQUE,
    packaging_date  DATE,
    status          order_status NOT NULL DEFAULT 'PENDING',
    customer_code   VARCHAR(40),
    concept         VARCHAR(80),
    -- Conversión y envío al ERP. El flujo de conversión NUNCA existió en el
    -- legacy: estas columnas estaban y no se escribían ni se leían (hallazgo
    -- R-82). Aquí tienen estado y se exportan al confirmar el empaque.
    converted       BOOLEAN NOT NULL DEFAULT FALSE,
    converted_at    TIMESTAMPTZ,
    export_status   VARCHAR(20),
    export_attempts INT NOT NULL DEFAULT 0,
    export_last_error TEXT,
    sent            BOOLEAN NOT NULL DEFAULT FALSE,
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by      BIGINT REFERENCES users(id),
    deleted_at      TIMESTAMPTZ,
    deleted_reason  TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT orders_deleted_coherent CHECK (
        (deleted = FALSE AND deleted_at IS NULL)
        OR (deleted = TRUE AND deleted_at IS NOT NULL
            AND deleted_reason IS NOT NULL AND btrim(deleted_reason) <> '')
    ),
    CONSTRAINT orders_converted_coherent CHECK (
        converted = FALSE OR converted_at IS NOT NULL
    )
);

CREATE INDEX orders_status_idx ON orders (status, created_at DESC) WHERE NOT deleted;
CREATE INDEX orders_pending_export_idx ON orders (id)
    WHERE converted = FALSE AND status = 'COMPLETED';

CREATE TABLE order_lines (
    id                 BIGSERIAL PRIMARY KEY,
    order_id           BIGINT NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id         BIGINT NOT NULL REFERENCES products(id),
    unit_measure_id    BIGINT NOT NULL REFERENCES units_of_measure(id),
    quantity           NUMERIC(14,3) NOT NULL,
    supplied_quantity  NUMERIC(14,3) NOT NULL DEFAULT 0,
    supplied           BOOLEAN NOT NULL DEFAULT FALSE,
    status             order_line_status NOT NULL DEFAULT 'PENDING',
    assigned_user_id   BIGINT REFERENCES users(id),
    location_id        BIGINT REFERENCES locations(id),
    deleted            BOOLEAN NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT order_lines_quantity_positive CHECK (quantity > 0),
    CONSTRAINT order_lines_supplied_not_exceeds CHECK (
        supplied_quantity >= 0 AND supplied_quantity <= quantity
    )
);

-- ═══════════════════════════════════════════════════════════════════════════
-- ETIQUETADO
--
-- Máquina de estados real: `PENDING` hasta que la IMPRESORA confirma. El legacy
-- marcaba `printed = true` sin esperar la respuesta, así que si la Zebra estaba
-- apagada la etiqueta se perdía para siempre (hallazgo R-83).
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE label_templates (
    id             BIGSERIAL PRIMARY KEY,
    code           VARCHAR(40) NOT NULL UNIQUE,
    name           VARCHAR(120) NOT NULL,
    description    TEXT,
    zpl            TEXT NOT NULL,
    size           VARCHAR(10),
    field_schema   JSONB,
    is_active      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE print_jobs (
    id                BIGSERIAL PRIMARY KEY,
    label_template_id BIGINT NOT NULL REFERENCES label_templates(id),
    payload           JSONB NOT NULL,
    copies            INT NOT NULL DEFAULT 1,
    status            print_status NOT NULL DEFAULT 'PENDING',
    attempts          INT NOT NULL DEFAULT 0,
    last_error        TEXT,
    printed_at        TIMESTAMPTZ,
    created_by        BIGINT REFERENCES users(id),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT print_jobs_copies_positive CHECK (copies > 0),
    -- Una etiqueta PRINTED debe tener la hora en que la impresora confirmó.
    CONSTRAINT print_jobs_printed_has_timestamp CHECK (
        status <> 'PRINTED' OR printed_at IS NOT NULL
    )
);

COMMENT ON CONSTRAINT print_jobs_printed_has_timestamp ON print_jobs IS
    'Una etiqueta no se marca impresa hasta que la impresora lo confirma. El '
    'legacy la marcaba sin esperar la respuesta y la etiqueta se perdía.';

CREATE INDEX print_jobs_pending_idx ON print_jobs (status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- ═══════════════════════════════════════════════════════════════════════════
-- SEMILLAS MÍNIMAS
--
-- Los datos que el sistema necesita para arrancar. El catálogo de permisos y
-- los roles base se siembran en la Fase 1 desde el dominio, que es la fuente de
-- verdad: duplicar aquí los 82 permisos los desincronizaría.
-- ═══════════════════════════════════════════════════════════════════════════

INSERT INTO units_of_measure (code, name, allows_fraction) VALUES
    ('PZA', 'Pieza', FALSE);
    -- PZA es la única unidad del archivo real (100% de las 1.398 filas). Las
    -- demás se añadirán cuando existan de verdad, no por anticipado.

INSERT INTO warehouses (code, name, is_default) VALUES
    ('PRINCIPAL', 'Bodega principal', TRUE);

-- La ubicación de recepción: la antigua 'ADMIN'. En el legacy era una
-- pseudoubicación con id ficticio que podía colisionar con la ubicación real 1.
INSERT INTO locations (warehouse_id, hall, code, position, levels, kind)
SELECT id, 'RECEPCION', 'RECEPCION', 'RECEPCION', 1, 'RECEIVING'
FROM warehouses WHERE is_default;

COMMENT ON TABLE label_templates IS
    'Plantillas ZPL. El legacy las tenía en una tabla `Labels` sin esquema de '
    'campos; aquí `field_schema` permite validar el payload de cada etiqueta.';
