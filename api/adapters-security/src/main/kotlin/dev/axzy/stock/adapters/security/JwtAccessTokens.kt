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
 * Los tokens de acceso: JWT firmados con HMAC-SHA256.
 *
 * **El token solo identifica** (quién, de qué sesión, hasta cuándo). Los permisos NO viajan en él:
 * se leen de la base en cada petición. Con los permisos dentro, quitarle uno a alguien o
 * desactivar su cuenta no surtía efecto hasta que el token caducaba, y además el token pesaba
 * varios kilobytes en cada petición.
 *
 * El mismo objeto emite y entrega el [decoder] que verifica, para que las dos mitades compartan
 * clave, emisor y audiencia por construcción.
 */
class JwtAccessTokens(
    secret: String,
    private val issuer: String,
    private val audience: String,
    /** El reloj con el que se comprueba la caducidad: el mismo con el que la aplicación fecha los tokens. */
    clock: Clock = Clock.systemUTC(),
) : AccessTokenIssuer {

    init {
        require(secret.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES) {
            "El secreto de firma debe tener al menos $MIN_SECRET_BYTES bytes: uno más corto se puede " +
                "adivinar por fuerza bruta y permitiría firmar tokens válidos."
        }
    }

    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val encoder = NimbusJwtEncoder(ImmutableSecret(key))

    /** Verifica firma, caducidad, emisor y audiencia. Lo usa la cadena de seguridad HTTP. */
    val decoder: JwtDecoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build().apply {
        setJwtValidator(
            DelegatingOAuth2TokenValidator(
                // Sin tolerancia de reloj: quien emite y quien verifica son el mismo proceso.
                JwtTimestampValidator(Duration.ZERO).apply { setClock(clock) },
                JwtIssuerValidator(issuer),
                OAuth2TokenValidator<Jwt> { jwt ->
                    if (jwt.audience.orEmpty().contains(audience)) OAuth2TokenValidatorResult.success()
                    else OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "La audiencia del token no es esta API.", null))
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
            // La familia de la sesión: permite saber de qué inicio de sesión viene una petición.
            .claim(CLAIM_FAMILY, familyId)
            // El sello de credenciales: si la contraseña cambia, este token deja de coincidir con la cuenta.
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
