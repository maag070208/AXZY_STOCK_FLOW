-- V12: la bitácora acepta la traza de la decisión de acceso.
--
-- **El defecto.** `audit_log` exige que un registro `CRITICAL` lleve su traza
-- (`audit_critical_has_trace`), pero `audit_log_record` (V7) no tenía parámetro para
-- recibirla. Consecuencia: un ajuste por robo solo se podía guardar como `RELEVANT`. La
-- regla existía en la tabla y era imposible de cumplir desde la función.
--
-- **La corrección.** Se añaden cinco parámetros al FINAL, todos con DEFAULT: las llamadas
-- de 29 argumentos siguen funcionando igual. `CREATE OR REPLACE` no puede cambiar la lista
-- de parámetros (crearía una sobrecarga ambigua), así que se elimina la firma anterior y
-- se crea la nueva. El cuerpo es el de V7 sin más cambios que las cinco columnas.

DROP FUNCTION audit_log_record(
    BIGINT, TEXT, TEXT, TEXT[],
    audit_channel, INET, TEXT, TEXT,
    TEXT, TEXT, TEXT,
    TEXT, audit_level, JSONB, JSONB, TEXT, TEXT,
    TEXT, audit_decision, TEXT,
    audit_result, TEXT, TEXT, INTEGER, UUID, UUID, TEXT, TEXT,
    DATE
);

CREATE FUNCTION audit_log_record(
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
    p_business_date      DATE DEFAULT NULL,

    -- LA TRAZA DE LA DECISIÓN DE ACCESO (nuevo en V12). Sin ella un registro CRITICAL no
    -- pasa el CHECK `audit_critical_has_trace`, y por eso los ajustes se guardaban como RELEVANT.
    p_access_trace       JSONB   DEFAULT NULL,
    p_decision_stage     TEXT    DEFAULT NULL,
    p_matrix_version     INTEGER DEFAULT NULL,
    p_policies_version   INTEGER DEFAULT NULL,
    -- Quién, cuando no es una persona: la clave de servicio (impresora, ERP).
    p_source_service_key_id BIGINT DEFAULT NULL
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
        decision_stage, access_trace, matrix_version, policies_version, source_service_key_id,
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
        p_decision_stage, p_access_trace, p_matrix_version, p_policies_version, p_source_service_key_id,
        p_result, p_error_code, p_error_message, p_duration_ms,
        p_correlation_id, p_causation_id, p_idempotency_key, p_request_id,
        v_prev_hash, v_record_hash
    )
    RETURNING id INTO v_id;

    RETURN v_id;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION audit_log_record IS
    'Escribe un registro de auditoria encadenando su hash con el anterior, con la traza '
    'de la decision de acceso cuando el nivel es CRITICAL. Serializa la insercion con un '
    'advisory lock porque la cadena es secuencial.';

-- La nueva firma es un objeto nuevo: necesita su propio permiso.
GRANT EXECUTE ON FUNCTION audit_log_record(
    BIGINT, TEXT, TEXT, TEXT[],
    audit_channel, INET, TEXT, TEXT,
    TEXT, TEXT, TEXT,
    TEXT, audit_level, JSONB, JSONB, TEXT, TEXT,
    TEXT, audit_decision, TEXT,
    audit_result, TEXT, TEXT, INTEGER, UUID, UUID, TEXT, TEXT,
    DATE, JSONB, TEXT, INTEGER, INTEGER, BIGINT
) TO stock_api;
