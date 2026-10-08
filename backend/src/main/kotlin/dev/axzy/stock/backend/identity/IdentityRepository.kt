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
        val row = UsersTable.selectAll().where { UsersTable.username eq username }
            .singleOrNull()
            ?: return@transaction null

        complete(row)
    }

    /** Busca un usuario por su identificador. */
    fun findById(id: Long): IdentityUser? = transaction(db) {
        val row = UsersTable.selectAll().where { UsersTable.id eq id }
            .singleOrNull()
            ?: return@transaction null

        complete(row)
    }

    /** Completa la fila con roles, permisos y bodegas. */
    private fun complete(row: ResultRow): IdentityUser {
        val userId = row[UsersTable.id]

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
        val permissions = mutableMapOf<String, String>()
        if (roleIds.isNotEmpty()) {
            val rolePermissions = RolePermissionsTable.selectAll().where { RolePermissionsTable.roleId inList roleIds }
                .map { it[RolePermissionsTable.permissionId] to it[RolePermissionsTable.scope] }

            if (rolePermissions.isNotEmpty()) {
                val permissionIds = rolePermissions.map { it.first }
                val keyById = PermissionsTable.selectAll().where { (PermissionsTable.id inList permissionIds) and (PermissionsTable.active eq true) }
                    .associate { it[PermissionsTable.id] to it[PermissionsTable.key] }

                rolePermissions.forEach { (permissionId, scope) ->
                    val key = keyById[permissionId] ?: return@forEach
                    // Se toma el alcance MÁS AMPLIO cuando el mismo permiso llega
                    // por dos roles: un usuario con JEFE_BODEGA (AREA) y AUDITOR
                    // (ALL) sobre el mismo permiso tiene ALL.
                    permissions[key] = scopeMoreBroad(permissions[key], scope)
                }
            }
        }

        // ── Paso 4: las bodegas del alcance AREA ─────────────────────────────
        val warehouses = UserWarehouseScopesTable.selectAll().where { UserWarehouseScopesTable.userId eq userId }
            .map { it[UserWarehouseScopesTable.warehouseId] }

        return IdentityUser(
            id = userId,
            username = row[UsersTable.username],
            name = row[UsersTable.name],
            passwordHash = row[UsersTable.passwordHash],
            disabled = row[UsersTable.disabled],
            roles = roles,
            permissions = permissions,
            warehouses = warehouses,
            mustChangePassword = row[UsersTable.mustChangePassword],
            passwordChangedAt = row[UsersTable.passwordChangedAt]?.toInstant(),
            lockedUntil = row[UsersTable.lockedUntil]?.toInstant(),
            failedLoginCount = row[UsersTable.failedLoginCount],
            lastLoginAt = row[UsersTable.lastLoginAt]?.toInstant(),
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
            // Igual que en los refresh tokens: la columna es `inet` y un nombre de
            // host no es válido. Se registra el intento sin la IP en ese caso, en
            // lugar de perder el registro entero.
            it[LoginAttemptsTable.ip] = ip?.takeIf { isDirectionIp(it) }
            it[LoginAttemptsTable.userAgent] = userAgent
        }

        // Si el usuario no existe no hay contador que incrementar. El intento se
        // registra igual, para poder detectar el ataque contra nombres válidos.
        if (userId == null) return@transaction FailedLoginOutcome(0, null)

        val row = UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()
            ?: return@transaction FailedLoginOutcome(0, null)

        val failures = row[UsersTable.failedLoginCount] + 1
        val lock = if (failures >= MAX_FAILED_ATTEMPTS) now.plusSeconds(LOCKOUT_SECONDS) else null

        UsersTable.update({ UsersTable.id eq userId }) {
            it[failedLoginCount] = failures
            it[lockedUntil] = lock?.aOffsetDateTime()
        }

        // Se registra el bloqueo: un bloqueo sin traza es indistinguible de un
        // fallo del sistema.
        if (lock != null) {
            logger.warn(
                "Cuenta '{}' bloqueada {} segundos tras {} intentos fallidos",
                username,
                LOCKOUT_SECONDS,
                failures,
            )
        }

        FailedLoginOutcome(failures, lock)
    }

    /**
     * Cambia la contraseña y marca la fecha del cambio.
     *
     * `password_changed_at` es lo que invalida las sesiones anteriores: si el
     * token se emitió **antes** de este instante, deja de servir. Sin actualizar
     * esta columna, «cambiar la contraseña cierra las demás sesiones» sería una
     * promesa vacía.
     *
     * También se limpia el bloqueo: quien acaba de demostrar que conoce la
     * contraseña nueva no debe seguir bloqueado por los intentos fallidos con la
     * vieja.
     */
    fun updatePassword(userId: Long, newHash: String, at: Instant = Instant.now()) =
        transaction(db) {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[passwordHash] = newHash
                it[passwordChangedAt] = at.aOffsetDateTime()
                it[failedLoginCount] = 0
                it[lockedUntil] = null
                it[mustChangePassword] = false
            }
            Unit
        }

    /** Cuenta los fallos recientes de una IP. Frena la fuerza bruta distribuida. */
    fun countRecentFailuresByIp(ip: String, since: Instant): Long = transaction(db) {
        if (!isDirectionIp(ip)) return@transaction 0L
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
    ): Long {
        // ── POR QUÉ SE USA SQL DIRECTO ──────────────────────────────────────
        // `refresh_tokens.family_id` es de tipo `uuid` e `issued_ip` es `inet`.
        // El DSL de Exposed los envía como texto y PostgreSQL rechaza la
        // escritura con «column "family_id" is of type uuid but expression is of
        // type character varying».
        //
        // El cast explícito `?::uuid` es lo que lo resuelve, y el DSL no lo
        // expresa. Es el mismo problema que resolvió el módulo de catálogo con
        // `?::location_kind`.
        return dataSource.connection.use { connection ->
            // ── CONFIRMACIÓN EXPLÍCITA, Y ES OBLIGATORIA ────────────────────
            // El pool se construye con `autoCommit = false`, que es lo correcto
            // para las operaciones de la aplicación: cada una declara su
            // transacción. Una sentencia suelta por una conexión propia NO declara
            // ninguna, así que al cerrar la conexión PostgreSQL hace **ROLLBACK** y
            // la fila desaparece sin ningún error.
            //
            // Éste fue un defecto real: el inicio de sesión devolvía un refresh
            // token correcto, la tabla `refresh_tokens` quedaba VACÍA, y el
            // refresco posterior fallaba con «token no válido». Es el mismo error
            // que cometí en `IdentitySeeder.insertarPermisoCrudo`.
            connection.autoCommit = true

            connection.prepareStatement(
                """
                INSERT INTO refresh_tokens
                    (token_hash, user_id, family_id, generation, issued_at, expires_at,
                     issued_ip, issued_user_agent)
                VALUES (?, ?, ?::uuid, ?, now(), ?, ?::inet, ?)
                RETURNING id
                """.trimIndent(),
            ).use { st ->
                st.setString(1, tokenHash)
                st.setLong(2, userId)
                st.setString(3, familyId)
                st.setInt(4, generation)
                st.setTimestamp(5, java.sql.Timestamp.from(expiresAt))
                // `ip` solo si es una dirección de verdad: la columna es `inet` y
                // PostgreSQL RECHAZA un nombre de host. Un `"localhost"` hacía
                // fallar el inicio de sesión con un error interno opaco, que es la
                // peor forma de fallar: el usuario no puede entrar y el motivo no
                // se le puede explicar.
                st.setString(6, ip?.takeIf { isDirectionIp(it) })
                st.setString(7, userAgent)
                st.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong("id")
                }
            }
        }
    }

    /** Busca un refresh token por su hash. */
    fun findRefreshToken(tokenHash: String): StoredRefreshToken? = transaction(db) {
        RefreshTokensTable.selectAll().where { RefreshTokensTable.tokenHash eq tokenHash }
            .singleOrNull()
            ?.let { row ->
                StoredRefreshToken(
                    id = row[RefreshTokensTable.id],
                    tokenHash = row[RefreshTokensTable.tokenHash],
                    userId = row[RefreshTokensTable.userId],
                    familyId = row[RefreshTokensTable.familyId],
                    generation = row[RefreshTokensTable.generation],
                    issuedAt = row[RefreshTokensTable.issuedAt].toInstant(),
                    expiresAt = row[RefreshTokensTable.expiresAt].toInstant(),
                    rotatedAt = row[RefreshTokensTable.rotatedAt]?.toInstant(),
                    replacedByHash = row[RefreshTokensTable.replacedByHash],
                    revokedAt = row[RefreshTokensTable.revokedAt]?.toInstant(),
                    revocationReason = row[RefreshTokensTable.revocationReason],
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
        val rows = RefreshTokensTable.update({ RefreshTokensTable.tokenHash eq tokenHash }) {
            it[rotatedAt] = at.aOffsetDateTime()
            it[RefreshTokensTable.replacedByHash] = replacedByHash
        }
        if (rows != 1) {
            // No debería ocurrir: el token se acaba de leer y se rota dentro de la
            // misma operación. Si pasa, hay una carrera entre dos refrescos
            // simultáneos y conviene saberlo.
            logger.warn("Se esperaba rotar 1 token y se rotaron {}", rows)
        }
        Unit
    }

    /**
     * El siguiente número de generación dentro de una familia.
     *
     * **Se usa SQL directo porque `family_id` es `uuid`.** Comparar la columna con
     * una cadena da «operator does not exist: uuid = character varying»: los tipos
     * tienen que coincidir y el DSL no aplica el cast. Es el mismo problema que
     * con `inet` y con los enums de PostgreSQL.
     */
    fun nextGeneration(familyId: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COALESCE(MAX(generation), 0) + 1 AS siguiente " +
                    "FROM refresh_tokens WHERE family_id = ?::uuid",
            ).use { st ->
                st.setString(1, familyId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt("siguiente") else 1 }
            }
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
        dataSource.connection.use { connection ->
            // Misma razón que en `storeRefreshToken`: sin confirmar, la revocación
            // se pierde y **la sesión comprometida seguiría viva**. Es el peor
            // sitio donde puede fallar en silencio.
            connection.autoCommit = true

            connection.prepareStatement("SELECT revoke_token_family(?::uuid, ?::revocation_reason)").use { st ->
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
        val families = RefreshTokensTable.selectAll().where { (RefreshTokensTable.userId eq userId) and (RefreshTokensTable.revokedAt.isNull()) }
            .map { it[RefreshTokensTable.familyId] }
            .distinct()

        families.sumOf { revokeFamily(it, reason) }
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
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.prepareStatement("DELETE FROM refresh_tokens WHERE expires_at < ?").use { st ->
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
        fun scopeMoreBroad(current: String?, new: String): String {
            if (current == null) return new
            val order = listOf("NONE", "OWN", "AREA", "ALL")
            val positionCurrent = order.indexOf(current)
            val positionNew = order.indexOf(new)
            if (positionNew < 0) return current
            if (positionCurrent < 0) return new
            return if (positionNew > positionCurrent) new else current
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

/**
 * Indica si un valor es una dirección IP válida.
 *
 * **Existe porque la columna es `inet` y PostgreSQL rechaza nombres de host.** El
 * valor que llega de `call.request.local.remoteHost` es `"localhost"` en
 * desarrollo, y guardarlo tal cual hacía fallar el inicio de sesión con un error
 * interno opaco: el usuario no podía entrar y el motivo no se le podía explicar.
 *
 * Se valida aquí y no en la base porque **la decisión es qué hacer**: se guarda
 * `NULL` en lugar de perder el registro entero. Un intento de inicio de sesión sin
 * IP sigue siendo información útil; un intento perdido, no.
 */
internal fun isDirectionIp(value: String): Boolean {
    if (value.isBlank()) return false
    // Se acepta IPv4 e IPv6 sin traer una dependencia: `InetAddress` no debe hacer
    // resolución DNS aquí, y `getByName` con un nombre SÍ la haría.
    if (value == "localhost") return false
    return runCatching {
        val parts = value.split('.')
        if (parts.size == 4) {
            parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
        } else {
            // IPv6: contiene `:` y solo caracteres hexadecimales.
            value.contains(':') && value.all { it.isDigit() || it in "abcdefABCDEF:" }
        }
    }.getOrDefault(false)
}
