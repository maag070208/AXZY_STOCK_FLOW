package dev.axzy.stock.adapters.persistence.identity

import dev.axzy.stock.application.identity.AccessCatalogStore
import dev.axzy.stock.application.identity.ConfigKind
import dev.axzy.stock.application.identity.ConfigVersion
import dev.axzy.stock.application.identity.RoleGrant
import dev.axzy.stock.domain.access.Permission
import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.access.Role
import org.springframework.jdbc.core.simple.JdbcClient
import tools.jackson.databind.json.JsonMapper

/**
 * El catálogo de acceso en PostgreSQL.
 *
 * **Cada sincronización es UNA sentencia**, sea cual sea el tamaño del catálogo: la lista viaja
 * como un documento JSON y la base la expande con `jsonb_to_recordset`. Una sentencia por permiso
 * y por celda serían varios cientos de viajes a la base en cada arranque.
 */
class JdbcAccessCatalogStore(
    private val jdbc: JdbcClient,
    private val json: JsonMapper,
) : AccessCatalogStore {

    override fun syncPermissions(permissions: List<Permission>): Int =
        jdbc.sql(
            """
            INSERT INTO permissions (key, module, resource, action, name, description, scopes, sensitive, active, sort_order)
            SELECT x.key, x.module, x.resource, x.action, x.name, x.description,
                   x.scopes::permission_scope[], x.sensitive, x.active, x.sort_order
            FROM jsonb_to_recordset(CAST(:catalog AS jsonb)) AS x(
                key text, module text, resource text, action text, name text, description text,
                scopes text[], sensitive boolean, active boolean, sort_order int
            )
            ON CONFLICT (key) DO UPDATE SET
                module = EXCLUDED.module, resource = EXCLUDED.resource, action = EXCLUDED.action,
                name = EXCLUDED.name, description = EXCLUDED.description, scopes = EXCLUDED.scopes,
                sensitive = EXCLUDED.sensitive, active = EXCLUDED.active, sort_order = EXCLUDED.sort_order
            -- Sin esta condición, cada arranque reescribiría todas las filas aunque nada hubiera cambiado.
            WHERE (permissions.module, permissions.resource, permissions.action, permissions.name,
                   permissions.description, permissions.scopes, permissions.sensitive, permissions.active,
                   permissions.sort_order)
                  IS DISTINCT FROM
                  (EXCLUDED.module, EXCLUDED.resource, EXCLUDED.action, EXCLUDED.name,
                   EXCLUDED.description, EXCLUDED.scopes, EXCLUDED.sensitive, EXCLUDED.active,
                   EXCLUDED.sort_order)
            """.trimIndent(),
        )
            .param(
                "catalog",
                json.writeValueAsString(
                    permissions.map {
                        mapOf(
                            "key" to it.key,
                            "module" to it.module.name,
                            "resource" to it.resource,
                            "action" to it.action,
                            "name" to it.name,
                            "description" to it.description,
                            "scopes" to it.grantableScopes.map(PermissionScope::name),
                            "sensitive" to it.sensitive,
                            "active" to it.active,
                            "sort_order" to it.sortOrder,
                        )
                    },
                ),
            )
            .update()

    override fun insertMissingRoles(roles: List<Role>): Int =
        jdbc.sql(
            """
            INSERT INTO roles (key, name, description, is_system, active, sort_order)
            SELECT x.key, x.name, x.description, x.is_system, x.active, x.sort_order
            FROM jsonb_to_recordset(CAST(:roles AS jsonb)) AS x(
                key text, name text, description text, is_system boolean, active boolean, sort_order int
            )
            ON CONFLICT (key) DO NOTHING
            """.trimIndent(),
        )
            .param(
                "roles",
                json.writeValueAsString(
                    roles.map {
                        mapOf(
                            "key" to it.key.value,
                            "name" to it.name,
                            "description" to it.description,
                            "is_system" to it.isSystem,
                            "active" to it.active,
                            "sort_order" to it.sortOrder,
                        )
                    },
                ),
            )
            .update()

    override fun insertMissingGrants(grants: List<RoleGrant>): Int =
        jdbc.sql(
            """
            INSERT INTO role_permissions (role_id, permission_id, scope)
            SELECT r.id, p.id, x.scope::permission_scope
            FROM jsonb_to_recordset(CAST(:grants AS jsonb)) AS x(role_key text, permission_key text, scope text)
            JOIN roles r ON r.key = x.role_key
            JOIN permissions p ON p.key = x.permission_key
            ON CONFLICT (role_id, permission_id) DO NOTHING
            """.trimIndent(),
        )
            .param(
                "grants",
                json.writeValueAsString(
                    grants.map { mapOf("role_key" to it.roleKey, "permission_key" to it.permissionKey, "scope" to it.scope.name) },
                ),
            )
            .update()

    override fun currentGrants(): List<RoleGrant> =
        jdbc.sql(
            """
            SELECT r.key AS role_key, p.key AS permission_key, rp.scope
            FROM role_permissions rp
            JOIN roles r ON r.id = rp.role_id
            JOIN permissions p ON p.id = rp.permission_id
            """.trimIndent(),
        )
            .query { rs, _ ->
                RoleGrant(rs.getString("role_key"), rs.getString("permission_key"), PermissionScope.valueOf(rs.getString("scope")))
            }
            .list()

    override fun latestVersion(kind: ConfigKind): ConfigVersion? =
        jdbc.sql(
            """
            SELECT version, snapshot ->> 'fingerprint' AS fingerprint
            FROM config_versions WHERE kind = :kind ORDER BY version DESC LIMIT 1
            """.trimIndent(),
        )
            .param("kind", kind.dbValue)
            .query { rs, _ -> ConfigVersion(rs.getInt("version"), rs.getString("fingerprint") ?: "") }
            .optional().orElse(null)

    override fun recordVersion(kind: ConfigKind, version: Int, fingerprint: String, snapshot: Map<String, Any?>, reason: String) {
        jdbc.sql(
            """
            INSERT INTO config_versions (kind, version, snapshot, reason)
            VALUES (:kind, :version, CAST(:snapshot AS jsonb), :reason)
            """.trimIndent(),
        )
            .param("kind", kind.dbValue)
            .param("version", version)
            .param("snapshot", json.writeValueAsString(snapshot + ("fingerprint" to fingerprint)))
            .param("reason", reason)
            .update()
    }

    override fun countUsers(): Long = jdbc.sql("SELECT count(*) FROM users").query(Long::class.java).single()

    override fun createUser(username: String, name: String, passwordHash: String, mustChangePassword: Boolean, roleKeys: List<String>): Long {
        val userId = jdbc.sql(
            """
            INSERT INTO users (username, password_hash, name, password_changed_at, must_change_password)
            VALUES (:username, :hash, :name, now(), :mustChange)
            RETURNING id
            """.trimIndent(),
        )
            .param("username", username)
            .param("hash", passwordHash)
            .param("name", name)
            .param("mustChange", mustChangePassword)
            .query(Long::class.java)
            .single()

        jdbc.sql(
            """
            INSERT INTO user_roles (user_id, role_id, is_primary)
            SELECT :userId, r.id, r.key = :primary
            FROM roles r
            WHERE r.key = ANY(string_to_array(:keys, ','))
            """.trimIndent(),
        )
            .param("userId", userId)
            .param("primary", roleKeys.firstOrNull() ?: "")
            .param("keys", roleKeys.joinToString(","))
            .update()
        return userId
    }
}
