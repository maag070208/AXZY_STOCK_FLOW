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

/** Una celda de la matriz: qué alcance tiene un rol sobre un permiso. */
data class RoleGrant(val roleKey: String, val permissionKey: String, val scope: PermissionScope)

/** La versión vigente de una pieza de configuración de acceso, con la huella de su contenido. */
data class ConfigVersion(val version: Int, val fingerprint: String)

enum class ConfigKind(val dbValue: String) {
    PERMISSION_MATRIX("permission_matrix"),
    POLICIES("policies"),
}

/** Donde vive el catálogo de acceso: permisos, roles, matriz y sus versiones. */
interface AccessCatalogStore {
    /** Deja el catálogo de permisos igual que el del código. Devuelve cuántos cambió o creó. */
    fun syncPermissions(permissions: List<Permission>): Int

    /** Crea los roles que falten. No toca los existentes: un administrador puede haberlos editado. */
    fun insertMissingRoles(roles: List<Role>): Int

    /** Concede las celdas que falten. No toca las existentes, por la misma razón. */
    fun insertMissingGrants(grants: List<RoleGrant>): Int

    /** La matriz tal como está en la base, que es la que decide. */
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

/** No hay ningún usuario y no se indicó con qué contraseña crear el primero. */
class MissingBootstrapPassword(message: String) : IllegalStateException(message)

/**
 * Prepara la identidad al arrancar: sincroniza el catálogo de permisos con el del código, crea los
 * roles base, versiona la matriz y, si la base no tiene ningún usuario, crea el primer
 * administrador obligándolo a cambiar la contraseña.
 *
 * **El catálogo de permisos es código, no una migración**: añadir un permiso es añadir una línea a
 * [PermissionCatalog], y esta clase lo lleva a la base. La MATRIZ, en cambio, es de la base: lo que
 * aquí se siembra es el punto de partida, y un cambio hecho por un administrador no se pisa.
 *
 * Es idempotente: ejecutarla dos veces no cambia nada la segunda.
 */
class BootstrapIdentity(
    private val store: AccessCatalogStore,
    private val hasher: PasswordHasher,
    private val adminUsername: String,
    /** Solo se usa si la base no tiene usuarios. Después se puede (y se debe) retirar del entorno. */
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
            reason = "Sincronización del catálogo al arrancar",
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
            reason = "Políticas declaradas en el código",
        )

        return BootstrapResult(
            versions = AccessVersions(matrix, policies),
            permissionsSynced = permissionsSynced,
            rolesCreated = rolesCreated,
            grantsCreated = grantsCreated,
            adminCreated = createFirstAdminIfNeeded(),
        )
    }

    /** Registra una versión nueva solo si el contenido cambió respecto de la última. */
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
                "La base no tiene ningún usuario y falta ADMIN_BOOTSTRAP_PASSWORD. " +
                    "Defínela para crear el primer administrador; se le exigirá cambiarla al entrar.",
            )
        val validation = PasswordPolicy.validate(password, adminUsername)
        if (validation is PasswordValidation.Invalid) {
            throw MissingBootstrapPassword(
                "ADMIN_BOOTSTRAP_PASSWORD no cumple la política de contraseñas: " + PasswordPolicy.explain(validation),
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
