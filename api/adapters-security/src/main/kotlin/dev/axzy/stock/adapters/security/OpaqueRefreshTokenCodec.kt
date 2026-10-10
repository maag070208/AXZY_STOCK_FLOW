package dev.axzy.stock.adapters.security

import dev.axzy.stock.application.identity.RefreshTokenCodec
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Opaque refresh tokens: 256 random bits, with no meaning. They are stored with SHA-256.
 *
 * Here SHA-256 is enough and Argon2 would be overkill: Argon2 protects guessable secrets (passwords), and a
 * 256-bit random token is not guessable. What matters is that a dump of the table does not
 * entregue tokens utilizables.
 */
class OpaqueRefreshTokenCodec : RefreshTokenCodec {

    private val random = SecureRandom()

    override fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    override fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    override fun newFamilyId(): String = UUID.randomUUID().toString()

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
