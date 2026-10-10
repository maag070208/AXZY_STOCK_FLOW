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
 * Escribe en la bitácora llamando a `audit_log_record` (migración V12), que encadena el hash del
 * registro con el anterior. La API no inserta en `audit_log` directamente: quien calcula la cadena
 * es la base, y una inserción por fuera la rompería.
 *
 * Usa la conexión de la transacción en curso: el registro se confirma o se revierte con el cambio
 * que describe.
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
            // Las claves de rol no llevan comas (`^[A-Z][A-Z0-9_]*$`), así que la lista viaja como texto.
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
 * Registra una denegación en una transacción PROPIA (`REQUIRES_NEW`). El caso de uso que la
 * provocó va a terminar en fallo y a revertir; la denegación tiene que quedar escrita igual.
 *
 * @param independent una plantilla configurada con `PROPAGATION_REQUIRES_NEW`.
 */
class IndependentAccessDenialLog(
    private val recorder: AuditRecorder,
    private val independent: TransactionTemplate,
) : AccessDenialLog {
    override fun record(event: AuditEvent) {
        independent.executeWithoutResult { recorder.record(event) }
    }
}
