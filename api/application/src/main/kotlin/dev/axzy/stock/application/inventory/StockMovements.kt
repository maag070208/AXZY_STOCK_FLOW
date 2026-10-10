package dev.axzy.stock.application.inventory

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
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.util.UUID

/** The body of locate and move: the same one, because the difference is the permission and the rule. */
data class MovementInput(
    val productCode: String?,
    val fromLocationCode: String?,
    val toLocationCode: String?,
    val quantity: Quantity?,
    /**
     * The invoice line the goods leave from, if they come from a receipt.
     *
     * Optional on purpose: locating goods that do not come from an invoice —a
     * regularization, a return— is still possible and has no line.
     */
    val invoiceLineId: Long? = null,
)

/**
 * The two operations that **move** stock without changing the total.
 *
 * They are distinguished on purpose, not just by name: locating takes out of receiving
 * and moving reorganizes shelves. One operator has the first and not necessarily the
 * second, and that is why they are different permissions (`invoice.locate` versus
 * `inventory.move`). If they were the same operation, whoever can locate could
 * reorganize the whole warehouse.
 */
enum class MovementKind(val permission: String, val moveType: MovementType) {
    LOCATE("invoice.locate", MovementType.LOCATE),
    MOVE("inventory.move", MovementType.MOVE_WITHIN_WAREHOUSE),
    ;

    /** The audit action, so that the audit report reads without translation. */
    val auditAction: String get() = if (this == LOCATE) "INVENTORY_LOCATED" else "INVENTORY_MOVED"
}

/**
 * Applies an internal transfer movement (docs/flujos/existencias-y-ajustes.md §4).
 *
 * **The total does not change**: it leaves one location and enters another. What does change is
 * the content of each location, which is why the origin's balance is locked before
 * subtracting (Q9) and a `LOCATE` or `MOVE_WITHIN_WAREHOUSE` movement is written in the
 * ledger, which is what makes it possible to explain afterwards where each piece left from.
 */
open class ApplyStockMovement(
    private val kind: MovementKind,
    private val catalog: InventoryCatalog,
    private val ledger: StockLedger,
    private val locatingLines: LocatingLines,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: java.time.Clock,
) {

    fun execute(
        actor: Actor,
        input: MovementInput,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
    ): Outcome<MovementResult> =
        parse(input).flatMap { parsed ->
            access.authorizeOr(actor, kind.permission, origin) { grant ->
                val warehouseId = actor.user.warehouseIds.singleOrNull() ?: catalog.defaultWarehouseId()
                val from = catalog.activeLocation(warehouseId, parsed.from)
                    ?: return@authorizeOr DomainError.LocationNotFound(parsed.from.displayName).toFailure().asLeft()
                val to = catalog.activeLocation(warehouseId, parsed.to)
                    ?: return@authorizeOr DomainError.LocationNotFound(parsed.to.displayName).toFailure().asLeft()

                // The same origin and destination is not a transfer: it moves nothing and dirties
                // the ledger with a movement that has no effect (A7).
                if (from.id == to.id) {
                    return@authorizeOr DomainError.SameSourceAndDestination(from.displayName).toFailure().asLeft()
                }

                // A movement does not change warehouse: that is what the transfer is for (M05),
                // which is the only one that knows how to put goods in transit.
                if (from.warehouseId != to.warehouseId) {
                    return@authorizeOr failure(
                        FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                        "The movement cannot change warehouse (${from.warehouseId} → ${to.warehouseId}): " +
                            "to move goods between warehouses, use a transfer.",
                        mapOf("rule" to "misma_bodega"),
                    )
                }

                val ruleError = checkRule(from, to)
                if (ruleError != null) return@authorizeOr ruleError

                // Only locating knows invoice lines: moving is reorganizing shelves.
                if (kind != MovementKind.LOCATE && parsed.invoiceLineId != null) {
                    return@authorizeOr failure(
                        FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                        "Only locating can refer to an invoice line.",
                        mapOf("field" to "invoiceLineId"),
                    )
                }

                access.authorizeOr(
                    actor, kind.permission, origin,
                    resource = ResourceContext(warehouseId = from.warehouseId, locationKind = from.kind.name),
                    entityType = "location",
                    entityId = from.id.toString(),
                ) { scopedGrant ->
                    val product = catalog.activeProduct(parsed.code)
                        ?: return@authorizeOr DomainError.ProductNotFound(parsed.code.value).toFailure().asLeft()

                    /*
                     * Rule B1 is applied **before** moving anything: if the line has
                     * an unapproved difference, the balance does not move and the line does not
                     * change. That is what R1 requires —"responds 409 and the inventory does not
                     * change"— and that is why the domain's validation goes first.
                     */
                    val line = parsed.invoiceLineId?.let { lineId ->
                        locatingLines.find(lineId) ?: return@authorizeOr failure(
                            FailureKind.NOT_FOUND, "LINE_NOT_FOUND",
                            "Line $lineId does not exist.",
                            mapOf("lineId" to lineId.toString()),
                        )
                    }
                    if (line != null && line.productCode != parsed.code) {
                        // Moving one product's balance while the line of another is recorded would leave the
                        // invoice saying one thing and the warehouse another.
                        return@authorizeOr failure(
                            FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                            "Line ${line.id} belongs to ${line.productCode.value}, not ${parsed.code.value}.",
                            mapOf(
                                "invoiceLineId" to line.id.toString(),
                                "productCode" to parsed.code.value,
                                "lineProductCode" to line.productCode.value,
                            ),
                        )
                    }
                    val located = line?.let { target ->
                        when (val result = target.locate(parsed.quantity)) {
                            is Either.Left -> return@authorizeOr result.error.toFailure().asLeft()
                            is Either.Right -> result.value
                        }
                    }
                    apply(actor, product, from, to, parsed, located, idempotencyKey, origin, scopedGrant)
                }
            }
        }

    private fun apply(
        actor: Actor,
        product: dev.axzy.stock.domain.catalog.Product,
        from: Location,
        to: Location,
        parsed: Parsed,
        locatedLine: dev.axzy.stock.domain.receiving.InvoiceLine?,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<MovementResult> = tx.inTransaction {
        // Idempotency: repeating the key returns the same movement and does not move again.
        val alreadyApplied = idempotencyKey?.let { ledger.movementIdOf(it) }
        if (alreadyApplied != null) {
            return@inTransaction MovementResult(
                kardexIds = listOf(alreadyApplied),
                status = MovementStatus.APPLIED,
                total = null,
                message = "This movement was already applied: the idempotency key was repeated.",
            ).asRight()
        }

        // The origin's balance is read WITH A LOCK: the number that is checked has to
        // still be true when subtracting (Q9).
        val available = ledger.balanceAt(product.id, from.id, forUpdate = true)
        if (parsed.quantity > available) {
            return@inTransaction DomainError.InsufficientStock(
                available = available.units,
                requested = parsed.quantity.units,
                location = from.displayName,
            ).toFailure().asLeft()
        }

        val kardexId = ledger.apply(
            MovementCommand(
                productId = product.id,
                warehouseId = from.warehouseId,
                moveType = kind.moveType,
                units = parsed.quantity,
                fromLocationId = from.id,
                toLocationId = to.id,
                userId = actor.id,
                clientUuid = idempotencyKey,
            ),
        )

        /*
         * The line is recorded **in the same transaction** as the movement: either both
         * stay or neither does. If recording the line failed after moving, the
         * balance would have changed and the invoice would not know it.
         */
        locatedLine?.let { line -> locatingLines.markLocated(line.id, line, to.id, clock.instant()) }

        audit.record(
            auditEvents.byActor(
                actor = actor,
                origin = origin,
                action = kind.auditAction,
                level = AuditLevel.CRITICAL,
                entity = AuditEntity("product", product.id.toString(), parsed.code.value),
                grant = grant,
                previousState = mapOf("from" to from.displayName, "available" to available.toString()),
                newState = mapOf("to" to to.displayName, "kardexId" to kardexId),
                idempotencyKey = idempotencyKey?.toString(),
            ),
        )

        // The total does NOT change when moving: returning it would invite believing that it does.
        MovementResult(listOf(kardexId), MovementStatus.APPLIED, total = null).asRight()
    }

    /**
     * The shape of each operation, which is what makes them different permissions.
     *
     * Locating **takes out of receiving** and puts goods on a shelf; moving goes from shelf to
     * shelf. Without this check, whoever can only locate could reorganize the
     * warehouse by calling `locate` with two shelves.
     */
    private fun checkRule(from: Location, to: Location): Outcome<Nothing>? = when (kind) {
        MovementKind.LOCATE -> when {
            from.kind != LocationKind.RECEIVING -> failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Locating takes goods out of the receiving location, and the source is ${from.displayName}.",
                mapOf("fromLocationCode" to from.displayName, "expectedKind" to "RECEIVING"),
            )
            to.kind != LocationKind.SHELF -> failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Locating puts the goods on a shelf, and the destination is ${to.displayName}.",
                mapOf("toLocationCode" to to.displayName, "expectedKind" to "SHELF"),
            )
            else -> null
        }

        MovementKind.MOVE -> when {
            from.kind != LocationKind.SHELF || to.kind != LocationKind.SHELF -> failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Moving is between shelves: ${from.displayName} → ${to.displayName} is not one. " +
                    "There is no operation to move goods to receiving or to the unassigned balance.",
                mapOf("fromLocationCode" to from.displayName, "toLocationCode" to to.displayName),
            )
            else -> null
        }
    }

    private data class Parsed(
        val code: ProductCode,
        val from: LocationRef,
        val to: LocationRef,
        val quantity: Quantity,
        val invoiceLineId: Long?,
    )

    private fun parse(input: MovementInput): Outcome<Parsed> =
        InventoryFilters.productCode(input.productCode).flatMap { code: ProductCode? ->
            if (code == null) {
                failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The product code is required.")
            } else {
                InventoryFilters.locationRef(input.fromLocationCode).flatMap { from: LocationRef? ->
                    InventoryFilters.locationRef(input.toLocationCode).flatMap { to: LocationRef? ->
                        when {
                            from == null -> failure(
                                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                                "The source location code is required.",
                            )
                            to == null -> failure(
                                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                                "The destination location code is required.",
                            )
                            input.quantity == null -> failure(
                                FailureKind.RULE_VIOLATION, "INVALID_QUANTITY",
                                "The quantity is required.",
                            )
                            else -> Parsed(code, from, to, input.quantity, input.invoiceLineId).asRight()
                        }
                    }
                }
            }
        }
}

/**
 * Locating: takes out of receiving and leaves the goods on a shelf (`invoice.locate`).
 *
 * The two movements are the same class with a different rule and a different permission. They
 * are declared as two types —and not as two instances of the same— because the wiring
 * builds **two objects** and, with the same type, which one is which would depend on the name
 * of the parameter. One type per operation makes it impossible to confuse them.
 */
class LocateStock(
    catalog: InventoryCatalog,
    ledger: StockLedger,
    locatingLines: LocatingLines,
    audit: AuditRecorder,
    auditEvents: AuditEvents,
    access: AccessDecider,
    tx: TransactionRunner,
    clock: java.time.Clock,
) : ApplyStockMovement(
    MovementKind.LOCATE, catalog, ledger, locatingLines, audit, auditEvents, access, tx, clock,
)

/** Moving: reorganizes shelves of the same warehouse (`inventory.move`). */
class MoveStock(
    catalog: InventoryCatalog,
    ledger: StockLedger,
    locatingLines: LocatingLines,
    audit: AuditRecorder,
    auditEvents: AuditEvents,
    access: AccessDecider,
    tx: TransactionRunner,
    clock: java.time.Clock,
) : ApplyStockMovement(
    MovementKind.MOVE, catalog, ledger, locatingLines, audit, auditEvents, access, tx, clock,
)
