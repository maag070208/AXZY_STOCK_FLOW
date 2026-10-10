package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.access.PermissionCatalog
import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight

/**
 * Creating and editing roles with their permission matrix (M07, docs/api/usuarios.md).
 *
 * Two rules govern here:
 *
 * · **The permission catalog is code, not an editable table.** A matrix cannot
 *   grant a permission that does not exist in [PermissionCatalog]: it would be a permission that nobody
 *   checks and that would give a false sense of control.
 * · **The scope has to be one of those the permission admits.** Granting `OWN` over a
 *   permission that only admits `ALL` is a cell the engine does not know how to evaluate.
 *
 * Every matrix change is `CRITICAL` in the audit log: changing what a role can do is
 * changing the permissions of everyone who has it.
 */

data class RoleGrantView(val permissionKey: String, val scope: PermissionScope)

data class RoleDetail(
    val key: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val active: Boolean,
    val grants: List<RoleGrantView>,
    val users: Int,
)

data class NewRole(
    val key: String?,
    val name: String?,
    val description: String?,
    val grants: List<RoleGrantView>?,
)

interface RoleAdminStore {
    fun find(key: String): RoleDetail?

    fun keyExists(key: String): Boolean

    fun insert(key: String, name: String, description: String?): Unit

    fun update(key: String, name: String?, description: String?, active: Boolean?): Unit

    /** Replaces the role's matrix: whatever is not sent stops being granted. */
    fun replaceGrants(key: String, grants: List<RoleGrantView>): Unit
}

class GetRole(private val store: RoleAdminStore, private val access: AccessDecider) {

    fun execute(actor: Actor, key: String, origin: RequestOrigin): Outcome<RoleDetail> =
        access.authorizeOr(actor, "role.manage", origin) {
            store.find(key)?.asRight() ?: roleNotFound(key)
        }
}

class CreateRole(
    private val store: RoleAdminStore,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, input: NewRole, origin: RequestOrigin): Outcome<RoleDetail> =
        access.authorizeOr(actor, "role.manage", origin) { grant ->
            val key = input.key?.trim()?.uppercase()
            if (key.isNullOrBlank()) return@authorizeOr fieldRequired("key")
            // The key travels in the token and in the audit log: with spaces or lowercase it would end up
            // being two different things depending on who types it.
            if (!KEY_PATTERN.matches(key)) {
                return@authorizeOr failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                    "The role key only allows uppercase letters, digits and underscore.",
                    mapOf("field" to "key", "value" to key),
                )
            }
            val name = input.name?.trim()
            if (name.isNullOrBlank()) return@authorizeOr fieldRequired("name")
            if (store.keyExists(key)) {
                return@authorizeOr failure(
                    FailureKind.CONFLICT, "DUPLICATE_ROLE",
                    "A role with the key '$key' already exists.",
                    mapOf("role" to key),
                )
            }
            when (val valid = validateGrants(input.grants ?: emptyList())) {
                is Either.Left -> return@authorizeOr valid.error.asLeft()
                is Either.Right -> Unit
            }

            tx.inTransaction {
                store.insert(key, name, input.description?.trim()?.takeUnless { it.isBlank() })
                store.replaceGrants(key, input.grants ?: emptyList())
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "ROLE_CREATED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("role", key, name),
                        grant = grant,
                        newState = mapOf("key" to key, "name" to name, "grants" to (input.grants?.size ?: 0)),
                    ),
                )
                store.find(key)?.asRight() ?: roleNotFound(key)
            }
        }
}

class UpdateRole(
    private val store: RoleAdminStore,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(
        actor: Actor,
        key: String,
        name: String?,
        description: String?,
        active: Boolean?,
        origin: RequestOrigin,
    ): Outcome<RoleDetail> = access.authorizeOr(actor, "role.manage", origin) { grant ->
        val current = store.find(key) ?: return@authorizeOr roleNotFound(key)
        name?.trim()?.takeIf { it.isBlank() }?.let { return@authorizeOr fieldRequired("name") }

        tx.inTransaction {
            store.update(key, name?.trim(), description?.trim(), active)
            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "ROLE_UPDATED",
                    level = AuditLevel.CRITICAL,
                    entity = AuditEntity("role", key, current.name),
                    grant = grant,
                    previousState = mapOf("name" to current.name, "active" to current.active),
                    newState = mapOf(
                        "name" to (name?.trim() ?: current.name),
                        "active" to (active ?: current.active),
                    ),
                ),
            )
            store.find(key)?.asRight() ?: roleNotFound(key)
        }
    }
}

class SetRolePermissions(
    private val store: RoleAdminStore,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(
        actor: Actor,
        key: String,
        grants: List<RoleGrantView>?,
        origin: RequestOrigin,
    ): Outcome<RoleDetail> = access.authorizeOr(actor, "role.manage", origin) { grant ->
        val current = store.find(key) ?: return@authorizeOr roleNotFound(key)
        if (grants == null) return@authorizeOr fieldRequired("permissions")
        when (val valid = validateGrants(grants)) {
            is Either.Left -> return@authorizeOr valid.error.asLeft()
            is Either.Right -> Unit
        }

        tx.inTransaction {
            store.replaceGrants(key, grants)
            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "ROLE_PERMISSIONS_SET",
                    level = AuditLevel.CRITICAL,
                    entity = AuditEntity("role", key, current.name),
                    grant = grant,
                    // The previous and the new matrix, permission by permission: that is what allows
                    // answering "since when can this role do this?".
                    previousState = mapOf("grants" to current.grants.associate { it.permissionKey to it.scope.name }),
                    newState = mapOf("grants" to grants.associate { it.permissionKey to it.scope.name }),
                    // Changing a role's matrix affects everyone who has it.
                    note = "Affects ${current.users} user(s) with this role.",
                ),
            )
            store.find(key)?.asRight() ?: roleNotFound(key)
        }
    }
}

// ── Validation ─────────────────────────────────────────────────────────

/**
 * Each permission has to exist in the catalog and admit the requested scope.
 *
 * And it cannot repeat: two cells of the same permission with different scopes would leave the
 * decision in the hands of the reading order.
 */
private fun validateGrants(grants: List<RoleGrantView>): Outcome<List<RoleGrantView>> {
    val repeated = grants.groupBy { it.permissionKey }.filterValues { it.size > 1 }.keys
    if (repeated.isNotEmpty()) {
        return failure(
            FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
            "Hay permisos repetidos: ${repeated.joinToString(", ")}.",
            mapOf("field" to "permissions", "repeated" to repeated.joinToString(",")),
        )
    }
    grants.forEach { grant ->
        val permission = PermissionCatalog.find(grant.permissionKey)
            ?: return failure(
                FailureKind.RULE_VIOLATION, "UNKNOWN_PERMISSION",
                "The permission '${grant.permissionKey}' does not exist in the catalog.",
                mapOf("permission" to grant.permissionKey),
            )
        if (grant.scope == PermissionScope.NONE) {
            // Granting NONE is not granting: to remove a cell, nothing is sent.
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The NONE scope is not granted: to remove a permission, do not send it.",
                mapOf("permission" to grant.permissionKey, "scope" to "NONE"),
            )
        }
        if (grant.scope !in permission.scopes) {
            return failure(
                FailureKind.RULE_VIOLATION, "SCOPE_NOT_ALLOWED",
                "Permission '${grant.permissionKey}' does not support the scope ${grant.scope.name}.",
                mapOf(
                    "permission" to grant.permissionKey,
                    "scope" to grant.scope.name,
                    "allowed" to permission.scopes.joinToString(",") { it.name },
                ),
            )
        }
    }
    return grants.asRight()
}

/** The scope of a cell, as it travels in the API. The adapters use it too. */
fun parseScope(raw: String?): PermissionScope? =
    raw?.trim()?.uppercase()?.let { value -> PermissionScope.entries.firstOrNull { it.name == value } }

private fun roleNotFound(key: String): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "ROLE_NOT_FOUND",
    "The role '$key' does not exist.",
    mapOf("role" to key),
)

private fun fieldRequired(field: String): Outcome<Nothing> = failure(
    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
    "The field '$field' is missing.",
    mapOf("field" to field),
)

private val KEY_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,39}$")
