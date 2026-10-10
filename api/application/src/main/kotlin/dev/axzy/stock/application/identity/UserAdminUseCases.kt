package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.access.PermissionCatalog
import dev.axzy.stock.domain.access.RoleDefinitions
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.domain.identity.PasswordValidation
import java.time.Clock

/**
 * User and role administration (M07, docs/api/usuarios.md).
 *
 * It is the gate to everything else: whoever can create users and assign them roles can
 * grant themselves any permission, so **all** these operations are `CRITICAL` in the
 * audit log and go through the access engine —creating a user grants access, and granting
 * access is what the critical level protects—.
 *
 * The password **never travels or is stored in clear**: it comes in, is validated against the policy
 * and its hash is stored. And changing it **invalidates the open sessions**, because the credentials stamp
 * of the token stops matching: if someone's password is reset
 * because they suspect it was stolen, leaving the thief's token alive would be doing nothing.
 * nada.
 */

// ── Read models ───────────────────────────────────────────────────────

data class UserSummary(
    val id: Long,
    val username: String,
    val name: String,
    val disabled: Boolean,
    val mustChangePassword: Boolean,
    val roles: List<String>,
    val lastLoginAt: java.time.Instant?,
    val createdAt: java.time.Instant,
)

data class UserDetail(
    val summary: UserSummary,
    val warehouseIds: List<Long>,
    val passwordChangedAt: java.time.Instant?,
)

data class RoleView(
    val key: String,
    val name: String,
    val description: String?,
    val permissions: Int,
)

data class PermissionView(
    val key: String,
    val module: String,
    val displayName: String,
    val sensitive: Boolean,
)

data class UserFilter(val query: String?, val role: String?, val active: Boolean?)

// ── Write models ──────────────────────────────────────────────────────

data class NewUser(
    val username: String?,
    val name: String?,
    val password: String?,
    /** If omitted, the account is born **required to change the password**: that is the prudent choice. */
    val mustChangePassword: Boolean?,
    val roles: List<String>?,
)

data class UserPatch(val name: String?, val disabled: Boolean?, val roles: List<String>?)

interface UserAdminStore {
    fun page(filter: UserFilter, request: PageRequest): Page<UserSummary>

    fun find(id: Long): UserDetail?

    fun usernameExists(username: String): Boolean

    fun insert(username: String, name: String, passwordHash: String, mustChangePassword: Boolean, roleKeys: List<String>): Long

    fun updateProfile(id: Long, name: String?, disabled: Boolean?): Unit

    /** Replaces the roles: assigning roles is deciding what someone can do. */
    fun replaceRoles(id: Long, roleKeys: List<String>): Unit

    fun updatePassword(id: Long, passwordHash: String, mustChangePassword: Boolean, at: java.time.Instant): Unit

    fun roleKeys(): List<String>

    fun roles(): List<RoleView>

    fun permissionKeys(): List<String>
}

// ── Consultas ───────────────────────────────────────────────────────────────

class ListUsers(private val store: UserAdminStore, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: UserFilter,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<UserSummary>> = access.authorizeOr(actor, "user.view", origin) {
        store.page(filter, request).asRight()
    }
}

class GetUser(private val store: UserAdminStore, private val access: AccessDecider) {

    fun execute(actor: Actor, id: Long, origin: RequestOrigin): Outcome<UserDetail> =
        access.authorizeOr(actor, "user.view", origin) {
            store.find(id)?.asRight() ?: userNotFound(id)
        }
}

/**
 * The roles that exist, with how many permissions each one has.
 *
 * Requires `role.manage` and not a `role.view` that does not exist: the catalog says that viewing the
 * permission matrix is part of managing it.
 */
class ListRoles(private val store: UserAdminStore, private val access: AccessDecider) {

    fun execute(actor: Actor, origin: RequestOrigin): Outcome<List<RoleView>> =
        access.authorizeOr(actor, "role.manage", origin) { store.roles().asRight() }
}

/** The permission catalog, which is **code** (`PermissionCatalog`), not an editable table. */
class ListPermissions(private val store: UserAdminStore, private val access: AccessDecider) {

    fun execute(actor: Actor, origin: RequestOrigin): Outcome<List<PermissionView>> =
        access.authorizeOr(actor, "role.manage", origin) {
            PermissionCatalog.all
                .map { permission ->
                    PermissionView(
                        key = permission.key,
                        module = permission.module.name,
                        displayName = permission.name,
                        sensitive = permission.sensitive,
                    )
                }
                .asRight()
        }
}

// ── Escrituras ──────────────────────────────────────────────────────────────

class CreateUser(
    private val store: UserAdminStore,
    private val hasher: PasswordHasher,
    private val policy: PasswordPolicy,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, input: NewUser, origin: RequestOrigin): Outcome<UserDetail> =
        access.authorizeOr(actor, "user.create", origin) { grant ->
            val username = input.username?.trim()
            if (username.isNullOrBlank()) {
                return@authorizeOr fieldRequired("username")
            }
            if (username.length > 80) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                    "The username cannot exceed 80 characters.",
                    mapOf("field" to "username", "maxLength" to "80"),
                )
            }
            val name = input.name?.trim()
            if (name.isNullOrBlank()) return@authorizeOr fieldRequired("name")

            val password = input.password
            if (password.isNullOrBlank()) return@authorizeOr fieldRequired("password")
            when (val valid = policy.validate(password)) {
                is PasswordValidation.Invalid -> return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "WEAK_PASSWORD",
                    valid.rejections.joinToString(" ") { it.message },
                    mapOf("field" to "password"),
                )
                PasswordValidation.Valid -> Unit
            }

            val roles = input.roles?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_ROLE)
            when (val rolesValid = validateRoles(store, roles)) {
                is Either.Left -> return@authorizeOr rolesValid.error.asLeft()
                is Either.Right -> Unit
            }
            if (store.usernameExists(username)) {
                return@authorizeOr failure(
                    FailureKind.CONFLICT, "DUPLICATE_USERNAME",
                    "A user with the name '$username' already exists.",
                    mapOf("username" to username),
                )
            }

            // It is born required to change the password unless stated otherwise: whoever
            // creates the account knows the password, and that cannot stay that way.
            val mustChange = input.mustChangePassword ?: true
            tx.inTransaction {
                val id = store.insert(username, name, hasher.hash(password), mustChange, roles)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "USER_CREATED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("user", id.toString(), username),
                        grant = grant,
                        newState = mapOf("username" to username, "roles" to roles, "mustChangePassword" to mustChange),
                    ),
                )
                store.find(id)?.asRight() ?: userNotFound(id)
            }
        }
}

class UpdateUser(
    private val store: UserAdminStore,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, id: Long, patch: UserPatch, origin: RequestOrigin): Outcome<UserDetail> {
        // Editing the profile and assigning roles are different permissions: one changes how
        // someone is called, the other what they can do.
        val permission = if (patch.roles != null) "role.assign" else "user.edit"
        return access.authorizeOr(actor, permission, origin) { grant ->
            val current = store.find(id) ?: return@authorizeOr userNotFound(id)
            patch.roles?.let { roles ->
                if (roles.isEmpty()) {
                    return@authorizeOr failure(
                        FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                        "A user with no roles can do nothing: assign at least one.",
                        mapOf("field" to "roles"),
                    )
                }
                when (val valid = validateRoles(store, roles)) {
                    is Either.Left -> return@authorizeOr valid.error.asLeft()
                    is Either.Right -> Unit
                }
            }
            patch.name?.trim()?.takeIf { it.isBlank() }?.let {
                return@authorizeOr fieldRequired("name")
            }

            tx.inTransaction {
                store.updateProfile(id, patch.name?.trim(), patch.disabled)
                patch.roles?.let { store.replaceRoles(id, it) }
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = if (patch.roles != null) "USER_ROLES_ASSIGNED" else "USER_UPDATED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("user", id.toString(), current.summary.username),
                        grant = grant,
                        previousState = mapOf(
                            "name" to current.summary.name,
                            "disabled" to current.summary.disabled,
                            "roles" to current.summary.roles,
                        ),
                        newState = mapOf(
                            "name" to (patch.name ?: current.summary.name),
                            "disabled" to (patch.disabled ?: current.summary.disabled),
                            "roles" to (patch.roles ?: current.summary.roles),
                        ),
                    ),
                )
                store.find(id)?.asRight() ?: userNotFound(id)
            }
        }
    }
}

class ResetUserPassword(
    private val store: UserAdminStore,
    private val hasher: PasswordHasher,
    private val policy: PasswordPolicy,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        id: Long,
        newPassword: String?,
        mustChangePassword: Boolean?,
        origin: RequestOrigin,
    ): Outcome<UserDetail> = access.authorizeOr(actor, "user.reset_password", origin) { grant ->
        val current = store.find(id) ?: return@authorizeOr userNotFound(id)
        if (newPassword.isNullOrBlank()) return@authorizeOr fieldRequired("password")
        when (val valid = policy.validate(newPassword)) {
            is PasswordValidation.Invalid -> return@authorizeOr failure(
                FailureKind.RULE_VIOLATION, "WEAK_PASSWORD",
                valid.rejections.joinToString(" ") { it.message },
                mapOf("field" to "password"),
            )
            PasswordValidation.Valid -> Unit
        }

        tx.inTransaction {
            store.updatePassword(id, hasher.hash(newPassword), mustChangePassword ?: true, clock.instant())
            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "USER_PASSWORD_RESET",
                    level = AuditLevel.CRITICAL,
                    entity = AuditEntity("user", id.toString(), current.summary.username),
                    grant = grant,
                    // **The password is not recorded**, neither the new one nor the old one: the audit log
                    // says it was changed, not what it is.
                    newState = mapOf("mustChangePassword" to (mustChangePassword ?: true)),
                ),
            )
            store.find(id)?.asRight() ?: userNotFound(id)
        }
    }
}

// ── Common ──────────────────────────────────────────────────────────────

/** The roles have to exist: inventing one would leave the user without permissions and without knowing it. */
private fun validateRoles(store: UserAdminStore, roles: List<String>): Outcome<Unit> {
    val known = store.roleKeys()
    val unknown = roles.filterNot { it in known }
    if (unknown.isNotEmpty()) {
        return failure(
            FailureKind.RULE_VIOLATION, "UNKNOWN_ROLE",
            "Los roles ${unknown.joinToString(", ")} do not exist.",
            mapOf("roles" to unknown.joinToString(","), "allowed" to known.joinToString(",")),
        )
    }
    return Unit.asRight()
}

private fun fieldRequired(field: String): Outcome<Nothing> = failure(
    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
    "The field '$field' is missing.",
    mapOf("field" to field),
)

private fun userNotFound(id: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "USER_NOT_FOUND",
    "User $id does not exist.",
    mapOf("userId" to id.toString()),
)

/** Without an explicit role, a user is born with the lowest one: never with more than was asked. */
private val DEFAULT_ROLE = RoleDefinitions.baseRoles.first().key.value
