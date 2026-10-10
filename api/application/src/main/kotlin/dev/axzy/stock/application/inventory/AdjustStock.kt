package dev.axzy.stock.application.inventory

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.application.shared.toOutcome
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentPolicy
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.AdjustmentRequest
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.util.UUID

/** The result of a movement, as the contract returns it. */
enum class MovementStatus { APPLIED, PENDING_APPROVAL }

data class MovementResult(
    val kardexIds: List<Long>,
    val status: MovementStatus,
    /** Product total after the movement. `null` when the total does not change (locating, moving). */
    val total: Quantity?,
    val message: String? = null,
)

/** An adjustment, already translated from the HTTP request. */
data class AdjustmentInput(
    val productCode: String?,
    val locationCode: String?,
    val quantity: Quantity?,
    val direction: String?,
    val reason: String?,
    val origin: String?,
    val note: String?,
    val approverId: Long?,
)

/**
 * An adjustment's permission **depends on the direction and the reason**.
 *
 * There is no generic "adjust" permission, and that is deliberate: increasing is not the same as
 * shrinking, and a theft is not a counting error. A single permission would give
 * anyone the ability to cover up a theft with an adjustment they sign themselves.
 * (docs/api/inventario.md).
 */
fun adjustmentPermission(direction: AdjustmentDirection, reason: AdjustmentReason): String = when {
    direction == AdjustmentDirection.INCREASE -> "inventory.adjust_increase"
    reason == AdjustmentReason.THEFT -> "inventory.adjust_theft"
    reason == AdjustmentReason.DAMAGE -> "inventory.adjust_damage"
    reason == AdjustmentReason.SHORTAGE -> "inventory.adjust_shortage"
    else -> "inventory.adjust_count_error"
}

/**
 * Adjusts stock: the single rule that unifies the legacy's four origins
 * (docs/flujos/existencias-y-ajustes.md §4).
 *
 * The order is not accidental:
 *
 *  1. **Validate and authorize before touching the database.** An adjustment that cannot be
 *     made must not open a connection.
 *  2. **Lock the balance inside the transaction** and decide with that number. The table's
 *     `CHECK` also prevents a negative balance, but its message says
 *     "stock_non_negative"; the use case can say **how much there is** (Q9).
 *  3. **Write only if the decision is to apply.** A pending adjustment
 *     awaiting approval **does not touch the inventory**: a stock change without authorization is
 *     exactly what approval prevents.
 */
class AdjustStock(
    private val catalog: InventoryCatalog,
    private val ledger: StockLedger,
    private val accounts: UserAccounts,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    /** What is needed to apply an adjustment, already validated. */
    private data class Parsed(
        val code: ProductCode,
        val location: LocationRef,
        val quantity: Quantity,
        val direction: AdjustmentDirection,
        val reason: AdjustmentReason,
        val origin: AdjustmentOrigin,
        val note: String?,
        val approverId: Long?,
    )

    fun execute(
        actor: Actor,
        input: AdjustmentInput,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
    ): Outcome<MovementResult> =
        parse(input).flatMap { parsed ->
            val permission = adjustmentPermission(parsed.direction, parsed.reason)
            access.authorizeOr(actor, permission, origin) { grant ->
                // The request's warehouse: the user's, if they have only one, and if not
                // the default one. The movements contract does not carry a `warehouseId`
                // because moving between warehouses is a transfer (M05), not a movement.
                val warehouseId = actor.user.warehouseIds.singleOrNull() ?: catalog.defaultWarehouseId()
                val location = catalog.activeLocation(warehouseId, parsed.location)
                    ?: return@authorizeOr DomainError.LocationNotFound(parsed.location.displayName).toFailure().asLeft()

                // Second check, now with the REAL warehouse of the location: the `AREA`/`OWN`
                // scope is checked against the resource, not against an assumption.
                access.authorizeOr(
                    actor, permission, origin,
                    resource = ResourceContext(warehouseId = location.warehouseId, locationKind = location.kind.name),
                    entityType = "location",
                    entityId = location.id.toString(),
                ) { scopedGrant ->
                    val product = catalog.activeProduct(parsed.code)
                        ?: return@authorizeOr DomainError.ProductNotFound(parsed.code.value).toFailure().asLeft()
                    apply(actor, product, location, parsed, idempotencyKey, origin, scopedGrant)
                }
            }
        }

    private fun apply(
        actor: Actor,
        product: Product,
        location: Location,
        parsed: Parsed,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<MovementResult> = tx.inTransaction {
        // ── Idempotency: repeating the key returns the SAME movement (A8) ──────
        val alreadyApplied = idempotencyKey?.let { ledger.movementIdOf(it) }
        if (alreadyApplied != null) {
            return@inTransaction MovementResult(
                kardexIds = listOf(alreadyApplied),
                status = MovementStatus.APPLIED,
                total = ledger.productTotal(product.id),
                message = "This movement was already applied: the idempotency key was repeated.",
            ).asRight()
        }

        // The balance is read WITH A LOCK when the adjustment subtracts: that way the number
        // that is checked is still true when writing (Q9).
        val available = if (parsed.direction == AdjustmentDirection.DECREASE) {
            ledger.balanceAt(product.id, location.id, forUpdate = true)
        } else {
            null
        }

        AdjustmentRequest.create(
            productCode = parsed.code,
            locationLabel = location.displayName,
            quantity = parsed.quantity,
            direction = parsed.direction,
            reason = parsed.reason,
            origin = parsed.origin,
            note = parsed.note,
            requestedBy = actor.id,
            idempotencyKey = idempotencyKey?.toString(),
        ).toOutcome().flatMap { request ->
            // **Whoever approves has to be able to approve.** Until now it was enough to send someone else's
            // `approverId`: anyone with the reason's permission could sign their own
            // adjustment with a supervisor's identifier, and the permission
            // `inventory.approve_adjustment` —which the catalog grants, documents and audits— was not
            // checked by anyone. A permission that is not checked is a permission that does not exist.
            val sinPermisoDeAprobar = approverDenial(parsed.approverId, location.warehouseId, origin)
            if (sinPermisoDeAprobar != null) {
                sinPermisoDeAprobar.asLeft()
            } else when (val decision = AdjustmentPolicy.decide(request, parsed.approverId, available)) {
                is AdjustmentPolicy.Decision.Reject -> decision.error.toFailure().asLeft()

                // Pending approval: nothing is written, neither ledger nor audit log.
                is AdjustmentPolicy.Decision.PendingApproval -> MovementResult(
                    kardexIds = emptyList(),
                    status = MovementStatus.PENDING_APPROVAL,
                    total = null,
                    message = decision.reason,
                ).asRight()

                AdjustmentPolicy.Decision.Apply -> {
                    val isIncrease = parsed.direction == AdjustmentDirection.INCREASE
                    val kardexId = ledger.apply(
                        MovementCommand(
                            productId = product.id,
                            warehouseId = location.warehouseId,
                            moveType = parsed.direction.movementType,
                            units = parsed.quantity,
                            fromLocationId = if (isIncrease) null else location.id,
                            toLocationId = if (isIncrease) location.id else null,
                            adjustmentReason = parsed.reason,
                            adjustmentOrigin = parsed.origin,
                            userId = actor.id,
                            clientUuid = idempotencyKey,
                            note = parsed.note?.trim()?.ifBlank { null },
                        ),
                    )
                    val total = ledger.productTotal(product.id)
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "INVENTORY_ADJUSTED",
                            // Moves inventory: critical level and in the SAME transaction (U1).
                            level = AuditLevel.CRITICAL,
                            entity = AuditEntity("product", product.id.toString(), parsed.code.value),
                            grant = grant,
                            previousState = mapOf(
                                "location" to location.displayName,
                                "available" to available?.toString(),
                            ),
                            newState = mapOf(
                                "kardexId" to kardexId,
                                "total" to total.toString(),
                                "direction" to parsed.direction.name,
                                "reason" to parsed.reason.name,
                            ),
                            reason = parsed.note?.trim()?.ifBlank { null },
                            idempotencyKey = idempotencyKey?.toString(),
                        ),
                    )
                    MovementResult(listOf(kardexId), MovementStatus.APPLIED, total).asRight()
                }
            }
        }
    }

    // ── Validation ─────────────────────────────────────────────────────────

    private fun parse(input: AdjustmentInput): Outcome<Parsed> =
        parseEnum(input.direction, AdjustmentDirection.entries, "direction").flatMap { direction: AdjustmentDirection ->
            parseReason(input.reason).flatMap { reason: AdjustmentReason ->
                parseEnum(input.origin, AdjustmentOrigin.entries, "origin").flatMap { origin: AdjustmentOrigin ->
                    InventoryFilters.productCode(input.productCode).flatMap { code: ProductCode? ->
                        if (code == null) {
                            failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The product code is required.")
                        } else {
                            InventoryFilters.locationRef(input.locationCode).flatMap { ref: LocationRef? ->
                                when {
                                    ref == null -> failure(
                                        FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                                        "The location code is required.",
                                    )
                                    input.quantity == null -> failure(
                                        FailureKind.RULE_VIOLATION, "INVALID_QUANTITY",
                                        "The quantity is required.",
                                    )
                                    else -> Parsed(
                                        code, ref, input.quantity, direction, reason, origin,
                                        input.note, input.approverId,
                                    ).asRight()
                                }
                            }
                        }
                    }
                }
            }
        }

    /**
     * The reason **is not an ordinary parameter**: without it, an adjustment cannot be
     * audited a year later, and that is why its absence is `REASON_REQUIRED` (A5) and not
     * an `INVALID_PARAMETER`. A reason that is not on the list is an invalid
     * parameter: the client sent something that is not admitted.
     */
    private fun parseReason(raw: String?): Outcome<AdjustmentReason> {
        if (raw.isNullOrBlank()) {
            return failure(
                FailureKind.RULE_VIOLATION, "REASON_REQUIRED",
                "Every stock adjustment requires a reason.",
            )
        }
        val allowed = AdjustmentReason.entries.joinToString(",") { it.name }
        val value = AdjustmentReason.entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The value of 'reason' is not in the list of allowed values.",
                mapOf("reason" to raw, "allowed" to allowed),
            )
        return value.asRight()
    }

    private fun <E : Enum<E>> parseEnum(raw: String?, values: List<E>, field: String): Outcome<E> {
        val allowed = values.joinToString(",") { it.name }
        if (raw == null) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Falta '$field'. Se admiten: $allowed.",
                mapOf(field to "", "allowed" to allowed),
            )
        }
        val value = values.firstOrNull { it.name == raw.trim().uppercase() }
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The value of '$field' is not in the list of allowed values.",
                mapOf(field to raw, "allowed" to allowed),
            )
        return value.asRight()
    }

    /**
     * The approver's failure, if there is one.
     *
     * Returns `null` when no approver is needed or when the one that comes can approve. The
     * scope is checked **in the adjustment location's warehouse**: an `AREA` approves only
     * in its own.
     */
    private fun approverDenial(approverId: Long?, warehouseId: Long, origin: RequestOrigin): Failure? {
        if (approverId == null) return null
        val approver = accounts.loadActor(approverId)?.actor
            ?: return Failure(
                FailureKind.RULE_VIOLATION, "APPROVER_NOT_FOUND",
                "Approver $approverId does not exist.",
                mapOf("approverId" to approverId.toString()),
            )
        val decision = access.authorize(
            actor = approver,
            permission = "inventory.approve_adjustment",
            origin = origin,
            resource = ResourceContext(warehouseId = warehouseId),
            entity = AuditEntity("user", approverId.toString(), approver.username),
        )
        return if (decision is Either.Left) {
            Failure(
                FailureKind.FORBIDDEN, "APPROVER_NOT_AUTHORIZED",
                "Approver ${approver.username} does not have permission to approve adjustments in this warehouse.",
                mapOf(
                    "approverId" to approverId.toString(),
                    "approver" to approver.username,
                    "permission" to "inventory.approve_adjustment",
                ),
            )
        } else {
            null
        }
    }
}
