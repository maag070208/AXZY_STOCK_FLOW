package dev.axzy.stock.application.shared

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError

/**
 * Why a use case failed, without saying anything about HTTP. `adapters-web` translates the type to a
 * status code; a client that is not HTTP (a job, the app) translates it to its own.
 */
enum class FailureKind {
    /** The request is malformed: it cannot even start to be evaluated. */
    INVALID_REQUEST,

    /** There is no valid identity: the remedy is to sign in. */
    UNAUTHENTICATED,

    /** There is an identity, but it cannot do this: the remedy is another permission, another person or another moment. */
    FORBIDDEN,

    NOT_FOUND,

    /** The resource's current state prevents the operation. */
    CONFLICT,

    /** The request is well formed but violates a business rule. */
    RULE_VIOLATION,
}

/**
 * An expected failure. `code` is stable and is what the client decides with (docs/api/errores.md);
 * `message` is for a person and may change.
 */
data class Failure(
    val kind: FailureKind,
    val code: String,
    val message: String,
    val details: Map<String, String> = emptyMap(),
)

/** The result of every use case: an expected failure is RETURNED, not thrown (ADR-0005). */
typealias Outcome<A> = Either<Failure, A>

fun failure(kind: FailureKind, code: String, message: String, details: Map<String, String> = emptyMap()): Outcome<Nothing> =
    Failure(kind, code, message, details).asLeft()

/**
 * Translates a domain result into a use-case result.
 *
 * It exists so that composing domain rules (`withUpdated`, `deactivate`) with the
 * body of a use case does not require a two-branch `when` for each step.
 */
fun <A> DomainResult<A>.toOutcome(): Outcome<A> = when (this) {
    is Either.Left -> error.toFailure().asLeft()
    is Either.Right -> value.asRight()
}

/**
 * Translates a domain error. The `when` is exhaustive on purpose: a new error in the
 * domain stops compiling here until someone decides what it means for the client.
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

    // ── Catalog (M01) ──────────────────────────────────────────────────────
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
    // The contract documents these two as 422 although its table groups them under 409: the
    // implementation prevails, and the code (`code`) is what the client decides on.
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
            // One detail per row, so the user knows WHAT to correct: without the
            // row number, a file of 1,398 rows with one error is unresolvable.
            rows.take(MAX_REJECTION_DETAILS).associate { "row_${it.rowNumber}" to it.reason },
        )
}

/**
 * How many rejected rows travel in the error body. A file with thousands of
 * bad rows must not produce a response of megabytes; the summary says how many
 * there were, and the first N are the ones corrected at once.
 */
private const val MAX_REJECTION_DETAILS = 50
