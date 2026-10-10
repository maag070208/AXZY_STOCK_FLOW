package dev.axzy.stock.adapters.web.identity

import dev.axzy.stock.adapters.web.http.PageDto
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.identity.CreateRole
import dev.axzy.stock.application.identity.CreateUser
import dev.axzy.stock.application.identity.GetRole
import dev.axzy.stock.application.identity.GetUser
import dev.axzy.stock.application.identity.ListPermissions
import dev.axzy.stock.application.identity.ListRoles
import dev.axzy.stock.application.identity.ListUsers
import dev.axzy.stock.application.identity.NewRole
import dev.axzy.stock.application.identity.NewUser
import dev.axzy.stock.application.identity.PermissionView
import dev.axzy.stock.application.identity.ResetUserPassword
import dev.axzy.stock.application.identity.RevokeUserSessions
import dev.axzy.stock.application.identity.SessionsRevoked
import dev.axzy.stock.application.identity.RoleDetail
import dev.axzy.stock.application.identity.RoleGrantView
import dev.axzy.stock.application.identity.RoleView
import dev.axzy.stock.application.identity.SetRolePermissions
import dev.axzy.stock.application.identity.UpdateRole
import dev.axzy.stock.application.identity.parseScope
import dev.axzy.stock.application.identity.UpdateUser
import dev.axzy.stock.application.identity.UserDetail
import dev.axzy.stock.application.identity.UserFilter
import dev.axzy.stock.application.identity.UserPatch
import dev.axzy.stock.application.identity.UserSummary
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.domain.common.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * La administración de usuarios (M07, docs/api/usuarios.md).
 *
 * Es la puerta de todo lo demás: **quien puede asignar roles puede darse cualquier
 * permiso**. Por eso editar el perfil y asignar roles son rutas con permisos distintos
 * (`user.edit` frente a `role.assign`) y por eso todo lo que se escribe aquí queda en la
 * bitácora con nivel `CRITICAL`.
 */
@RestController
@RequestMapping("/v1/users")
class UserController(
    private val listUsers: ListUsers,
    private val getUser: GetUser,
    private val createUser: CreateUser,
    private val updateUser: UpdateUser,
    private val resetPassword: ResetUserPassword,
    private val revokeUserSessions: RevokeUserSessions,
) {

    @GetMapping
    @RequiresPermission("user.view")
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) role: String?,
        @RequestParam(required = false) active: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, null, setOf("username"), SortSpec("username"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val filter = UserFilter(
            query = q?.trim()?.takeUnless { it.isBlank() },
            role = role?.trim()?.takeUnless { it.isBlank() },
            active = active,
        )
        return listUsers.execute(actor, filter, request, origin).toResponse { it.toDto { user -> user.toDto() } }
    }

    @PostMapping
    @RequiresPermission("user.create")
    fun create(
        @RequestBody body: NewUserDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (
        val result = createUser.execute(
            actor,
            NewUser(
                username = body.username,
                name = body.name,
                password = body.password,
                mustChangePassword = body.mustChangePassword,
                roles = body.roles,
            ),
            origin,
        )
    ) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.CREATED).body(result.value.toDto())
    }

    @GetMapping("/{id}")
    @RequiresPermission("user.view")
    fun detail(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = getUser.execute(actor, id, origin).toResponse { it.toDto() }

    /**
     * El mismo cuerpo sirve para editar el perfil y para asignar roles, y **el permiso que
     * se exige depende de lo que venga**: mandar `roles` es decidir qué puede hacer alguien,
     * y eso es `role.assign`.
     */
    @PutMapping("/{id}")
    @RequiresPermission("user.edit", "role.assign")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: UserPatchDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = updateUser
        .execute(actor, id, UserPatch(name = body.name, disabled = body.disabled, roles = body.roles), origin)
        .toResponse { it.toDto() }

    @PostMapping("/{id}/reset-password")
    @RequiresPermission("user.reset_password")
    fun resetPassword(
        @PathVariable id: Long,
        @RequestBody body: ResetPasswordDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = resetPassword
        .execute(actor, id, body.password, body.mustChangePassword, origin)
        .toResponse { it.toDto() }

    /**
     * Cierra todas las sesiones de un usuario.
     *
     * **Corta la renovación, no el token vivo**: el acceso ya emitido es un JWT firmado y vale
     * hasta que expire por su cuenta; lo que se revoca son los tokens de refresco. Para cortar
     * el acceso de inmediato está restablecer la contraseña, que sí mueve el sello de
     * credenciales.
     */
    @PostMapping("/{id}/revoke-sessions")
    @RequiresPermission("session.revoke")
    fun revokeSessions(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = revokeUserSessions.execute(actor, id, origin).toResponse { it.toDto() }
}

/**
 * Los roles y el catálogo de permisos.
 *
 * Los dos exigen `role.manage`: el catálogo dice que ver la matriz de permisos es parte de
 * administrarla, y `role.view` no existe a propósito.
 */
@RestController
@RequestMapping("/v1")
class RoleController(
    private val listRoles: ListRoles,
    private val listPermissions: ListPermissions,
    private val getRole: GetRole,
    private val createRole: CreateRole,
    private val updateRole: UpdateRole,
    private val setRolePermissions: SetRolePermissions,
) {

    @GetMapping("/roles")
    @RequiresPermission("role.manage")
    fun roles(actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        listRoles.execute(actor, origin).toResponse { roles -> roles.map { it.toDto() } }

    @PostMapping("/roles")
    @RequiresPermission("role.manage")
    fun create(
        @RequestBody body: NewRoleDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (
        val result = createRole.execute(
            actor,
            NewRole(
                key = body.key,
                name = body.name,
                description = body.description,
                grants = body.permissions?.map { it.toGrant() },
            ),
            origin,
        )
    ) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.CREATED).body(result.value.toDto())
    }

    @GetMapping("/roles/{key}")
    @RequiresPermission("role.manage")
    fun detail(
        @PathVariable key: String,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = getRole.execute(actor, key, origin).toResponse { it.toDto() }

    @PutMapping("/roles/{key}")
    @RequiresPermission("role.manage")
    fun update(
        @PathVariable key: String,
        @RequestBody body: UpdateRoleDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = updateRole
        .execute(actor, key, body.name, body.description, body.active, origin)
        .toResponse { it.toDto() }

    /** La matriz del rol: lo que no venga, deja de estar concedido. */
    @PutMapping("/roles/{key}/permissions")
    @RequiresPermission("role.manage")
    fun setPermissions(
        @PathVariable key: String,
        @RequestBody body: RolePermissionsDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = setRolePermissions
        .execute(actor, key, body.permissions?.map { it.toGrant() }, origin)
        .toResponse { it.toDto() }

    @GetMapping("/permissions")
    @RequiresPermission("role.manage")
    fun permissions(actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        listPermissions.execute(actor, origin).toResponse { permissions -> permissions.map { it.toDto() } }
}

// ── DTOs ────────────────────────────────────────────────────────────────────

data class UserSummaryDto(
    val id: Long,
    val username: String,
    val name: String,
    val disabled: Boolean,
    val mustChangePassword: Boolean,
    val roles: List<String>,
    val lastLoginAt: String?,
    val createdAt: String,
)

fun UserSummary.toDto(): UserSummaryDto = UserSummaryDto(
    id = id,
    username = username,
    name = name,
    disabled = disabled,
    mustChangePassword = mustChangePassword,
    roles = roles,
    lastLoginAt = lastLoginAt?.toIso(),
    createdAt = createdAt.toIso(),
)

data class UserDetailDto(
    val id: Long,
    val username: String,
    val name: String,
    val disabled: Boolean,
    val mustChangePassword: Boolean,
    val roles: List<String>,
    val lastLoginAt: String?,
    val createdAt: String,
    val warehouseIds: List<Long>,
    val passwordChangedAt: String?,
)

fun UserDetail.toDto(): UserDetailDto = UserDetailDto(
    id = summary.id,
    username = summary.username,
    name = summary.name,
    disabled = summary.disabled,
    mustChangePassword = summary.mustChangePassword,
    roles = summary.roles,
    lastLoginAt = summary.lastLoginAt?.toIso(),
    createdAt = summary.createdAt.toIso(),
    warehouseIds = warehouseIds,
    passwordChangedAt = passwordChangedAt?.toIso(),
)

data class NewUserDto(
    val username: String? = null,
    val name: String? = null,
    val password: String? = null,
    val mustChangePassword: Boolean? = null,
    val roles: List<String>? = null,
)

data class UserPatchDto(
    val name: String? = null,
    val disabled: Boolean? = null,
    val roles: List<String>? = null,
)

data class ResetPasswordDto(val password: String? = null, val mustChangePassword: Boolean? = null)

data class SessionsRevokedDto(val userId: Long, val username: String, val revokedSessions: Int)

fun SessionsRevoked.toDto(): SessionsRevokedDto = SessionsRevokedDto(userId, username, revoked)

data class RoleDto(val key: String, val name: String, val description: String?, val permissions: Int)

fun RoleView.toDto(): RoleDto = RoleDto(key, name, description, permissions)

/** Un rol con su matriz: es lo que hace falta para editarlo sin adivinar. */
data class RoleDetailDto(
    val key: String,
    val name: String,
    val description: String?,
    val isSystem: Boolean,
    val active: Boolean,
    val users: Int,
    val permissions: List<RoleGrantDto>,
)

fun RoleDetail.toDto(): RoleDetailDto = RoleDetailDto(
    key = key,
    name = name,
    description = description,
    isSystem = isSystem,
    active = active,
    users = users,
    permissions = grants.map { RoleGrantDto(it.permissionKey, it.scope.name) },
)

data class RoleGrantDto(val permissionKey: String, val scope: String) {
    fun toGrant(): RoleGrantView =
        RoleGrantView(permissionKey, parseScope(scope) ?: error("Scope '${scope}' is invalid."))
}

data class NewRoleDto(
    val key: String? = null,
    val name: String? = null,
    val description: String? = null,
    val permissions: List<RoleGrantDto>? = null,
)

data class UpdateRoleDto(val name: String? = null, val description: String? = null, val active: Boolean? = null)

data class RolePermissionsDto(val permissions: List<RoleGrantDto>? = null)

data class PermissionDto(val key: String, val module: String, val displayName: String, val sensitive: Boolean)

fun PermissionView.toDto(): PermissionDto = PermissionDto(key, module, displayName, sensitive)

/** Un instante en ISO 8601 UTC, que es el formato de la API. */
private fun Instant.toIso(): String = toString()
