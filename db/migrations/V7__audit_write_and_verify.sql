-- ═══════════════════════════════════════════════════════════════════════════
-- V7: LA ESCRITURA DE AUDITORÍA QUE NUNCA EXISTIÓ
--
-- **POR QUÉ HACE FALTA ESTA MIGRACIÓN, y es el hallazgo más grave del proyecto.**
--
-- `audit_log` tiene el esquema completo: 44 columnas con las 6 dimensiones, cinco
-- particiones mensuales, disparadores de inmutabilidad y una cadena de hash
-- (`prev_hash` + `record_hash`). Y tiene **CERO FILAS**.
--
-- `grep -r "INSERT INTO audit_log" backend/src/main` no encontraba **nadie que
-- escribiera**. La auditoría llevaba toda la vida del proyecto siendo una fachada:
-- completa por fuera y vacía por dentro, mientras el requisito central del usuario
-- —«TODO DEBE DE SER AUDITABLE: quién, cuándo, dónde, de dónde, a qué hora, tenía
-- permiso»— no se cumplía en absoluto.
--
-- **POR QUÉ LA INSERCIÓN VA EN SQL Y NO EN KOTLIN.** Es la misma razón por la que el
-- motor de inventario está en SQL (ver V6): la cadena de hash es una estructura
-- **compartida y secuencial**. Si dos procesos insertaran a la vez calculando el hash
-- en Kotlin, los dos leerían el MISMO `prev_hash` y **la cadena se bifurcaría**: los
-- registros existirían, la auditoría parecería completa, y la verificación de
-- integridad fallaría sin decir por qué. Es la clase de fallo que se descubre un año
-- después, cuando alguien intenta demostrar que nadie manipuló el registro.
--
-- El `pg_advisory_xact_lock` serializa la inserción dentro de la transacción del
-- llamador. Es un cuello de botella deliberado: la auditoría es un libro contable, y
-- un libro contable no se escribe en paralelo.
--
-- **NO HACE FALTA `pgcrypto`.** `sha256()` es nativa en PostgreSQL 11+, así que la
-- cadena no añade una dependencia. Se comprobó antes de escribir esto:
--   SELECT encode(sha256('hola'::bytea), 'hex');   -- funciona
-- ═══════════════════════════════════════════════════════════════════════════

-- ── La función de escritura ────────────────────────────────────────────────
--
-- Los parámetros siguen el orden de las 6 dimensiones para que la llamada se lea
-- como la frase que el usuario pidió: QUIÉN hizo QUÉ sobre QUÉ, CUÁNDO, desde
-- DÓNDE, y con QUÉ PERMISO.
CREATE OR REPLACE FUNCTION audit_log_record(
    -- QUIÉN
    p_actor_user_id      BIGINT,
    p_actor_username     TEXT,
    p_actor_name         TEXT,
    p_actor_roles        TEXT[],

    -- DESDE DÓNDE
    p_source_channel     audit_channel,
    p_source_ip          INET,
    p_source_user_agent  TEXT,
    p_source_device      TEXT,

    -- SOBRE QUÉ
    p_entity_type        TEXT,
    p_entity_id          TEXT,
    p_entity_label       TEXT,

    -- QUÉ
    p_action             TEXT,
    p_action_level       audit_level,
    p_previous_state     JSONB,
    p_new_state          JSONB,
    p_reason             TEXT,
    p_note               TEXT,

    -- CON QUÉ PERMISO
    p_permission_key     TEXT,
    p_decision           audit_decision,
    p_decision_code      TEXT,

    -- RESULTADO Y TRAZABILIDAD
    p_result             audit_result,
    p_error_code         TEXT,
    p_error_message      TEXT,
    p_duration_ms        INTEGER,
    p_correlation_id     UUID,
    p_causation_id       UUID,
    p_idempotency_key    TEXT,
    p_request_id         TEXT,

    -- Fecha de NEGOCIO. Opcional: si no se envía se usa la del reloj en UTC. Un
    -- turno de noche que cruza medianoche se audita contra el día en que empezó,
    -- no contra el día natural, y por eso es un parámetro y no un DEFAULT de columna.
    p_business_date      DATE DEFAULT NULL
) RETURNS BIGINT AS $$
DECLARE
    v_prev_hash   TEXT;
    v_record_hash TEXT;
    v_id          BIGINT;
    v_campos      TEXT[];
BEGIN
    -- ── Serializar la escritura ────────────────────────────────────────────
    -- Sin esto, dos transacciones simultáneas leerían el mismo `prev_hash` y la
    -- cadena se bifurcaría. El cerrojo se libera solo al terminar la transacción
    -- (`xact`), así que no hay forma de olvidarse de soltarlo.
    PERFORM pg_advisory_xact_lock(hashtext('audit_log_chain'));

    -- ── El eslabón anterior ────────────────────────────────────────────────
    -- `GENESIS` cuando es el primer registro. Se usa una constante y no NULL para
    -- que el hash del primer registro también dependa de algo: si se usara NULL,
    -- dos cadenas distintas podrían empezar igual.
    SELECT record_hash INTO v_prev_hash
    FROM audit_log
    ORDER BY sequence_no DESC
    LIMIT 1;

    v_prev_hash := COALESCE(v_prev_hash, 'GENESIS');

    -- ── Qué campos cambiaron ───────────────────────────────────────────────
    -- Se calcula AQUÍ y no se pide al llamador: es información derivada de lo que
    -- ya se está enviando, y pedirla aparte garantiza que algún día no coincida.
    IF p_previous_state IS NOT NULL AND p_new_state IS NOT NULL THEN
        SELECT array_agg(nueva.clave ORDER BY nueva.clave) INTO v_campos
        FROM jsonb_each_text(p_new_state) AS nueva(clave, valor)
        WHERE p_previous_state ->> nueva.clave IS DISTINCT FROM nueva.valor;
    END IF;

    -- ── El hash del registro ───────────────────────────────────────────────
    -- Encadena el hash anterior con la IDENTIDAD del registro (quién, qué, sobre
    -- qué, cuándo, resultado). **No se incluye todo el `new_state`**: bastan los
    -- datos que identifican el hecho. Incluir el estado completo haría que el hash
    -- dependiera de campos que pueden reformatearse (un JSON con las claves en otro
    -- orden cambia el texto y por tanto el hash) y la verificación daría falsos
    -- positivos por una razón que no es una manipulación.
    v_record_hash := encode(
        sha256(
            (
                v_prev_hash || '|' ||
                COALESCE(p_actor_user_id::text, 'anon') || '|' ||
                COALESCE(p_action, '') || '|' ||
                COALESCE(p_entity_type, '') || '|' ||
                COALESCE(p_entity_id, '') || '|' ||
                COALESCE(p_result::text, '') || '|' ||
                to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
            )::bytea
        ),
        'hex'
    );

    INSERT INTO audit_log (
        -- `business_date` es NOT NULL y NO tiene DEFAULT: es la fecha de NEGOCIO, que
        -- no siempre coincide con la del reloj (un turno de noche que cruza
        -- medianoche pertenece al día anterior). La decide quien llama; si no la
        -- manda, se usa la fecha UTC actual como aproximación razonable.
        business_date,
        actor_user_id, actor_username, actor_name, actor_roles,
        source_channel, source_ip, source_user_agent, source_device,
        entity_type, entity_id, entity_label,
        action, action_level, previous_state, new_state, changed_fields,
        reason, note,
        permission_key, decision, decision_code,
        result, error_code, error_message, duration_ms,
        correlation_id, causation_id, idempotency_key, request_id,
        prev_hash, record_hash
    ) VALUES (
        COALESCE(p_business_date, (now() AT TIME ZONE 'UTC')::date),
        p_actor_user_id, p_actor_username, p_actor_name, p_actor_roles,
        p_source_channel, p_source_ip, p_source_user_agent, p_source_device,
        p_entity_type, p_entity_id, p_entity_label,
        p_action, p_action_level, p_previous_state, p_new_state, v_campos,
        p_reason, p_note,
        p_permission_key, p_decision, p_decision_code,
        p_result, p_error_code, p_error_message, p_duration_ms,
        p_correlation_id, p_causation_id, p_idempotency_key, p_request_id,
        v_prev_hash, v_record_hash
    )
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION audit_log_record IS
    'Escribe un registro de auditoría encadenando su hash con el anterior. '
    'Serializa la inserción con un advisory lock porque la cadena es secuencial: '
    'sin él, dos escrituras simultáneas leerían el mismo prev_hash y la cadena se '
    'bifurcaría, lo que haría fallar la verificación de integridad sin explicar por qué.';

-- ── La verificación de la cadena ───────────────────────────────────────────
--
-- **Sin esto, la cadena de hash no sirve para nada.** Un hash que nadie comprueba
-- es decoración: da la apariencia de integridad sin demostrarla. Esta función
-- recalcula los hashes en orden y devuelve la primera ruptura.
--
-- Se devuelve la PRIMERA y no todas: si alguien manipuló un registro, todo lo
-- posterior queda inválido en cascada, y una lista de mil filas rotas no ayuda a
-- investigar. Lo que se necesita saber es dónde empieza.
CREATE OR REPLACE FUNCTION audit_log_verify_chain(
    p_from TIMESTAMPTZ DEFAULT NULL,
    p_to   TIMESTAMPTZ DEFAULT NULL
)
RETURNS TABLE (
    sequence_no BIGINT,
    id          BIGINT,
    occurred_at TIMESTAMPTZ,
    problema    TEXT
) AS $$
DECLARE
    r          RECORD;
    v_esperado TEXT;
BEGIN
    FOR r IN
        SELECT a.sequence_no, a.id, a.occurred_at, a.prev_hash, a.record_hash,
               a.actor_user_id, a.action, a.entity_type, a.entity_id,
               a.result, a.occurred_at AS cuando
        FROM audit_log a
        WHERE (p_from IS NULL OR a.occurred_at >= p_from)
          AND (p_to   IS NULL OR a.occurred_at <= p_to)
        ORDER BY a.sequence_no
    LOOP
        v_esperado := encode(
            sha256(
                (
                    COALESCE(r.prev_hash, 'GENESIS') || '|' ||
                    COALESCE(r.actor_user_id::text, 'anon') || '|' ||
                    COALESCE(r.action, '') || '|' ||
                    COALESCE(r.entity_type, '') || '|' ||
                    COALESCE(r.entity_id, '') || '|' ||
                    COALESCE(r.result::text, '') || '|' ||
                    -- El mismo formato que al escribir. `US` son microsegundos, y
                    -- PostgreSQL los guarda con esa precisión, así que el texto
                    -- reconstruido es idéntico.
                    to_char(r.cuando AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US')
                )::bytea
            ),
            'hex'
        );

        IF v_esperado <> r.record_hash THEN
            sequence_no := r.sequence_no;
            id          := r.id;
            occurred_at := r.occurred_at;
            problema    := 'El hash no coincide: el registro fue alterado, o se '
                        || 'insertó por fuera de audit_log_record().';
            RETURN NEXT;
            RETURN;
        END IF;
    END LOOP;

    RETURN;
END;
$$ LANGUAGE plpgsql STABLE;

COMMENT ON FUNCTION audit_log_verify_chain IS
    'Recalcula la cadena de hash y devuelve la PRIMERA ruptura, o ninguna fila si '
    'está íntegra. Sin esta función el hash sería decoración: apariencia de '
    'integridad sin demostración.';

-- ── Vista de resumen: la auditoría tiene que ser LEÍBLE ────────────────────
--
-- 44 columnas en crudo no las lee nadie, y una auditoría que no se lee no se usa.
-- Esta vista arma la frase que el usuario pidió desde el primer día.
CREATE OR REPLACE VIEW audit_log_readable AS
SELECT
    a.id,
    a.occurred_at                                        AS "when",
    a.sequence_no,
    -- QUIÉN: se usa el nombre, y si no hay, el usuario; y si no, el canal.
    COALESCE(a.actor_name, a.actor_username, a.source_channel::text) AS "who",
    a.actor_roles,
    a.action                                             AS "what",
    a.action_level,
    a.entity_type || COALESCE(' #' || a.entity_id, '')    AS "on_what",
    a.entity_label,
    a.source_channel                                     AS "from_where",
    a.source_ip,
    a.permission_key                                     AS "with_permission",
    a.decision,
    a.result,
    a.reason,
    a.note,
    a.correlation_id,
    a.changed_fields,
    a.error_code
FROM audit_log a;

COMMENT ON VIEW audit_log_readable IS
    'La auditoría en la forma en que se lee: quién hizo qué sobre qué, cuándo, desde '
    'dónde y con qué permiso. Las 44 columnas en crudo no las lee nadie, y una '
    'auditoría que no se lee no se usa.';
