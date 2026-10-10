package dev.axzy.stock.application.identity

import dev.axzy.stock.domain.access.BaseRole
import dev.axzy.stock.domain.access.Permission
import dev.axzy.stock.domain.access.PermissionCatalog
import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.access.Role
import dev.axzy.stock.domain.access.RoleDefinitions
import dev.axzy.stock.domain.access.StockPolicies
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.domain.identity.PasswordValidation
import java.security.MessageDigest

/** A cell of the matrix: what scope a role has over a permission. */
data class RoleGrant(val roleKey: String, val permissionKey: String, val scope: PermissionScope)

/** The version in force of an access configuration piece, with the fingerprint of its content. */
data class ConfigVersion(val version: Int, val fingerprint: String)

enum class ConfigKind(val dbValue: String) {
    PERMISSION_MATRIX("permission_matrix"),
    POLICIES("policies"),
}

/** Where the access catalog lives: permissions, roles, matrix and their versions. */
interface AccessCatalogStore {
    /** Leaves the permission catalog the same as the code's. Returns how many it changed or created. */
    fun syncPermissions(permissions: List<Permission>): Int

    /** Creates the missing roles. It does not touch existing ones: an administrator may have edited them. */
    fun insertMissingRoles(roles: List<Role>): Int

    /** Grants the missing cells. It does not touch existing ones, for the same reason. */
    fun insertMissingGrants(grants: List<RoleGrant>): Int

    /** The matrix as it is in the database, which is the one that decides. */
    fun currentGrants(): List<RoleGrant>

    fun latestVersion(kind: ConfigKind): ConfigVersion?

    fun recordVersion(kind: ConfigKind, version: Int, fingerprint: String, snapshot: Map<String, Any?>, reason: String)

    fun countUsers(): Long

    fun createUser(username: String, name: String, passwordHash: String, mustChangePassword: Boolean, roleKeys: List<String>): Long
}

data class AccessVersions(val matrix: Int, val policies: Int)

data class BootstrapResult(
    val versions: AccessVersions,
    val permissionsSynced: Int,
    val rolesCreated: Int,
    val grantsCreated: Int,
    val adminCreated: Boolean,
)

/** There is no user and it was not said with which password to create the first one. */
class MissingBootstrapPassword(message: String) : IllegalStateException(message)

/**
 * Prepares identity on startup: it syncs the permission catalog with the code's, creates the base
 * roles, versions the matrix and, if the database has no user, creates the first
 * administrator, forcing them to change the password.
 *
 * **The permission catalog is code, not a migration**: adding a permission means adding a line to
 * [PermissionCatalog], and this class carries it to the database. The MATRIX, in contrast, belongs to the database: what
 * is seeded here is the starting point, and a change made by an administrator is not overwritten.
 *
 * It is idempotent: running it twice does not change anything the second time.
 */
class BootstrapIdentity(
    private val store: AccessCatalogStore,
    private val hasher: PasswordHasher,
    private val adminUsername: String,
    /** Only used if the database has no users. Afterwards it can (and should) be removed from the environment. */
    private val adminPassword: String?,
) {
    fun execute(): BootstrapResult {
        val permissionsSynced = store.syncPermissions(PermissionCatalog.all)
        val rolesCreated = store.insertMissingRoles(RoleDefinitions.baseRoles)
        val grantsCreated = store.insertMissingGrants(
            RoleDefinitions.baseRoles.flatMap { role ->
                role.permissions
                    .filterValues { it.grants }
                    .map { (permission, scope) -> RoleGrant(role.key.value, permission, scope) }
            },
        )

        val matrix = versionOf(
            kind = ConfigKind.PERMISSION_MATRIX,
            lines = store.currentGrants().map { "${it.roleKey}|${it.permissionKey}|${it.scope}" },
            reason = "Catalog synchronization at startup",
        )
        val policies = versionOf(
            kind = ConfigKind.POLICIES,
            lines = StockPolicies.all.map { policy ->
                listOf(
                    policy.id, policy.action, policy.effect, policy.priority, policy.active, policy.shadowMode,
                    policy.roles.map { it.value }.sorted(),
                    policy.conditions.map { "${it.field} ${it.operator.symbol} ${it.value}" },
                ).joinToString("|")
            },
            reason = "Policies declared in code",
        )

        return BootstrapResult(
            versions = AccessVersions(matrix, policies),
            permissionsSynced = permissionsSynced,
            rolesCreated = rolesCreated,
            grantsCreated = grantsCreated,
            adminCreated = createFirstAdminIfNeeded(),
        )
    }

    /** Records a new version only if the content changed from the last one. */
    private fun versionOf(kind: ConfigKind, lines: List<String>, reason: String): Int {
        val sorted = lines.sorted()
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(sorted.joinToString("\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val latest = store.latestVersion(kind)
        if (latest?.fingerprint == fingerprint) return latest.version

        val next = (latest?.version ?: 0) + 1
        store.recordVersion(kind, next, fingerprint, mapOf("entries" to sorted), reason)
        return next
    }

    private fun createFirstAdminIfNeeded(): Boolean {
        if (store.countUsers() > 0) return false

        val password = adminPassword?.takeIf { it.isNotBlank() }
            ?: throw MissingBootstrapPassword(
                "The database has no users and ADMIN_BOOTSTRAP_PASSWORD is missing. " +
                    "Define it to create the first administrator; they will be required to change it on sign-in.",
            )
        val validation = PasswordPolicy.validate(password, adminUsername)
        if (validation is PasswordValidation.Invalid) {
            throw MissingBootstrapPassword(
                "ADMIN_BOOTSTRAP_PASSWORD does not meet the password policy: " + PasswordPolicy.explain(validation),
            )
        }
        store.createUser(
            username = adminUsername,
            name = "Administrador",
            passwordHash = hasher.hash(password),
            mustChangePassword = true,
            roleKeys = listOf(BaseRole.ADMIN.key.value),
        )
        return true
    }
}
