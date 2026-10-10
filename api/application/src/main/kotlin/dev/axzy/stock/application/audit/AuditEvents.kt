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
 * Builds the audit events. It exists so that each use case writes only what is its own
 * (what happened, to what, why) and does not repeat how the who, the when and the where are obtained.
 */
class AuditEvents(private val clock: Clock) {

    /** A fact done by a person. */
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
     * A fact without an identified person yet (a failed sign-in) or done by the system itself
     * (a seed, a scheduled task).
     */
    fun byActor(
        actor: AuditActor,
        origin: RequestOrigin,
        action: String,
        level: AuditLevel,
        entity: AuditEntity,
        permissionKey: String? = null,
        trace: AccessDecisionTrace? = null,
        /**
         * Which service credential did it.
         *
         * A job marked as printed with no user behind it must be distinguishable
         * from one done by a person, and `null` does not distinguish "it was a system" from "I do not
         * sabemos».
         */
        serviceKeyId: Long? = null,
        previousState: Map<String, Any?>? = null,
        newState: Map<String, Any?>? = null,
        reason: String? = null,
        note: String? = null,
        result: AuditResult = AuditResult.SUCCESS,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): AuditEvent = event(
        actor, origin, action, level, entity, permissionKey, trace, previousState, newState,
        reason, note, null, result, errorCode, errorMessage, serviceKeyId,
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
        serviceKeyId: Long? = null,
    ): AuditEvent {
        val now = clock.instant()
        return AuditEvent(
            timing = AuditTiming(
                occurredAt = now.toEpochMilli(),
                // Business date in UTC. When the night shift exists, the caller will decide it.
                businessDate = now.atZone(ZoneOffset.UTC).toLocalDate().toString(),
            ),
            actor = actor,
            source = AuditSource(
                channel = origin.channel,
                deviceId = origin.deviceId,
                ip = origin.ip,
                userAgent = origin.userAgent,
                serviceKeyId = serviceKeyId,
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
