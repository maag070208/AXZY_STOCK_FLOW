package dev.axzy.stock.application.identity

import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.domain.identity.PasswordPolicy
import dev.axzy.stock.domain.identity.PasswordValidation
import dev.axzy.stock.domain.identity.RefreshToken
import dev.axzy.stock.domain.identity.RevocationReason
import java.time.Clock

/**
 * Cierra la sesión: revoca el refresh token presentado y su familia.
 *
 * **Nunca falla.** Es idempotente y no exige un token válido: si alguien quiere cerrar sesión hay
 * que dejarlo, y responder con error dejaría la sesión viva justo cuando pide lo contrario.
 */
class Logout(
    private val refreshTokens: RefreshTokens,
    private val codec: RefreshTokenCodec,
) {
    fun execute(rawToken: String?) {
        val token = rawToken?.let(RefreshToken::of) ?: return
        refreshTokens.revokeFamilyOf(codec.hash(token.value), RevocationReason.LOGOUT)
    }
}

/**
 * Cambia la contraseña e **invalida todas las sesiones**, incluida la actual: quien la cambia
 * suele sospechar que está comprometida. El cambio y la revocación van en la misma transacción;
 * por separado, un fallo entre ambos dejaría la contraseña nueva con las sesiones viejas vivas.
 */
class ChangePassword(
    private val users: UserAccounts,
    private val refreshTokens: RefreshTokens,
    private val hasher: PasswordHasher,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {
    fun execute(actor: Actor, currentPassword: String, newPassword: String, origin: RequestOrigin): Outcome<Unit> {
        val account = users.findById(actor.id)
            ?: return failure(FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED", "El usuario ya no existe.")

        if (!hasher.verify(currentPassword, account.passwordHash)) {
            return failure(FailureKind.UNAUTHENTICATED, "INVALID_CREDENTIALS", "La contraseña actual no es correcta.")
        }
        val validation = PasswordPolicy.validate(newPassword, account.username)
        if (validation is PasswordValidation.Invalid) {
            return failure(FailureKind.RULE_VIOLATION, "WEAK_PASSWORD", PasswordPolicy.explain(validation))
        }
        if (newPassword == currentPassword) {
            return failure(FailureKind.RULE_VIOLATION, "SAME_PASSWORD", "La contraseña nueva debe ser distinta de la actual.")
        }

        // El hash es deliberadamente lento: se calcula ANTES de abrir la transacción para no
        // retener una conexión de la base mientras tanto.
        val newHash = hasher.hash(newPassword)
        val now = clock.instant()
        return tx.inTransaction {
            users.updatePassword(account.id, newHash, now)
            val revoked = refreshTokens.revokeAllForUser(account.id, RevocationReason.PASSWORD_CHANGED)
            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "PASSWORD_CHANGED",
                    level = AuditLevel.RELEVANT,
                    entity = AuditEntity(type = "user", id = account.id.toString(), label = account.username),
                    newState = mapOf("revokedSessions" to revoked),
                ),
            )
            Unit.asRight()
        }
    }
}

/**
 * Reconstruye al [Actor] de una petición a partir de un token ya verificado.
 *
 * Es donde un token todavía vigente deja de servir: si la cuenta se desactivó, o si la contraseña
 * cambió después de emitirse, la petición se rechaza aunque la firma y la caducidad sean válidas.
 */
class ResolveActor(private val users: UserAccounts) {

    fun execute(userId: Long, credentialsStamp: Long): Outcome<Actor> {
        val snapshot = users.loadActor(userId)
            ?: return failure(FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED", "El usuario ya no existe.")
        if (!snapshot.actor.user.active) {
            return failure(FailureKind.UNAUTHENTICATED, "ACCOUNT_DISABLED", "La cuenta está desactivada.")
        }
        if (credentialsStamp != snapshot.credentialsStamp) {
            return failure(
                FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED",
                "La contraseña cambió después de emitirse esta sesión. Vuelve a iniciar sesión.",
            )
        }
        return snapshot.actor.asRight()
    }
}
