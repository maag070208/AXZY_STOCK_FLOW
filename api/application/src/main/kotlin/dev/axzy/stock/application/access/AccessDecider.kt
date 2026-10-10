package dev.axzy.stock.application.access

import dev.axzy.stock.application.audit.AccessDenialLog
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.access.AccessDecision
import dev.axzy.stock.domain.access.AccessEngine
import dev.axzy.stock.domain.access.DeviceType
import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.access.PolicyTrace
import dev.axzy.stock.domain.access.RequestContext
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AbacTrace
import dev.axzy.stock.domain.audit.AccessDecisionTrace
import dev.axzy.stock.domain.audit.AccessTraceBuilder
import dev.axzy.stock.domain.audit.AuditChannel
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.audit.AuditResult
import dev.axzy.stock.domain.audit.AuditedCondition
import dev.axzy.stock.domain.audit.AuditedContext
import dev.axzy.stock.domain.audit.AuditedPolicy
import dev.axzy.stock.domain.audit.ConfigSnapshot
import dev.axzy.stock.domain.audit.IdentityTrace
import dev.axzy.stock.domain.audit.RbacTrace
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import java.time.Clock
import java.time.ZoneId

/**
 * A granted authorization. It carries the decision trace so that the use case attaches it to
 * its audit log record: a critical record must be able to explain with which permission it was done.
 */
data class AccessGrant(
    val permission: String,
    val scope: PermissionScope,
    val trace: AccessDecisionTrace,
)

/**
 * Decides whether an [Actor] can do something. **It is the only authorization gate of the API**: the
 * use cases call it; the controllers do not decide permissions (docs/arquitectura/api-spring.md §6).
 *
 * The rule lives in the domain ([AccessEngine]): identity → RBAC → ABAC, denying by default.
 * This class gives it the request context, translates the decision and records each
 * denial.
 *
 * @param businessZone the operation's time zone. The schedule policies ("adjustments only from
 *   8 to 18") are evaluated in it and not in the client's: a client header can be changed
 *   to skip the policy.
 */
class AccessDecider(
    private val engine: AccessEngine,
    private val denials: AccessDenialLog,
    private val auditEvents: AuditEvents,
    private val clock: Clock,
    private val businessZone: ZoneId,
) {

    /**
     * @param resource the resource attributes that the scope and the policies evaluate. If the
     *   operation applies to a specific warehouse, it has to be passed: without it a scope `AREA`
     *   cannot be checked.
     * @param entity what was attempted on; it is only used to describe the denial.
     */
    fun authorize(
        actor: Actor,
        permission: String,
        origin: RequestOrigin,
        resource: ResourceContext = ResourceContext(),
        entity: AuditEntity = AuditEntity(type = permission.substringBefore('.'), id = null),
    ): Outcome<AccessGrant> {
        val context = requestContext(origin)
        val decision = engine.decide(actor.user, permission, resource, context)
        val trace = traceOf(actor, permission, decision, context)

        if (decision is AccessDecision.Allowed) {
            return AccessGrant(permission, decision.effectiveScope, trace).asRight()
        }

        denials.record(
            auditEvents.byActor(
                actor = actor,
                origin = origin,
                action = "ACCESS_DENIED",
                level = AuditLevel.ACCESS,
                entity = entity,
                grant = AccessGrant(permission, PermissionScope.NONE, trace),
                result = AuditResult.DENIED,
                errorCode = decision.code,
                errorMessage = trace.explain(),
            ),
        )
        return decision.toFailure(trace).asLeft()
    }

    private fun requestContext(origin: RequestOrigin): RequestContext {
        val now = clock.instant().atZone(businessZone)
        return RequestContext(
            hour = now.hour,
            // The domain numbers the week from Sunday (0) to Saturday (6).
            weekday = now.dayOfWeek.value % 7,
            deviceType = when {
                origin.channel == AuditChannel.WEB -> DeviceType.WEB
                origin.channel.isHuman -> DeviceType.MOBILE
                else -> DeviceType.SERVICE
            },
            offlineSync = origin.channel == AuditChannel.MOBILE_OFFLINE,
            ip = origin.ip,
        )
    }

    private fun traceOf(
        actor: Actor,
        permission: String,
        decision: AccessDecision,
        context: RequestContext,
    ): AccessDecisionTrace {
        val (effectiveScope, byRole) = when (decision) {
            is AccessDecision.Allowed -> decision.effectiveScope to decision.scopeByRole
            is AccessDecision.ScopeDenied -> decision.effectiveScope to decision.scopeByRole
            else -> PermissionScope.NONE to emptyMap()
        }
        val policies: List<PolicyTrace> = when (decision) {
            is AccessDecision.Allowed -> decision.policyTrace
            is AccessDecision.PolicyDenied -> decision.policyTrace
            is AccessDecision.PolicyRequired -> decision.policyTrace
            is AccessDecision.SegregationOfDuties -> decision.policyTrace
            is AccessDecision.NotAuthenticated, is AccessDecision.ScopeDenied -> emptyList()
        }
        val decisive = policies.firstOrNull { it.matched && !it.shadowMode }

        return AccessTraceBuilder.build(
            permissionKey = permission,
            decisionCode = decision.code,
            stage = decision.stage.name,
            allowed = decision.allowed,
            identity = IdentityTrace(userId = actor.id, active = actor.user.active),
            rbac = RbacTrace(
                effectiveScope = effectiveScope.name,
                byRole = byRole.mapValues { it.value.name },
                rolesAtTime = actor.roleKeys,
                exceptionScope = actor.user.exceptions[permission]?.name,
                warehousesAtTime = actor.user.warehouseIds.sorted(),
            ),
            abac = AbacTrace(
                evaluated = policies.isNotEmpty(),
                allowed = decision.allowed,
                decidedByPolicyId = decisive?.policyId.takeIf { decision.allowed },
                decidedByPolicyName = decisive?.policyName.takeIf { decision.allowed },
                deniedByPolicyId = (decision as? AccessDecision.PolicyDenied)?.decidedBy?.policyId,
                rules = policies.map { it.audited() },
            ),
            context = AuditedContext(
                hour = context.hour,
                weekday = context.weekday,
                deviceType = context.deviceType.name,
                offlineSync = context.offlineSync,
                ip = context.ip,
            ),
            snapshot = ConfigSnapshot(decision.matrixVersion, decision.policiesVersion),
        )
    }

    private fun PolicyTrace.audited() = AuditedPolicy(
        policyId = policyId,
        policyName = policyName,
        effect = effect.name,
        priority = priority,
        appliesToUser = appliesToUser,
        matched = matched,
        shadowMode = shadowMode,
        conditions = conditions.map {
            AuditedCondition(
                field = it.condition.field,
                operator = it.condition.operator.symbol,
                expected = it.condition.value,
                current = it.current,
                matches = it.matches,
            )
        },
    )

    /**
     * The five authorization codes are different on purpose (docs/api/errores.md): the user
     * does something different for each one. Collapsing them into one "unauthorized" was the legacy's defect.
     */
    private fun AccessDecision.toFailure(trace: AccessDecisionTrace): Failure = Failure(
        kind = if (this is AccessDecision.NotAuthenticated) FailureKind.UNAUTHENTICATED else FailureKind.FORBIDDEN,
        code = code,
        message = trace.explain(),
        details = mapOf("permission" to trace.permissionKey),
    )
}
