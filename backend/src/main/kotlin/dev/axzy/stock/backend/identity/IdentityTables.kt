package dev.axzy.stock.backend.identity

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TABLAS DE IDENTIDAD
 *
 * La definición en Exposed de las tablas que ya existen en las migraciones V1 y
 * V4. **El esquema lo gobierna Flyway, no Exposed**: aquí solo se declara la
 * forma para poder consultarlo. Si esta declaración y la migración se separan,
 * el error aparece en tiempo de ejecución, así que los nombres deben coincidir
 * exactamente.
 * ═══════════════════════════════════════════════════════════════════════════
 */

object UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val username = varchar("username", 80)
    val passwordHash = text("password_hash")
    val name = varchar("name", 160)
    val disabled = bool("disabled").default(false)
    val passwordChangedAt = timestampWithTimeZone("password_changed_at").nullable()
    val mustChangePassword = bool("must_change_password").default(false)
    val lastLoginAt = timestampWithTimeZone("last_login_at").nullable()
    val failedLoginCount = integer("failed_login_count").default(0)
    val lockedUntil = timestampWithTimeZone("locked_until").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object RolesTable : Table("roles") {
    val id = long("id").autoIncrement()

    /**
     * La clave estable e inmutable (`WAREHOUSE_MANAGER`).
     *
     * **Es lo que reemplaza al `roleId` numérico del legacy**, donde el código
     * hacía `if (role === 1)` en cuatro puntos y dependía de que el seed insertara
     * `admin` primero. Añadir un rol o reordenar el seed rompía los permisos en
     * silencio.
     */
    val key = varchar("key", 50)
    val name = varchar("name", 120)
    val description = text("description").nullable()
    val isSystem = bool("is_system").default(false)
    val active = bool("active").default(true)
    val sortOrder = integer("sort_order").default(0)

    override val primaryKey = PrimaryKey(id)
}

object PermissionsTable : Table("permissions") {
    val id = long("id").autoIncrement()
    val key = varchar("key", 80)
    val module = varchar("module", 40)
    val resource = varchar("resource", 40)
    val action = varchar("action", 40)
    val name = varchar("name", 160)
    val sensitive = bool("sensitive").default(false)
    val active = bool("active").default(true)

    override val primaryKey = PrimaryKey(id)
}

object RolePermissionsTable : Table("role_permissions") {
    val roleId = long("role_id").references(RolesTable.id)
    val permissionId = long("permission_id").references(PermissionsTable.id)

    /**
     * El alcance concedido, como texto.
     *
     * Se lee como `String` y se convierte a `PermissionScope` en el repositorio,
     * en lugar de declarar el enum aquí: un enum de Exposed ataría la capa de
     * datos al dominio, y el dominio debe poder evolucionar sin migrar la base.
     */
    val scope = text("scope")

    override val primaryKey = PrimaryKey(roleId, permissionId)
}

object UserRolesTable : Table("user_roles") {
    val userId = long("user_id").references(UsersTable.id)
    val roleId = long("role_id").references(RolesTable.id)
    val isPrimary = bool("is_primary").default(false)

    override val primaryKey = PrimaryKey(userId, roleId)
}

object UserWarehouseScopesTable : Table("user_warehouse_scopes") {
    val userId = long("user_id").references(UsersTable.id)
    val warehouseId = long("warehouse_id")

    override val primaryKey = PrimaryKey(userId, warehouseId)
}

object RefreshTokensTable : Table("refresh_tokens") {
    val id = long("id").autoIncrement()

    /** El hash SHA-256 del token, en hexadecimal. **Nunca el token.** */
    val tokenHash = char("token_hash", 64)
    val userId = long("user_id").references(UsersTable.id)
    val familyId = text("family_id")
    val generation = integer("generation").default(1)
    val issuedAt = timestampWithTimeZone("issued_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val rotatedAt = timestampWithTimeZone("rotated_at").nullable()
    val replacedByHash = char("replaced_by_hash", 64).nullable()
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val revocationReason = text("revocation_reason").nullable()
    val issuedIp = text("issued_ip").nullable()
    val issuedUserAgent = text("issued_user_agent").nullable()
    val lastUsedIp = text("last_used_ip").nullable()
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object LoginAttemptsTable : Table("login_attempts") {
    val id = long("id").autoIncrement()
    val attemptedAt = timestampWithTimeZone("attempted_at")

    /** El nombre tal como se escribió, **aunque el usuario no exista**. */
    val username = varchar("username", 80)
    val userId = long("user_id").nullable()
    val succeeded = bool("succeeded")
    val failureCode = text("failure_code").nullable()
    val ip = text("ip").nullable()
    val userAgent = text("user_agent").nullable()

    override val primaryKey = PrimaryKey(id)
}
