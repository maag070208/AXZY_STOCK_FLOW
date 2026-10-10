package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.identity.RevocationReason
import java.time.Instant

/** What is needed to know about an account to authenticate it. It never leaves the application. */
data class UserAccount(
    val id: Long,
    val username: String,
    val name: String,
    val passwordHash: String,
    val disabled: Boolean,
    val mustChangePassword: Boolean,
    val passwordChangedAt: Instant?,
)

/** An [Actor] as it is now in the database, with what is needed to validate the token that brought it. */
data class ActorSnapshot(
    val actor: Actor,
    val passwordChangedAt: Instant?,
) {
    /**
     * The stamp of its credentials: it changes every time the password changes. It travels inside the
     * access token, and a token whose stamp no longer matches the account's stops working.
     *
     * It is an exact comparison. Comparing the issue time of the token with that of the change is not:
     * the token stores seconds, and one issued in the same second as the change would still be valid.
     */
    val credentialsStamp: Long get() = passwordChangedAt?.toEpochMilli() ?: 0L
}

/** The user accounts. */
interface UserAccounts {
    fun findByUsername(username: String): UserAccount?

    fun findById(id: Long): UserAccount?

    /**
     * The account with its accesses: roles, scope per permission and warehouses. **Fixed number of
     * queries**, whatever the number of roles: it is called on every authenticated request.
     *
     * A deactivated account is returned with `actor.user.active = false`; deciding what to do with
     * it is the caller's responsibility.
     */
    fun loadActor(userId: Long): ActorSnapshot?

    fun recordLogin(userId: Long, at: Instant)

    /** Changes the password and stops requiring the change. */
    fun updatePassword(userId: Long, passwordHash: String, at: Instant)
}

/**
 * The sign-in attempts. They go in their own table and not in the audit log: they are written a lot
 * more, and an attacker must not be able to flood a ledger that is written in series.
 */
interface LoginAttempts {
    fun record(username: String, userId: Long?, succeeded: Boolean, failureCode: String?, origin: RequestOrigin, at: Instant)

    /** Credential failures for that username since [since]. Whether or not the account exists. */
    fun failedSince(username: String, since: Instant): Int
}

data class StoredRefreshToken(
    val id: Long,
    val userId: Long,
    val familyId: String,
    val generation: Int,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val rotatedAt: Instant?,
    val replacedByHash: String?,
    val revokedAt: Instant?,
    val revocationReason: RevocationReason?,
)

/** The refresh tokens. Only their hash is stored: a dump of the table does not allow impersonating anyone. */
interface RefreshTokens {
    fun store(tokenHash: String, userId: Long, familyId: String, generation: Int, issuedAt: Instant, expiresAt: Instant, origin: RequestOrigin)

    /**
     * Looks up the token **locking its row** until the end of the transaction. Two simultaneous renewals
     * with the same token are served one after the other: the second already sees it rotated.
     */
    fun findForUpdate(tokenHash: String): StoredRefreshToken?

    fun markRotated(tokenHash: String, replacedByHash: String, at: Instant)

    fun nextGeneration(familyId: String): Int

    /** Revokes the live tokens of the family. Returns how many. */
    fun revokeFamily(familyId: String, reason: RevocationReason): Int

    /** Revokes the family of the presented token, if it exists. Returns how many tokens. */
    fun revokeFamilyOf(tokenHash: String, reason: RevocationReason): Int

    fun revokeAllForUser(userId: Long, reason: RevocationReason): Int
}

data class IssuedAccessToken(val token: String, val expiresAt: Instant)

/** Issues the access token. It only identifies: the permissions do NOT travel in it. */
fun interface AccessTokenIssuer {
    /** @param credentialsStamp the account's credentials stamp at issue time ([ActorSnapshot.credentialsStamp]). */
    fun issue(userId: Long, familyId: String, credentialsStamp: Long, issuedAt: Instant, expiresAt: Instant): IssuedAccessToken
}

/** Generates opaque refresh tokens and computes the hash they are stored with. */
interface RefreshTokenCodec {
    fun generate(): String
    fun hash(token: String): String
    fun newFamilyId(): String
}
