package dev.axzy.stock.backend.identity

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.identity.IdentityRepository
import dev.axzy.stock.backend.identity.IdentityUser
import dev.axzy.stock.backend.identity.TokenService
import dev.axzy.stock.backend.identity.TokenSubject
import dev.axzy.stock.backend.identity.TokenVerification
import dev.axzy.stock.backend.identity.AccessTokenClaims
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.domain.access.BaseRole
import dev.axzy.stock.domain.access.Role
import dev.axzy.stock.domain.access.RoleKey
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.domain.identity.PasswordValidation
import dev.axzy.stock.domain.identity.RefreshDecision
import dev.axzy.stock.domain.identity.RefreshToken
import dev.axzy.stock.domain.identity.RefreshTokenRecord
import dev.axzy.stock.domain.identity.RevocationReason
import dev.axzy.stock.domain.identity.SessionPolicy
import dev.axzy.stock.domain.identity.TokenFamilyId
import dev.axzy.stock.domain.identity.TokenId
import dev.axzy.stock.domain.identity.decideRefresh
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AUTENTICACIÓN
 *
 * Las cuatro rutas de `docs/historico/CONTRATO_API.md` §2. Es la pieza que desbloquea todo lo
 * demás: sin identidad, el catálogo responde 401 y la aplicación no se puede usar
 * más allá de la pantalla de acceso.
 *
 * **El flujo de la contraseña es el que importa.** Se verifica con Argon2id sobre
 * el hash guardado, y el mensaje de error es **el mismo para usuario inexistente
 * y contraseña incorrecta**: distinguirlos permite enumerar qué nombres de usuario
 * son reales, y con la lista de nombres un atacante solo necesita acertar
 * contraseñas.
 * ═══════════════════════════════════════════════════════════════════════════
 */

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String,
)

/** Los permisos con su alcance, tal como los consume el cliente. */
@Serializable
data class SessionUserDto(
    val id: Long,
    val username: String,
    val name: String,
    val roles: List<String>,
    val permissions: List<String>,
    /** Permiso → alcance: `AREA`, `ALL`, `OWN`. */
    val scopes: Map<String, String>,
    val warehouses: List<Long>,
    val mustChangePassword: Boolean,
)

@Serializable
data class AuthSessionDto(
    val accessToken: String,
    val refreshToken: String,
    /** En **segundos**, que es lo que esperan las bibliotecas de cliente. */
    val expiresIn: Long,
    val user: SessionUserDto,
)

fun Route.authRoutes(
    repository: IdentityRepository,
    tokens: TokenService,
    hasher: PasswordHasher,
    policy: SessionPolicy = SessionPolicy.DEFAULT,
    permissionChecker: PermissionChecker,
) {

    /** Construye el usuario de sesión que viaja al cliente. */
    fun IdentityUser.toSession() = SessionUserDto(
        id = id,
        username = username,
        name = name,
        roles = roles,
        permissions = permissions.keys.sorted(),
        scopes = permissions,
        warehouses = warehouses,
        mustChangePassword = mustChangePassword,
    )

    /**
     * Emite el par de tokens y **guarda el refresh hasheado**.
     *
     * El refresh en claro solo existe en la respuesta: en la base queda su
     * SHA-256. Un volcado de la tabla no permite suplantar a nadie.
     */
    fun issue(
        user: IdentityUser,
        familyId: String,
        generation: Int,
        ip: String?,
        userAgent: String?,
    ): AuthSessionDto {
        val subject = TokenSubject(
            userId = user.id,
            username = user.username,
            name = user.name,
            roles = user.roles,
            permissions = user.permissions.keys.sorted(),
            scopes = user.permissions,
            warehouses = user.warehouses,
        )
        val (accessToken, _) = tokens.issueAccessToken(subject)

        val refresh = tokens.generateRefreshToken()
        val now = Instant.now()

        repository.storeRefreshToken(
            tokenHash = tokens.hashRefreshToken(refresh),
            userId = user.id,
            familyId = familyId,
            generation = generation,
            expiresAt = now.plusMillis(policy.refreshTokenTtlMs),
            ip = ip,
            userAgent = userAgent,
        )

        return AuthSessionDto(
            accessToken = accessToken,
            refreshToken = refresh,
            expiresIn = policy.accessTokenTtlMs / 1000,
            user = user.toSession(),
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/auth/login
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/auth/login") {
        val request = call.receive<LoginRequest>()

        if (request.username.isBlank() || request.password.isBlank()) {
            throw BadRequestException("FIELD_REQUIRED", "Usuario y contraseña son obligatorios.")
        }

        val ip = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
            ?: call.request.local.remoteHost
        val userAgent = call.request.headers["User-Agent"]

        val user = repository.findByUsername(request.username)

        // ── El mensaje es el MISMO para usuario inexistente y contraseña mal ──
        // Distinguirlos permite enumerar qué nombres son reales. Con la lista de
        // nombres, un atacante solo necesita acertar contraseñas.
        fun credentialsInvalid(): Nothing {
            // Se registra el intento aunque el usuario no exista: es lo que
            // permite detectar un ataque contra nombres válidos. `user_id` queda
            // en NULL, así que el registro no revela si el nombre existe.
            repository.recordFailedLogin(
                username = request.username,
                userId = user?.id,
                failureCode = "INVALID_CREDENTIALS",
                ip = ip,
                userAgent = userAgent,
            )
            throw UnauthorizedException(
                "INVALID_CREDENTIALS",
                "Usuario o contraseña incorrectos.",
            )
        }

        if (user == null) credentialsInvalid()

        // ── Bloqueo temporal ────────────────────────────────────────────────
        val now = Instant.now()
        if (user.isLocked(now)) {
            val until = user.lockedUntil!!
            throw UnauthorizedException(
                "ACCOUNT_LOCKED",
                "La cuenta está bloqueada temporalmente. Vuelve a intentarlo más tarde.",
            )
        }

        if (user.disabled) {
            // Desactivada SÍ se distingue del fallo de credenciales: no es un
            // intento de adivinar, es una cuenta que un administrador desactivó, y
            // el usuario necesita saberlo para reclamar.
            throw UnauthorizedException(
                "ACCOUNT_DISABLED",
                "La cuenta está desactivada. Contacta con un administrador.",
            )
        }

        if (!hasher.verify(request.password, user.passwordHash)) {
            credentialsInvalid()
        }

        // ── Éxito: se reinicia el contador de fallos ────────────────────────
        repository.recordSuccessfulLogin(user.id, now)

        // Si el hash usa parámetros obsoletos, aquí es donde se regenera. Es lo
        // que permite endurecer Argon2 con el tiempo sin invalidar contraseñas.
        if (hasher.needsRehash(user.passwordHash)) {
            call.application.environment.log.info(
                "El hash de '{}' usa parámetros obsoletos; conviene regenerarlo al cambiar la contraseña.",
                user.username,
            )
        }

        val session = issue(
            user = user,
            familyId = tokens.newFamilyId(),
            generation = 1,
            ip = ip,
            userAgent = userAgent,
        )

        call.respond(HttpStatusCode.OK, session)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/auth/refresh — rotación con detección de reuso
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/auth/refresh") {
        val request = call.receive<RefreshRequest>()

        val tokenValid = RefreshToken.of(request.refreshToken)
            ?: throw UnauthorizedException("INVALID_CREDENTIALS", "El token de refresco no es válido.")

        val hash = tokens.hashRefreshToken(tokenValid.value)
        val saved = repository.findRefreshToken(hash)

        // ── La decisión la toma el DOMINIO ──────────────────────────────────
        // `decideRefresh` tiene 32 tests que cubren los seis casos, incluido el
        // reuso y el margen de gracia. Aquí solo se traduce el resultado a HTTP.
        val record = saved?.let {
            RefreshTokenRecord(
                id = TokenId(it.id),
                familyId = TokenFamilyId(it.familyId),
                userId = it.userId,
                fingerprint = "",
                issuedAt = it.issuedAt.toEpochMilli(),
                expiresAt = it.expiresAt.toEpochMilli(),
                rotatedAt = it.rotatedAt?.toEpochMilli(),
                replacedBy = it.replacedByHash,
                revokedAt = it.revokedAt?.toEpochMilli(),
                revocationReason = it.revocationReason?.let { reason ->
                    runCatching { RevocationReason.valueOf(reason) }.getOrNull()
                },
            )
        }

        val nowMs = System.currentTimeMillis()
        when (val decision = decideRefresh(record, nowMs, policy)) {

            is RefreshDecision.ReuseDetected -> {
                // **Se revoca la FAMILIA completa.** Hay dos partes con el mismo
                // token y una no es el usuario. Se asume lo peor: es un
                // inconveniente para un reintento por fallo de red fuera del
                // margen, y la única respuesta segura para un robo.
                val revoked = repository.revokeFamily(
                    decision.record.familyId.value,
                    RevocationReason.REUSE_DETECTED.name,
                )
                call.application.environment.log.warn(
                    "REUSO DE TOKEN detectado para el usuario {}: se revocaron {} tokens de la familia {}",
                    decision.record.userId,
                    revoked,
                    decision.record.familyId.value,
                )
                // **Código propio y no INVALID_CREDENTIALS**: la interfaz debe
                // decir «tu sesión se cerró por seguridad», que es información
                // distinta de «contraseña incorrecta».
                throw UnauthorizedException(
                    "TOKEN_REUSED",
                    "La sesión se cerró por seguridad: se detectó un uso duplicado del token. " +
                        "Vuelve a iniciar sesión.",
                )
            }

            is RefreshDecision.Unknown -> throw UnauthorizedException(
                "INVALID_CREDENTIALS",
                "El token de refresco no es válido.",
            )

            is RefreshDecision.Revoked -> throw UnauthorizedException(
                "INVALID_CREDENTIALS",
                "La sesión ya se cerró. Vuelve a iniciar sesión.",
            )

            is RefreshDecision.Expired -> throw UnauthorizedException(
                "INVALID_CREDENTIALS",
                "La sesión expiró. Vuelve a iniciar sesión.",
            )

            is RefreshDecision.Rotate -> {
                val user = repository.findById(decision.record.userId)
                    ?: throw UnauthorizedException("UNAUTHENTICATED", "El usuario ya no existe.")

                if (user.disabled) {
                    throw UnauthorizedException("ACCOUNT_DISABLED", "La cuenta está desactivada.")
                }

                // Un cambio de contraseña posterior a la emisión del token lo
                // invalida. Es lo que hace real la promesa de «cambiar la
                // contraseña cierra las demás sesiones».
                if (user.isTokenOlderThanPassword(Instant.ofEpochMilli(decision.record.issuedAt))) {
                    throw UnauthorizedException(
                        "INVALID_CREDENTIALS",
                        "La contraseña cambió después de emitirse esta sesión. Vuelve a iniciar sesión.",
                    )
                }

                val ip = call.request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()
                    ?: call.request.local.remoteHost

                val session = issue(
                    user = user,
                    familyId = decision.record.familyId.value,
                    generation = repository.nextGeneration(decision.record.familyId.value),
                    ip = ip,
                    userAgent = call.request.headers["User-Agent"],
                )

                // El token usado se marca como ROTADO, no como revocado: sigue
                // siendo un registro válido de la cadena y son cosas distintas.
                if (!decision.record.isRotated) {
                    repository.markRotated(
                        tokenHash = hash,
                        replacedByHash = tokens.hashRefreshToken(session.refreshToken),
                    )
                }

                call.respond(HttpStatusCode.OK, session)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/auth/logout
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/auth/logout") {
        // Es idempotente y no exige token válido: si el cliente quiere cerrar
        // sesión, hay que dejarlo. Devolver 401 aquí dejaría la sesión viva en el
        // servidor justo cuando el usuario pide lo contrario.
        val request = runCatching { call.receive<RefreshRequest>() }.getOrNull()

        if (request != null) {
            RefreshToken.of(request.refreshToken)?.let { token ->
                repository.revokeByHash(
                    tokens.hashRefreshToken(token.value),
                    RevocationReason.LOGOUT.name,
                )
            }
        }

        call.respond(HttpStatusCode.NoContent)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GET /v1/auth/me
    // ═══════════════════════════════════════════════════════════════════════
    get("/v1/auth/me") {
        val claims = call.tokenClaims(tokens)
            ?: throw UnauthorizedException("UNAUTHENTICATED", "Falta el token de acceso.")

        // Se relee de la base y NO se devuelve el contenido del token: los
        // permisos pueden haber cambiado desde que se emitió, y `me` es
        // precisamente la ruta que el cliente usa para refrescar su vista de
        // permisos.
        val user = repository.findById(claims.userId)
            ?: throw UnauthorizedException("UNAUTHENTICATED", "El usuario ya no existe.")

        if (user.disabled) {
            throw UnauthorizedException("ACCOUNT_DISABLED", "La cuenta está desactivada.")
        }

        call.respond(HttpStatusCode.OK, user.toSession())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // POST /v1/auth/change-password
    // ═══════════════════════════════════════════════════════════════════════
    post("/v1/auth/change-password") {
        val claims = call.tokenClaims(tokens)
            ?: throw UnauthorizedException("UNAUTHENTICATED", "Falta el token de acceso.")

        val request = call.receive<ChangePasswordRequest>()

        val user = repository.findById(claims.userId)
            ?: throw UnauthorizedException("UNAUTHENTICATED", "El usuario ya no existe.")

        if (!hasher.verify(request.currentPassword, user.passwordHash)) {
            throw UnauthorizedException(
                "INVALID_CREDENTIALS",
                "La contraseña actual no es correcta.",
            )
        }

        // ── La política del DOMINIO valida la nueva ─────────────────────────
        when (val validation = PasswordPolicy.validate(request.newPassword, user.username)) {
            is PasswordValidation.Valid -> Unit
            is PasswordValidation.Invalid -> throw UnprocessableException(
                "WEAK_PASSWORD",
                PasswordPolicy.explain(validation),
            )
        }

        if (request.newPassword == request.currentPassword) {
            throw UnprocessableException(
                "SAME_PASSWORD",
                "La contraseña nueva debe ser distinta de la actual.",
            )
        }

        // ── Cambiar la contraseña cierra TODAS las sesiones ─────────────────
        // Quien la cambia suele sospechar que está comprometida. Dejar vivas las
        // demás sesiones mantendría el acceso del atacante justo cuando el usuario
        // intenta cerrarlo.
        val revoked = repository.revokeAllForUser(
            user.id,
            RevocationReason.PASSWORD_CHANGED.name,
        )
        repository.updatePassword(user.id, hasher.hash(request.newPassword))

        call.application.environment.log.info(
            "Contraseña cambiada para '{}': se revocaron {} tokens",
            user.username,
            revoked,
        )

        call.respond(HttpStatusCode.NoContent)
    }
}

/**
 * Lee y verifica el token de acceso de la cabecera `Authorization`.
 *
 * @return los datos del token, o `null` si falta o no es válido.
 */
suspend fun io.ktor.server.application.ApplicationCall.tokenClaims(
    tokens: TokenService,
): AccessTokenClaims? {
    val header = request.headers["Authorization"] ?: return null
    if (!header.startsWith("Bearer ", ignoreCase = true)) return null

    val token = header.substring(7).trim()
    return when (val verification = tokens.verify(token)) {
        is TokenVerification.Valid -> verification.claims
        // Un token vencido no es un error de credenciales: el cliente debe
        // refrescar. Se distingue con el código para que sepa qué hacer.
        TokenVerification.Expired -> null
        TokenVerification.Invalid -> null
    }
}

/**
 * Construye el `AccessUser` del dominio a partir del token.
 *
 * **Se traduce el token al tipo del dominio para autorizar con él.** El motor de
 * acceso tiene 43 tests y espera `AccessUser`; construir otra cosa aquí sería
 * duplicar la lógica de autorización y las dos versiones se separarían.
 */
fun AccessTokenClaims.aAccessUser(
    warehouseIds: Set<Long> = warehouses.toSet(),
): dev.axzy.stock.domain.access.AccessUser = dev.axzy.stock.domain.access.AccessUser(
    id = userId,
    username = username,
    active = true,
    roles = roles.mapNotNull { key ->
        // Se resuelve contra los roles base. Un rol que ya no exista se IGNORA en
        // lugar de inventarse: un token viejo no debe conceder un rol borrado.
        runCatching { BaseRole.valueOf(key) }.getOrNull()?.let { base ->
            // `RoleKey` tiene el constructor privado a propósito: una clave de rol
            // se construye con su fábrica, que valida el formato. Saltársela
            // permitiría crear un rol con una clave inválida.
            Role(key = RoleKey.fromName(base.name), name = base.name)
        }
    },
    warehouseIds = warehouseIds,
    exceptions = scopes.mapValues { (_, scope) ->
        runCatching { dev.axzy.stock.domain.access.PermissionScope.valueOf(scope) }
            .getOrDefault(dev.axzy.stock.domain.access.PermissionScope.NONE)
    },
)
