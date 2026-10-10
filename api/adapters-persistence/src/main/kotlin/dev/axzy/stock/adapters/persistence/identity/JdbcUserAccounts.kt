package dev.axzy.stock.adapters.persistence.identity

import dev.axzy.stock.adapters.persistence.shared.instant
import dev.axzy.stock.adapters.persistence.shared.toSql
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.identity.ActorSnapshot
import dev.axzy.stock.application.identity.UserAccount
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.domain.access.AccessUser
import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.access.Role
import dev.axzy.stock.domain.access.RoleKey
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant

class JdbcUserAccounts(private val jdbc: JdbcClient) : UserAccounts {

    override fun findByUsername(username: String): UserAccount? =
        jdbc.sql("$SELECT_ACCOUNT WHERE username = :username")
            .param("username", username)
            .query { rs, _ -> rs.toAccount() }
            .optional().orElse(null)

    override fun findById(id: Long): UserAccount? =
        jdbc.sql("$SELECT_ACCOUNT WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> rs.toAccount() }
            .optional().orElse(null)

    /**
     * Two queries, always: the account with its warehouses, and its roles with all their cells. One
     * query per role would make the cost of EACH request grow with the user's roles.
     */
    override fun loadActor(userId: Long): ActorSnapshot? {
        data class Row(
            val username: String,
            val name: String,
            val disabled: Boolean,
            val mustChangePassword: Boolean,
            val passwordChangedAt: Instant?,
            val warehouses: Set<Long>,
        )

        val account = jdbc.sql(
            """
            SELECT u.username, u.name, u.disabled, u.must_change_password, u.password_changed_at,
                   ARRAY(SELECT s.warehouse_id FROM user_warehouse_scopes s WHERE s.user_id = u.id) AS warehouses
            FROM users u
            WHERE u.id = :id
            """.trimIndent(),
        )
            .param("id", userId)
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                val warehouses = (rs.getArray("warehouses").array as Array<Any?>).mapNotNull { (it as Number?)?.toLong() }
                Row(
                    username = rs.getString("username"),
                    name = rs.getString("name"),
                    disabled = rs.getBoolean("disabled"),
                    mustChangePassword = rs.getBoolean("must_change_password"),
                    passwordChangedAt = rs.instant("password_changed_at"),
                    warehouses = warehouses.toSet(),
                )
            }
            .optional().orElse(null) ?: return null

        // LinkedHashMap: keeps the order of `sort_order` with which the roles arrive.
        val roles = LinkedHashMap<String, RoleRow>()
        jdbc.sql(
            """
            SELECT r.key, r.name, r.description, r.is_system, r.active, r.sort_order,
                   p.key AS permission_key, rp.scope
            FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            LEFT JOIN role_permissions rp ON rp.role_id = r.id
            LEFT JOIN permissions p ON p.id = rp.permission_id AND p.active
            WHERE ur.user_id = :id
            ORDER BY r.sort_order, r.key
            """.trimIndent(),
        )
            .param("id", userId)
            .query { rs ->
                val role = roles.getOrPut(rs.getString("key")) {
                    RoleRow(
                        name = rs.getString("name"),
                        description = rs.getString("description"),
                        isSystem = rs.getBoolean("is_system"),
                        active = rs.getBoolean("active"),
                        sortOrder = rs.getInt("sort_order"),
                    )
                }
                val permission = rs.getString("permission_key")
                if (permission != null) role.permissions[permission] = PermissionScope.valueOf(rs.getString("scope"))
            }

        val user = AccessUser(
            id = userId,
            username = account.username,
            active = !account.disabled,
            roles = roles.map { (key, row) ->
                Role(
                    key = RoleKey.of(key),
                    name = row.name,
                    description = row.description,
                    permissions = row.permissions,
                    isSystem = row.isSystem,
                    active = row.active,
                    sortOrder = row.sortOrder,
                )
            },
            warehouseIds = account.warehouses,
        )
        return ActorSnapshot(
            actor = Actor(user = user, displayName = account.name, mustChangePassword = account.mustChangePassword),
            passwordChangedAt = account.passwordChangedAt,
        )
    }

    override fun recordLogin(userId: Long, at: Instant) {
        jdbc.sql("UPDATE users SET last_login_at = :at WHERE id = :id")
            .param("at", at.toSql())
            .param("id", userId)
            .update()
    }

    override fun updatePassword(userId: Long, passwordHash: String, at: Instant) {
        jdbc.sql(
            """
            UPDATE users
            SET password_hash = :hash, password_changed_at = :at, must_change_password = FALSE, updated_at = :at
            WHERE id = :id
            """.trimIndent(),
        )
            .param("hash", passwordHash)
            .param("at", at.toSql())
            .param("id", userId)
            .update()
    }

    private class RoleRow(
        val name: String,
        val description: String?,
        val isSystem: Boolean,
        val active: Boolean,
        val sortOrder: Int,
        val permissions: MutableMap<String, PermissionScope> = linkedMapOf(),
    )

    private fun ResultSet.toAccount() = UserAccount(
        id = getLong("id"),
        username = getString("username"),
        name = getString("name"),
        passwordHash = getString("password_hash"),
        disabled = getBoolean("disabled"),
        mustChangePassword = getBoolean("must_change_password"),
        passwordChangedAt = instant("password_changed_at"),
    )

    private companion object {
        const val SELECT_ACCOUNT =
            "SELECT id, username, name, password_hash, disabled, must_change_password, password_changed_at FROM users"
    }
}
