package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.RevocationReason

/** Lo que se revocó, para que la respuesta diga algo y no solo «vale». */
data class SessionsRevoked(val userId: Long, val username: String, val revoked: Int)

/**
 * Cierra **todas** las sesiones de un usuario, desde la cuenta de un administrador (M07,
 * `session.revoke`).
 *
 * Es la respuesta a «a esta persona le robaron el portátil» o «se va de la empresa hoy»: sin
 * esta ruta, cerrar la sesión de otro exigía cambiarle la contraseña, que es una decisión
 * mucho más grande —se la comunicas, la cambia, y con eso invalidas también lo que estaba
 * haciendo—. Revocar es lo pequeño y suficiente.
 *
 * **Lo que corta es la renovación, y hay que decirlo.** El token de acceso ya emitido es un
 * JWT firmado y no se consulta en la base en cada petición, así que sigue siendo válido hasta
 * que expire por su cuenta; lo que se revoca son los tokens de refresco, que son los que
 * mantienen la sesión viva. Para cortar el acceso de inmediato está cambiar la contraseña, que
 * sí mueve el sello de credenciales.
 */
class RevokeUserSessions(
    private val accounts: UserAccounts,
    private val refreshTokens: RefreshTokens,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, userId: Long, origin: RequestOrigin): Outcome<SessionsRevoked> =
        access.authorizeOr(actor, "session.revoke", origin) { grant ->
            tx.inTransaction {
                val account = accounts.findById(userId)
                    ?: return@inTransaction failure(
                        FailureKind.NOT_FOUND, "USER_NOT_FOUND",
                        "No existe el usuario $userId.",
                        mapOf("userId" to userId.toString()),
                    )

                val revoked = refreshTokens.revokeAllForUser(userId, RevocationReason.ADMIN_REVOKED)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "USER_SESSIONS_REVOKED",
                        // Crítico: revocar sesiones es quitarle el acceso a alguien, y quién lo
                        // hizo tiene que poder responderse un año después.
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("user", userId.toString(), account.username),
                        grant = grant,
                        newState = mapOf("revokedSessions" to revoked, "reason" to RevocationReason.ADMIN_REVOKED.name),
                    ),
                )
                SessionsRevoked(userId, account.username, revoked).asRight()
            }
        }
}
