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
 * Crear y editar roles con su matriz de permisos (M07, docs/api/usuarios.md).
 *
 * Dos reglas mandan aquí:
 *
 * · **El catálogo de permisos es código, no una tabla editable.** Una matriz no puede
 *   conceder un permiso que no exista en [PermissionCatalog]: sería un permiso que nadie
 *   comprueba y que daría una falsa sensación de control.
 * · **El alcance tiene que ser uno de los que el permiso admite.** Conceder `OWN` sobre un
 *   permiso que solo admite `ALL` es una celda que el motor no sabe evaluar.
 *
 * Todo cambio de matriz es `CRITICAL` en la bitácora: cambiar qué puede hacer un rol es
 * cambiar los permisos de todas las personas que lo tienen.
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

    /** Reemplaza la matriz del rol: lo que no venga, deja de estar concedido. */
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
            // La clave viaja en el token y en la bitácora: con espacios o minúsculas acabaría
            // siendo dos cosas distintas según quién la escriba.
            if (!KEY_PATTERN.matches(key)) {
                return@authorizeOr failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                    "La clave del rol solo admite mayúsculas, dígitos y guion bajo.",
                    mapOf("field" to "key", "value" to key),
                )
            }
            val name = input.name?.trim()
            if (name.isNullOrBlank()) return@authorizeOr fieldRequired("name")
            if (store.keyExists(key)) {
                return@authorizeOr failure(
                    FailureKind.CONFLICT, "DUPLICATE_ROLE",
                    "Ya existe un rol con la clave '$key'.",
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
                    // La matriz anterior y la nueva, permiso a permiso: es lo que permite
                    // responder «¿desde cuándo puede este rol hacer esto?».
                    previousState = mapOf("grants" to current.grants.associate { it.permissionKey to it.scope.name }),
                    newState = mapOf("grants" to grants.associate { it.permissionKey to it.scope.name }),
                    // Cambiar la matriz de un rol afecta a todas las personas que lo tienen.
                    note = "Afecta a ${current.users} usuario(s) con este rol.",
                ),
            )
            store.find(key)?.asRight() ?: roleNotFound(key)
        }
    }
}

// ── Validación ──────────────────────────────────────────────────────────────

/**
 * Cada permiso tiene que existir en el catálogo y admitir el alcance pedido.
 *
 * Y no puede repetirse: dos celdas del mismo permiso con alcances distintos dejarían la
 * decisión en manos del orden de lectura.
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
                "El permiso '${grant.permissionKey}' no existe en el catálogo.",
                mapOf("permission" to grant.permissionKey),
            )
        if (grant.scope == PermissionScope.NONE) {
            // Conceder NONE no es conceder: para quitar una celda, no se manda.
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "El alcance NONE no se concede: para quitar un permiso, no lo mandes.",
                mapOf("permission" to grant.permissionKey, "scope" to "NONE"),
            )
        }
        if (grant.scope !in permission.scopes) {
            return failure(
                FailureKind.RULE_VIOLATION, "SCOPE_NOT_ALLOWED",
                "El permiso '${grant.permissionKey}' no admite el alcance ${grant.scope.name}.",
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

/** El alcance de una celda, tal como viaja en la API. Lo usan también los adaptadores. */
fun parseScope(raw: String?): PermissionScope? =
    raw?.trim()?.uppercase()?.let { value -> PermissionScope.entries.firstOrNull { it.name == value } }

private fun roleNotFound(key: String): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "ROLE_NOT_FOUND",
    "No existe el rol '$key'.",
    mapOf("role" to key),
)

private fun fieldRequired(field: String): Outcome<Nothing> = failure(
    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
    "Falta el campo '$field'.",
    mapOf("field" to field),
)

private val KEY_PATTERN = Regex("^[A-Z][A-Z0-9_]{1,39}$")
