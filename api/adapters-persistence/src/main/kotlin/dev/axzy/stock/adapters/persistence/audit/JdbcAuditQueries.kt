package dev.axzy.stock.adapters.persistence.audit

import dev.axzy.stock.application.audit.AuditEntryView
import dev.axzy.stock.application.audit.AuditFilters
import dev.axzy.stock.application.audit.AuditQueries
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The audit log read (M08).
 *
 * **Two statements per query** —the count and the page— with **the same filters**: if
 * the count ignored them, the envelope would say one thing and the rows another, and U5 requires
 * precisely that the total is computed with the same filter.
 *
 * The table is immutable through triggers and partitioned by month: the date range
 * is not just a business filter; it is what lets the query touch a single
 * partition when a month is requested.
 */
class JdbcAuditQueries(private val jdbc: JdbcClient) : AuditQueries {

    override fun page(filters: AuditFilters, request: PageRequest): Page<AuditEntryView> {
        val where = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any?>()

        filters.entityType?.let { where += "entity_type = :entityType"; params["entityType"] = it }
        filters.entityId?.let { where += "entity_id = :entityId"; params["entityId"] = it }
        filters.actorUserId?.let { where += "actor_user_id = :actorUserId"; params["actorUserId"] = it }
        filters.action?.let { where += "action = :action"; params["action"] = it }
        filters.level?.let { where += "action_level = CAST(:level AS audit_level)"; params["level"] = it }
        filters.result?.let { where += "result = CAST(:result AS audit_result)"; params["result"] = it }
        filters.channel?.let { where += "source_channel = CAST(:channel AS audit_channel)"; params["channel"] = it }
        filters.from?.let { where += "business_date >= CAST(:from AS date)"; params["from"] = it.toString() }
        filters.to?.let { where += "business_date <= CAST(:to AS date)"; params["to"] = it.toString() }

        val clause = "WHERE " + where.joinToString(" AND ")

        val total = query("SELECT count(*) FROM audit_log $clause", params) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$COLUMNS FROM audit_log $clause ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toView() }

        return Page(items, request.page, request.size, total)
    }

    private fun ResultSet.toView(): AuditEntryView {
        val actorUserId = getLong("actor_user_id").takeUnless { wasNull() }
        val serviceKeyId = getLong("source_service_key_id").takeUnless { wasNull() }
        @Suppress("UNCHECKED_CAST")
        val roles = (getArray("actor_roles")?.array as? Array<String>)?.toList() ?: emptyList()
        return AuditEntryView(
            id = getLong("id"),
            occurredAt = getObject("occurred_at", OffsetDateTime::class.java).toInstant(),
            businessDate = getObject("business_date", LocalDate::class.java),
            actorUserId = actorUserId,
            actorUsername = getString("actor_username"),
            actorName = getString("actor_name"),
            actorRoles = roles,
            channel = getString("source_channel"),
            entityType = getString("entity_type"),
            entityId = getString("entity_id"),
            entityLabel = getString("entity_label"),
            action = getString("action"),
            level = getString("action_level"),
            // The previous and the new state are returned **as JSON text**: they are
            // free-form documents and each action has its own.
            previousState = getString("previous_state"),
            newState = getString("new_state"),
            reason = getString("reason"),
            note = getString("note"),
            permissionKey = getString("permission_key"),
            decision = getString("decision"),
            result = getString("result"),
            errorCode = getString("error_code"),
            errorMessage = getString("error_message"),
            serviceKeyId = serviceKeyId,
            requestId = getString("request_id"),
            idempotencyKey = getString("idempotency_key"),
        )
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private companion object {
        /**
         * The response columns. `previous_state` and `new_state` are requested as
         * text and not as an object: the driver does not have to map JSONB to a
         * domain type that does not exist, and the contract publishes them as documents.
         */
        val COLUMNS =
            """
            SELECT id, occurred_at, business_date, actor_user_id, actor_username, actor_name,
                   actor_roles, source_channel::text AS source_channel, source_service_key_id,
                   entity_type, entity_id, entity_label, action, action_level::text AS action_level,
                   previous_state::text AS previous_state, new_state::text AS new_state,
                   reason, note, permission_key, decision::text AS decision, result::text AS result,
                   error_code, error_message, request_id, idempotency_key
            """.trimIndent()
    }
}
