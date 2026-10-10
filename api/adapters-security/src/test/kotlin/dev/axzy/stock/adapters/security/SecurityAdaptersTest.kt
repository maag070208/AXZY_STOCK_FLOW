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
    fun `el hash es Argon2id con los parametros configurados y verifica su contrasena`() {
        val hash = hasher.hash("Almacen-Seguro-2026")

        assertThat(hash).startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$")
        assertThat(hasher.verify("Almacen-Seguro-2026", hash)).isTrue()
        assertThat(hasher.verify("almacen-seguro-2026", hash)).isFalse()
        assertThat(hasher.needsRehash(hash)).isFalse()
    }

    @Test
    fun `la misma contrasena da hashes distintos - cada uno lleva su sal`() {
        assertThat(hasher.hash("Almacen-Seguro-2026")).isNotEqualTo(hasher.hash("Almacen-Seguro-2026"))
    }

    @Test
    fun `un hash con parametros mas debiles sigue verificando, y se marca para regenerar`() {
        // Generado con m=4096: válido, pero por debajo del coste actual.
        val weak = org.springframework.security.crypto.argon2.Argon2PasswordEncoder(16, 32, 1, 4096, 2).encode("Almacen-Seguro-2026")!!

        assertThat(hasher.verify("Almacen-Seguro-2026", weak)).isTrue()
        assertThat(hasher.needsRehash(weak)).isTrue()
    }

    @Test
    fun `una cadena que no es un hash no verifica nada, y no lanza`() {
        assertThat(hasher.verify("x", "no-es-un-hash")).isFalse()
        assertThat(hasher.verify("x", "")).isFalse()
    }
}

class JwtAccessTokensTest {

    private val secret = "un-secreto-de-pruebas-de-al-menos-32-bytes!!"
    private val tokens = JwtAccessTokens(secret, issuer = "stock-api", audience = "stock-clients")
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    @Test
    fun `un token emitido se verifica y solo lleva identidad - ni roles ni permisos`() {
        val issued = tokens.issue(userId = 7, familyId = "fam-1", credentialsStamp = 1_760_000_000_123, issuedAt = now, expiresAt = now.plusSeconds(900))

        val jwt = tokens.decoder.decode(issued.token)

        assertThat(jwt.subject).isEqualTo("7")
        assertThat(jwt.issuedAt).isEqualTo(now)
        assertThat(jwt.expiresAt).isEqualTo(now.plusSeconds(900))
        assertThat(jwt.getClaimAsString(JwtAccessTokens.CLAIM_FAMILY)).isEqualTo("fam-1")
        // El sello conserva los milisegundos: es lo que hace exacta la comparación con la cuenta.
        assertThat((jwt.claims[JwtAccessTokens.CLAIM_CREDENTIALS] as Number).toLong()).isEqualTo(1_760_000_000_123)
        assertThat(jwt.claims.keys).containsExactlyInAnyOrder("iss", "aud", "sub", "jti", "iat", "exp", "fam", "pwd")
        // Un token de identidad es corto: el anterior, con los permisos dentro, pasaba de 4.600 caracteres.
        assertThat(issued.token.length).isLessThan(400)
    }

    @Test
    fun `un token vencido se rechaza`() {
        val expired = tokens.issue(7, "fam-1", 0, issuedAt = now.minusSeconds(3600), expiresAt = now.minusSeconds(1800))

        assertThatThrownBy { tokens.decoder.decode(expired.token) }.isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `un token alterado se rechaza`() {
        val token = tokens.issue(7, "fam-1", 0, now, now.plusSeconds(900)).token
        val (header, payload, signature) = token.split(".")
        val forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
            String(java.util.Base64.getUrlDecoder().decode(payload)).replace("\"sub\":\"7\"", "\"sub\":\"1\"").toByteArray(),
        )

        assertThatThrownBy { tokens.decoder.decode("$header.$forgedPayload.$signature") }.isInstanceOf(JwtException::class.java)
    }

    @Test
    fun `un token firmado con otra clave, de otro emisor o para otra audiencia se rechaza`() {
        val otherKey = JwtAccessTokens("otro-secreto-distinto-de-al-menos-32-bytes!!", "stock-api", "stock-clients")
        val otherIssuer = JwtAccessTokens(secret, "otro-emisor", "stock-clients")
        val otherAudience = JwtAccessTokens(secret, "stock-api", "otra-audiencia")

        for (foreign in listOf(otherKey, otherIssuer, otherAudience)) {
            val token = foreign.issue(7, "fam-1", 0, now, now.plusSeconds(900)).token
            assertThatThrownBy { tokens.decoder.decode(token) }.isInstanceOf(JwtException::class.java)
        }
    }

    @Test
    fun `un secreto corto no se acepta`() {
        assertThatThrownBy { JwtAccessTokens("corto", "stock-api", "stock-clients") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("32 bytes")
    }
}

class OpaqueRefreshTokenCodecTest {

    private val codec = OpaqueRefreshTokenCodec()

    @Test
    fun `cada token es distinto y tiene el formato que el dominio acepta`() {
        val generated = (1..200).map { codec.generate() }

        assertThat(generated.toSet()).hasSize(200)
        assertThat(generated).allMatch { RefreshToken.of(it) != null }
    }

    @Test
    fun `el hash es SHA-256 en hexadecimal y es estable`() {
        val token = codec.generate()

        assertThat(codec.hash(token)).hasSize(64).matches("[0-9a-f]{64}")
        assertThat(codec.hash(token)).isEqualTo(codec.hash(token))
        assertThat(codec.hash(token)).isNotEqualTo(codec.hash(codec.generate()))
    }
}
