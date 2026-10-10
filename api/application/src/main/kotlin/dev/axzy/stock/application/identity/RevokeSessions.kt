package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.RevocationReason

/** What was revoked, so that the response says something and not just "ok". */
data class SessionsRevoked(val userId: Long, val username: String, val revoked: Int)

/**
 * Closes **all** the sessions of a user, from an administrator's account (M07,
 * `session.revoke`).
 *
 * It is the answer to "this person's laptop was stolen" or "they leave the company today": without
 * this route, closing someone else's session required changing their password, which is a decision
 * much bigger —you tell them, they change it, and with that you also invalidate what they were
 * doing—. Revoking is the small and sufficient thing.
 *
 * **What it cuts is the renewal, and it has to be said.** The access token already issued is a
 * signed JWT and is not checked against the database on every request, so it stays valid until
 * it expires on its own; what is revoked are the refresh tokens, which are the ones that
 * keep the session alive. To cut access immediately, there is changing the password, which
 * does move the credentials stamp.
 */
class RevokeUserSessions(
    private val accounts: UserAccounts,
    private val refreshTokens: RefreshTokens,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, userId: Long, origin: RequestOrigin): Outcome<SessionsRevoked> =
        access.authorizeOr(actor, "session.revoke", origin) { grant ->
            tx.inTransaction {
                val account = accounts.findById(userId)
                    ?: return@inTransaction failure(
                        FailureKind.NOT_FOUND, "USER_NOT_FOUND",
                        "User $userId does not exist.",
                        mapOf("userId" to userId.toString()),
                    )

                val revoked = refreshTokens.revokeAllForUser(userId, RevocationReason.ADMIN_REVOKED)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "USER_SESSIONS_REVOKED",
                        // Critical: revoking sessions is taking someone's access away, and whoever
                        // did it must be able to answer for it a year later.
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("user", userId.toString(), account.username),
                        grant = grant,
                        newState = mapOf("revokedSessions" to revoked, "reason" to RevocationReason.ADMIN_REVOKED.name),
                    ),
                )
                SessionsRevoked(userId, account.username, revoked).asRight()
            }
        }
}
