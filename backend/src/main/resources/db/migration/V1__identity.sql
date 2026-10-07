-- ═══════════════════════════════════════════════════════════════════════════
-- V1: IDENTIDAD
--
-- Prerrequisito de la auditoría: `audit_log` referencia `users(id)` para
-- registrar quién hizo cada cosa. Sin esta tabla no se puede responder la
-- primera de las seis dimensiones.
--
-- Alcance de la Fase 0: lo mínimo para que la auditoría tenga integridad
-- referencial. El resto del módulo de identidad (refresh tokens, excepciones de
-- permiso, bodegas del alcance AREA) llega en la Fase 1.
-- ═══════════════════════════════════════════════════════════════════════════

-- Los enums de alcance y efecto se definen aquí porque `permissions` los usa.
-- Los específicos de auditoría viven en V2.
CREATE TYPE permission_scope AS ENUM ('NONE', 'OWN', 'AREA', 'ALL');
CREATE TYPE policy_effect AS ENUM ('ALLOW', 'DENY');

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(80) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    name          VARCHAR(160) NOT NULL,
    disabled      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE users IS
    'Usuarios del sistema. La auditoría guarda además un SNAPSHOT de username y '
    'name en cada evento: si el usuario se renombra o se borra, el histórico '
    'debe seguir siendo legible. Un FK solo no basta para auditar.';

CREATE INDEX users_active_idx ON users (id) WHERE NOT disabled;

-- ═══════════════════════════════════════════════════════════════════════════
-- ROLES Y PERMISOS
--
-- `roles.key` es la clave ESTABLE e inmutable, y reemplaza al `roleId` numérico
-- que el legacy hardcodeaba como `if (role === 1)` en cuatro puntos del backend
-- y que dependía de que el seed insertara `admin` primero (hallazgo S2).
--
-- El nombre visible es renombrable; la clave no.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE roles (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(50) NOT NULL UNIQUE,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    is_system   BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT roles_key_format CHECK (key ~ '^[A-Z][A-Z0-9_]*$'),
    CONSTRAINT roles_description_present CHECK (
        is_system = FALSE OR (description IS NOT NULL AND btrim(description) <> '')
    )
);

COMMENT ON TABLE roles IS
    'Roles con clave estable. `is_system` impide el borrado de los cinco roles '
    'base, y un rol de sistema debe tener descripción: cada rol se explica en '
    'una frase o está mal cortado.';

CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    key         VARCHAR(80) NOT NULL UNIQUE,
    module      VARCHAR(40) NOT NULL,
    resource    VARCHAR(40) NOT NULL,
    action      VARCHAR(40) NOT NULL,
    name        VARCHAR(160) NOT NULL,
    description TEXT,
    scopes      permission_scope[] NOT NULL,
    sensitive   BOOLEAN NOT NULL DEFAULT FALSE,
    active      BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order  INT NOT NULL DEFAULT 0,
    -- Formato `recurso.accion` en minúsculas: es la convención que permite
    -- agrupar por recurso y ordenar por ciclo de vida en la matriz de la UI.
    CONSTRAINT permissions_key_format CHECK (key ~ '^[a-z][a-z_]*\.[a-z_]+$'),
    CONSTRAINT permissions_scopes_not_empty CHECK (array_length(scopes, 1) > 0),
    -- El alcance NONE no se concede: no debe listarse como admitido.
    CONSTRAINT permissions_none_not_grantable CHECK (NOT ('NONE' = ANY(scopes))),
    UNIQUE (module, resource, action)
);

COMMENT ON COLUMN permissions.sensitive IS
    'Si es sensible, su uso se audita SIEMPRE con detalle completo (Nivel 3) y '
    'no solo con contador agregado. Consultar el costo del inventario o la '
    'auditoría son ejemplos.';

-- Matriz rol → permiso → alcance. Una fila por celda concedida: lo ausente es
-- NONE, de modo que un permiso nuevo en el catálogo NO se concede por olvido.
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    scope         permission_scope NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT role_permissions_scope_granted CHECK (scope <> 'NONE')
);

COMMENT ON TABLE role_permissions IS
    'La matriz rol->permiso->alcance. Solo se guardan las celdas concedidas: el '
    'comportamiento por defecto es DEFAULT DENY, y la matriz lo refleja.';

-- Varios roles por persona. El legacy tenía `User.roleId` único, lo que obligaba
-- a crear roles híbridos cuando alguien cubría dos funciones.
CREATE TABLE user_roles (
    user_id    BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id    BIGINT NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    granted_by BIGINT REFERENCES users(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE UNIQUE INDEX user_roles_one_primary_idx ON user_roles (user_id)
    WHERE is_primary;

-- Bodegas del alcance AREA. **Van en la PERSONA, no en el rol**: así un solo
-- rol JEFE_BODEGA sirve para cualquier bodega y no hay que crear un rol por
-- cada una.
CREATE TABLE user_warehouse_scopes (
    user_id      BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    warehouse_id BIGINT NOT NULL,
    granted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, warehouse_id)
);

COMMENT ON TABLE user_warehouse_scopes IS
    'Bodegas que resuelven el alcance AREA de una persona. Ponerlo en la persona '
    'y no en el rol evita la explosion de roles por bodega.';
