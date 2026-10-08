package dev.axzy.stock.backend.audit

import java.sql.ResultSet

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MAPPERS DE AUDITORÍA — de fila de `audit_log` a DTO
 *
 * **Estaban dentro del servicio y eran `private`**, así que no se podían probar sin
 * arrancar PostgreSQL. Y una de ellas se llamaba **`aDto()`** — en español, cuando el
 * estándar del proyecto exige identificadores en inglés. La guardia no la cazó porque
 * «a» no es una palabra de la lista: **una lista negra no sustituye a revisar**.
 *
 * Ver `inventory/InventoryMappers.kt` para el razonamiento completo del patrón, incluida
 * la trampa que el compilador NO detecta: el nombre de columna va en una **cadena**, así
 * que un mapper mal escrito **no rompe, miente**. Se verifica con `curl`.
 * ═══════════════════════════════════════════════════════════════════════════
 */

    internal fun ResultSet.toAuditEntryDto(): AuditEntryDto = AuditEntryDto(
        id = getLong("id"),
        sequenceNo = getLong("sequence_no"),
        occurredAt = getTimestamp("occurred_at").toInstant().toString(),
        businessDate = getDate("business_date").toLocalDate().toString(),
        userId = getLongOrNull("actor_user_id"),
        username = getString("actor_username"),
        userName = getString("actor_name"),
        roles = textArray("actor_roles"),
        action = getString("action"),
        actionLevel = getString("action_level"),
        entity = getString("entity_type"),
        entityId = getString("entity_id"),
        entityLabel = getString("entity_label"),
        changedFields = textArray("changed_fields"),
        channel = getString("source_channel"),
        device = getString("source_device"),
        ip = getString("source_ip"),
        userAgent = getString("source_user_agent"),
        permission = getString("permission_key"),
        decision = getString("decision"),
        result = getString("result"),
        errorCode = getString("error_code"),
        errorMessage = getString("error_message"),
        reason = getString("reason"),
        correlationId = getObject("correlation_id")?.toString(),
        causationId = getObject("causation_id")?.toString(),
        requestId = getString("request_id"),
        prevHash = getString("prev_hash"),
        recordHash = getString("record_hash"),
    )

    internal fun ResultSet.getLongOrNull(column: String): Long? {
        val value = getLong(column)
        return if (wasNull()) null else value
    }

    /** `TEXT[]` de PostgreSQL (`actor_roles`, `changed_fields`) como lista. */
    internal fun ResultSet.textArray(column: String): List<String> {
        val array = getArray(column) ?: return emptyList()
        return try {
            @Suppress("UNCHECKED_CAST")
            (array.array as? Array<Any?>)?.mapNotNull { it?.toString() } ?: emptyList()
        } finally {
            array.free()
        }
    }
