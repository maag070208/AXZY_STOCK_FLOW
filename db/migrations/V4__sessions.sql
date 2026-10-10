-- ═══════════════════════════════════════════════════════════════════════════
-- V4: SESIONES Y REFRESH TOKENS
--
-- El legacy no tenía nada de esto: sus sesiones eran un JWT sin revocación, así
-- que **cerrar sesión no cerraba nada**. El token seguía siendo válido hasta que
-- vencía, y no había forma de expulsar a un usuario.
--
-- Aquí el refresh token es **opaco y con estado**, lo que permite revocarlo. La
-- rotación con detección de reuso está en el dominio
-- (`decideRefresh`, 32 tests); este esquema es lo que la hace posible.
--
-- Referencia: `stock-domain/.../identity/SessionModel.kt`.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TYPE revocation_reason AS ENUM (
    'LOGOUT',
    'REUSE_DETECTED',
    'PASSWORD_CHANGED',
    'ADMIN_REVOKED',
    'EXPIRED'
);

-- ═══════════════════════════════════════════════════════════════════════════
-- REFRESH_TOKENS
--
-- **NO se guarda el token, se guarda su hash.** Si alguien obtiene una copia de
-- esta tabla —un volcado, una copia de seguridad mal protegida— no puede
-- suplantar a nadie: tendría los hashes, no los tokens.
--
-- Es la misma disciplina que con las contraseñas, y es la razón de que este
-- esquema sea distinto de los que se ven habitualmente, donde el token se guarda
-- en claro «porque es temporal». Un token de 30 días no es temporal.
--
-- Se usa SHA-256 y no Argon2 **a propósito**: el token ya tiene 256 bits de
-- entropía aleatoria, así que no es adivinable por fuerza bruta y no hace falta
-- un hash lento. Argon2 aquí solo encarecería cada refresco sin aportar
-- seguridad. (Con una contraseña es al revés: es adivinable, y por eso necesita
-- un hash lento.)
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE refresh_tokens (
    id                BIGSERIAL PRIMARY KEY,
    -- El hash del token, en hexadecimal. Es lo que se busca al refrescar.
    token_hash        CHAR(64) NOT NULL UNIQUE,
    user_id           BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,

    -- ── FAMILIA DE TOKENS ──────────────────────────────────────────────────
    -- Una familia nace en cada inicio de sesión y agrupa todas sus rotaciones.
    -- **Es la unidad de revocación**: al detectar un reuso se revoca la familia
    -- entera, que equivale a decir «esta sesión está comprometida».
    --
    -- Sin familias, detectar un reuso solo permitiría revocar ESE token, y el
    -- atacante seguiría teniendo el que robó.
    family_id         UUID NOT NULL,
    -- Posición dentro de la familia, para poder reconstruir la cadena de
    -- rotaciones al investigar un incidente.
    generation        INT NOT NULL DEFAULT 1,

    -- ── CICLO DE VIDA ──────────────────────────────────────────────────────
    issued_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at        TIMESTAMPTZ NOT NULL,

    -- Cuándo se usó para rotar. **Es el campo que hace posible la detección de
    -- reuso**: un token con `rotated_at` no nulo ya se usó, y si vuelve a
    -- aparecer, hay dos partes con el mismo token.
    rotated_at        TIMESTAMPTZ,
    -- El token que lo reemplazó. Permite reconstruir la cadena completa.
    replaced_by_hash  CHAR(64),

    revoked_at        TIMESTAMPTZ,
    revocation_reason revocation_reason,

    -- ── CONTEXTO, PARA INVESTIGAR UN INCIDENTE ─────────────────────────────
    -- Cuando se detecta un reuso hay que poder responder «¿desde dónde?». Sin
    -- estos campos, el incidente se detecta y no se puede investigar.
    issued_ip         INET,
    issued_user_agent TEXT,
    last_used_ip      INET,
    last_used_at      TIMESTAMPTZ,

    -- ── INVARIANTES ────────────────────────────────────────────────────────

    -- Un token revocado debe decir POR QUÉ. Un `revoked_at` sin motivo obliga a
    -- adivinar si fue el usuario, un administrador o una detección de robo.
    CONSTRAINT refresh_tokens_revocation_has_reason CHECK (
        (revoked_at IS NULL AND revocation_reason IS NULL)
        OR (revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
    ),

    -- Un token rotado apunta a su reemplazo. Es lo que permite seguir la cadena
    -- de rotaciones; sin el puntero, la familia es una lista suelta.
    CONSTRAINT refresh_tokens_rotated_has_replacement CHECK (
        (rotated_at IS NULL AND replaced_by_hash IS NULL)
        OR (rotated_at IS NOT NULL AND replaced_by_hash IS NOT NULL)
    ),

    -- **Un token no puede estar rotado y revocado a la vez.**
    --
    -- La rotación marca el token como rotado, no como revocado: sigue siendo un
    -- registro válido de la cadena. La revocación es una acción distinta y
    -- posterior —un cierre de sesión, un cambio de contraseña, o la revocación
    -- de la familia entera al detectar un reuso—.
    --
    -- Confundir ambas cosas es lo que hace que un sistema reporte «token
    -- revocado» cuando en realidad detectó un robo, y entonces no revoca la
    -- familia.
    CONSTRAINT refresh_tokens_rotated_xor_revoked CHECK (
        rotated_at IS NULL OR revoked_at IS NULL
    ),

    -- No puede vencer antes de emitirse.
    CONSTRAINT refresh_tokens_expires_after_issue CHECK (expires_at > issued_at),

    -- La generación empieza en 1.
    CONSTRAINT refresh_tokens_generation_positive CHECK (generation >= 1)
);

COMMENT ON TABLE refresh_tokens IS
    'Refresh tokens opacos, hasheados con SHA-256. Se guarda el hash y no el '
    'token: un volcado de esta tabla no permite suplantar a nadie. La rotación '
    'con detección de reuso se decide en el dominio (decideRefresh).';

COMMENT ON COLUMN refresh_tokens.family_id IS
    'Familia de rotaciones de un mismo inicio de sesión. Es la unidad de '
    'revocación: al detectar un reuso se revoca la familia entera.';

COMMENT ON COLUMN refresh_tokens.rotated_at IS
    'Cuando el token se uso para rotar. Es el campo que permite detectar el '
    'reuso: si un token rotado vuelve a aparecer, hay dos partes usando la misma '
    'sesion. NO se marca revoked_at a la vez: son cosas distintas.';

COMMENT ON COLUMN refresh_tokens.generation IS
    'Posicion en la cadena de rotaciones de la familia. Permite reconstruir el '
    'orden de los tokens al investigar un incidente.';

-- ── Índices ────────────────────────────────────────────────────────────────
-- El más importante es el de la familia: la revocación al detectar un reuso
-- actualiza TODOS los tokens de una familia, y sin este índice sería un recorrido
-- completo de la tabla.
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id, generation);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id, issued_at DESC);
-- Un token solo se busca por su hash, y el UNIQUE ya lo indexa.

-- Para el job de limpieza: borrar lo que ya venció hace tiempo.
CREATE INDEX refresh_tokens_expiry_idx ON refresh_tokens (expires_at)
    WHERE revoked_at IS NULL AND rotated_at IS NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- FUNCIÓN DE REVOCACIÓN DE FAMILIA
--
-- Se hace en la BASE DE DATOS y no en el código porque la revocación por reuso
-- **debe ser atómica**: si el proceso falla a mitad, quedarían tokens vivos de
-- una sesión que se acaba de declarar comprometida. Eso es peor que el propio
-- reuso.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE OR REPLACE FUNCTION revoke_token_family(
    target_family UUID,
    reason revocation_reason
)
RETURNS INT AS $$
DECLARE
    afectados INT;
BEGIN
    -- Solo se revocan los que todavía sirven: un token ya rotado o ya revocado no
    -- se toca. Así la cadena de rotaciones se conserva intacta para la
    -- investigación, y cada registro mantiene su motivo original.
    UPDATE refresh_tokens
    SET revoked_at = now(),
        revocation_reason = reason
    WHERE family_id = target_family
      AND revoked_at IS NULL
      AND rotated_at IS NULL;

    GET DIAGNOSTICS afectados = ROW_COUNT;
    RETURN afectados;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION revoke_token_family IS
    'Revoca todos los tokens VIVOS de una familia. Se usa al detectar un reuso. '
    'No toca los ya rotados ni los ya revocados, para conservar la cadena de '
    'rotaciones y el motivo original de cada registro.';

-- ═══════════════════════════════════════════════════════════════════════════
-- INTENTOS DE INICIO DE SESIÓN
--
-- Tabla separada de la auditoría porque se escribe **mucho más**: cada intento
-- fallido escribe una fila, y la auditoría es para lo que de verdad importa.
--
-- Sirve para dos cosas: **bloquear por fuerza bruta** y detectar un ataque de
-- credenciales antes de que tenga éxito.
--
-- No se guarda la contraseña intentada, obviamente. Tampoco el usuario cuando no
-- existe: guardarlo permitiría enumerar qué nombres de usuario son reales.
-- ═══════════════════════════════════════════════════════════════════════════

CREATE TABLE login_attempts (
    id            BIGSERIAL PRIMARY KEY,
    attempted_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- El nombre de usuario tal como se escribió, aunque no exista. Es lo que
    -- permite detectar un ataque de fuerza bruta contra una cuenta.
    username      VARCHAR(80) NOT NULL,
    -- `NULL` si el usuario no existe: así un atacante no puede distinguir
    -- «usuario inexistente» de «usuario sin intentos registrados».
    user_id       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    succeeded     BOOLEAN NOT NULL,
    failure_code  TEXT,
    ip            INET,
    user_agent    TEXT
);

COMMENT ON TABLE login_attempts IS
    'Intentos de inicio de sesion, para bloquear la fuerza bruta. Separada de '
    'audit_log porque se escribe mucho mas. NO se guarda la contrasena intentada.';

-- Índice para contar los fallos recientes de un usuario.
CREATE INDEX login_attempts_failures_idx ON login_attempts (username, attempted_at DESC)
    WHERE NOT succeeded;

CREATE INDEX login_attempts_ip_idx ON login_attempts (ip, attempted_at DESC)
    WHERE NOT succeeded;

-- ═══════════════════════════════════════════════════════════════════════════
-- COLUMNAS DE CONTRASEÑA EN users
--
-- Se añaden tres cosas:
--   1. `password_changed_at`, para poder invalidar sesiones por antigüedad.
--   2. `must_change_password`, para la contraseña inicial de una cuenta nueva.
--   3. `last_login_at`, que la interfaz muestra al usuario para que note un
--      acceso que no reconoce. Es una defensa barata y eficaz.
-- ═══════════════════════════════════════════════════════════════════════════

ALTER TABLE users
    ADD COLUMN password_changed_at   TIMESTAMPTZ,
    ADD COLUMN must_change_password  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN last_login_at         TIMESTAMPTZ,
    ADD COLUMN failed_login_count    INT NOT NULL DEFAULT 0,
    ADD COLUMN locked_until          TIMESTAMPTZ;

COMMENT ON COLUMN users.password_changed_at IS
    'Cuando se cambio la contrasena por ultima vez. Un cambio invalida TODAS las '
    'sesiones activas: quien la cambia suele sospechar que esta comprometida.';

COMMENT ON COLUMN users.must_change_password IS
    'Obliga a cambiar la contrasena al entrar. Se usa en cuentas nuevas y en '
    'restablecimientos por un administrador.';

COMMENT ON COLUMN users.locked_until IS
    'Bloqueo temporal por intentos fallidos. Temporal y no permanente: un bloqueo '
    'permanente convierte la fuerza bruta en una denegacion de servicio contra el '
    'usuario legitimo, que es justo lo que busca un atacante que conoce su nombre.';

ALTER TABLE users
    ADD CONSTRAINT users_failed_login_count_non_negative CHECK (failed_login_count >= 0);

-- Las cuentas existentes no tienen fecha de cambio: se rellena con su creación
-- para que la comprobación de «sesión más antigua que la contraseña» funcione.
UPDATE users SET password_changed_at = created_at WHERE password_changed_at IS NULL;

-- ═══════════════════════════════════════════════════════════════════════════
-- PERMISOS
-- ═══════════════════════════════════════════════════════════════════════════

DO $$
DECLARE
    app_role TEXT := current_user;
BEGIN
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens TO %I', app_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON login_attempts TO %I', app_role);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE refresh_tokens_id_seq TO %I', app_role);
    EXECUTE format('GRANT USAGE, SELECT ON SEQUENCE login_attempts_id_seq TO %I', app_role);
END $$;
