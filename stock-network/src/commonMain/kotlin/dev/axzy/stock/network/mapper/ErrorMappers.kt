package dev.axzy.stock.network.mapper

import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.network.error.ApiError

/**
 * Mapper de errores: `ApiError` → `DomainError`.
 *
 * **Para qué sirve:** el móvil necesita decidir con la misma semántica que el
 * backend. Cuando el servidor contesta `422 SEGREGATION_OF_DUTIES`, la pantalla
 * tiene que comportarse igual que si el dominio hubiera detectado la
 * segregación en local (que es justo lo que hace cuando se valida sin señal).
 * Sin esta traducción habría dos maneras de reaccionar al mismo hecho: una para
 * «el servidor lo dijo» y otra para «el dominio lo dijo», y con el tiempo
 * divergen.
 *
 * **Devuelve `null` cuando no hay equivalente.** No se inventa un `DomainError`
 * genérico: un error de infraestructura (`Network`, `ServerUnavailable`) no es
 * un error de dominio, y forzarlo haría que la UI mostrara «no se pudo conectar»
 * como si fuera una regla de negocio.
 */
fun ApiError.toDomainErrorOrNull(): DomainError? = when (this) {
    is ApiError.SegregationOfDuties -> DomainError.SegregationOfDuties(message)

    is ApiError.PermissionDenied -> DomainError.PermissionDenied(
        permission = permission ?: "desconocido",
        message = message,
    )

    is ApiError.ScopeInsufficient -> DomainError.ScopeInsufficient(
        permission = permission ?: "desconocido",
        scope = scope ?: "desconocido",
        message = message,
    )

    is ApiError.PolicyDenied -> DomainError.PolicyDenied(
        policyName = policyName ?: "desconocida",
        message = message,
    )

    is ApiError.NotFound -> DomainError.NotFound(
        entity = resource ?: "recurso",
        id = details?.get("id")?.toString()?.trim('"') ?: "desconocido",
        message = message,
    )

    is ApiError.InsufficientStock -> DomainError.InsufficientStock(
        // El dominio cuenta en milésimas enteras; el contrato, en decimales.
        available = available?.toQuantity()?.value ?: 0L,
        requested = requested?.toQuantity()?.value ?: 0L,
        location = location ?: "desconocida",
        message = message,
    )

    is ApiError.Conflict -> when (code) {
        "DISCREPANCY_NOT_APPROVED" -> DomainError.DiscrepancyNotApproved(
            difference = detailDifference() ?: 0L,
            message = message,
        )

        "HAS_PENDING_LINES" -> DomainError.HasPendingLines(
            pendingCount = (details?.get("pendingLines")?.toString()?.toIntOrNull()) ?: 0,
            message = message,
        )

        "DUPLICATE_PRODUCT_CODE" -> DomainError.Duplicate(
            entity = "producto",
            value = details?.get("productCode")?.toString()?.trim('"') ?: "desconocido",
            message = message,
        )

        "SAME_SOURCE_AND_DESTINATION" -> DomainError.SameSourceAndDestination(
            location = details?.get("location")?.toString()?.trim('"') ?: "desconocida",
            message = message,
        )

        // El resto de conflictos (`PRODUCT_HAS_STOCK`, `ORDER_ALREADY_CONFIRMED`,
        // `ORDER_NOT_CONVERTED`…) no tienen equivalente en el dominio: son
        // reglas del ciclo de vida del documento que el servidor decide.
        else -> null
    }

    is ApiError.Validation -> when (code) {
        "REASON_REQUIRED" -> DomainError.ReasonRequired(
            context = "la operación solicitada",
            message = message,
        )

        else -> null
    }

    is ApiError.Unauthenticated,
    is ApiError.TokenReused,
    is ApiError.InvalidCredentials,
    is ApiError.AccountDisabled,
    is ApiError.AccountLocked,
    is ApiError.RateLimited,
    is ApiError.Network,
    is ApiError.ServerUnavailable,
    is ApiError.MalformedResponse,
    is ApiError.Unknown,
    -> null
}

/** `details.difference` en milésimas, si viene. */
private fun ApiError.Conflict.detailDifference(): Long? =
    details?.get("difference")?.toString()?.trim('"')?.toDoubleOrNull()?.let { it * 1000 }?.toLong()
