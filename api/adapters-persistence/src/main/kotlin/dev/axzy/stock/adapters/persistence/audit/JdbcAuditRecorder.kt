package dev.axzy.stock.adapters.persistence.audit

import dev.axzy.stock.adapters.persistence.shared.inetOrNull
import dev.axzy.stock.application.audit.AccessDenialLog
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.domain.audit.AuditEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * Writes to the audit log by calling `audit_log_record` (migration V12), which chains the hash of the
 * record with the previous one. The API does not insert into `audit_log` directly: whoever computes the chain
 * is the database, and an insert from outside would break it.
 *
 * Uses the connection of the transaction in progress: the record is committed or rolled back with the change
 * that it describes.
 */
class JdbcAuditRecorder(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AuditRecorder {

    override fun record(event: AuditEvent): Long {
        val trace = event.trace
        return jdbc.sql(SQL)
            .param("actorUserId", event.actor.userId)
            .param("actorUsername", event.actor.username)
            .param("actorName", event.actor.displayName)
            // Role keys have no commas (`^[A-Z][A-Z0-9_]*$`), so the list travels as text.
            .param("actorRoles", event.actor.roles.takeIf { it.isNotEmpty() }?.joinToString(","))
            .param("channel", event.source.channel.name)
            .param("ip", inetOrNull(event.source.ip))
            .param("userAgent", event.source.userAgent)
            .param("device", event.source.deviceId)
            .param("entityType", event.entity.type)
            .param("entityId", event.entity.id)
            .param("entityLabel", event.entity.label)
            .param("action", event.action)
            .param("level", event.level.name)
            .param("previousState", event.previousState?.let(json::writeValueAsString))
            .param("newState", event.newState?.let(json::writeValueAsString))
            .param("reason", event.reason)
            .param("note", event.note)
            .param("permissionKey", event.permissionKey)
            .param("decision", trace?.decision ?: "NOT_APPLICABLE")
            .param("decisionCode", trace?.decisionCode)
            .param("result", event.result.name)
            .param("errorCode", event.errorCode)
            .param("errorMessage", event.errorMessage)
            .param("durationMs", event.durationMs?.toInt())
            .param("correlationId", event.correlationId)
            .param("causationId", event.causationId)
            .param("idempotencyKey", event.idempotencyKey)
            .param("requestId", event.requestId)
            .param("businessDate", LocalDate.parse(event.timing.businessDate))
            .param("accessTrace", trace?.let(json::writeValueAsString))
            .param("decisionStage", trace?.stage)
            .param("matrixVersion", trace?.snapshot?.matrixVersion)
            .param("policiesVersion", trace?.snapshot?.policiesVersion)
            .param("serviceKeyId", event.source.serviceKeyId)
            .query(Long::class.java)
            .single()
    }

    private companion object {
        val SQL = """
            SELECT audit_log_record(
                p_actor_user_id      => :actorUserId,
                p_actor_username     => :actorUsername,
                p_actor_name         => :actorName,
                p_actor_roles        => string_to_array(:actorRoles, ','),
                p_source_channel     => CAST(:channel AS audit_channel),
                p_source_ip          => CAST(:ip AS inet),
                p_source_user_agent  => :userAgent,
                p_source_device      => :device,
                p_entity_type        => :entityType,
                p_entity_id          => :entityId,
                p_entity_label       => :entityLabel,
                p_action             => :action,
                p_action_level       => CAST(:level AS audit_level),
                p_previous_state     => CAST(:previousState AS jsonb),
                p_new_state          => CAST(:newState AS jsonb),
                p_reason             => :reason,
                p_note               => :note,
                p_permission_key     => :permissionKey,
                p_decision           => CAST(:decision AS audit_decision),
                p_decision_code      => :decisionCode,
                p_result             => CAST(:result AS audit_result),
                p_error_code         => :errorCode,
                p_error_message      => :errorMessage,
                p_duration_ms        => :durationMs,
                p_correlation_id     => CAST(:correlationId AS uuid),
                p_causation_id       => CAST(:causationId AS uuid),
                p_idempotency_key    => :idempotencyKey,
                p_request_id         => :requestId,
                p_business_date      => :businessDate,
                p_access_trace       => CAST(:accessTrace AS jsonb),
                p_decision_stage     => :decisionStage,
                p_matrix_version     => :matrixVersion,
                p_policies_version   => :policiesVersion,
                p_source_service_key_id => :serviceKeyId
            )
        """.trimIndent()
    }
}

/**
 * Records a denial in its OWN transaction (`REQUIRES_NEW`). The use case that
 * caused it is going to fail and roll back; the denial has to be written anyway.
 *
 * @param independent a template configured with `PROPAGATION_REQUIRES_NEW`.
 */
class IndependentAccessDenialLog(
    private val recorder: AuditRecorder,
    private val independent: TransactionTemplate,
) : AccessDenialLog {
    override fun record(event: AuditEvent) {
        independent.executeWithoutResult { recorder.record(event) }
    }
}
