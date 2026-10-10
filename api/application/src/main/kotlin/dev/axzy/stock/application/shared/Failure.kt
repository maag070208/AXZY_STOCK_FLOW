package dev.axzy.stock.application.shared

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError

/**
 * Por qué falló un caso de uso, sin decir nada de HTTP. `adapters-web` traduce el tipo a un
 * código de estado; un cliente que no sea HTTP (un job, la app) lo traduce a lo suyo.
 */
enum class FailureKind {
    /** La petición está mal formada: no se puede ni empezar a evaluarla. */
    INVALID_REQUEST,

    /** No hay identidad válida: el remedio es iniciar sesión. */
    UNAUTHENTICATED,

    /** Hay identidad, pero no puede hacer esto: el remedio es otro permiso, otra persona u otro momento. */
    FORBIDDEN,

    NOT_FOUND,

    /** El estado actual del recurso impide la operación. */
    CONFLICT,

    /** La petición está bien formada pero viola una regla de negocio. */
    RULE_VIOLATION,
}

/**
 * Un fallo esperado. `code` es estable y es con lo que decide el cliente (docs/api/errores.md);
 * `message` es para una persona y puede cambiar.
 */
data class Failure(
    val kind: FailureKind,
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/** El resultado de todo caso de uso: un fallo esperado se DEVUELVE, no se lanza (ADR-0005). */
typealias Outcome<A> = Either<Failure, A>

fun failure(kind: FailureKind, code: String, message: String, details: Map<String, String> = emptyMap()): Outcome<Nothing> =
    Failure(kind, code, message, details).asLeft()

/**
 * Traduce un resultado del dominio a un resultado de caso de uso.
 *
 * Existe para que componer reglas del dominio (`withUpdated`, `deactivate`) con el
 * cuerpo de un caso de uso no obligue a un `when` de dos ramas por cada paso.
 */
fun <A> DomainResult<A>.toOutcome(): Outcome<A> = when (this) {
    is Either.Left -> error.toFailure().asLeft()
    is Either.Right -> value.asRight()
}

/**
 * Traduce un error del dominio. El `when` es exhaustivo a propósito: un error nuevo en el
 * dominio deja de compilar aquí hasta que alguien decida qué significa para el cliente.
 */
fun DomainError.toFailure(): Failure = when (this) {
    is DomainError.InvalidTransition ->
        Failure(FailureKind.CONFLICT, code, message, mapOf("entity" to entity, "from" to from, "action" to action))
    is DomainError.ExceedsLineQuantity ->
        Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("requested" to "$requested", "available" to "$available"))
    is DomainError.BoxesDoNotDivideEvenly ->
        Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("remainder" to "$remainder"))
    is DomainError.InconsistentBoxCount -> Failure(FailureKind.RULE_VIOLATION, code, message)
    is DomainError.InvalidQuantity -> Failure(FailureKind.RULE_VIOLATION, code, message)
    is DomainError.DiscrepancyNotApproved ->
        Failure(FailureKind.CONFLICT, code, message, mapOf("difference" to "$difference"))
    is DomainError.SegregationOfDuties -> Failure(FailureKind.FORBIDDEN, code, message)
    is DomainError.NotPendingApproval ->
        Failure(FailureKind.CONFLICT, code, message, mapOf("currentStatus" to currentStatus))
    is DomainError.ReasonRequired -> Failure(FailureKind.RULE_VIOLATION, code, message)
    is DomainError.InsufficientStock ->
        Failure(
            FailureKind.RULE_VIOLATION, code, message,
            mapOf("available" to "$available", "requested" to "$requested", "location" to location),
        )
    is DomainError.SameSourceAndDestination -> Failure(FailureKind.RULE_VIOLATION, code, message)
    is DomainError.HasPendingLines ->
        Failure(FailureKind.CONFLICT, code, message, mapOf("pendingLines" to "$pendingCount"))
    is DomainError.CannotDeleteWithInventoryEffect -> Failure(FailureKind.CONFLICT, code, message)
    is DomainError.Duplicate -> Failure(FailureKind.CONFLICT, code, message, mapOf("entity" to entity, "value" to value))
    is DomainError.NotFound -> Failure(FailureKind.NOT_FOUND, code, message, mapOf("entity" to entity, "id" to id))
    is DomainError.PermissionDenied -> Failure(FailureKind.FORBIDDEN, code, message, mapOf("permission" to permission))
    is DomainError.ScopeInsufficient ->
        Failure(FailureKind.FORBIDDEN, code, message, mapOf("permission" to permission, "scope" to scope))
    is DomainError.PolicyDenied -> Failure(FailureKind.FORBIDDEN, code, message, mapOf("policy" to policyName))
    is DomainError.PolicyRequired -> Failure(FailureKind.FORBIDDEN, code, message)
    is DomainError.SameWarehouse -> Failure(FailureKind.RULE_VIOLATION, code, message)
    is DomainError.EmptyTransfer -> Failure(FailureKind.RULE_VIOLATION, code, message)
    is DomainError.OrderAlreadyConfirmed ->
        Failure(FailureKind.CONFLICT, code, message, mapOf("orderNumber" to orderNumber))
    is DomainError.OrderNotConverted ->
        Failure(FailureKind.CONFLICT, code, message, mapOf("orderNumber" to orderNumber))
    is DomainError.ExceedsPendingQuantity ->
        Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("requested" to "$requested", "pending" to "$pending"))
    is DomainError.MissingLabelField ->
        Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("fields" to fields.joinToString(",")))
    is DomainError.LabelAlreadyPrinted -> Failure(FailureKind.CONFLICT, code, message, mapOf("jobId" to "$jobId"))

    // ── Catálogo (M01) ──────────────────────────────────────────────────────
    is DomainError.InvalidProductCode ->
        Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("productCode" to raw))
    is DomainError.UnknownUnitMeasure -> Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("unitMeasure" to unit))
    is DomainError.InvalidLevels -> Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("levels" to "$levels"))
    is DomainError.InvalidLocationKind -> Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("kind" to raw))
    is DomainError.InvalidLocationPart ->
        Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("field" to part))
    is DomainError.UnknownWarehouse -> Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("warehouseId" to "$id"))
    is DomainError.InvalidExternalId ->
        Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("externalId" to raw))
    // El contrato documenta estos dos como 422 aunque su tabla los agrupe en 409: la
    // implementación manda, y el código (`code`) es lo que decide el cliente.
    is DomainError.ProductCodeImmutable -> Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("productCode" to productCode))
    is DomainError.WarehouseImmutable -> Failure(FailureKind.RULE_VIOLATION, code, message, mapOf("locationId" to "$locationId"))
    is DomainError.DuplicateProductCode -> Failure(FailureKind.CONFLICT, code, message, mapOf("productCode" to productCode))
    is DomainError.DuplicateExternalId -> Failure(FailureKind.CONFLICT, code, message, mapOf("externalId" to externalId))
    is DomainError.DuplicateLocation ->
        Failure(FailureKind.CONFLICT, code, message, mapOf("location" to "$hall-$locationCode-$position"))
    is DomainError.DuplicateWarehouseCode -> Failure(FailureKind.CONFLICT, code, message, mapOf("code" to warehouseCode))
    is DomainError.ProductAlreadyInactive -> Failure(FailureKind.CONFLICT, code, message, mapOf("productCode" to productCode))
    is DomainError.LocationAlreadyInactive -> Failure(FailureKind.CONFLICT, code, message, mapOf("location" to locationCode))
    is DomainError.ReceivingLocationProtected -> Failure(FailureKind.CONFLICT, code, message)
    is DomainError.LocationHasStock -> Failure(FailureKind.CONFLICT, code, message, mapOf("quantity" to quantity))
    is DomainError.ProductHasStock -> Failure(FailureKind.CONFLICT, code, message, mapOf("stock" to stock))
    is DomainError.ProductNotFound -> Failure(FailureKind.NOT_FOUND, code, message, mapOf("product" to reference))
    is DomainError.LocationNotFound -> Failure(FailureKind.NOT_FOUND, code, message, mapOf("location" to reference))
    is DomainError.ImportInvalidRows ->
        Failure(
            FailureKind.RULE_VIOLATION, code, message,
            // Un detalle por fila, para que el usuario sepa QUÉ corregir: sin el
            // número de fila, un archivo de 1.398 filas con un error es irresoluble.
            rows.take(MAX_REJECTION_DETAILS).associate { "fila_${it.rowNumber}" to it.reason },
        )
}

/**
 * Cuántas filas rechazadas viajan en el cuerpo del error. Un archivo con miles de
 * filas malas no debe producir una respuesta de megabytes; el resumen dice cuántas
 * hubo y las primeras N son las que se corrigen de una vez.
 */
private const val MAX_REJECTION_DETAILS = 50
