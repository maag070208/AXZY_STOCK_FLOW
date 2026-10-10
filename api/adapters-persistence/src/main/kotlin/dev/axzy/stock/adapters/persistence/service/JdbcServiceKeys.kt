package dev.axzy.stock.adapters.persistence.service

import dev.axzy.stock.application.labeling.ServiceIdentity
import dev.axzy.stock.application.labeling.ServiceKeys
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Service credentials (the Zebra, and tomorrow the ERP).
 *
 * The hash is computed **here**, not in the application layer: the application does not do
 * cryptography. **SHA-256** is used, not Argon2id —the one that protects passwords— for
 * a specific reason: a person's password is short and guessable, and every guess has to be made
 * expensive; a service secret is 32 random bytes, so there is nothing
 * to guess, and instead one has to be able to **look up by hash** on each call to the
 * printer. With Argon2id, each row would have to be tried one by one.
 */
class JdbcServiceKeys(private val jdbc: JdbcClient) : ServiceKeys {

    override fun findActive(secret: String, at: Instant): ServiceIdentity? {
        if (secret.isBlank()) return null
        return jdbc.sql(
            """
            SELECT id, name, scopes FROM service_api_keys
            WHERE key_hash = :hash AND active AND revoked_at IS NULL
              AND (expires_at IS NULL OR expires_at > CAST(:at AS timestamptz))
            """.trimIndent(),
        )
            .param("hash", sha256(secret))
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                ServiceIdentity(
                    keyId = rs.getLong("id"),
                    name = rs.getString("name"),
                    scopes = (rs.getArray("scopes")?.array as? Array<String>)?.toList() ?: emptyList(),
                )
            }
            .optional()
            .orElse(null)
    }

    override fun touch(keyId: Long, at: Instant) {
        jdbc.sql("UPDATE service_api_keys SET last_used_at = CAST(:at AS timestamptz) WHERE id = :id")
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .param("id", keyId)
            .update()
    }

    private fun sha256(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    /*
     * The hash is not exposed: whoever seeds a credential —a test, a provisioning script—
     * computes the SHA-256 in hexadecimal, which is the format that this
     * class expects. If the algorithm changed, those seeds would fail and the test
     * would say so, which is exactly what is wanted from a credential format.
     */
}
