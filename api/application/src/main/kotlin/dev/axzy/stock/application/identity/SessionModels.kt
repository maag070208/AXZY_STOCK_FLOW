package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.domain.access.PermissionScope

/**
 * Lo que el cliente necesita saber de quien inició sesión (docs/api/autenticacion.md): para
 * dibujar la interfaz, no para decidir. Quien decide es siempre la API.
 */
data class SessionUser(
    val id: Long,
    val username: String,
    val name: String,
    val roles: List<String>,
    /** Los permisos concedidos, ordenados. */
    val permissions: List<String>,
    /** El alcance efectivo de cada permiso: el mayor entre sus roles y sus excepciones. */
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
    /** En claro solo aquí: la base guarda su hash. */
    val refreshToken: String,
    val expiresInSeconds: Long,
    val user: SessionUser,
)
