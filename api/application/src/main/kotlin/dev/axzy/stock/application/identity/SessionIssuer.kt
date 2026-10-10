package dev.axzy.stock.application.identity

import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.identity.SessionPolicy
import java.time.Instant

/**
 * Emite una sesión: guarda el refresh token (su hash) y firma el token de acceso. Lo comparten el
 * inicio de sesión y la renovación para que una sesión se emita de una sola forma.
 */
class SessionIssuer(
    private val refreshTokens: RefreshTokens,
    private val codec: RefreshTokenCodec,
    private val accessTokens: AccessTokenIssuer,
    private val policy: SessionPolicy,
) {
    /** Debe llamarse dentro de la transacción de quien emite. */
    fun issue(account: ActorSnapshot, familyId: String, generation: Int, origin: RequestOrigin, now: Instant): IssuedSession {
        val actor = account.actor
        val refreshToken = codec.generate()
        val refreshHash = codec.hash(refreshToken)
        refreshTokens.store(
            tokenHash = refreshHash,
            userId = actor.id,
            familyId = familyId,
            generation = generation,
            issuedAt = now,
            expiresAt = now.plusMillis(policy.refreshTokenTtlMs),
            origin = origin,
        )
        val access = accessTokens.issue(
            userId = actor.id,
            familyId = familyId,
            credentialsStamp = account.credentialsStamp,
            issuedAt = now,
            expiresAt = now.plusMillis(policy.accessTokenTtlMs),
        )
        return IssuedSession(
            session = AuthSession(
                accessToken = access.token,
                refreshToken = refreshToken,
                expiresInSeconds = policy.accessTokenTtlMs / 1000,
                user = SessionUser.of(actor),
            ),
            refreshHash = refreshHash,
        )
    }
}

data class IssuedSession(val session: AuthSession, val refreshHash: String)
