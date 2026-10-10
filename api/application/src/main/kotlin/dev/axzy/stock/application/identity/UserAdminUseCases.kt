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
 * La administración de usuarios y roles (M07, docs/api/usuarios.md).
 *
 * Es la puerta de todo lo demás: quien puede crear usuarios y asignarles roles puede
 * darse cualquier permiso, así que **todas** estas operaciones son `CRITICAL` en la
 * bitácora y pasan por el motor de acceso —crear un usuario concede acceso, y conceder
 * acceso es lo que el nivel crítico protege—.
 *
 * La contraseña **nunca viaja ni se guarda en claro**: entra, se valida contra la política
 * y se guarda su hash. Y cambiarla **invalida las sesiones abiertas**, porque el sello de
 * credenciales del token deja de coincidir: si a alguien se le restablece la contraseña
 * porque sospecha que se la robaron, dejar vivo el token del ladrón sería no haber hecho
 * nada.
 */

// ── Modelos de lectura ──────────────────────────────────────────────────────

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

// ── Modelos de escritura ────────────────────────────────────────────────────

data class NewUser(
    val username: String?,
    val name: String?,
    val password: String?,
    /** Si se omite, la cuenta nace **obligada a cambiar la contraseña**: es lo prudente. */
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

    /** Reemplaza los roles: asignar roles es decidir qué puede hacer alguien. */
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
 * Los roles que existen, con cuántos permisos tiene cada uno.
 *
 * Exige `role.manage` y no un `role.view` que no existe: el catálogo dice que ver la
 * matriz de permisos es parte de administrarla.
 */
class ListRoles(private val store: UserAdminStore, private val access: AccessDecider) {

    fun execute(actor: Actor, origin: RequestOrigin): Outcome<List<RoleView>> =
        access.authorizeOr(actor, "role.manage", origin) { store.roles().asRight() }
}

/** El catálogo de permisos, que es **código** (`PermissionCatalog`), no una tabla editable. */
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
                    "El nombre de usuario no puede pasar de 80 caracteres.",
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
                    "Ya existe un usuario con el nombre '$username'.",
                    mapOf("username" to username),
                )
            }

            // Nace obligado a cambiar la contraseña salvo que se diga lo contrario: quien
            // crea la cuenta conoce la contraseña, y eso no puede quedarse así.
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
        // Editar el perfil y asignar roles son permisos distintos: uno cambia cómo se llama
        // alguien, el otro qué puede hacer.
        val permission = if (patch.roles != null) "role.assign" else "user.edit"
        return access.authorizeOr(actor, permission, origin) { grant ->
            val current = store.find(id) ?: return@authorizeOr userNotFound(id)
            patch.roles?.let { roles ->
                if (roles.isEmpty()) {
                    return@authorizeOr failure(
                        FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                        "Un usuario sin roles no puede hacer nada: asigna al menos uno.",
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
                    // **La contraseña no se registra**, ni la nueva ni la vieja: la bitácora
                    // dice que se cambió, no cuál es.
                    newState = mapOf("mustChangePassword" to (mustChangePassword ?: true)),
                ),
            )
            store.find(id)?.asRight() ?: userNotFound(id)
        }
    }
}

// ── Común ───────────────────────────────────────────────────────────────────

/** Los roles tienen que existir: inventar uno dejaría al usuario sin permisos y sin saberlo. */
private fun validateRoles(store: UserAdminStore, roles: List<String>): Outcome<Unit> {
    val known = store.roleKeys()
    val unknown = roles.filterNot { it in known }
    if (unknown.isNotEmpty()) {
        return failure(
            FailureKind.RULE_VIOLATION, "UNKNOWN_ROLE",
            "Los roles ${unknown.joinToString(", ")} no existen.",
            mapOf("roles" to unknown.joinToString(","), "allowed" to known.joinToString(",")),
        )
    }
    return Unit.asRight()
}

private fun fieldRequired(field: String): Outcome<Nothing> = failure(
    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
    "Falta el campo '$field'.",
    mapOf("field" to field),
)

private fun userNotFound(id: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "USER_NOT_FOUND",
    "No existe el usuario $id.",
    mapOf("userId" to id.toString()),
)

/** Sin rol explícito, un usuario nace con el más bajo: nunca con más de lo que se pidió. */
private val DEFAULT_ROLE = RoleDefinitions.baseRoles.first().key.value
