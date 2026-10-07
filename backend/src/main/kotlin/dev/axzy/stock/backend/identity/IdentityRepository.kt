package dev.axzy.stock.backend.identity

import dev.axzy.stock.backend.config.logger
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPOSITORIO DE IDENTIDAD
 *
 * Carga el usuario con sus roles, permisos y bodegas, y gestiona los refresh
 * tokens.
 *
 * **Las consultas son deliberadamente simples y en varios pasos en lugar de un
 * `JOIN` grande.** Cargar los permisos de un usuario son tres consultas
 * pequeñas sobre tablas indexadas; unirlas todas en una sola daría una consulta
 * más difícil de leer y de depurar, y el ahorro sería irrelevante: esto se
 * ejecuta **una vez por inicio de sesión**, no en cada petición.
 *
 * **Todo lo que lee un permiso se hace en UNA transacción.** El legacy tenía el
 * atajo `if (role === 1)` repartido por los controladores, así que la decisión se
 * tomaba con datos leídos en momentos distintos y podía quedar incoherente.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** El usuario completo, con todo lo que hace falta para autorizar. */
data class IdentityUser(
    val id: Long,
    val username: String,
    val name: String,
    /** El hash de la contraseña. **Nunca sale de esta capa.** */
    val passwordHash: String,
    val disabled: Boolean,
    val roles: List<String>,
    /** Permiso → alcance concedido. Solo aparecen los concedidos. */
    val permissions: Map<String, String>,
    /** Bodegas que resuelven el alcance `AREA`. */
    val warehouses: List<Long>,
    val mustChangePassword: Boolean,
    val passwordChangedAt: Instant?,
    val lockedUntil: Instant?,
    val failedLoginCount: Int,
    val lastLoginAt: Instant?,
) {
    /** Indica si la cuenta está bloqueada temporalmente. */
    fun isLocked(now: Instant): Boolean = lockedUntil?.let { it.isAfter(now) } ?: false

    /**
     * Indica si un token es más antiguo que la contraseña actual.
     *
     * Es lo que hace real la promesa de «cambiar la contraseña cierra las demás
     * sesiones»: si la contraseña se cambió **después** de emitirse el token, el
     * token no debe seguir sirviendo.
     */
    fun isTokenOlderThanPassword(tokenIssuedAt: Instant): Boolean =
        passwordChangedAt?.let { it.isAfter(tokenIssuedAt) } ?: false
}

/** Un refresh token tal como está guardado. */
data class StoredRefreshToken(
    val id: Long,
    val tokenHash: String,
    val userId: Long,
    val familyId: String,
    val generation: Int,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val rotatedAt: Instant?,
    val replacedByHash: String?,
    val revokedAt: Instant?,
    val revocationReason: String?,
)

/** Cuántos fallos acumula una cuenta y hasta cuándo está bloqueada. */
data class FailedLoginOutcome(
    val failedCount: Int,
    val lockedUntil: Instant?,
)

class IdentityRepository(private val dataSource: DataSource) {

    /**
     * La base de datos de Exposed, creada **una sola vez**.
     *
     * `transaction {}` espera un `Database` de Exposed, no un `DataSource`. Se
     * construye aquí y se reutiliza: crear uno por operación registraría un
     * `Database` nuevo en cada llamada y Exposed avisa de que eso es un error.
     */
    private val db: Database = Database.connect(dataSource)

    /**
     * Busca un usuario por su nombre, con roles, permisos y bodegas.
     *
     * Todo ocurre en una transacción, así que la decisión de autorización nunca
     * se toma sobre un estado a medias.
     */
    fun findByUsername(username: String): IdentityUser? = transaction(db) {
        val fila = UsersTable.selectAll().where { UsersTable.username eq username }
            .singleOrNull()
            ?: return@transaction null

        completar(fila)
    }

    /** Busca un usuario por su identificador. */
    fun findById(id: Long): IdentityUser? = transaction(db) {
        val fila = UsersTable.selectAll().where { UsersTable.id eq id }
            .singleOrNull()
            ?: return@transaction null

        completar(fila)
    }

    /** Completa la fila con roles, permisos y bodegas. */
    private fun completar(fila: ResultRow): IdentityUser {
        val userId = fila[UsersTable.id]

        // ── Paso 1: los identificadores de los roles ─────────────────────────
        val roleIds = UserRolesTable.selectAll().where { UserRolesTable.userId eq userId }
            .map { it[UserRolesTable.roleId] }

        // ── Paso 2: las claves de los roles activos ──────────────────────────
        val roles = if (roleIds.isEmpty()) {
            emptyList()
        } else {
            RolesTable.selectAll().where { (RolesTable.id inList roleIds) and (RolesTable.active eq true) }
                .map { it[RolesTable.key] }
        }

        // ── Paso 3: los permisos con su alcance ──────────────────────────────
        val permisos = mutableMapOf<String, String>()
        if (roleIds.isNotEmpty()) {
            val permisosDeRoles = RolePermissionsTable.selectAll().where { RolePermissionsTable.roleId inList roleIds }
                .map { it[RolePermissionsTable.permissionId] to it[RolePermissionsTable.scope] }

            if (permisosDeRoles.isNotEmpty()) {
                val idsDePermiso = permisosDeRoles.map { it.first }
                val clavePorId = PermissionsTable.selectAll().where { (PermissionsTable.id inList idsDePermiso) and (PermissionsTable.active eq true) }
                    .associate { it[PermissionsTable.id] to it[PermissionsTable.key] }

                permisosDeRoles.forEach { (permissionId, alcance) ->
                    val clave = clavePorId[permissionId] ?: return@forEach
                    // Se toma el alcance MÁS AMPLIO cuando el mismo permiso llega
                    // por dos roles: un usuario con JEFE_BODEGA (AREA) y AUDITOR
                    // (ALL) sobre el mismo permiso tiene ALL.
                    permisos[clave] = alcanceMasAmplio(permisos[clave], alcance)
                }
            }
        }

        // ── Paso 4: las bodegas del alcance AREA ─────────────────────────────
        val bodegas = UserWarehouseScopesTable.selectAll().where { UserWarehouseScopesTable.userId eq userId }
            .map { it[UserWarehouseScopesTable.warehouseId] }

        return IdentityUser(
            id = userId,
            username = fila[UsersTable.username],
            name = fila[UsersTable.name],
            passwordHash = fila[UsersTable.passwordHash],
            disabled = fila[UsersTable.disabled],
            roles = roles,
            permissions = permisos,
            warehouses = bodegas,
            mustChangePassword = fila[UsersTable.mustChangePassword],
            passwordChangedAt = fila[UsersTable.passwordChangedAt]?.toInstant(),
            lockedUntil = fila[UsersTable.lockedUntil]?.toInstant(),
            failedLoginCount = fila[UsersTable.failedLoginCount],
            lastLoginAt = fila[UsersTable.lastLoginAt]?.toInstant(),
        )
    }

    /**
     * Registra un inicio de sesión correcto.
     *
     * Reinicia el contador de fallos: si no, un usuario que falló tres veces y
     * luego entró bien seguiría acumulando hacia el bloqueo.
     */
    fun recordSuccessfulLogin(userId: Long, at: Instant = Instant.now()) = transaction(db) {
        UsersTable.update({ UsersTable.id eq userId }) {
            it[lastLoginAt] = at.aOffsetDateTime()
            it[failedLoginCount] = 0
            it[lockedUntil] = null
        }
        Unit
    }

    /**
     * Registra un intento fallido y decide si hay que bloquear.
     *
     * **Se bloquea por un tiempo, no para siempre.** Un bloqueo permanente
     * convierte la fuerza bruta en una denegación de servicio contra el usuario
     * legítimo: basta con que un atacante conozca su nombre de usuario para
     * dejarlo fuera de forma indefinida. El bloqueo temporal limita el ataque sin
     * darle esa palanca.
     */
    fun recordFailedLogin(
        username: String,
        userId: Long?,
        failureCode: String,
        ip: String?,
        userAgent: String?,
        now: Instant = Instant.now(),
    ): FailedLoginOutcome = transaction(db) {
        LoginAttemptsTable.insert {
            it[attemptedAt] = now.aOffsetDateTime()
            it[LoginAttemptsTable.username] = username
            it[LoginAttemptsTable.userId] = userId
            it[succeeded] = false
            it[LoginAttemptsTable.failureCode] = failureCode
            it[LoginAttemptsTable.ip] = ip
            it[LoginAttemptsTable.userAgent] = userAgent
        }

        // Si el usuario no existe no hay contador que incrementar. El intento se
        // registra igual, para poder detectar el ataque contra nombres válidos.
        if (userId == null) return@transaction FailedLoginOutcome(0, null)

        val fila = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()
            ?: return@transaction FailedLoginOutcome(0, null)

        val fallos = fila[UsersTable.failedLoginCount] + 1
        val bloqueo = if (fallos >= MAX_FAILED_ATTEMPTS) now.plusSeconds(LOCKOUT_SECONDS) else null

        UsersTable.update({ UsersTable.id eq userId }) {
            it[failedLoginCount] = fallos
            it[lockedUntil] = bloqueo?.aOffsetDateTime()
        }

        // Se registra el bloqueo: un bloqueo sin traza es indistinguible de un
        // fallo del sistema.
        if (bloqueo != null) {
            logger.warn(
                "Cuenta '{}' bloqueada {} segundos tras {} intentos fallidos",
                username,
                LOCKOUT_SECONDS,
                fallos,
            )
        }

        FailedLoginOutcome(fallos, bloqueo)
    }

    /** Cuenta los fallos recientes de una IP. Frena la fuerza bruta distribuida. */
    fun countRecentFailuresByIp(ip: String, since: Instant): Long = transaction(db) {
        LoginAttemptsTable.selectAll().where {
            (LoginAttemptsTable.ip eq ip) and
                (LoginAttemptsTable.succeeded eq false) and
                (LoginAttemptsTable.attemptedAt greaterEq since.aOffsetDateTime())
        }.count()
    }

    // ── Refresh tokens ──────────────────────────────────────────────────────

    /**
     * Guarda un refresh token recién emitido.
     *
     * **Se guarda el hash, no el token.** Un volcado de esta tabla no permite
     * suplantar a nadie.
     */
    fun storeRefreshToken(
        tokenHash: String,
        userId: Long,
        familyId: String,
        generation: Int,
        expiresAt: Instant,
        ip: String?,
        userAgent: String?,
    ): Long = transaction(db) {
        RefreshTokensTable.insert {
            it[RefreshTokensTable.tokenHash] = tokenHash
            it[RefreshTokensTable.userId] = userId
            it[RefreshTokensTable.familyId] = familyId
            it[RefreshTokensTable.generation] = generation
            it[issuedAt] = Instant.now().aOffsetDateTime()
            it[RefreshTokensTable.expiresAt] = expiresAt.aOffsetDateTime()
            it[issuedIp] = ip
            it[issuedUserAgent] = userAgent
        } get RefreshTokensTable.id
    }

    /** Busca un refresh token por su hash. */
    fun findRefreshToken(tokenHash: String): StoredRefreshToken? = transaction(db) {
        RefreshTokensTable.selectAll().where { RefreshTokensTable.tokenHash eq tokenHash }
            .singleOrNull()
            ?.let { fila ->
                StoredRefreshToken(
                    id = fila[RefreshTokensTable.id],
                    tokenHash = fila[RefreshTokensTable.tokenHash],
                    userId = fila[RefreshTokensTable.userId],
                    familyId = fila[RefreshTokensTable.familyId],
                    generation = fila[RefreshTokensTable.generation],
                    issuedAt = fila[RefreshTokensTable.issuedAt].toInstant(),
                    expiresAt = fila[RefreshTokensTable.expiresAt].toInstant(),
                    rotatedAt = fila[RefreshTokensTable.rotatedAt]?.toInstant(),
                    replacedByHash = fila[RefreshTokensTable.replacedByHash],
                    revokedAt = fila[RefreshTokensTable.revokedAt]?.toInstant(),
                    revocationReason = fila[RefreshTokensTable.revocationReason],
                )
            }
    }

    /**
     * Marca un token como rotado.
     *
     * **No se marca como revocado.** Son cosas distintas y la base lo impone con
     * un `CHECK` (`refresh_tokens_rotated_xor_revoked`): el token rotado sigue
     * siendo un registro válido de la cadena. Confundirlos es lo que hace que un
     * sistema reporte «token revocado» cuando en realidad detectó un robo, y
     * entonces no revoca la familia.
     */
    fun markRotated(
        tokenHash: String,
        replacedByHash: String,
        at: Instant = Instant.now(),
    ) = transaction(db) {
        val filas = RefreshTokensTable.update({ RefreshTokensTable.tokenHash eq tokenHash }) {
            it[rotatedAt] = at.aOffsetDateTime()
            it[RefreshTokensTable.replacedByHash] = replacedByHash
        }
        if (filas != 1) {
            // No debería ocurrir: el token se acaba de leer y se rota dentro de la
            // misma operación. Si pasa, hay una carrera entre dos refrescos
            // simultáneos y conviene saberlo.
            logger.warn("Se esperaba rotar 1 token y se rotaron {}", filas)
        }
        Unit
    }

    /** El siguiente número de generación dentro de una familia. */
    fun nextGeneration(familyId: String): Int = transaction(db) {
        (RefreshTokensTable.selectAll().where { RefreshTokensTable.familyId eq familyId }
            .maxOfOrNull { it[RefreshTokensTable.generation] } ?: 0) + 1
    }

    /**
     * Revoca una familia completa llamando a la función SQL.
     *
     * **La revocación por reuso debe ser atómica.** Si el proceso falla a mitad de
     * un `UPDATE` desde código, quedarían tokens vivos de una sesión recién
     * declarada comprometida, que es peor que el propio reuso. Por eso la lógica
     * vive en la base y es una sola sentencia.
     */
    fun revokeFamily(familyId: String, reason: String): Int =
        dataSource.connection.use { conexion ->
            conexion.prepareStatement("SELECT revoke_token_family(?::uuid, ?::revocation_reason)").use { st ->
                st.setString(1, familyId)
                st.setString(2, reason)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
        }

    /** Revoca el token presentado **y toda su familia**. Se usa al cerrar sesión. */
    fun revokeByHash(tokenHash: String, reason: String): Int = transaction(db) {
        val familyId = RefreshTokensTable.selectAll().where { RefreshTokensTable.tokenHash eq tokenHash }
            .singleOrNull()
            ?.get(RefreshTokensTable.familyId)
            ?: return@transaction 0

        revokeFamily(familyId, reason)
    }

    /** Revoca todas las familias de un usuario. Se usa al cambiar la contraseña. */
    fun revokeAllForUser(userId: Long, reason: String): Int = transaction(db) {
        val familias = RefreshTokensTable.selectAll().where { (RefreshTokensTable.userId eq userId) and (RefreshTokensTable.revokedAt.isNull()) }
            .map { it[RefreshTokensTable.familyId] }
            .distinct()

        familias.sumOf { revokeFamily(it, reason) }
    }

    /**
     * Borra los tokens vencidos antes de un instante. Para el job de limpieza.
     *
     * **Se hace con SQL directo y no con `deleteWhere`.** El bloque de
     * `deleteWhere` de esta versión de Exposed no expone los operadores de
     * comparación: intentarlo da un `Unresolved reference` sobre `less` y
     * `lessEq` que no explica el motivo. Una sentencia SQL explícita es más clara
     * y no depende del alcance que decida abrir el DSL.
     */
    fun purgeExpiredTokens(olderThan: Instant): Int =
        dataSource.connection.use { conexion ->
            conexion.prepareStatement("DELETE FROM refresh_tokens WHERE expires_at < ?").use { st ->
                st.setObject(1, olderThan.aOffsetDateTime())
                st.executeUpdate()
            }
        }

    companion object {
        /**
         * Fallos antes de bloquear la cuenta.
         *
         * Cinco es el equilibrio: suficiente para equivocarse un par de veces sin
         * quedarse fuera, y suficientemente bajo para que la fuerza bruta no sea
         * viable.
         */
        const val MAX_FAILED_ATTEMPTS = 5

        /**
         * Duración del bloqueo: quince minutos.
         *
         * **Temporal y no permanente, a propósito**: un bloqueo permanente
         * convierte la fuerza bruta en una denegación de servicio contra el
         * usuario legítimo, porque basta con conocer su nombre de usuario para
         * dejarlo fuera de forma indefinida.
         */
        const val LOCKOUT_SECONDS = 15L * 60

        /** Compara dos alcances y devuelve el más amplio. */
        fun alcanceMasAmplio(actual: String?, nuevo: String): String {
            if (actual == null) return nuevo
            val orden = listOf("NONE", "OWN", "AREA", "ALL")
            val posicionActual = orden.indexOf(actual)
            val posicionNueva = orden.indexOf(nuevo)
            if (posicionNueva < 0) return actual
            if (posicionActual < 0) return nuevo
            return if (posicionNueva > posicionActual) nuevo else actual
        }
    }
}

/**
 * Convierte a la hora con zona que entiende Exposed.
 *
 * Se usa `OffsetDateTime` en UTC y no la hora local: guardar la hora local hace
 * que el histórico sea ambiguo en los cambios de horario de verano, y ese es
 * exactamente el defecto R-15 del legacy.
 */
private fun Instant.aOffsetDateTime(): OffsetDateTime =
    OffsetDateTime.ofInstant(this, ZoneOffset.UTC)
