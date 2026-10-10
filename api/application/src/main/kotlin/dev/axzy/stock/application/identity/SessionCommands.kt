package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.domain.identity.PasswordValidation
import dev.axzy.stock.domain.identity.RefreshToken
import dev.axzy.stock.domain.identity.RevocationReason
import java.time.Clock

/**
 * Signs out: revokes the presented refresh token and its family.
 *
 * **It never fails.** It is idempotent and does not require a valid token: if someone wants to sign out
 * they have to be let do it, and answering with an error would leave the session alive exactly when they ask for the opposite.
 */
class Logout(
    private val refreshTokens: RefreshTokens,
    private val codec: RefreshTokenCodec,
) {
    fun execute(rawToken: String?) {
        val token = rawToken?.let(RefreshToken::of) ?: return
        refreshTokens.revokeFamilyOf(codec.hash(token.value), RevocationReason.LOGOUT)
    }
}

/**
 * Changes the password and **invalidates all the sessions**, including the current one: whoever changes it
 * usually suspects it has been compromised. The change and the revocation go in the same transaction;
 * separately, a failure between the two would leave the new password with the old sessions still alive.
 */
class ChangePassword(
    private val users: UserAccounts,
    private val refreshTokens: RefreshTokens,
    private val hasher: PasswordHasher,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    fun execute(actor: Actor, currentPassword: String, newPassword: String, origin: RequestOrigin): Outcome<Unit> {
        val account = users.findById(actor.id)
            ?: return failure(FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED", "The user no longer exists.")

        if (!hasher.verify(currentPassword, account.passwordHash)) {
            return failure(FailureKind.UNAUTHENTICATED, "INVALID_CREDENTIALS", "The current password is not correct.")
        }
        val validation = PasswordPolicy.validate(newPassword, account.username)
        if (validation is PasswordValidation.Invalid) {
            return failure(FailureKind.RULE_VIOLATION, "WEAK_PASSWORD", PasswordPolicy.explain(validation))
        }
        if (newPassword == currentPassword) {
            return failure(FailureKind.RULE_VIOLATION, "SAME_PASSWORD", "The new password must be different from the current one.")
        }

        // The hash is deliberately slow: it is computed BEFORE opening the transaction so as not to
        // hold a database connection meanwhile.
        val newHash = hasher.hash(newPassword)
        val now = clock.instant()
        return tx.inTransaction {
            users.updatePassword(account.id, newHash, now)
            val revoked = refreshTokens.revokeAllForUser(account.id, RevocationReason.PASSWORD_CHANGED)
            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "PASSWORD_CHANGED",
                    level = AuditLevel.RELEVANT,
                    entity = AuditEntity(type = "user", id = account.id.toString(), label = account.username),
                    newState = mapOf("revokedSessions" to revoked),
                ),
            )
            Unit.asRight()
        }
    }
}

/**
 * Rebuilds the [Actor] of a request from an already verified token.
 *
 * It is where a token that is still valid stops working: if the account was deactivated, or the password
 * changed after it was issued, the request is rejected even though the signature and the expiry are valid.
 */
class ResolveActor(private val users: UserAccounts) {

    fun execute(userId: Long, credentialsStamp: Long): Outcome<Actor> {
        val snapshot = users.loadActor(userId)
            ?: return failure(FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED", "The user no longer exists.")
        if (!snapshot.actor.user.active) {
            return failure(FailureKind.UNAUTHENTICATED, "ACCOUNT_DISABLED", "The account is disabled.")
        }
        if (credentialsStamp != snapshot.credentialsStamp) {
            return failure(
                FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED",
                "The password changed after this session was issued. Sign in again.",
            )
        }
        return snapshot.actor.asRight()
    }
}
