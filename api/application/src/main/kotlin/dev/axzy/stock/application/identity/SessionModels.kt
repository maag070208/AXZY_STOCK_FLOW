package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.domain.access.PermissionScope

/**
 * What the client needs to know about who signed in (docs/api/autenticacion.md): to
 * draw the interface, not to decide. The API always decides.
 */
data class SessionUser(
    val id: Long,
    val username: String,
    val name: String,
    val roles: List<String>,
    /** The granted permissions, sorted. */
    val permissions: List<String>,
    /** The effective scope of each permission: the largest among its roles and its exceptions. */
    val scopes: Map<String, String>,
    val warehouses: List<Long>,
    val mustChangePassword: Boolean,
) {
    companion object {
        fun of(actor: Actor): SessionUser {
            val scopes = sortedMapOf<String, PermissionScope>()
            actor.user.roles.filter { it.active }.forEach { role ->
                role.permissions.forEach { (permission, scope) ->
                    scopes.merge(permission, scope) { a, b -> PermissionScope.max(a, b) }
                }
            }
            actor.user.exceptions.forEach { (permission, scope) ->
                scopes.merge(permission, scope) { a, b -> PermissionScope.max(a, b) }
            }
            val granted = scopes.filterValues { it.grants }
            return SessionUser(
                id = actor.id,
                username = actor.username,
                name = actor.displayName,
                roles = actor.roleKeys,
                permissions = granted.keys.toList(),
                scopes = granted.mapValues { it.value.name },
                warehouses = actor.user.warehouseIds.sorted(),
                mustChangePassword = actor.mustChangePassword,
            )
        }
    }
}

data class AuthSession(
    val accessToken: String,
    /** In clear only here: the database stores its hash. */
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: SessionUser,
)
