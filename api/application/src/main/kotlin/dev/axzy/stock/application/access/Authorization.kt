package dev.axzy.stock.application.access

import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.common.Either

/**
 * Autoriza y, solo si se concede, sigue con el cuerpo del caso de uso.
 *
 * Existe para que ningún caso de uso pueda **olvidarse de comprobar el
 * resultado**: `access.authorize(...)` devuelve un `Outcome`, y quien lo ignore
 * con `_ =` o con un `if` mal escrito deja la puerta abierta. Aquí el cuerpo no
 * se ejecuta si la decisión es denegar, y el fallo sale tal cual hacia el
 * cliente, con su código (`PERMISSION_DENIED`, `SCOPE_INSUFFICIENT`, …).
 *
 * Es `inline` a propósito: el `return@authorizeOr` del cuerpo sale del caso de
 * uso, no de una lambda intermedia.
 */
inline fun <T> AccessDecider.authorizeOr(
    actor: Actor,
    permission: String,
    origin: RequestOrigin,
    resource: ResourceContext = ResourceContext(),
    entityType: String = permission.substringBefore('.'),
    entityId: String? = null,
    block: (AccessGrant) -> Outcome<T>,
): Outcome<T> = when (
    val decision = authorize(
        actor, permission, origin, resource,
        AuditEntity(type = entityType, id = entityId),
    )
) {
    is Either.Left -> decision
    is Either.Right -> block(decision.value)
}
