package dev.axzy.stock.application.identity

import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditActor
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.audit.AuditResult
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.RefreshDecision
import dev.axzy.stock.domain.identity.RefreshToken
import dev.axzy.stock.domain.identity.RefreshTokenRecord
import dev.axzy.stock.domain.identity.RevocationReason
import dev.axzy.stock.domain.identity.SessionPolicy
import dev.axzy.stock.domain.identity.TokenFamilyId
import dev.axzy.stock.domain.identity.TokenId
import dev.axzy.stock.domain.identity.decideRefresh
import java.time.Clock

/**
 * Renews the session by rotating the refresh token. The decision —rotate, expired, reuse— belongs to the domain
 * ([decideRefresh]); here it is given the record and the result is applied.
 *
 * The lookup locks the token's row: two simultaneous renewals with the same token cannot
 * both rotate it "for the first time".
 */
class RefreshSession(
    private val users: UserAccounts,
    private val refreshTokens: RefreshTokens,
    private val codec: RefreshTokenCodec,
    private val sessions: SessionIssuer,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val tx: TransactionRunner,
    private val clock: Clock,
    private val policy: SessionPolicy,
) {
    fun execute(rawToken: String, origin: RequestOrigin): Outcome<AuthSession> {
        val token = RefreshToken.of(rawToken) ?: return invalid("The refresh token is not valid.")
        val hash = codec.hash(token.value)
        val now = clock.instant()
        var reused: StoredRefreshToken? = null

        val result = tx.inTransaction {
            val stored = refreshTokens.findForUpdate(hash)
            when (val decision = decideRefresh(stored?.toRecord(), now.toEpochMilli(), policy)) {
                is RefreshDecision.ReuseDetected -> {
                    reused = stored
                    failure(
                        FailureKind.UNAUTHENTICATED, "TOKEN_REUSED",
                        "The session was closed for security: a duplicate use of the token was detected. Sign in again.",
                    )
                }
                is RefreshDecision.Unknown -> invalid("The refresh token is not valid.")
                is RefreshDecision.Revoked -> invalid("The session is already closed. Sign in again.")
                is RefreshDecision.Expired -> invalid("The session expired. Sign in again.")
                is RefreshDecision.Rotate -> {
                    val record = stored!!
                    val snapshot = users.loadActor(record.userId)
                        ?: return@inTransaction failure(FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED", "The user no longer exists.")
                    if (!snapshot.actor.user.active) {
                        return@inTransaction failure(FailureKind.UNAUTHENTICATED, "ACCOUNT_DISABLED", "The account is disabled.")
                    }
                    val issued = sessions.issue(
                        account = snapshot,
                        familyId = record.familyId,
                        generation = refreshTokens.nextGeneration(record.familyId),
                        origin = origin,
                        now = now,
                    )
                    // In the grace window the token was already rotated: a new session is delivered
                    // without marking it again, to keep which token gave way first.
                    if (record.rotatedAt == null) {
                        refreshTokens.markRotated(hash, issued.refreshHash, now)
                    }
                    issued.session.asRight()
                }
            }
        }

        // The revocation goes in its own transaction: the previous one ended in failure and was rolled back,
        // and this has to be written down.
        reused?.let { record ->
            tx.inTransaction {
                val revoked = refreshTokens.revokeFamily(record.familyId, RevocationReason.REUSE_DETECTED)
                audit.record(
                    auditEvents.byActor(
                        actor = AuditActor(userId = record.userId, username = null, displayName = null),
                        origin = origin,
                        action = "TOKEN_REUSE_DETECTED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity(type = "session", id = record.familyId),
                        newState = mapOf("revokedTokens" to revoked, "generation" to record.generation),
                        result = AuditResult.DENIED,
                        errorCode = "TOKEN_REUSED",
                    ),
                )
                Unit.asRight()
            }
        }
        return result
    }

    private fun invalid(message: String): Outcome<Nothing> =
        failure(FailureKind.UNAUTHENTICATED, "INVALID_CREDENTIALS", message)

    private fun StoredRefreshToken.toRecord() = RefreshTokenRecord(
        id = TokenId(id),
        familyId = TokenFamilyId(familyId),
        userId = userId,
        fingerprint = "",
        issuedAt = issuedAt.toEpochMilli(),
        expiresAt = expiresAt.toEpochMilli(),
        rotatedAt = rotatedAt?.toEpochMilli(),
        replacedBy = replacedByHash,
        revokedAt = revokedAt?.toEpochMilli(),
        revocationReason = revocationReason,
    )
}
