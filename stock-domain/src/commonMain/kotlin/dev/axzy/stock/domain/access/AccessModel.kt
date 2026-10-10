package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ACCESS MODEL — hybrid RBAC + ABAC
 *
 * It adapts the model that already works in production in `RESPONSIVA/web`
 * (role console, policies, simulation and audit log) to the warehouse domain,
 * where the scope is not "my area" but **warehouse and location**.
 *
 * Four concepts, in order of dependency:
 *
 *   1. [PermissionScope] — how far a permission reaches: own, its warehouse, everything.
 *   2. [Permission]      — WHAT can be done (`invoice.approve`), with its name
 *                          and the module catalog.
 *   3. [RoleKey]         — stable role key. It replaces the numeric `roleId` that the
 *                          legacy hard-coded in four places (§S2).
 *   4. [Policy]          — UNDER WHAT CONDITIONS it is allowed or denied (ABAC).
 *
 * Referencia: docs/historico/PLAN_ROLES_PERMISOS_POLITICAS.md.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Scope of a permission over the data.
 *
 * It is the second axis of RBAC: a permission is not just yes/no. `inventory.view`
 * can mean "see the whole warehouse" or "see only my warehouse".
 *
 * **`AREA` is the key to multi-warehouse**: it means "their warehouse", and which
 * warehouses are theirs is determined by the assignment of the **person**, not by
 * the role. That avoids creating a role per warehouse (`JEFE_BODEGA_NORTE`,
 * `JEFE_BODEGA_SUR`…), which is the classic mistake.
 */
enum class PermissionScope {
    /** No access. */
    NONE,

    /** Only their own assignments or their location. */
    OWN,

    /** Their warehouse (or the warehouses assigned to them). */
    AREA,

    /** All warehouses. */
    ALL,
    ;

    /** Indicates whether it grants any access. */
    val grants: Boolean get() = this != NONE

    /**
     * Checks whether this scope covers the required scope.
     *
     * It is a ladder: `ALL ⊇ AREA ⊇ OWN ⊇ NONE`. Granting `AREA` **does not**
     * grant `ALL`, and that error (treating the scope as a boolean) is what
     * turns a permission model into a hole.
     */
    fun covers(required: PermissionScope): Boolean = when (this) {
        ALL -> true
        AREA -> required == AREA || required == OWN || required == NONE
        OWN -> required == OWN || required == NONE
        NONE -> required == NONE
    }

    companion object {
        /** Sorts from smallest to largest scope, to resolve the effective maximum. */
        fun max(a: PermissionScope, b: PermissionScope): PermissionScope =
            if (a.ordinal >= b.ordinal) a else b
    }
}

/**
 * Catalog modules. They group the permissions for the administration matrix
 * and determine the order in which they are shown.
 */
enum class PermissionModule(val displayName: String) {
    IDENTITY("identidad"),
    CATALOG("catalog"),
    WAREHOUSE("physical warehouse"),
    RECEIVING("receiving"),
    FULFILLMENT("surtido"),
    INVENTORY("inventario"),
    TRANSFER("transferencias"),
    LABELING("etiquetado"),
    REPORT("reportes"),
    SYSTEM("sistema"),
}

/**
 * A catalog permission, with key `resource.action`.
 *
 * **The `resource.action` convention in lowercase is deliberate**: it is the one
 * production uses, and it allows the policy editor to group by resource and
 * sort by lifecycle (view → create → edit → delete → business
 * actions). The `ALMACEN:CREA` example you proposed did not scale: it had
 * an inconsistent direction and a resource that was too broad.
 *
 * @param key stable, immutable key. It is the contract with the database.
 * @param module the module it belongs to, for grouping in the UI.
 * @param scopes the **admitted** scopes. A permission that does not admit `OWN` does not
 *   offer that option on the administration screen.
 * @param sensitive whether its use must always be audited (Level 3).
 */
data class Permission(
    val key: String,
    val module: PermissionModule,
    val name: String,
    val description: String? = null,
    val scopes: Set<PermissionScope> = setOf(PermissionScope.ALL),
    val sensitive: Boolean = false,
    val active: Boolean = true,
    val sortOrder: Int = 0,
) {
    init {
        require(key.isNotBlank()) { "The permission key cannot be empty" }
        require('.' in key) {
            "The permission key must have the format 'resource.action': '$key'"
        }
        require(key == key.lowercase()) {
            "The permission key must be lowercase: '$key'"
        }
        require(scopes.isNotEmpty()) { "A permission must allow at least one scope: '$key'" }
        require(PermissionScope.NONE !in scopes) {
            "The NONE scope is not granted: it must not be listed among the allowed scopes of '$key'"
        }
    }

    /** The resource of the permission (`invoice` in `invoice.approve`). */
    val resource: String get() = key.substringBefore('.')

    /** The action of the permission (`approve` in `invoice.approve`). */
    val action: String get() = key.substringAfter('.')

    /** Grantable scopes, without `NONE`, in canonical order. */
    val grantableScopes: List<PermissionScope>
        get() = listOf(PermissionScope.OWN, PermissionScope.AREA, PermissionScope.ALL)
            .filter { it in scopes }
}

/**
 * Key of a role. **Stable and immutable**, unlike the legacy's numeric `roleId`,
 * which was hard-coded as `if (role === 1)` in four places of the backend and
 * depended on the seed inserting `admin` first (finding S2).
 *
 * The visible name can be renamed; the key cannot.
 */
@kotlin.jvm.JvmInline
value class RoleKey private constructor(val value: String) {

    companion object {
        const val MAX_LENGTH = 50

        private val PATTERN = Regex("^[A-Z][A-Z0-9_]*$")

        fun of(raw: String): RoleKey {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "The role key cannot be empty" }
            require(trimmed.length <= MAX_LENGTH) {
                "The role key exceeds $MAX_LENGTH characters: '$trimmed'"
            }
            require(PATTERN.matches(trimmed)) {
                "The role key must be UPPERCASE, digits and underscore, starting with a letter: '$trimmed'"
            }
            return RoleKey(trimmed)
        }

        /**
         * Generates a key from the visible name, as the reference implementation already does:
         * reference implementation:
         * `"Warehouse manager"` → `WAREHOUSE_MANAGER`, without clashes with the existing ones.
         */
        fun fromName(name: String, taken: Set<String> = emptySet()): RoleKey {
            var base = name.trim().uppercase()
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_')

            if (base.isEmpty()) return of("ROL")
            if (!base[0].isLetter()) base = "R_$base"
            base = base.take(MAX_LENGTH).trimEnd('_')

            var candidate = base
            var suffix = 2
            while (candidate in taken) {
                val tail = "_$suffix"
                candidate = base.take(MAX_LENGTH - tail.length).trimEnd('_') + tail
                suffix++
            }
            return of(candidate)
        }
    }

    override fun toString(): String = value
}

/** System role: stable key + visible name + its permissions with scope. */
data class Role(
    val key: RoleKey,
    val name: String,
    val description: String? = null,
    /** The granted permissions **and with what scope**. */
    val permissions: Map<String, PermissionScope> = emptyMap(),
    /** A system role cannot be deleted. */
    val isSystem: Boolean = false,
    val active: Boolean = true,
    val sortOrder: Int = 0,
) {
    /**
     * Scope this role contributes for a permission.
     * `NONE` if it does not have it or if it is inactive.
     */
    fun scopeOf(permissionKey: String): PermissionScope =
        if (!active) PermissionScope.NONE
        else permissions[permissionKey] ?: PermissionScope.NONE
}

/**
 * The five base warehouse roles.
 *
 * Each one is explained in a sentence. **If a role cannot be explained in a
 * sentence, it is badly cut.** It is the test that rules out the generic `SUPERVISOR` role,
 * which in practice ends up with permissions for everything so as not to block
 * the work, and it is the role that destroys a permission model.
 */
enum class BaseRole(val key: RoleKey, val displayName: String, val oneLiner: String) {
    ADMIN(RoleKey.of("ADMIN"), "Administrator", "Can do everything, including configuration"),
    OPERATIONS_MANAGER(
        RoleKey.of("OPERATIONS_MANAGER"),
        "Operations manager",
        "Views and approves everything, without administering the system",
    ),
    WAREHOUSE_MANAGER(
        RoleKey.of("WAREHOUSE_MANAGER"),
        "Warehouse manager",
        "Runs the warehouse: captures, locates, approves and adjusts",
    ),
    WAREHOUSE_OPERATOR(
        RoleKey.of("WAREHOUSE_OPERATOR"),
        "Warehouse operator",
        "Carries out the work assigned, approves nothing",
    ),
    AUDITOR(RoleKey.of("AUDITOR"), "Auditor", "Read-only: reports, ledger and audit log"),
}

// ═════════════════════════════════════════════════════════════════════════════
// ABAC — policies ──────────────────────────────────────────────────────────
// ═════════════════════════════════════════════════════════════════════════════

/** Effect of a policy. */
enum class PolicyEffect {
    ALLOW,
    DENY,
}

/** Condition operators. */
enum class PolicyOperator(val symbol: String) {
    EQUALS("=="),
    NOT_EQUALS("!="),
    LESS_THAN("<"),
    LESS_OR_EQUAL("<="),
    GREATER_THAN(">"),
    GREATER_OR_EQUAL(">="),
    IN("in"),
    NOT_IN("not_in"),
    ;

    /**
     * Compares an actual value against the expected one, according to the operator.
     *
     * **Careful with numbers: in Kotlin `Int(0) != Long(0)`.** `equals` between
     * different numeric types returns false, so `0 in listOf(0L, 6L)` is
     * **false** even though it is conceptually true. That detail meant that a
     * `DENY` weekend policy never matched the real day.
     *
     * That is why ALL numeric comparisons go through [asLong], and
     * equality only uses `equals` when there are no numbers involved.
     */
    fun matches(current: Any?, expected: Any?): Boolean = when (this) {
        EQUALS -> areEqual(current, expected)
        NOT_EQUALS -> !areEqual(current, expected)
        IN -> when (expected) {
            is Collection<*> -> expected.any { areEqual(current, it) }
            else -> false
        }
        NOT_IN -> when (expected) {
            is Collection<*> -> expected.none { areEqual(current, it) }
            else -> false
        }
        LESS_THAN -> compareNumbers(current, expected) { a, b -> a < b }
        LESS_OR_EQUAL -> compareNumbers(current, expected) { a, b -> a <= b }
        GREATER_THAN -> compareNumbers(current, expected) { a, b -> a > b }
        GREATER_OR_EQUAL -> compareNumbers(current, expected) { a, b -> a >= b }
    }

    /**
     * Type-tolerant equality for numbers.
     *
     * If **both** sides are numbers, it compares as `Long` (so that
     * `Int(8) == Long(8)`). If either is not, it uses the normal `equals`.
     */
    private fun areEqual(a: Any?, b: Any?): Boolean {
        val na = asLong(a)
        val nb = asLong(b)
        if (na != null && nb != null) return na == nb
        return a == b
    }

    private inline fun compareNumbers(
        current: Any?,
        expected: Any?,
        predicate: (Long, Long) -> Boolean,
    ): Boolean {
        val a = asLong(current) ?: return false
        val b = asLong(expected) ?: return false
        return predicate(a, b)
    }

    private fun asLong(value: Any?): Long? = when (value) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> value.toLong()
        is Float -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

/**
 * A condition of a policy: `field operator value`.
 * Ejemplo real: `resource.difference <= 50`.
 */
data class PolicyCondition(
    val field: String,
    val operator: PolicyOperator,
    val value: Any?,
)

/** Trace of the evaluation of **one** condition. It is what makes the "why not" explainable. */
data class ConditionTrace(
    val condition: PolicyCondition,
    val current: Any?,
    val matches: Boolean,
)

/** Trace of the evaluation of **one** policy. */
data class PolicyTrace(
    val policyId: Long,
    val policyName: String,
    val effect: PolicyEffect,
    val priority: Int,
    /** Indicates whether the policy applied to the user (by role). */
    val appliesToUser: Boolean,
    /** Indicates whether all its conditions matched. */
    val matched: Boolean,
    val conditions: List<ConditionTrace>,
    /**
     * Observation mode of **this** policy, copied into the trace.
     *
     * It is copied here instead of being looked up afterwards by id: the traces are
     * sorted by priority, and searching for the policy again was a source of
     * errors. A policy in observation **records** its decision but **does not
     * apply it**, and that must be impossible to confuse.
     */
    val shadowMode: Boolean = false,
)

/**
 * An ABAC policy.
 *
 * Evaluation rules, to be implemented exactly like this:
 *  1. Only `active` policies are evaluated whose `action` matches and whose `roles`
 *     include one of the user's (or are empty).
 *  2. The conditions are combined with **AND**.
 *  3. **If any `DENY` matches, it is denied**, regardless of how many `ALLOW`s there are.
 *  4. If there is no `DENY` and at least one `ALLOW` matches, it is allowed.
 *  5. If there are applicable policies but **no `ALLOW` matches**, it is denied
 *     with `POLICY_REQUIRED`. **This is what makes ABAC raise the bar.**
 *  6. Without applicable policies: `DEFAULT DENY`.
 */
data class Policy(
    val id: Long,
    val name: String,
    val description: String,
    val action: String,
    val effect: PolicyEffect,
    val priority: Int = 0,
    val active: Boolean = true,
    /** In observation: it records the decision but **does not apply it**. */
    val shadowMode: Boolean = false,
    /** Not disableable without explicit confirmation (for legal reasons). */
    val protected: Boolean = false,
    /** Roles it applies to. Empty = all of them. */
    val roles: Set<RoleKey> = emptySet(),
    val conditions: List<PolicyCondition> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "A policy must have a name" }
        require(description.isNotBlank()) {
            "A policy must have a description: in six months nobody will remember why it exists. " +
                "Policy without description: '$name'"
        }
        require(action.isNotBlank()) { "A policy must state which action it applies to" }
    }
}
