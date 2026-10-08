package dev.axzy.stock.backend.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SERVICIO DE TOKENS
 *
 * Access tokens como **JWT firmados** y refresh tokens como **valores opacos**.
 * La asimetría es deliberada:
 *
 *  · El **access token es un JWT** porque se verifica en cada petición y no se
 *    quiere consultar la base para ello. Es autocontenido: lleva el usuario, sus
 *    roles y sus permisos. Su vida es corta (15 minutos) precisamente porque **no
 *    se puede revocar sin consultar la base**.
 *
 *  · El **refresh token es opaco** porque tiene que poder revocarse. Un JWT de
 *    refresco sería autocontenido y no se podría invalidar sin una lista negra,
 *    que es justo lo que se quiere evitar. Como valor opaco, el servidor lo busca
 *    y revocarlo es cambiar una fila.
 *
 * **Firma HMAC-SHA256 en lugar de RSA.** La decisión depende de quién verifica:
 *  · Con **HMAC**, todos los que verifican pueden también **firmar**. Sirve si el
 *    único que verifica es este backend.
 *  · Con **RSA/ECDSA**, la clave pública verifica pero no firma. Es lo necesario
 *    si un tercero —otro servicio, un gateway— tiene que validar tokens sin poder
 *    emitirlos.
 *
 * Hoy el único verificador es este backend, así que HMAC es suficiente y más
 * simple. **Si eso cambia, hay que migrar a RSA**, y por eso la decisión queda
 * documentada aquí en lugar de quedar implícita.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Los datos que viajan dentro del access token. */
data class AccessTokenClaims(
    val userId: Long,
    val username: String,
    val name: String,
    val roles: List<String>,
    /** Permisos efectivos, ya resueltos. Evita consultar la base en cada petición. */
    val permissions: List<String>,
    /** Alcance por permiso: `invoice.approve` → `AREA`. */
    val scopes: Map<String, String>,
    /** Bodegas del alcance `AREA`. */
    val warehouses: List<Long>,
    /** Identificador del token, para poder correlacionar con la auditoría. */
    val tokenId: String,
    val issuedAt: Instant,
    val expiresAt: Instant,
)

/** Un par de tokens recién emitidos. */
data class IssuedTokens(
    val accessToken: String,
    /** El token opaco, en claro. **Solo existe aquí**: en la base está su hash. */
    val refreshToken: String,
    val expiresInSeconds: Long,
    val refreshExpiresAt: Instant,
)

/**
 * La configuración de los tokens.
 *
 * **El secreto no tiene valor por defecto, y es obligatorio.** Un secreto por
 * defecto es el peor tipo de fallo de seguridad: el sistema funciona, los tests
 * pasan, y cualquiera que lea el repositorio puede firmar tokens válidos.
 */
data class TokenConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    val accessTokenTtlSeconds: Long,
) {
    init {
        require(secret.length >= 32) {
            "El secreto de firma debe tener al menos 32 bytes. Uno corto es " +
                "adivinable por fuerza bruta y permite firmar tokens válidos. " +
                "Longitud actual: ${secret.length}."
        }
    }

    companion object {
        /**
         * Lee la configuración del entorno.
         *
         * @throws IllegalStateException si falta el secreto. Se prefiere no
         *   arrancar que arrancar con un secreto conocido.
         */
        fun fromEnvironment(): TokenConfig {
            val secret = System.getenv("STOCK_JWT_SECRET")?.takeIf { it.isNotBlank() }
                ?: error(
                    "Falta STOCK_JWT_SECRET. Genéralo con:\n" +
                        "  openssl rand -base64 48\n" +
                        "No hay valor por defecto: un secreto conocido permite a " +
                        "cualquiera firmar tokens válidos, y el sistema seguiría " +
                        "funcionando sin que nadie lo note."
                )

            return TokenConfig(
                secret = secret,
                issuer = System.getenv("STOCK_JWT_ISSUER") ?: "stock-almacen",
                audience = System.getenv("STOCK_JWT_AUDIENCE") ?: "stock-api",
                accessTokenTtlSeconds = System.getenv("STOCK_JWT_TTL_SECONDS")?.toLongOrNull()
                    ?: 15 * 60L,
            )
        }
    }
}

/** El resultado de verificar un access token. */
sealed interface TokenVerification {
    data class Valid(val claims: AccessTokenClaims) : TokenVerification

    /** El token venció. Es el caso normal y esperado: el cliente debe refrescar. */
    data object Expired : TokenVerification

    /**
     * El token no es válido: firma incorrecta, emisor distinto, audiencia
     * distinta, o formato ilegible.
     *
     * **No se distingue el motivo hacia fuera a propósito.** Decir «la firma es
     * incorrecta» frente a «el emisor es incorrecto» ayuda a quien está probando
     * a fabricar un token.
     */
    data object Invalid : TokenVerification
}

class TokenService(private val config: TokenConfig) {

    /**
     * El algoritmo de firma.
     *
     * `HMAC256` con un secreto de al menos 32 bytes. **La longitud se valida en
     * [TokenConfig]**, porque HMAC-SHA256 con un secreto corto es tan débil como
     * un hash sin sal.
     */
    private val algorithm: Algorithm = Algorithm.HMAC256(config.secret)

    /**
     * El verificador, construido **una sola vez**.
     *
     * Se fijan `issuer` y `audience`, y eso importa: sin `audience`, un token
     * emitido para otro servicio con el mismo secreto serviría aquí. Sin
     * `issuer`, un token de otro entorno —pruebas, por ejemplo— valdría en
     * producción.
     */
    private val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()

    private val random = SecureRandom()

    /**
     * Emite un access token.
     *
     * Los roles, permisos y alcances van **dentro** del token, así que una
     * petición autenticada no necesita consultar la base para autorizar. El precio
     * es que un cambio de permisos tarda hasta 15 minutos en aplicarse; es un
     * intercambio aceptado y por eso la vida del token es corta.
     */
    fun issueAccessToken(
        claims: TokenSubject,
    ): Pair<String, Instant> {
        val now = Instant.now()
        val expires = now.plusSeconds(config.accessTokenTtlSeconds)

        val token = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(claims.userId.toString())
            .withJWTId(claims.tokenId)
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(expires))
            // Se usa `withClaim` con tipos simples: un `Map` anidado se
            // serializaría de forma implícita y el contrato quedaría sin
            // documentar.
            .withClaim(CLAIM_USERNAME, claims.username)
            .withClaim(CLAIM_NAME, claims.name)
            .withClaim(CLAIM_ROLES, claims.roles)
            .withClaim(CLAIM_PERMISSIONS, claims.permissions)
            .withClaim(CLAIM_SCOPES, claims.scopes)
            .withClaim(CLAIM_WAREHOUSES, claims.warehouses)
            .sign(algorithm)

        return token to expires
    }

    /**
     * Verifica un access token.
     *
     * **Se distingue «vencido» de «inválido», y eso es una decisión de
     * interfaz.** Un token vencido significa «refresca»; uno inválido significa
     * «vuelve a entrar». Si se devolviera lo mismo para ambos, el cliente no
     * sabría si intentar el refresco o pedir credenciales, y acabaría pidiendo
     * credenciales cada 15 minutos.
     */
    fun verify(token: String): TokenVerification = try {
        val jwt = verifier.verify(token)
        TokenVerification.Valid(jwt.toClaims())
    } catch (_: TokenExpiredException) {
        TokenVerification.Expired
    } catch (_: JWTVerificationException) {
        TokenVerification.Invalid
    } catch (_: Exception) {
        // Un token con formato ilegible lanza otra excepción. Se trata como
        // inválido y no se propaga: un token basura no debe provocar un 500.
        TokenVerification.Invalid
    }

    /**
     * Lee los datos de un token ya verificado.
     *
     * Los permisos y el alcance se recuperan como listas y mapas. Si un campo
     * falta —un token emitido por una versión anterior— se devuelve vacío en
     * lugar de lanzar: un token sin permisos no autoriza nada, que es la
     * respuesta segura.
     */
    private fun DecodedJWT.toClaims(): AccessTokenClaims {
        // `asMap()` sin argumentos devuelve un mapa genérico. Se convierte con
        // `orEmpty()` y un `mapValues` explícito: pedir un tipo concreto con
        // `asMap(String::class.java)` no existe en esta versión de la librería, y
        // el tipo que devuelve es `Map<String, Any!>?` de la plataforma.
        @Suppress("UNCHECKED_CAST")
        val scopesRaw = (getClaim(CLAIM_SCOPES).asMap() as? Map<String, Any?>).orEmpty()

        return AccessTokenClaims(
            userId = subject.toLongOrNull() ?: 0L,
            username = getClaim(CLAIM_USERNAME).asString() ?: "",
            name = getClaim(CLAIM_NAME).asString() ?: "",
            roles = getClaim(CLAIM_ROLES).asList(String::class.java) ?: emptyList(),
            permissions = getClaim(CLAIM_PERMISSIONS).asList(String::class.java) ?: emptyList(),
            scopes = scopesRaw.mapValues { it.value?.toString() ?: "" },
            warehouses = getClaim(CLAIM_WAREHOUSES)
                .asList(Long::class.javaObjectType)
                ?.mapNotNull { it }
                ?: emptyList(),
            tokenId = id ?: "",
            issuedAt = issuedAt?.toInstant() ?: Instant.EPOCH,
            expiresAt = expiresAt?.toInstant() ?: Instant.EPOCH,
        )
    }

    /**
     * Genera un refresh token opaco.
     *
     * **256 bits de `SecureRandom`**, codificados en base64url sin relleno, que
     * produce 43 caracteres. El `SecureRandom` de la plataforma es un CSPRNG: usar
     * `Random` o un UUID —que tiene 122 bits y una estructura predecible— sería un
     * error, porque el token es la única prueba de identidad del refresco.
     */
    fun generateRefreshToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Calcula el hash con el que se guarda un refresh token.
     *
     * **Se guarda el hash, no el token.** Si alguien obtiene una copia de la
     * tabla —un volcado, una copia de seguridad mal protegida— no puede suplantar
     * a nadie.
     *
     * **SHA-256 y no Argon2, a propósito.** El token ya tiene 256 bits de entropía
     * aleatoria: no es adivinable, así que un hash lento solo encarecería cada
     * refresco sin aportar seguridad. Con una contraseña es al revés: es
     * adivinable, y por eso necesita un hash lento.
     */
    fun hashRefreshToken(token: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(token.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    /** Un identificador nuevo de familia de tokens. */
    fun newFamilyId(): String = UUID.randomUUID().toString()

    private companion object {
        const val CLAIM_USERNAME = "username"
        const val CLAIM_NAME = "name"
        const val CLAIM_ROLES = "roles"
        const val CLAIM_PERMISSIONS = "permissions"
        const val CLAIM_SCOPES = "scopes"
        const val CLAIM_WAREHOUSES = "warehouses"
    }
}

/**
 * Los datos que identifican al sujeto de un token.
 *
 * Se separa de [AccessTokenClaims] para que quien emite **no pueda** fijar las
 * fechas de emisión y vencimiento: son responsabilidad del servicio, y permitir
 * que el llamador las ponga abre la puerta a emitir un token con una vida
 * arbitraria. El `tokenId` sí se puede pasar, para poder correlacionar con la
 * auditoría antes de emitir.
 */
data class TokenSubject(
    val userId: Long,
    val username: String,
    val name: String,
    val roles: List<String>,
    val permissions: List<String>,
    val scopes: Map<String, String>,
    val warehouses: List<Long>,
    val tokenId: String = UUID.randomUUID().toString(),
)
