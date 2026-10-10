package dev.axzy.stock.adapters.security

import dev.axzy.stock.application.identity.RefreshTokenCodec
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/**
 * Refresh tokens opacos: 256 bits aleatorios, sin significado. Se guardan con SHA-256.
 *
 * Aquí SHA-256 basta y Argon2 sobraría: Argon2 protege secretos adivinables (contraseñas), y un
 * token de 256 bits aleatorios no se adivina. Lo que importa es que un volcado de la tabla no
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
