package dev.axzy.stock.application.access

import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.common.Either

/**
 * Authorizes and, only if granted, continues with the use case's body.
 *
 * It exists so that no use case can **forget to check the**
 * result**: `access.authorize(...)` returns an `Outcome`, and whoever ignores it
 * with `_ =` or with a badly written `if` leaves the door open. Here the body
 * does not run if the decision is to deny, and the failure goes out as it is to the
 * client, with its code (`PERMISSION_DENIED`, `SCOPE_INSUFFICIENT`, …).
 *
 * It is `inline` on purpose: the `return@authorizeOr` in the body exits the use case,
 * not an intermediate lambda.
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
