package dev.axzy.stock.adapters.persistence.identity

import dev.axzy.stock.adapters.persistence.shared.instant
import dev.axzy.stock.application.identity.RoleAdminStore
import dev.axzy.stock.application.identity.RoleDetail
import dev.axzy.stock.application.identity.RoleGrantView
import dev.axzy.stock.application.identity.RoleView
import dev.axzy.stock.application.identity.parseScope
import dev.axzy.stock.application.identity.UserAdminStore
import dev.axzy.stock.application.identity.UserDetail
import dev.axzy.stock.application.identity.UserFilter
import dev.axzy.stock.application.identity.UserSummary
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * User administration on PostgreSQL (M07, docs/api/usuarios.md).
 *
 * The roles are read with `array_agg` in the same query as the user: requesting them per
 * row would be one query per user in a listing of two hundred accounts.
 *
 * **Changing the password does not need to invalidate anything by hand**: the credentials stamp
 * of the token is `password_changed_at`, so when it is updated the live tokens stop
 * matching and the session dies on its own (R3). Doing it "by hand" would also duplicate the rule.
 */
class JdbcUserAdmin(private val jdbc: JdbcClient) : UserAdminStore, RoleAdminStore {

    override fun page(filter: UserFilter, request: PageRequest): Page<UserSummary> {
        val where = mutableListOf("1 = 1")
        val params = mutableMapOf<String, Any?>()

        filter.query?.let {
            where += "(u.username ILIKE :q OR u.name ILIKE :q)"
            params["q"] = "%${it.trim()}%"
        }
        filter.active?.let {
            where += "u.disabled = :disabled"
            params["disabled"] = !it
        }
        filter.role?.let {
            where += "EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE ur.user_id = u.id AND r.key = :role)"
            params["role"] = it
        }
        val clause = "WHERE " + where.joinToString(" AND ")

        val total = query("SELECT count(*) FROM users u $clause", params) { rs, _ -> rs.getLong(1) }.single()
        val items = query(
            "$COLUMNS $FROM $clause ORDER BY u.username LIMIT :limit OFFSET :offset",
            params + mapOf("limit" to request.size, "offset" to request.offset),
        ) { rs, _ -> rs.toSummary() }

        return Page(items, request.page, request.size, total)
    }

    override fun find(id: Long): UserDetail? =
        query("$COLUMNS $FROM WHERE u.id = :id", mapOf("id" to id)) { rs, _ -> rs.toSummary() }
            .firstOrNull()
            ?.let { summary ->
                UserDetail(
                    summary = summary,
                    warehouseIds = query(
                        "SELECT warehouse_id FROM user_warehouse_scopes WHERE user_id = :id ORDER BY warehouse_id",
                        mapOf("id" to id),
                    ) { rs, _ -> rs.getLong(1) },
                    passwordChangedAt = query(
                        "SELECT password_changed_at FROM users WHERE id = :id",
                        mapOf("id" to id),
                    ) { rs, _ -> rs.instant("password_changed_at") }.firstOrNull(),
                )
            }

    override fun usernameExists(username: String): Boolean =
        query("SELECT 1 FROM users WHERE username = :username", mapOf("username" to username)) { _, _ -> 1 }.isNotEmpty()

    override fun insert(
        username: String,
        name: String,
        passwordHash: String,
        mustChangePassword: Boolean,
        roleKeys: List<String>,
    ): Long {
        val id = try {
            query(
                """
                INSERT INTO users (username, password_hash, name, must_change_password)
                VALUES (:username, :hash, :name, :mustChange)
                RETURNING id
                """.trimIndent(),
                mapOf(
                    "username" to username,
                    "hash" to passwordHash,
                    "name" to name,
                    "mustChange" to mustChangePassword,
                ),
            ) { rs, _ -> rs.getLong(1) }.single()
        } catch (error: DataIntegrityViolationException) {
            // The unique index decides in a race; the normal case is caught by the pre-check.
            if (error.message?.contains(USERNAME_INDEX) == true) {
                error("The user '$username' already exists (the use case checked it and the index confirmed it).")
            }
            throw error
        }
        replaceRoles(id, roleKeys)
        return id
    }

    override fun updateProfile(id: Long, name: String?, disabled: Boolean?) {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>("id" to id)
        name?.let { sets += "name = :name"; params["name"] = it }
        disabled?.let { sets += "disabled = :disabled"; params["disabled"] = it }
        if (sets.isEmpty()) return
        sets += "updated_at = now()"
        update("UPDATE users SET ${sets.joinToString(", ")} WHERE id = :id", params)
    }

    /**
     * Replaces the roles in **one statement**: removing the surplus ones and adding the
     * missing ones in two trips would leave, if something failed in the middle, a person with more
     * permissions than were meant to be given.
     */
    override fun replaceRoles(id: Long, roleKeys: List<String>) {
        update("DELETE FROM user_roles WHERE user_id = :id", mapOf("id" to id))
        if (roleKeys.isEmpty()) return
        update(
            """
            INSERT INTO user_roles (user_id, role_id)
            SELECT :id, r.id FROM roles r WHERE r.key = ANY(CAST(:roles AS text[]))
            """.trimIndent(),
            mapOf("id" to id, "roles" to "{${roleKeys.joinToString(",")}}"),
        )
    }

    override fun updatePassword(id: Long, passwordHash: String, mustChangePassword: Boolean, at: Instant) {
        update(
            """
            UPDATE users SET password_hash = :hash, password_changed_at = CAST(:at AS timestamptz),
                   must_change_password = :mustChange, updated_at = CAST(:at AS timestamptz)
            WHERE id = CAST(:id AS bigint)
            """.trimIndent(),
            mapOf(
                "hash" to passwordHash,
                "mustChange" to mustChangePassword,
                "at" to OffsetDateTime.ofInstant(at, ZoneOffset.UTC),
                "id" to id,
            ),
        )
    }

    override fun roleKeys(): List<String> = query("SELECT key FROM roles ORDER BY key", emptyMap()) { rs, _ -> rs.getString(1) }

    override fun roles(): List<RoleView> =
        query(
            """
            SELECT r.key, r.name, r.description,
                   (SELECT count(*) FROM role_permissions rp WHERE rp.role_id = r.id) AS permissions
            FROM roles r ORDER BY r.key
            """.trimIndent(),
            emptyMap(),
        ) { rs, _ ->
            RoleView(
                key = rs.getString("key"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                permissions = rs.getInt("permissions"),
            )
        }

    override fun permissionKeys(): List<String> =
        query("SELECT key FROM permissions ORDER BY key", emptyMap()) { rs, _ -> rs.getString(1) }

    override fun find(key: String): RoleDetail? =
        query(
            """
            SELECT r.key, r.name, r.description, r.is_system, r.active,
                   (SELECT count(*) FROM user_roles ur WHERE ur.role_id = r.id) AS users,
                   COALESCE((SELECT array_agg(p.key || '|' || rp.scope::text ORDER BY p.key)
                             FROM role_permissions rp JOIN permissions p ON p.id = rp.permission_id
                             WHERE rp.role_id = r.id), ARRAY[]::text[]) AS grants
            FROM roles r WHERE r.key = :key
            """.trimIndent(),
            mapOf("key" to key),
        ) { rs, _ ->
            @Suppress("UNCHECKED_CAST")
            val grants = (rs.getArray("grants")?.array as? Array<String>)?.toList() ?: emptyList()
            RoleDetail(
                key = rs.getString("key"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                isSystem = rs.getBoolean("is_system"),
                active = rs.getBoolean("active"),
                grants = grants.mapNotNull { grant ->
                    val parts = grant.split("|")
                    parseScope(parts.getOrNull(1))?.let { RoleGrantView(parts[0], it) }
                },
                users = rs.getInt("users"),
            )
        }.firstOrNull()

    override fun keyExists(key: String): Boolean =
        query("SELECT 1 FROM roles WHERE key = :key", mapOf("key" to key)) { _, _ -> 1 }.isNotEmpty()

    override fun insert(key: String, name: String, description: String?) {
        update(
            "INSERT INTO roles (key, name, description, is_system) VALUES (:key, :name, :description, FALSE)",
            mapOf("key" to key, "name" to name, "description" to description),
        )
    }

    override fun update(key: String, name: String?, description: String?, active: Boolean?) {
        val sets = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>("key" to key)
        name?.let { sets += "name = :name"; params["name"] = it }
        description?.let { sets += "description = :description"; params["description"] = it }
        active?.let { sets += "active = :active"; params["active"] = it }
        if (sets.isEmpty()) return
        sets += "updated_at = now()"
        update("UPDATE roles SET ${sets.joinToString(", ")} WHERE key = :key", params)
    }

    /**
     * Replaces the matrix in **two statements**: deleting the old cells and inserting the new ones.
     *
     * The permission/scope pairs travel as two parallel arrays and are crossed with
     * `unnest`, so that a matrix of twenty permissions is not twenty round trips to the database.
     */
    override fun replaceGrants(key: String, grants: List<RoleGrantView>) {
        update("DELETE FROM role_permissions WHERE role_id = (SELECT id FROM roles WHERE key = :key)", mapOf("key" to key))
        if (grants.isEmpty()) return
        update(
            """
            INSERT INTO role_permissions (role_id, permission_id, scope)
            SELECT r.id, p.id, CAST(u.scope AS permission_scope)
            FROM roles r
            CROSS JOIN unnest(CAST(:keys AS text[]), CAST(:scopes AS text[])) AS u(pkey, scope)
            JOIN permissions p ON p.key = u.pkey
            WHERE r.key = :key
            """.trimIndent(),
            mapOf(
                "key" to key,
                "keys" to "{${grants.joinToString(",") { it.permissionKey }}}",
                "scopes" to "{${grants.joinToString(",") { it.scope.name }}}",
            ),
        )
    }

    private fun ResultSet.toSummary(): UserSummary {
        @Suppress("UNCHECKED_CAST")
        val roles = (getArray("roles")?.array as? Array<String>)?.toList() ?: emptyList()
        return UserSummary(
            id = getLong("id"),
            username = getString("username"),
            name = getString("name"),
            disabled = getBoolean("disabled"),
            mustChangePassword = getBoolean("must_change_password"),
            roles = roles,
            lastLoginAt = instant("last_login_at"),
            createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        )
    }

    private fun <T> query(sql: String, params: Map<String, Any?>, mapper: (ResultSet, Int) -> T): List<T> {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.query(mapper).list()
    }

    private fun update(sql: String, params: Map<String, Any?>): Int {
        var spec = jdbc.sql(sql)
        params.forEach { (name, value) -> spec = spec.param(name, value) }
        return spec.update()
    }

    private companion object {
        const val USERNAME_INDEX = "users_username_key"

        /**
         * The roles in an array, in the same query as the user: it is what keeps
         * a listing of two hundred accounts still **two statements**.
         */
        val COLUMNS =
            """
            SELECT u.id, u.username, u.name, u.disabled, u.must_change_password,
                   u.last_login_at, u.created_at,
                   COALESCE((SELECT array_agg(r.key ORDER BY r.key)
                             FROM user_roles ur JOIN roles r ON r.id = ur.role_id
                             WHERE ur.user_id = u.id), ARRAY[]::varchar[]) AS roles
            """.trimIndent()

        const val FROM = "FROM users u"
    }
}
