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
 * Una autorización concedida. Lleva la traza de la decisión para que el caso de uso la adjunte a
 * su registro de bitácora: un registro crítico tiene que poder explicar con qué permiso se hizo.
 */
data class AccessGrant(
    val permission: String,
    val scope: PermissionScope,
    val trace: AccessDecisionTrace,
)

/**
 * Decide si un [Actor] puede hacer algo. **Es la única puerta de autorización de la API**: los
 * casos de uso la llaman; los controladores no deciden permisos (docs/arquitectura/api-spring.md §6).
 *
 * La regla la tiene el dominio ([AccessEngine]): identidad → RBAC → ABAC, denegando por defecto.
 * Esta clase le da el contexto de la petición, traduce la decisión y deja constancia de cada
 * denegación.
 *
 * @param businessZone la zona horaria de la operación. Las políticas de horario («ajustes solo de
 *   8 a 18») se evalúan en ella y no en la del cliente: una cabecera del cliente se puede cambiar
 *   para saltarse la política.
 */
class AccessDecider(
    private val engine: AccessEngine,
    private val denials: AccessDenialLog,
    private val auditEvents: AuditEvents,
    private val clock: Clock,
    private val businessZone: ZoneId,
) {

    /**
     * @param resource los atributos del recurso que evalúan el alcance y las políticas. Si la
     *   operación recae sobre una bodega concreta, hay que pasarla: sin ella un alcance `AREA`
     *   no se puede comprobar.
     * @param entity sobre qué se intentó actuar; solo se usa para describir la denegación.
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
            // El dominio numera la semana desde el domingo (0) hasta el sábado (6).
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
     * Los cinco códigos de autorización son distintos a propósito (docs/api/errores.md): el usuario
     * hace algo distinto ante cada uno. Colapsarlos en un «no autorizado» era el defecto del legacy.
     */
    private fun AccessDecision.toFailure(trace: AccessDecisionTrace): Failure = Failure(
        kind = if (this is AccessDecision.NotAuthenticated) FailureKind.UNAUTHENTICATED else FailureKind.FORBIDDEN,
        code = code,
        message = trace.explain(),
        details = mapOf("permission" to trace.permissionKey),
    )
}
