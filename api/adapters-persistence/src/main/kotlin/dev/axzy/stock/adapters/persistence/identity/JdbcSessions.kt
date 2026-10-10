package dev.axzy.stock.adapters.persistence.identity

import dev.axzy.stock.adapters.persistence.shared.inetOrNull
import dev.axzy.stock.adapters.persistence.shared.instant
import dev.axzy.stock.adapters.persistence.shared.toSql
import dev.axzy.stock.application.identity.LoginAttempts
import dev.axzy.stock.application.identity.RefreshTokens
import dev.axzy.stock.application.identity.StoredRefreshToken
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.identity.RevocationReason
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Instant

class JdbcLoginAttempts(private val jdbc: JdbcClient) : LoginAttempts {

    override fun record(username: String, userId: Long?, succeeded: Boolean, failureCode: String?, origin: RequestOrigin, at: Instant) {
        jdbc.sql(
            """
            INSERT INTO login_attempts (attempted_at, username, user_id, succeeded, failure_code, ip, user_agent)
            VALUES (:at, :username, :userId, :succeeded, :failureCode, CAST(:ip AS inet), :userAgent)
            """.trimIndent(),
        )
            // La columna admite 80 caracteres: un nombre más largo no corresponde a ninguna cuenta,
            // pero el intento se registra igual, recortado.
            .param("at", at.toSql())
            .param("username", username.take(USERNAME_MAX))
            .param("userId", userId)
            .param("succeeded", succeeded)
            .param("failureCode", failureCode)
            .param("ip", inetOrNull(origin.ip))
            .param("userAgent", origin.userAgent?.take(USER_AGENT_MAX))
            .update()
    }

    /**
     * Solo cuentan los fallos de CREDENCIALES. Los intentos hechos durante el bloqueo se guardan
     * con otro código y no lo prolongan. La consulta recorre el índice parcial
     * `login_attempts_failures_idx (username, attempted_at DESC) WHERE NOT succeeded`.
     */
    override fun failedSince(username: String, since: Instant): Int =
        jdbc.sql(
            """
            SELECT count(*) FROM login_attempts
            WHERE username = :username
              AND NOT succeeded
              AND attempted_at > :since
              AND failure_code = 'INVALID_CREDENTIALS'
            """.trimIndent(),
        )
            .param("username", username.take(USERNAME_MAX))
            .param("since", since.toSql())
            .query(Int::class.java)
            .single()

    private companion object {
        const val USERNAME_MAX = 80
        const val USER_AGENT_MAX = 500
    }
}

class JdbcRefreshTokens(private val jdbc: JdbcClient) : RefreshTokens {

    override fun store(tokenHash: String, userId: Long, familyId: String, generation: Int, issuedAt: Instant, expiresAt: Instant, origin: RequestOrigin) {
        jdbc.sql(
            """
            INSERT INTO refresh_tokens (token_hash, user_id, family_id, generation, issued_at, expires_at, issued_ip, issued_user_agent)
            VALUES (:hash, :userId, CAST(:family AS uuid), :generation, :issuedAt, :expiresAt, CAST(:ip AS inet), :userAgent)
            """.trimIndent(),
        )
            .param("hash", tokenHash)
            .param("userId", userId)
            .param("family", familyId)
            .param("generation", generation)
            .param("issuedAt", issuedAt.toSql())
            .param("expiresAt", expiresAt.toSql())
            .param("ip", inetOrNull(origin.ip))
            .param("userAgent", origin.userAgent?.take(500))
            .update()
    }

    override fun findForUpdate(tokenHash: String): StoredRefreshToken? =
        jdbc.sql(
            """
            SELECT id, user_id, family_id, generation, issued_at, expires_at,
                   rotated_at, replaced_by_hash, revoked_at, revocation_reason
            FROM refresh_tokens
            WHERE token_hash = :hash
            FOR UPDATE
            """.trimIndent(),
        )
            .param("hash", tokenHash)
            .query { rs, _ ->
                StoredRefreshToken(
                    id = rs.getLong("id"),
                    userId = rs.getLong("user_id"),
                    familyId = rs.getString("family_id"),
                    generation = rs.getInt("generation"),
                    issuedAt = rs.instant("issued_at")!!,
                    expiresAt = rs.instant("expires_at")!!,
                    rotatedAt = rs.instant("rotated_at"),
                    replacedByHash = rs.getString("replaced_by_hash")?.trim(),
                    revokedAt = rs.instant("revoked_at"),
                    revocationReason = rs.getString("revocation_reason")?.let(RevocationReason::valueOf),
                )
            }
            .optional().orElse(null)

    override fun markRotated(tokenHash: String, replacedByHash: String, at: Instant) {
        jdbc.sql(
            """
            UPDATE refresh_tokens
            SET rotated_at = :at, replaced_by_hash = :replacement, last_used_at = :at
            WHERE token_hash = :hash AND rotated_at IS NULL
            """.trimIndent(),
        )
            .param("at", at.toSql())
            .param("replacement", replacedByHash)
            .param("hash", tokenHash)
            .update()
    }

    override fun nextGeneration(familyId: String): Int =
        jdbc.sql("SELECT COALESCE(max(generation), 0) + 1 FROM refresh_tokens WHERE family_id = CAST(:family AS uuid)")
            .param("family", familyId)
            .query(Int::class.java)
            .single()

    override fun revokeFamily(familyId: String, reason: RevocationReason): Int =
        jdbc.sql("SELECT revoke_token_family(CAST(:family AS uuid), CAST(:reason AS revocation_reason))")
            .param("family", familyId)
            .param("reason", reason.name)
            .query(Int::class.java)
            .single()

    override fun revokeFamilyOf(tokenHash: String, reason: RevocationReason): Int =
        jdbc.sql(
            """
            SELECT COALESCE(sum(revoke_token_family(family_id, CAST(:reason AS revocation_reason))), 0)
            FROM refresh_tokens
            WHERE token_hash = :hash
            """.trimIndent(),
        )
            .param("reason", reason.name)
            .param("hash", tokenHash)
            .query(Int::class.java)
            .single()

    /** Solo los tokens vivos: un token ya rotado conserva su registro (`rotated` y `revoked` son excluyentes). */
    override fun revokeAllForUser(userId: Long, reason: RevocationReason): Int =
        jdbc.sql(
            """
            UPDATE refresh_tokens
            SET revoked_at = now(), revocation_reason = CAST(:reason AS revocation_reason)
            WHERE user_id = :userId AND revoked_at IS NULL AND rotated_at IS NULL
            """.trimIndent(),
        )
            .param("reason", reason.name)
            .param("userId", userId)
            .update()
}
