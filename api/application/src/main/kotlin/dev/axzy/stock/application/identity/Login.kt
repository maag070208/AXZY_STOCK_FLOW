package dev.axzy.stock.application.identity

import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.PasswordHasher
import java.time.Clock
import java.time.Duration

/**
 * Cuántos fallos bloquean un nombre de usuario y durante cuánto. El bloqueo es TEMPORAL: uno
 * permanente convertiría la fuerza bruta en una denegación de servicio contra el usuario legítimo.
 */
data class LockoutPolicy(
    val maxFailedAttempts: Int = 5,
    val window: Duration = Duration.ofMinutes(15),
) {
    init {
        require(maxFailedAttempts >= 1) { "The number of attempts must be at least 1" }
        require(!window.isNegative && !window.isZero) { "The lock window must be positive" }
    }
}

/**
 * Inicia sesión (docs/flujos/autenticacion-y-permisos.md §3).
 *
 * **No se puede averiguar si un usuario existe.** Las tres defensas:
 *  1. Usuario inexistente y contraseña incorrecta devuelven lo mismo, y tardan lo mismo: cuando no
 *     hay cuenta se verifica igualmente contra un hash señuelo.
 *  2. El bloqueo se decide por NOMBRE de usuario, exista o no la cuenta. Si solo se bloquearan las
 *     cuentas reales, cinco intentos bastarían para saber cuáles lo son.
 *  3. «Cuenta desactivada» solo se dice a quien acertó la contraseña.
 */
class Login(
    private val users: UserAccounts,
    private val attempts: LoginAttempts,
    private val hasher: PasswordHasher,
    private val sessions: SessionIssuer,
    private val codec: RefreshTokenCodec,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val lockout: LockoutPolicy = LockoutPolicy(),
) {
    /** Un hash válido que no corresponde a ninguna contraseña conocida. Se calcula una sola vez. */
    private val decoyHash: String by lazy { hasher.hash(codec.generate()) }

    fun execute(username: String, password: String, origin: RequestOrigin): Outcome<AuthSession> {
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) {
            return failure(FailureKind.INVALID_REQUEST, "FIELD_REQUIRED", "Username and password are required.")
        }

        val now = clock.instant()
        if (attempts.failedSince(name, now.minus(lockout.window)) >= lockout.maxFailedAttempts) {
            // No se verifica la contraseña ni se cuenta el intento: contar durante el bloqueo
            // permitiría mantener bloqueada una cuenta para siempre con una petición cada rato.
            attempts.record(name, null, succeeded = false, failureCode = ACCOUNT_LOCKED, origin = origin, at = now)
            return failure(
                FailureKind.UNAUTHENTICATED, ACCOUNT_LOCKED,
                "The account is temporarily locked after failed attempts. Try again later.",
            )
        }

        val account = users.findByUsername(name)
        val passwordMatches = hasher.verify(password, account?.passwordHash ?: decoyHash)
        if (account == null || !passwordMatches) {
            attempts.record(name, account?.id, succeeded = false, failureCode = INVALID_CREDENTIALS, origin = origin, at = now)
            return failure(FailureKind.UNAUTHENTICATED, INVALID_CREDENTIALS, "Wrong username or password.")
        }

        if (account.disabled) {
            attempts.record(name, account.id, succeeded = false, failureCode = ACCOUNT_DISABLED, origin = origin, at = now)
            return failure(
                FailureKind.UNAUTHENTICATED, ACCOUNT_DISABLED,
                "The account is disabled. Contact an administrator.",
            )
        }

        return tx.inTransaction {
            val snapshot = users.loadActor(account.id)
                ?: return@inTransaction failure(FailureKind.UNAUTHENTICATED, INVALID_CREDENTIALS, "Wrong username or password.")
            users.recordLogin(account.id, now)
            attempts.record(name, account.id, succeeded = true, failureCode = null, origin = origin, at = now)
            sessions.issue(snapshot, codec.newFamilyId(), generation = 1, origin = origin, now = now).session.asRight()
        }
    }

    private companion object {
        const val INVALID_CREDENTIALS = "INVALID_CREDENTIALS"
        const val ACCOUNT_LOCKED = "ACCOUNT_LOCKED"
        const val ACCOUNT_DISABLED = "ACCOUNT_DISABLED"
    }
}
