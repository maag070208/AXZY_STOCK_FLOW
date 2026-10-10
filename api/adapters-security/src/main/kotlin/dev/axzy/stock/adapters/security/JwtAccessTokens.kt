package dev.axzy.stock.adapters.security

import com.nimbusds.jose.jwk.source.ImmutableSecret
import dev.axzy.stock.application.identity.AccessTokenIssuer
import dev.axzy.stock.application.identity.IssuedAccessToken
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/**
 * Access tokens: JWTs signed with HMAC-SHA256.
 *
 * **The token only identifies** (who, which session, until when). Permissions are NOT carried in it:
 * they are read from the database on every request. With permissions inside, removing one from someone or
 * deactivating their account had no effect until the token expired, and the token also weighed
 * several kilobytes on every request.
 *
 * The same object issues and delivers the [decoder] that verifies, so that the two halves share
 * key, issuer and audience by construction.
 */
class JwtAccessTokens(
    secret: String,
    private val issuer: String,
    private val audience: String,
    /** The clock used to check expiry: the same one the application timestamps the tokens with. */
    clock: Clock = Clock.systemUTC(),
) : AccessTokenIssuer {

    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "The signing secret must be at least $MIN_SECRET_BYTES bytes: a shorter one can be " +
                "brute-force guess and would allow signing valid tokens."
        }
    }

    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val encoder = NimbusJwtEncoder(ImmutableSecret(key))

    /** Verifies signature, expiry, issuer and audience. Used by the HTTP security chain. */
    val decoder: JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
        setJwtValidator(
            DelegatingOAuth2TokenValidator(
                // No clock tolerance: whoever issues and whoever verifies are the same process.
                JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) },
                JwtIssuerValidator(issuer),
                OAuth2TokenValidator<Jwt> { jwt ->
                    if (jwt.audience.orEmpty().contains(audience)) OAuth2TokenValidatorResult.success()
                    else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "The token audience is not this API.", null))
                },
            ),
        )
    }

    override fun issue(userId: Long, familyId: String, credentialsStamp: Long, issuedAt: Instant, expiresAt: Instant): IssuedAccessToken {
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(listOf(audience))
            .subject(userId.toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            // The session's family: it allows knowing which sign-in a request comes from.
            .claim(CLAIM_FAMILY, familyId)
            // The credentials stamp: if the password changes, this token stops matching the account.
            .claim(CLAIM_CREDENTIALS, credentialsStamp)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = encoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
        return IssuedAccessToken(token, expiresAt)
    }

    companion object {
        const val CLAIM_FAMILY = "fam"
        const val CLAIM_CREDENTIALS = "pwd"
        const val MIN_SECRET_BYTES = 32
    }
}
