package dev.axzy.stock.application.audit

import dev.axzy.stock.application.access.AccessGrant
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.audit.AccessDecisionTrace
import dev.axzy.stock.domain.audit.AuditActor
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditEvent
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.audit.AuditResult
import dev.axzy.stock.domain.audit.AuditSource
import dev.axzy.stock.domain.audit.AuditTiming
import java.time.Clock
import java.time.ZoneOffset

/**
 * Construye los eventos de bitácora. Existe para que cada caso de uso escriba solo lo que es
 * suyo (qué pasó, sobre qué, por qué) y no repita cómo se obtiene el quién, el cuándo y el dónde.
 */
class AuditEvents(private val clock: Clock) {

    /** Un hecho realizado por una persona. */
    fun byActor(
        actor: Actor,
        origin: RequestOrigin,
        action: String,
        level: AuditLevel,
        entity: AuditEntity,
        grant: AccessGrant? = null,
        previousState: Map<String, Any?>? = null,
        newState: Map<String, Any?>? = null,
        reason: String? = null,
        note: String? = null,
        idempotencyKey: String? = null,
        result: AuditResult = AuditResult.SUCCESS,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): AuditEvent = event(
        actor = AuditActor(actor.id, actor.username, actor.displayName, actor.roleKeys),
        origin = origin,
        action = action,
        level = level,
        entity = entity,
        permissionKey = grant?.permission,
        trace = grant?.trace,
        previousState = previousState,
        newState = newState,
        reason = reason,
        note = note,
        idempotencyKey = idempotencyKey,
        result = result,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    /**
     * Un hecho sin persona identificada todavía (un login fallido) o hecho por el propio sistema
     * (una siembra, una tarea programada).
     */
    fun byActor(
        actor: AuditActor,
        origin: RequestOrigin,
        action: String,
        level: AuditLevel,
        entity: AuditEntity,
        permissionKey: String? = null,
        trace: AccessDecisionTrace? = null,
        previousState: Map<String, Any?>? = null,
        newState: Map<String, Any?>? = null,
        reason: String? = null,
        note: String? = null,
        result: AuditResult = AuditResult.SUCCESS,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): AuditEvent = event(
        actor, origin, action, level, entity, permissionKey, trace, previousState, newState,
        reason, note, null, result, errorCode, errorMessage,
    )

    private fun event(
        actor: AuditActor,
        origin: RequestOrigin,
        action: String,
        level: AuditLevel,
        entity: AuditEntity,
        permissionKey: String?,
        trace: AccessDecisionTrace?,
        previousState: Map<String, Any?>?,
        newState: Map<String, Any?>?,
        reason: String?,
        note: String?,
        idempotencyKey: String?,
        result: AuditResult,
        errorCode: String?,
        errorMessage: String?,
    ): AuditEvent {
        val now = clock.instant()
        return AuditEvent(
            timing = AuditTiming(
                occurredAt = now.toEpochMilli(),
                // Fecha de negocio en UTC. Cuando exista el turno de noche, la decidirá quien llama.
                businessDate = now.atZone(ZoneOffset.UTC).toLocalDate().toString(),
            ),
            actor = actor,
            source = AuditSource(
                channel = origin.channel,
                deviceId = origin.deviceId,
                ip = origin.ip,
                userAgent = origin.userAgent,
            ),
            entity = entity,
            action = action,
            level = level,
            permissionKey = permissionKey,
            trace = trace,
            previousState = previousState,
            newState = newState,
            reason = reason,
            note = note,
            result = result,
            errorCode = errorCode,
            errorMessage = errorMessage,
            idempotencyKey = idempotencyKey,
            requestId = origin.requestId,
        )
    }
}
