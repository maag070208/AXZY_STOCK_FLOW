package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE ACCESS ENGINE — pipeline Identity → RBAC → ABAC
 *
 * It is a **pure function**: it receives the user, the action, the resource and the
 * context, and returns a decision. It does not touch the database, it is not `suspend`, and it does not
 * tiene efectos secundarios.
 *
 * That purity is what allows three things:
 *   1. Testing thousands of combinations in milliseconds, without a server.
 *   2. That **the backend and the mobile take the same decision**, because they run
 *      literally the same code. The UI is a hint; the server is the
 *      authority, but both agree.
 *   3. **Replay** a historical decision with the version of the matrix and of
 *      the policies that was in force (§3.3 of docs/historico/PLAN_AUDITORIA.md).
 *
 * **The rule that holds everything is `DEFAULT DENY`:** if nothing
 * explicitly allows it, it is denied. The legacy did the opposite — `if (role === 1)
 * return next()` — and the admin role did not have a single permission in the database.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * The user, with their access information.
 *
 * Note that the **warehouses of the `AREA` scope belong to the user, not to the role**.
 * That is what allows a single `WAREHOUSE_MANAGER` role to serve any warehouse,
 * instead of creating one role per warehouse.
 */
data class AccessUser(
    val id: Long,
    val username: String,
    val active: Boolean = true,
    val roles: List<Role> = emptyList(),
    /** Warehouses that are "theirs": they resolve the `AREA` scope. */
    val warehouseIds: Set<Long> = emptySet(),
    /** Per-person exceptions: a specific permission above or below the role. */
    val exceptions: Map<String, PermissionScope> = emptyMap(),
    val exceptionExpiries: Map<String, Long> = emptyMap(),
)

/**
 * The resource acted upon. It is what ABAC evaluates.
 *
 * The fields belong to the warehouse domain, not generic ones: `difference`,
 * `adjustmentReason`, `locationKind`… An ABAC with generic attributes is no use
 * for anything.
 */
data class ResourceContext(
    val warehouseId: Long? = null,
    val locationCode: String? = null,
    /** `SHELF` or `RECEIVING`: the receiving location has its own rules. */
    val locationKind: String? = null,
    val productCode: String? = null,
    /** Difference between what was counted and what was invoiced. A key attribute of the policies. */
    val difference: Long? = null,
    val quantity: Long? = null,
    /** The adjustment reason: it distinguishes shrinkage from theft. */
    val adjustmentReason: String? = null,
    val unitCost: Long? = null,
    val totalValue: Long? = null,
    /** Who counted: used by the segregation of duties (PB3). */
    val capturedBy: Long? = null,
    /** Additional fields that a future policy may need. */
    val extra: Map<String, Any?> = emptyMap(),
) {
    /**
     * Resolves the value of an attribute by its qualified name
     * (`resource.difference`). It is what the condition evaluator uses.
     */
    fun resolve(field: String): Any? = when (field) {
        "resource.warehouseId" -> warehouseId
        "resource.locationCode" -> locationCode
        "resource.locationKind" -> locationKind
        "resource.productCode" -> productCode
        "resource.difference" -> difference
        "resource.quantity" -> quantity
        "resource.adjustmentReason" -> adjustmentReason
        "resource.unitCost" -> unitCost
        "resource.totalValue" -> totalValue
        "resource.capturedBy" -> capturedBy
        else -> extra[field.removePrefix("resource.")]
    }
}

/** The request's context. */
data class RequestContext(
    val hour: Int = 12,
    val weekday: Int = 3,
    val deviceType: DeviceType = DeviceType.WEB,
    val offlineSync: Boolean = false,
    val ip: String? = null,
) {
    fun resolve(field: String): Any? = when (field) {
        "context.hour" -> hour
        "context.weekday" -> weekday
        "context.deviceType" -> deviceType.name
        "context.offlineSync" -> offlineSync
        "context.ip" -> ip
        else -> null
    }
}

enum class DeviceType { WEB, MOBILE, SERVICE }

/** At which stage the decision was taken. It allows explaining the "why not". */
enum class DecisionStage { IDENTITY, RBAC, ABAC }

/**
 * The decision, with its full trace.
 *
 * The trace is not decoration: it is what allows answering *"why wasn't I allowed to
 * approve?"* with **which condition of which policy** failed, instead of an
 * "access denied" that is not actionable.
 */
sealed interface AccessDecision {

    val allowed: Boolean
    val code: String
    val stage: DecisionStage

    /** The configuration snapshot that makes this decision reproducible. */
    val matrixVersion: Int
    val policiesVersion: Int

    data class Allowed(
        val permission: String,
        val effectiveScope: PermissionScope,
        val scopeByRole: Map<String, PermissionScope>,
        val policyTrace: List<PolicyTrace> = emptyList(),
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = true
        override val code = "OK"
        override val stage = if (policyTrace.isEmpty()) DecisionStage.RBAC else DecisionStage.ABAC
    }

    /** The user does not exist or is deactivated. */
    data class NotAuthenticated(
        val reason: String,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "UNAUTHENTICATED"
        override val stage = DecisionStage.IDENTITY
    }

    /** RBAC is not enough: it does not have the permission, or not for that resource. */
    data class ScopeDenied(
        val permission: String,
        val effectiveScope: PermissionScope,
        val requiredScope: PermissionScope,
        val scopeByRole: Map<String, PermissionScope>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val stage = DecisionStage.RBAC
        override val code =
            if (effectiveScope == PermissionScope.NONE) "PERMISSION_DENIED" else "SCOPE_INSUFFICIENT"
    }

    /** An ABAC policy denied the action. */
    data class PolicyDenied(
        val permission: String,
        val decidedBy: PolicyTrace,
        val policyTrace: List<PolicyTrace>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "POLICY_DENIED"
        override val stage = DecisionStage.ABAC
    }

    /** There are applicable policies and none `ALLOW` matched. */
    data class PolicyRequired(
        val permission: String,
        val policyTrace: List<PolicyTrace>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "POLICY_REQUIRED"
        override val stage = DecisionStage.ABAC
    }

    /** Segregation of duties: it tried to approve its own work. */
    data class SegregationOfDuties(
        val permission: String,
        val policyTrace: List<PolicyTrace>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "SEGREGATION_OF_DUTIES"
        override val stage = DecisionStage.ABAC
    }
}

/**
 * The engine.
 *
 * @param matrixVersion the version of the role→permission matrix in force. It is stored in
 *   each audited decision so that it can be replayed later.
 * @param policiesVersion the version of the policy set.
 */
class AccessEngine(
    private val policies: List<Policy> = emptyList(),
    private val matrixVersion: Int = 0,
    private val policiesVersion: Int = 0,
    /** Allows temporarily disabling ABAC evaluation (diagnostics). */
    private val abacEnabled: Boolean = true,
) {

    /**
     * Decides whether the user can perform the action on the resource.
     *
     * **The three stages, in order, without exceptions:**
     *
     *  1. **Identity** — does the user exist and is active? If not, it stops here.
     *  2. **RBAC** — does it have the permission, and with sufficient scope **for this
     *     resource**? A `WAREHOUSE_MANAGER` with `AREA` scope does not reach a warehouse
     *     that is not theirs.
     *  3. **ABAC** — are the conditions met? With `DENY` always winning and
     *     `DEFAULT DENY` if there are applicable policies and none allows.
     */
    fun decide(
        user: AccessUser?,
        permissionKey: String,
        resource: ResourceContext = ResourceContext(),
        context: RequestContext = RequestContext(),
    ): AccessDecision {

        // ── ETAPA 1: IDENTIDAD ───────────────────────────────────────────────
        // It is checked BEFORE looking at permissions: a deactivated user has no access even if their role
        // says otherwise. The legacy did not check this in the middleware, so deactivating someone did not
        // invalidate their token.
        // hasta 24 horas (hallazgo S3).
        if (user == null) {
            return AccessDecision.NotAuthenticated("User not authenticated", matrixVersion, policiesVersion)
        }
        if (!user.active) {
            return AccessDecision.NotAuthenticated("The account is disabled", matrixVersion, policiesVersion)
        }

        val permission = PermissionCatalog.find(permissionKey)
            ?: return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = PermissionScope.NONE,
                requiredScope = PermissionScope.NONE,
                scopeByRole = emptyMap(),
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )

        // ── ETAPA 2: RBAC ────────────────────────────────────────────────────
        val scopeByRole = user.roles
            .filter { it.active }
            .associate { it.key.value to it.scopeOf(permissionKey) }
            .filterValues { it.grants }

        val fromRoles = scopeByRole.values.fold(PermissionScope.NONE) { acc, scope ->
            PermissionScope.max(acc, scope)
        }

        // The per-person exception can raise the scope, but **it cannot
        // grant a permission that no role provides** when the system is
        // configured in strict mode. Here the explicit exception is allowed:
        // a managed and audited decision (with a mandatory reason in the
        // application layer).
        val exceptionScope = user.exceptions[permissionKey]
            ?.takeIf { isExceptionValid(user, permissionKey) }
            ?: PermissionScope.NONE

        val effectiveScope = PermissionScope.max(fromRoles, exceptionScope)

        if (!effectiveScope.grants) {
            return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = PermissionScope.NONE,
                requiredScope = requiredScopeFor(permission, resource),
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        val required = requiredScopeFor(permission, resource)
        if (!effectiveScope.covers(required)) {
            return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                requiredScope = required,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // ── The AREA scope has to be THEIR warehouse ─────────────────────────────
        // Without this check, `AREA` would be equivalent to `ALL` and the multi-warehouse permissions model
        // would be useless: a warehouse manager could operate in any warehouse. It is the check that makes
        // operate in any warehouse. It is the check that makes a single
        // `WAREHOUSE_MANAGER` role serve all the warehouses without creating a role for
        // each one.
        //
        // It is the S7 finding of the legacy: there authorization failed with a 401 instead of
        // a 403 and did not distinguish "you do not have the permission" from "you do not have
        // permission over THIS", which is what is actionable.
        // It applies to OWN **and** to AREA: an operator whose scope is "their own" does not reach a warehouse
        // that is not theirs either. Checking only AREA
        // would leave OWN as a wildcard over all the warehouses.
        val resourceWarehouse = resource.warehouseId
        if (effectiveScope != PermissionScope.ALL &&
            resourceWarehouse != null &&
            resourceWarehouse !in user.warehouseIds
        ) {
            return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                requiredScope = PermissionScope.ALL,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // ── ETAPA 3: ABAC ────────────────────────────────────────────────────
        if (!abacEnabled) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        val applicable = policies.filter { policy ->
            policy.active &&
                policy.action == permissionKey &&
                (policy.roles.isEmpty() || policy.roles.any { roleKey ->
                    user.roles.any { it.key == roleKey }
                })
        }

        // Without applicable policies, RBAC decides and it is allowed.
        if (applicable.isEmpty()) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // Evaluate each applicable policy and build the trace.
        val traces = applicable
            .sortedByDescending { it.priority }
            .map { policy -> evaluate(policy, user, resource, context) }

        // Rule 3: DENY always wins, even with a lower priority. A denial cannot be
        // "bought" with a higher-priority policy.
        val denied = traces.filter {
            it.matched && it.effect == PolicyEffect.DENY && !it.shadowMode
        }
        if (denied.isNotEmpty()) {
            val deciding = denied.first()
            // Segregation of duties has its own error code, because
            // it is actionable: the user must know that they need ANOTHER person, not just
            // that they "cannot". It is identified by the DENY effect on the
            // authorship condition, which is the only policy of this kind.
            if (deciding.conditions.any { it.condition.field == "resource.capturedBy" }) {
                return AccessDecision.SegregationOfDuties(permissionKey, traces, matrixVersion, policiesVersion)
            }
            return AccessDecision.PolicyDenied(permissionKey, deciding, traces, matrixVersion, policiesVersion)
        }

        // Rule 4: at least one matching ALLOW.
        val allowed = traces.any {
            it.matched && it.effect == PolicyEffect.ALLOW && !it.shadowMode
        }
        if (allowed) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                policyTrace = traces,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // Rule 5: there are applicable policies and none ALLOW matched.
        // **This is what makes ABAC raise the bar instead of lowering it.**
        // A user with the permission through RBAC is denied if there is a policy
        // on that action that is not met.
        //
        // Unless ALL the applicable policies are in observation: in that
        // case there is no rule in force that requires authorization, and the action
        // is decided by RBAC alone. That is the essence of observation mode.
        val hasActivePolicies = traces.any { !it.shadowMode }
        if (!hasActivePolicies) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                policyTrace = traces,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        return AccessDecision.PolicyRequired(permissionKey, traces, matrixVersion, policiesVersion)
    }

    /**
     * ABAC evaluation **without applying it**: it returns what it would have decided.
     *
     * It is what allows observation mode. A policy is activated in this
     * mode, the audit log is reviewed for a few days and only then is it really
     * activated. Without this, a badly written `DENY` policy could block
     * the warehouse in the middle of receiving a container.
     */
    fun evaluateForObservation(
        user: AccessUser?,
        permissionKey: String,
        resource: ResourceContext = ResourceContext(),
        context: RequestContext = RequestContext(),
    ): List<PolicyTrace> {
        if (user == null) return emptyList()
        return policies
            .filter { it.active && it.action == permissionKey }
            .sortedByDescending { it.priority }
            .map { evaluate(it, user, resource, context) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNO
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * The minimum scope required to act on a specific resource.
     *
     * Domain rule: if the resource declares a warehouse and the user's scope is
     * `AREA`, it has to be checked that **that warehouse is theirs**. If the
     * resource does not declare a warehouse, `OWN`/`AREA` are enough to act on one's own items and
     * `ALL` for global ones.
     */
    private fun requiredScopeFor(permission: Permission, resource: ResourceContext): PermissionScope =
        when {
            // Permission that is only granted globally.
            resource.warehouseId == null && permission.scopes == setOf(PermissionScope.ALL) ->
                PermissionScope.ALL

            else -> PermissionScope.OWN
        }

    /** Checks that the per-person exception is still in force (not expired). */
    private fun isExceptionValid(user: AccessUser, permissionKey: String): Boolean {
        val expiry = user.exceptionExpiries[permissionKey] ?: return true
        // `now` is injected from outside in the application layer; here it is compared
        // against the system clock as the default value.
        return expiry > currentEpochDay()
    }

    /** Evaluates a policy against the user, the resource and the context. */
    private fun evaluate(
        policy: Policy,
        user: AccessUser,
        resource: ResourceContext,
        context: RequestContext,
    ): PolicyTrace {
        val appliesToUser = policy.roles.isEmpty() || policy.roles.any { roleKey ->
            user.roles.any { it.key == roleKey }
        }

        val conditionTraces = policy.conditions.map { condition ->
            val current = resolveField(condition.field, user, resource, context)
            // The expected value can be a constant or a reference to the
            // acting user. Segregation of duties needs the second:
            // compares `capturedBy` against **whoever is trying to approve**.
            val expected = resolveExpected(condition.value, user)
            ConditionTrace(
                condition = condition,
                current = current,
                matches = condition.operator.matches(current, expected),
            )
        }

        // Conditions are combined with AND: all of them must match.
        val matched = appliesToUser && conditionTraces.all { it.matches }

        return PolicyTrace(
            policyId = policy.id,
            policyName = policy.name,
            effect = policy.effect,
            priority = policy.priority,
            appliesToUser = appliesToUser,
            matched = matched,
            conditions = conditionTraces,
            shadowMode = policy.shadowMode,
        )
    }

    /**
     * Resolves the expected value of a condition.
     *
     * If it is the [StockPolicies.CURRENT_USER_MARKER] marker, it returns the id of the
     * acting user. This way the segregation-of-duties policy stays
     * **configurable** instead of being hard-coded in the engine.
     */
    private fun resolveExpected(value: Any?, user: AccessUser): Any? =
        if (value == StockPolicies.CURRENT_USER_MARKER) user.id else value

    /** Resolves an attribute by its qualified name. */
    private fun resolveField(
        field: String,
        user: AccessUser,
        resource: ResourceContext,
        context: RequestContext,
    ): Any? = when {
        field.startsWith("resource.") -> resource.resolve(field)
        field.startsWith("context.") -> context.resolve(field)
        field.startsWith("user.") -> when (field) {
            "user.id" -> user.id
            "user.role" -> user.roles.firstOrNull()?.key?.value
            "user.roles" -> user.roles.map { it.key.value }
            "user.warehouseIds" -> user.warehouseIds.toList()
            else -> null
        }
        else -> null
    }

    private companion object {
        /**
         * Current day. It is kept isolated here so that replacing it with an
         * injectable clock is a one-line change in Phase 1.
         */
        fun currentEpochDay(): Long = 0L
    }
}
