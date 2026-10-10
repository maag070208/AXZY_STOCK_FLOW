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
 * How many failures lock a username and for how long. The lock is TEMPORARY: a
 * permanent one would turn brute force into a denial of service against the legitimate user.
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
 * Signs in (docs/flujos/autenticacion-y-permisos.md §3).
 *
 * **It cannot be found out whether a user exists.** The three defenses:
 *  1. A nonexistent user and a wrong password return the same thing, and take the same time: when
 *     there is no account, the password is verified anyway against a decoy hash.
 *  2. The lock is decided by USERNAME, whether or not the account exists. If only real accounts were locked,
 *     five attempts would be enough to know which ones are real.
 *  3. "Account deactivated" is only said to whoever guessed the password.
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
    /** A valid hash that matches no known password. It is computed only once. */
    private val decoyHash: String by lazy { hasher.hash(codec.generate()) }

    fun execute(username: String, password: String, origin: RequestOrigin): Outcome<AuthSession> {
        val name = username.trim()
        if (name.isEmpty() || password.isEmpty()) {
            return failure(FailureKind.INVALID_REQUEST, "FIELD_REQUIRED", "Username and password are required.")
        }

        val now = clock.instant()
        if (attempts.failedSince(name, now.minus(lockout.window)) >= lockout.maxFailedAttempts) {
            // The password is not verified nor is the attempt counted: counting during the lock
            // would allow keeping an account locked forever with a request every so often.
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
