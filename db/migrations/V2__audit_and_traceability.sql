-- ═══════════════════════════════════════════════════════════════════════════
-- V2: ESQUEMA DE AUDITORÍA Y TRAZABILIDAD
--
-- Requisito de primer orden: TODO debe ser auditable — quién, cuándo, dónde,
-- de dónde, a qué hora y con qué permiso.
--
-- El legacy NO tenía ninguna tabla de auditoría. Su único rastro era el kardex,
-- y era mutable (`removeUnitsFromKardex` hacía `kardex.update`). Este esquema
-- es la corrección, y nace bien en lugar de parchearse.
--
-- Diseño completo: PLAN_AUDITORIA.md.
-- ═══════════════════════════════════════════════════════════════════════════

-- ── Enums del dominio de auditoría ─────────────────────────────────────────

CREATE TYPE audit_channel AS ENUM (
    'WEB', 'MOBILE', 'MOBILE_OFFLINE',
    'SERVICE_ERP', 'SERVICE_PRINTER', 'SERVICE_JOB',
    'MIGRATION', 'SYSTEM'
);

CREATE TYPE audit_level AS ENUM ('CRITICAL', 'RELEVANT', 'ACCESS', 'TECHNICAL');

CREATE TYPE audit_result AS ENUM ('SUCCESS', 'FAILURE', 'DENIED', 'PARTIAL');

CREATE TYPE audit_decision AS ENUM ('ALLOWED', 'DENIED', 'NOT_APPLICABLE');

-- ═══════════════════════════════════════════════════════════════════════════
-- AUDIT_LOG — el ledger de auditoría
--
-- PARTICIONADA POR MES desde el inicio. Una tabla sin particionar se vuelve
-- inmanejable en un año, y añadir el particionado después es una migración
-- dolorosa sobre datos que no se pueden bloquear.
--
-- APPEND-ONLY: los permisos del rol de la aplicación no incluyen UPDATE ni
-- DELETE, y hay un trigger que lo refuerza incluso si alguien tiene permisos.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE SEQUENCE audit_log_id_seq AS BIGINT;

COMMENT ON SEQUENCE audit_log_id_seq IS
    'Secuencia compartida por todas las particiones de audit_log: garantiza un '
    'orden total del ledger, no uno por partición.';

CREATE TABLE audit_log (
    -- Una SECUENCIA COMPARTIDA, no IDENTITY: en una tabla particionada el
    -- `GENERATED ... AS IDENTITY` del padre no se aplica a las particiones, y
    -- una inserción por el padre fallaba con "null value in column id".
    -- Una secuencia única para todas las particiones es además lo correcto:
    -- garantiza un orden global del ledger, no uno por partición.
    --
    -- La clave primaria incluye occurred_at porque una tabla particionada exige
    -- que su PK contenga la columna de partición.
    id                  BIGINT NOT NULL DEFAULT nextval('audit_log_id_seq'),
    PRIMARY KEY (id, occurred_at),

    -- ── CUÁNDO ─────────────────────────────────────────────────────────────
    occurred_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    business_date       DATE NOT NULL,
    offline_created_at  TIMESTAMPTZ,
    synced_at           TIMESTAMPTZ,
    clock_skew_ms       BIGINT,

    -- ── QUIÉN (con snapshot, para que el histórico siga siendo legible) ────
    actor_user_id       BIGINT REFERENCES users(id),
    actor_username      TEXT,
    actor_name          TEXT,
    actor_roles         TEXT[],

    -- ── DE DÓNDE (la dimensión que el legacy no tenía en absoluto) ─────────
    source_channel      audit_channel NOT NULL,
    source_device       TEXT,
    source_ip           INET,
    source_user_agent   TEXT,
    source_service_key_id BIGINT,

    -- ── SOBRE QUÉ ──────────────────────────────────────────────────────────
    entity_type         TEXT NOT NULL,
    entity_id           TEXT,
    entity_label        TEXT,

    -- ── QUÉ ────────────────────────────────────────────────────────────────
    action              TEXT NOT NULL,
    action_level        audit_level NOT NULL,
    previous_state      JSONB,
    new_state           JSONB,
    changed_fields      TEXT[],

    -- ── POR QUÉ ────────────────────────────────────────────────────────────
    reason              TEXT,
    note                TEXT,

    -- ── CON QUÉ PERMISO (el corazón del requisito) ─────────────────────────
    permission_key      TEXT,
    decision            audit_decision,
    decision_code       TEXT,
    decision_stage      TEXT,
    access_trace        JSONB,
    matrix_version      INT,
    policies_version    INT,

    -- ── RESULTADO ──────────────────────────────────────────────────────────
    result              audit_result NOT NULL DEFAULT 'SUCCESS',
    error_code          TEXT,
    error_message       TEXT,
    duration_ms         INT,

    -- ── CORRELACIÓN ────────────────────────────────────────────────────────
    correlation_id      UUID,
    causation_id        UUID,
    idempotency_key     TEXT,
    request_id          TEXT,

    -- ── INTEGRIDAD (cadena de hashes: tamper-evident) ──────────────────────
    sequence_no         BIGSERIAL,
    prev_hash           TEXT,
    record_hash         TEXT NOT NULL,

    -- ── Invariantes del propio registro ────────────────────────────────────
    CONSTRAINT audit_critical_has_permission
        CHECK (action_level <> 'CRITICAL' OR permission_key IS NOT NULL),
    CONSTRAINT audit_critical_has_trace
        CHECK (action_level <> 'CRITICAL' OR access_trace IS NOT NULL),
    CONSTRAINT audit_adjustment_has_reason
        CHECK (
            action NOT LIKE 'STOCK_ADJUSTED%'
            OR (reason IS NOT NULL AND btrim(reason) <> '')
        ),
    CONSTRAINT audit_human_has_actor
        CHECK (
            source_channel NOT IN ('WEB', 'MOBILE', 'MOBILE_OFFLINE')
            OR actor_user_id IS NOT NULL
        )
) PARTITION BY RANGE (occurred_at);

COMMENT ON TABLE audit_log IS
    'Ledger de auditoría, append-only y particionado por mes. '
    'Responde quién/cuándo/dónde/de dónde/a qué hora/con qué permiso. '
    'La cadena de hashes lo hace tamper-evident: alterar un registro del medio '
    'rompe la verificación. Ver PLAN_AUDITORIA.md';

COMMENT ON COLUMN audit_log.access_trace IS
    'Traza completa del pipeline Identidad -> RBAC -> ABAC con cada condición '
    'evaluada. Es lo que permite responder "¿estaba autorizado ENTONCES?" y no '
    'solo "¿está autorizado AHORA?".';

COMMENT ON COLUMN audit_log.matrix_version IS
    'Versión de la matriz rol->permiso vigente al tomar la decisión. Junto con '
    'policies_version permite REPRODUCIR la decisión histórica.';

COMMENT ON COLUMN audit_log.offline_created_at IS
    'Cuándo lo capturó el móvil sin señal. Junto con occurred_at distingue '
    'el hecho de cuándo se supo: son dos hechos distintos y ambos importan.';

-- ── Índices: sin ellos la auditoría es inútil porque no se puede consultar ──

CREATE INDEX audit_log_occurred_at_idx ON audit_log (occurred_at DESC);
CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id, occurred_at DESC);
CREATE INDEX audit_log_actor_idx ON audit_log (actor_user_id, occurred_at DESC);
CREATE INDEX audit_log_action_idx ON audit_log (action, occurred_at DESC);
CREATE INDEX audit_log_correlation_idx ON audit_log (correlation_id)
    WHERE correlation_id IS NOT NULL;
CREATE INDEX audit_log_denied_idx ON audit_log (decision, occurred_at DESC)
    WHERE decision = 'DENIED';
CREATE INDEX audit_log_changed_fields_idx ON audit_log USING GIN (changed_fields);

-- ═══════════════════════════════════════════════════════════════════════════
-- INMUTABILIDAD
--
-- Tres capas: permisos, trigger y cadena de hashes. Ninguna basta sola.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION audit_log_block_mutations()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION
        'audit_log es append-only: % no está permitido. '
        'La auditoría que se puede editar no es auditoría. '
        'Para corregir un registro se añade uno nuevo que lo compensa.',
        TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

-- IMPORTANTE: en PostgreSQL, un trigger declarado en una tabla PARTICIONADA no
-- se aplica a sus particiones. Hay que declararlo en cada una. Crear el trigger
-- solo en el padre daba una falsa sensación de protección: el UPDATE sobre una
-- partición concreta **pasaba**.
--
-- Por eso `audit_log_ensure_partition` crea los dos triggers al crear cada
-- partición, y la partición por defecto los recibe aquí.
CREATE OR REPLACE FUNCTION audit_log_attach_immutability(target_table TEXT)
RETURNS TEXT AS $$
BEGIN
    EXECUTE format(
        'CREATE TRIGGER %I BEFORE UPDATE ON %I FOR EACH ROW '
        'EXECUTE FUNCTION audit_log_block_mutations()',
        'no_update_' || target_table, target_table
    );
    EXECUTE format(
        'CREATE TRIGGER %I BEFORE DELETE ON %I FOR EACH ROW '
        'EXECUTE FUNCTION audit_log_block_mutations()',
        'no_delete_' || target_table, target_table
    );
    RETURN format('inmutabilidad aplicada a %s', target_table);
END;
$$ LANGUAGE plpgsql;

-- ═══════════════════════════════════════════════════════════════════════════
-- GESTIÓN DE PARTICIONES
--
-- Se crean por adelantado. Un mes sin partición hace fallar la inserción, así
-- que se planifican con antelación desde un job.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION audit_log_ensure_partition(target_month DATE)
RETURNS TEXT AS $$
DECLARE
    inicio DATE := date_trunc('month', target_month)::DATE;
    fin    DATE := (inicio + INTERVAL '1 month')::DATE;
    nombre TEXT := format('audit_log_%s', to_char(inicio, 'YYYY_MM'));
BEGIN
    IF to_regclass(nombre) IS NULL THEN
        EXECUTE format(
            'CREATE TABLE %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            nombre, inicio, fin
        );
        -- El rol de la aplicación solo puede INSERTAR y CONSULTAR.
        EXECUTE format(
            'REVOKE UPDATE, DELETE, TRUNCATE ON %I FROM PUBLIC', nombre
        );
        -- Los triggers se aplican POR PARTICIÓN: los del padre no heredan.
        PERFORM audit_log_attach_immutability(nombre);
        RETURN format('partición %s creada (%s a %s)', nombre, inicio, fin);
    END IF;
    RETURN format('partición %s ya existía', nombre);
END;
$$ LANGUAGE plpgsql;

-- Partición por defecto: evita que una inserción falle por falta de partición.
-- Un fallo de inserción en auditoría sería peor que un registro en la partición
-- por defecto, que la verificación periódica puede detectar y reubicar.
CREATE TABLE audit_log_default PARTITION OF audit_log DEFAULT;
SELECT audit_log_attach_immutability('audit_log_default');

COMMENT ON TABLE audit_log_default IS
    'Partición por defecto. Si recibe filas, significa que faltó crear una '
    'partición mensual: el job de verificación lo reporta.';

-- Mes actual y los tres siguientes, para no depender del job desde el día uno.
SELECT audit_log_ensure_partition(CURRENT_DATE);
SELECT audit_log_ensure_partition((CURRENT_DATE + INTERVAL '1 month')::DATE);
SELECT audit_log_ensure_partition((CURRENT_DATE + INTERVAL '2 month')::DATE);
SELECT audit_log_ensure_partition((CURRENT_DATE + INTERVAL '3 month')::DATE);

-- ═══════════════════════════════════════════════════════════════════════════
-- ACCESS_COUNTER — accesos agregados (Nivel 3)
--
-- Auditar cada lectura con trace completo es inviable: un listado paginado
-- genera decenas de registros y el volumen de escritura supera al de lectura
-- real. Un contador agregado responde la pregunta que de verdad se hace en una
-- auditoría: "¿qué consultó, cuánto, y hubo denegaciones?".
--
-- Las lecturas de datos SENSIBLES y las DENEGADAS sí van a audit_log completo.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE access_counter (
    id              BIGSERIAL PRIMARY KEY,
    bucket_hour     TIMESTAMPTZ NOT NULL,
    actor_user_id   BIGINT NOT NULL REFERENCES users(id),
    permission_key  TEXT NOT NULL,
    entity_type     TEXT,
    scope           permission_scope,
    count           INT NOT NULL DEFAULT 0,
    denied_count    INT NOT NULL DEFAULT 0,
    first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (bucket_hour, actor_user_id, permission_key, entity_type)
);

COMMENT ON TABLE access_counter IS
    'Accesos agregados por hora. Responde "¿quién consultó qué y cuánto" sin '
    'el coste de un registro por lectura. Las denegaciones y los datos '
    'sensibles van a audit_log con detalle completo.';

CREATE INDEX access_counter_actor_idx ON access_counter (actor_user_id, bucket_hour DESC);
CREATE INDEX access_counter_denied_idx ON access_counter (bucket_hour DESC)
    WHERE denied_count > 0;

-- ═══════════════════════════════════════════════════════════════════════════
-- CONFIG_VERSIONS — versionado de la configuración de acceso
--
-- Es lo que hace POSIBLE la reproducibilidad: cada cambio de matriz o de
-- políticas incrementa la versión y guarda el estado completo. El coste de
-- almacenamiento es trivial a cambio de poder reproducir cualquier decisión.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE config_versions (
    id          BIGSERIAL PRIMARY KEY,
    kind        TEXT NOT NULL CHECK (kind IN ('permission_matrix', 'policies')),
    version     INT NOT NULL,
    snapshot    JSONB NOT NULL,
    created_by  BIGINT REFERENCES users(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    reason      TEXT,
    UNIQUE (kind, version)
);

COMMENT ON TABLE config_versions IS
    'Snapshot completo de la matriz de permisos o del conjunto de políticas en '
    'cada versión. Permite REPRODUCIR una decisión histórica con las reglas '
    'vigentes entonces, y detectar que una decisión pasada no se tomaría hoy.';

-- ═══════════════════════════════════════════════════════════════════════════
-- SERVICE_API_KEYS — credenciales de las integraciones intencionales
--
-- `PATCH /prints` (la impresora Zebra) y `GET /kardo` (el ERP) son públicos en
-- el legacy de forma intencional. Aquí siguen sin exigir JWT de usuario, pero
-- SÍ exigen una credencial de servicio: mismo consumidor, sin endpoint abierto.
--
-- El secreto se guarda HASHEADO y se devuelve UNA sola vez al crearlo.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE service_api_keys (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(80) NOT NULL,
    key_hash     TEXT NOT NULL,
    scopes       TEXT[] NOT NULL,
    active       BOOLEAN NOT NULL DEFAULT TRUE,
    last_used_at TIMESTAMPTZ,
    expires_at   TIMESTAMPTZ,
    created_by   BIGINT REFERENCES users(id),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at   TIMESTAMPTZ
);

COMMENT ON TABLE service_api_keys IS
    'Credenciales de las integraciones (ERP y Zebra). El secreto en claro solo '
    'se devuelve al crearlo; aquí vive su hash. Su uso queda auditado con '
    'source_channel = SERVICE_* y source_service_key_id.';

CREATE INDEX service_api_keys_active_idx ON service_api_keys (id) WHERE active;

-- ═══════════════════════════════════════════════════════════════════════════
-- PERMISOS DEL ROL DE APLICACIÓN
--
-- La auditoría y el kardex se protegen a nivel de BASE DE DATOS, no de código:
-- una regla que solo vive en el código se salta con un script de mantenimiento.
-- ═══════════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    app_role TEXT := current_user;
BEGIN
    -- audit_log: solo INSERT y SELECT. El trigger refuerza el UPDATE/DELETE
    -- aunque alguien tuviera los permisos.
    EXECUTE format('GRANT SELECT, INSERT ON audit_log TO %I', app_role);
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE ON audit_log FROM %I', app_role);

    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON access_counter TO %I', app_role);
    EXECUTE format('GRANT SELECT, INSERT ON config_versions TO %I', app_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON service_api_keys TO %I', app_role);
END $$;
