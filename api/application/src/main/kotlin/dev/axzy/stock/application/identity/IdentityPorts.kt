package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.identity.RevocationReason
import java.time.Instant

/** Lo que hace falta saber de una cuenta para autenticarla. Nunca sale de la aplicación. */
data class UserAccount(
    val id: Long,
    val username: String,
    val name: String,
    val passwordHash: String,
    val disabled: Boolean,
    val mustChangePassword: Boolean,
    val passwordChangedAt: Instant?,
)

/** Un [Actor] tal como está ahora en la base, con lo necesario para validar el token que lo trajo. */
data class ActorSnapshot(
    val actor: Actor,
    val passwordChangedAt: Instant?,
) {
    /**
     * El sello de sus credenciales: cambia cada vez que cambia la contraseña. Viaja dentro del
     * token de acceso, y un token cuyo sello ya no coincide con el de la cuenta deja de servir.
     *
     * Es una comparación exacta. Comparar la hora de emisión del token con la del cambio no lo es:
     * el token guarda segundos, y uno emitido en el mismo segundo que el cambio seguiría valiendo.
     */
    val credentialsStamp: Long get() = passwordChangedAt?.toEpochMilli() ?: 0L
}

/** Las cuentas de usuario. */
interface UserAccounts {
    fun findByUsername(username: String): UserAccount?

    fun findById(id: Long): UserAccount?

    /**
     * La cuenta con sus accesos: roles, alcance por permiso y bodegas. **Número fijo de
     * consultas**, sea cual sea el número de roles: se llama en cada petición autenticada.
     *
     * Una cuenta desactivada se devuelve con `actor.user.active = false`; decidir qué hacer con
     * ella es de quien llama.
     */
    fun loadActor(userId: Long): ActorSnapshot?

    fun recordLogin(userId: Long, at: Instant)

    /** Cambia la contraseña y deja de exigir el cambio. */
    fun updatePassword(userId: Long, passwordHash: String, at: Instant)
}

/**
 * Los intentos de inicio de sesión. Van a su propia tabla y no a la bitácora: se escriben mucho
 * más, y un atacante no debe poder inundar un libro que se escribe en serie.
 */
interface LoginAttempts {
    fun record(username: String, userId: Long?, succeeded: Boolean, failureCode: String?, origin: RequestOrigin, at: Instant)

    /** Fallos de credenciales de ese nombre de usuario desde [since]. Exista o no la cuenta. */
    fun failedSince(username: String, since: Instant): Int
}

data class StoredRefreshToken(
    val id: Long,
    val userId: Long,
    val familyId: String,
    val generation: Int,
    val issuedAt: Instant,
    val expiresAt: Instant,
    val rotatedAt: Instant?,
    val replacedByHash: String?,
    val revokedAt: Instant?,
    val revocationReason: RevocationReason?,
)

/** Los refresh tokens. Solo se guarda su hash: un volcado de la tabla no permite suplantar a nadie. */
interface RefreshTokens {
    fun store(tokenHash: String, userId: Long, familyId: String, generation: Int, issuedAt: Instant, expiresAt: Instant, origin: RequestOrigin)

    /**
     * Busca el token **bloqueando su fila** hasta el fin de la transacción. Dos renovaciones
     * simultáneas con el mismo token se atienden una tras otra: la segunda ya lo ve rotado.
     */
    fun findForUpdate(tokenHash: String): StoredRefreshToken?

    fun markRotated(tokenHash: String, replacedByHash: String, at: Instant)

    fun nextGeneration(familyId: String): Int

    /** Revoca los tokens vivos de la familia. Devuelve cuántos. */
    fun revokeFamily(familyId: String, reason: RevocationReason): Int

    /** Revoca la familia del token presentado, si existe. Devuelve cuántos tokens. */
    fun revokeFamilyOf(tokenHash: String, reason: RevocationReason): Int

    fun revokeAllForUser(userId: Long, reason: RevocationReason): Int
}

data class IssuedAccessToken(val token: String, val expiresAt: Instant)

/** Emite el token de acceso. Solo identifica: los permisos NO viajan en él. */
fun interface AccessTokenIssuer {
    /** @param credentialsStamp el sello de credenciales de la cuenta al emitir ([ActorSnapshot.credentialsStamp]). */
    fun issue(userId: Long, familyId: String, credentialsStamp: Long, issuedAt: Instant, expiresAt: Instant): IssuedAccessToken
}

/** Genera refresh tokens opacos y calcula el hash con el que se guardan. */
interface RefreshTokenCodec {
    fun generate(): String
    fun hash(token: String): String
    fun newFamilyId(): String
}
