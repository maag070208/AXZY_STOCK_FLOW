package dev.axzy.stock.adapters.security

import dev.axzy.stock.domain.identity.RefreshToken
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant
import java.time.temporal.ChronoUnit

class Argon2idPasswordHasherTest {

    private val hasher = Argon2idPasswordHasher()

    @Test
    fun `the hash is Argon2id with the configured parameters and verifies its password`() {
        val hash = hasher.hash("Almacen-Seguro-2026")

        assertThat(hash).startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$")
        assertThat(hasher.verify("Almacen-Seguro-2026", hash)).isTrue()
        assertThat(hasher.verify("almacen-seguro-2026", hash)).isFalse()
        assertThat(hasher.needsRehash(hash)).isFalse()
    }

    @Test
    fun `the same password gives different hashes - each carries its salt`() {
        assertThat(hasher.hash("Almacen-Seguro-2026")).isNotEqualTo(hasher.hash("Almacen-Seguro-2026"))
    }

    @Test
    fun `a hash with weaker parameters still verifies, and is marked for regeneration`() {
        // Generated with m=4096: valid, but below the current cost.
        val weak = org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 4096, 2).encode("Almacen-Seguro-2026")!!

        assertThat(hasher.verify("Almacen-Seguro-2026", weak)).isTrue()
        assertThat(hasher.needsRehash(weak)).isTrue()
    }

    @Test
    fun `a string that is not a hash verifies nothing, and does not throw`() {
        assertThat(hasher.verify("x", "no-es-un-hash")).isFalse()
        assertThat(hasher.verify("x", "")).isFalse()
    }
}

class JwtAccessTokensTest {

    private val secret = "un-secreto-de-pruebas-de-al-menos-32-bytes!!"
    private val tokens = JwtAccessTokens(secret, issuer = "stock-api", audience = "stock-clients")
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `an issued token is verified and carries only identity - neither roles nor permissions`() {
        val issued = tokens.issue(userId = 7, familyId = "fam-1", credentialsStamp = 1_760_000_000_123, issuedAt = now, expiresAt = now.plusSeconds(900))

        val jwt = tokens.decoder.decode(issued.token)

        assertThat(jwt.subject).isEqualTo("7")
        assertThat(jwt.issuedAt).isEqualTo(now)
        assertThat(jwt.expiresAt).isEqualTo(now.plusSeconds(900))
        assertThat(jwt.getClaimAsString(JwtAccessTokens.CLAIM_FAMILY)).isEqualTo("fam-1")
        // The stamp keeps the milliseconds: it is what makes the comparison with the account exact.
        assertThat((jwt.claims[JwtAccessTokens.CLAIM_CREDENTIALS] as Number).toLong()).isEqualTo(1_760_000_000_123)
        assertThat(jwt.claims.keys).containsExactlyInAnyOrder("iss", "aud", "sub", "jti", "iat", "exp", "fam", "pwd")
        // An identity token is short: the previous one, with the permissions inside, went past 4,600 characters.
        assertThat(issued.token.length).isLessThan(400)
    }

    @Test
    fun `an expired token is rejected`() {
        val expired = tokens.issue(7, "fam-1", 0, issuedAt = now.minusSeconds(3600), expiresAt = now.minusSeconds(1800))

        assertThatThrownBy { tokens.decoder.decode(expired.token) }.isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `a tampered token is rejected`() {
        val token = tokens.issue(7, "fam-1", 0, now, now.plusSeconds(900)).token
        val (header, payload, signature) = token.split(".")
        val forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            String(java.util.Base64.getUrlDecoder().decode(payload)).replace("\"sub\":\"7\"", "\"sub\":\"1\"").toByteArray(),
        )

        assertThatThrownBy { tokens.decoder.decode("$header.$forgedPayload.$signature") }.isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `a token signed with another key, from another issuer or for another audience is rejected`() {
        val otherKey = JwtAccessTokens("otro-secreto-distinto-de-al-menos-32-bytes!!", "stock-api", "stock-clients")
        val otherIssuer = JwtAccessTokens(secret, "otro-emisor", "stock-clients")
        val otherAudience = JwtAccessTokens(secret, "stock-api", "otra-audiencia")

        for (foreign in listOf(otherKey, otherIssuer, otherAudience)) {
            val token = foreign.issue(7, "fam-1", 0, now, now.plusSeconds(900)).token
            assertThatThrownBy { tokens.decoder.decode(token) }.isInstanceOf(JwtException::class.java)
        }
    }

    @Test
    fun `a short secret is not accepted`() {
        assertThatThrownBy { JwtAccessTokens("corto", "stock-api", "stock-clients") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32 bytes")
    }
}

class OpaqueRefreshTokenCodecTest {

    private val codec = OpaqueRefreshTokenCodec()

    @Test
    fun `every token is different and has the format the domain accepts`() {
        val generated = (1..200).map { codec.generate() }

        assertThat(generated.toSet()).hasSize(200)
        assertThat(generated).allMatch { RefreshToken.of(it) != null }
    }

    @Test
    fun `the hash is SHA-256 in hexadecimal and is stable`() {
        val token = codec.generate()

        assertThat(codec.hash(token)).hasSize(64).matches("[0-9a-f]{64}")
        assertThat(codec.hash(token)).isEqualTo(codec.hash(token))
        assertThat(codec.hash(token)).isNotEqualTo(codec.hash(codec.generate()))
    }
}
