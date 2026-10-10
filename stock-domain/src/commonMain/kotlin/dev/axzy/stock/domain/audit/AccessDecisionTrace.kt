package dev.axzy.stock.domain.audit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ACCESS DECISION TRACE
 *
 * It is **the heart of the requirement** "with which permission". It does not store only the
 * result: it stores **how it was reached**, with each stage and each condition
 * evaluada.
 *
 * Three concrete reasons, not theoretical ones:
 *
 *  1. **Permissions change.** Without the trace, six months from now it cannot be
 *     reconstructed why something was allowed.
 *  2. **The "why not" is as valuable as the "why yes".** When someone claims *"it did not let me
 *     approve"*, the trace says **which condition of which
 *     policy** blocked it. Without that, technical support is guesswork.
 *  3. **Policies evolve.** If tomorrow the threshold changes, the trace
 *     answers why a past decision was valid under the rules that existed then.
 *     entonces.
 *
 * Referencia: docs/historico/PLAN_AUDITORIA.md §3.1.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** The user's status and authentication at the time of the decision. */
data class IdentityTrace(
    val userId: Long,
    val active: Boolean,
    /** When the token used for the request was issued. */
    val authenticatedAt: Long? = null,
)

/** What RBAC contributed, with the **exact state at that moment**. */
data class RbacTrace(
    /** Alcance efectivo resultante. */
    val effectiveScope: String,
    /** What each role contributed. */
    val byRole: Map<String, String>,
    /** Snapshot of the roles in force **then**, so that the history is readable. */
    val rolesAtTime: List<String>,
    /** Per-person exception, if there was one. */
    val exceptionId: Long? = null,
    val exceptionScope: String? = null,
    /** The warehouses of the `AREA` scope at that moment. */
    val warehousesAtTime: List<Long> = emptyList(),
)

/** Trace of **one** condition evaluated. */
data class AuditedCondition(
    val field: String,
    val operator: String,
    val expected: Any?,
    val current: Any?,
    val matches: Boolean,
)

/** Trace of **one** policy evaluated. */
data class AuditedPolicy(
    val policyId: Long,
    val policyName: String,
    val effect: String,
    val priority: Int,
    val appliesToUser: Boolean,
    val matched: Boolean,
    val shadowMode: Boolean,
    val conditions: List<AuditedCondition>,
)

/** What ABAC decided. */
data class AbacTrace(
    val evaluated: Boolean,
    val allowed: Boolean,
    val decidedByPolicyId: Long? = null,
    val decidedByPolicyName: String? = null,
    val deniedByPolicyId: Long? = null,
    val rules: List<AuditedPolicy> = emptyList(),
)

/** The request's context. */
data class AuditedContext(
    val hour: Int,
    val weekday: Int,
    val deviceType: String,
    val offlineSync: Boolean,
    val ip: String? = null,
)

/**
 * The configuration snapshot that makes this decision **reproducible**.
 *
 * It is the piece that turns the audit into something defensible: it allows
 * **replaying** a historical decision as it was taken, and **detecting** that a
 * past decision would not be taken today (because the rules changed). That
 * last one is valuable information for auditing the evolution of the internal control.
 */
data class ConfigSnapshot(
    val matrixVersion: Int,
    val policiesVersion: Int,
)

/**
 * The full trace: the three stages of the pipeline, in order.
 */
data class AccessDecisionTrace(
    val permissionKey: String,
    val decision: String,
    val decisionCode: String,
    /** Stage at which it was decided: `IDENTITY`, `RBAC` or `ABAC`. */
    val stage: String,
    val identity: IdentityTrace,
    val rbac: RbacTrace,
    val abac: AbacTrace,
    val context: AuditedContext,
    val snapshot: ConfigSnapshot,
) {
    /** Indicates whether the decision was to deny. */
    val wasDenied: Boolean get() = decision != "ALLOWED"

    /**
     * Readable explanation of the decision, to show it to the user.
     *
     * It is built from the trace, so it **cannot contradict it**: if the
     * explanation says that a condition failed, that condition is in the trace
     * with its actual value.
     */
    fun explain(): String = when {
        !wasDenied -> "Allowed by $stage (scope ${rbac.effectiveScope})"

        stage == "IDENTITY" -> "Invalid user"

        stage == "RBAC" && decisionCode == "PERMISSION_DENIED" ->
            "The user does not have the permission $permissionKey. " +
                "Roles: ${rbac.rolesAtTime.joinToString(", ").ifEmpty { "ninguno" }}"

        stage == "RBAC" && decisionCode == "SCOPE_INSUFFICIENT" ->
            "Has the permission but not for this resource " +
                "(alcance ${rbac.effectiveScope}, bodegas ${rbac.warehousesAtTime})"

        stage == "ABAC" -> {
            val rule = abac.rules.firstOrNull { it.policyId == (abac.deniedByPolicyId ?: abac.decidedByPolicyId) }
            val conditionsThatFailed = rule?.conditions?.filterNot { it.matches }.orEmpty()
            buildString {
                append("Denied by policy '${rule?.policyName ?: "?"}'")
                if (conditionsThatFailed.isNotEmpty()) {
                    append(". Unmet conditions: ")
                    append(
                        conditionsThatFailed.joinToString("; ") {
                            "${it.field} ${it.operator} ${it.expected} (valor real: ${it.current})"
                        }
                    )
                }
            }
        }

        else -> "Denegado ($decisionCode)"
    }
}

/**
 * Trace builder from a decision of the access engine.
 *
 * It lives in the domain and not in the HTTP layer, so that the mobile app can build the
 * same trace: the decision is taken locally with the same engine, and what is
 * sent to the server is the result, not an audit record invented by
 * the client.
 */
object AccessTraceBuilder {

    fun build(
        permissionKey: String,
        decisionCode: String,
        stage: String,
        allowed: Boolean,
        identity: IdentityTrace,
        rbac: RbacTrace,
        abac: AbacTrace,
        context: AuditedContext,
        snapshot: ConfigSnapshot,
    ): AccessDecisionTrace = AccessDecisionTrace(
        permissionKey = permissionKey,
        decision = if (allowed) "ALLOWED" else "DENIED",
        decisionCode = decisionCode,
        stage = stage,
        identity = identity,
        rbac = rbac,
        abac = abac,
        context = context,
        snapshot = snapshot,
    )
}
