package dev.axzy.stock.application.transfer

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.LocationRef
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.transfer.Transfer
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.transfer.TransferStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Clock
import java.time.Instant
import java.time.Year
import java.time.temporal.ChronoUnit

/**
 * The transfer use cases (M05, docs/flujos/traslados.md).
 *
 * **None of them moves balance on its own**: dispatching, receiving and cancelling call the
 * engine's functions (V13), which apply the movement, open or close the
 * transit and recalculate the total counting what is on the way.
 *
 * What the application layer does do is **check before calling**: that the
 * transition is legal (the domain), that there is balance at the origin (with the balance read
 * **with a lock**) and that the destination warehouse has where to receive. Without those
 * checks, the engine would fail with a constraint error that does not say what happened.
 */

// ── Consultas ───────────────────────────────────────────────────────────────

class ListTransfers(private val transfers: Transfers, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: TransferFilter,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<TransferHeader>> = access.authorizeOr(actor, "transfer.view", origin) {
        transfers.page(filter, request).asRight()
    }
}

class GetTransfer(private val transfers: Transfers, private val access: AccessDecider) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.view", origin) {
            transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
        }
}

/**
 * The report of goods in transit.
 *
 * Its permission is **`transit.view` and not `transfer.view`**: whoever watches the debt of
 * transfers does not need to be able to see or create transfers. And the days are counted by the
 * server: with the machine's clock, the same data would give a different number on each
 * machine, and a management figure that changes depending on who looks at it is no use for deciding.
 */
class InTransitReport(
    private val transfers: Transfers,
    private val access: AccessDecider,
    private val clock: Clock,
) {

    fun execute(actor: Actor, origin: RequestOrigin): Outcome<List<InTransitRow>> =
        access.authorizeOr(actor, "transit.view", origin) {
            transfers.inTransit().map { row ->
                row.copy(daysInTransit = daysSince(row.dispatchedAt))
            }.asRight()
        }

    private fun daysSince(dispatchedAt: Instant?): Long =
        dispatchedAt?.let { ChronoUnit.DAYS.between(it, clock.instant()) } ?: 0
}

// ── Alta ────────────────────────────────────────────────────────────────────

class CreateTransfer(
    private val catalog: TransferCatalog,
    private val transfers: Transfers,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, input: NewTransfer, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.create", origin) { grant ->
            when (val lines = resolveLines(input.lines)) {
                is Either.Left -> lines.error.asLeft()
                is Either.Right -> {
                    val from = input.fromWarehouseId
                    val to = input.toWarehouseId
                    if (from == null || to == null) {
                        return@authorizeOr failure(
                            FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                            "The transfer needs an origin and a destination warehouse.",
                            mapOf("field" to if (from == null) "fromWarehouseId" else "toWarehouseId"),
                        )
                    }
                    // The two rules that do not depend on the state are validated by the domain.
                    when (
                        val valid = Transfer.validate(
                            fromWarehouseId = from,
                            toWarehouseId = to,
                            lines = lines.value.map { line ->
                                TransferLine(
                                    id = 0,
                                    productId = line.productId,
                                    productCode = line.productCode.value,
                                    productName = "",
                                    quantity = line.quantity,
                                )
                            },
                        )
                    ) {
                        is Either.Left -> valid.error.toFailure().asLeft()
                        is Either.Right -> insert(actor, input, from, to, lines.value, origin, grant)
                    }
                }
            }
        }

    private fun insert(
        actor: Actor,
        input: NewTransfer,
        from: Long,
        to: Long,
        lines: List<ResolvedTransferLine>,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<TransferDetail> {
        val draft = TransferDraft(
            code = transfers.nextCode(Year.now(clock).value),
            fromWarehouseId = from,
            toWarehouseId = to,
            note = input.note?.trim()?.takeUnless { it.isBlank() },
        )
        return tx.inTransaction {
            when (val inserted = transfers.insert(draft, lines)) {
                InsertTransferResult.DuplicateCode -> failure(
                    FailureKind.CONFLICT, "DUPLICATE_TRANSFER_CODE",
                    "A transfer with the code '${draft.code}' already exists.",
                    mapOf("code" to draft.code),
                )
                is InsertTransferResult.Inserted -> {
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "TRANSFER_CREATED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("transfer", inserted.id.toString(), draft.code),
                            grant = grant,
                            newState = mapOf("code" to draft.code, "lines" to lines.size, "status" to "DRAFT"),
                        ),
                    )
                    transfers.detail(inserted.id)?.asRight() ?: transferNotFound(inserted.id)
                }
            }
        }
    }

    private fun resolveLines(lines: List<NewTransferLine>): Outcome<List<ResolvedTransferLine>> {
        if (lines.isEmpty()) return DomainError.EmptyTransfer().toFailure().asLeft()
        val resolved = mutableListOf<ResolvedTransferLine>()
        lines.forEachIndexed { index, line ->
            val position = index + 1
            val rawCode = line.productCode?.trim()
            if (rawCode.isNullOrBlank()) {
                return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The product code of line $position is missing.",
                    mapOf("field" to "lines[$index].productCode"),
                )
            }
            val code = runCatching { ProductCode.of(rawCode) }.getOrElse {
                return DomainError.InvalidProductCode(rawCode, it.message ?: "invalid code").toFailure().asLeft()
            }
            val product = catalog.product(code)
                ?: return DomainError.InvalidProductCode(rawCode, "does not exist in the catalog or is inactive")
                    .toFailure().asLeft()
            val quantity = line.quantity
                ?: return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The quantity of line $position is missing.",
                    mapOf("field" to "lines[$index].quantity"),
                )
            if (!quantity.isPositive) {
                return DomainError.InvalidQuantity("The quantity of line $position must be greater than zero")
                    .toFailure().asLeft()
            }
            resolved += ResolvedTransferLine(
                productId = product.id,
                productCode = code,
                quantity = quantity,
                fromLocationId = line.fromLocationId,
                note = line.note?.trim()?.takeUnless { it.isBlank() },
            )
        }
        return resolved.asRight()
    }
}

// ── Dispatch, receiving and cancellation ───────────────────────────────────

/**
 * Dispatches the transfer.
 *
 * Before calling the engine, it is checked, **with the balance read with a lock** (Q9), that
 * each line has somewhere to leave from: without that check the engine would fail with the
 * non-negative balance constraint, which does not say which line or how much is missing. The
 * check does not replace the engine: if two simultaneous dispatches passed both
 * checks, the database constraint would still prevent a negative balance.
 */
class DispatchTransfer(
    private val transfers: Transfers,
    private val catalog: TransferCatalog,
    private val ledger: StockLedger,
    private val inventoryCatalog: InventoryCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.dispatch", origin) { grant ->
            tx.inTransaction {
                val detail = transfers.detail(transferId) ?: return@inTransaction transferNotFound(transferId)
                val transfer = toDomain(detail)
                when (val moved = transfer.dispatch(actor.id)) {
                    is Either.Left -> return@inTransaction moved.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }

                // Sufficient balance at each origin, with a lock.
                detail.lines.forEach { view ->
                    val originLocation = view.line.fromLocationId
                        ?: catalog.receivingLocationId(detail.header.fromWarehouseId)
                        ?: return@inTransaction failure(
                            FailureKind.RULE_VIOLATION, "NO_RECEIVING_LOCATION",
                            "The source warehouse has no receiving location.",
                            mapOf("warehouseId" to detail.header.fromWarehouseId.toString()),
                        )
                    val available = ledger.balanceAt(view.line.productId, originLocation, forUpdate = true)
                    if (view.line.quantity > available) {
                        return@inTransaction DomainError.InsufficientStock(
                            available = available.units,
                            requested = view.line.quantity.units,
                            // The visible name is composed by the domain from the
                            // location; if it is a specific shelf, it is resolved there.
                            location = inventoryCatalog.activeLocation(
                                detail.header.fromWarehouseId,
                                LocationRef.Receiving,
                            )?.displayName ?: originLocation.toString(),
                        ).toFailure().asLeft()
                    }
                }

                transfers.dispatch(transferId, actor.id)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "TRANSFER_DISPATCHED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("transfer", transferId.toString(), detail.header.code),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to "DISPATCHED", "lines" to detail.lines.size),
                    ),
                )
                transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
            }
        }
}

class ReceiveTransfer(
    private val transfers: Transfers,
    private val catalog: TransferCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.receive", origin) { grant ->
            tx.inTransaction {
                val detail = transfers.detail(transferId) ?: return@inTransaction transferNotFound(transferId)
                val transfer = toDomain(detail)
                when (val moved = transfer.receive(actor.id)) {
                    is Either.Left -> return@inTransaction moved.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }
                // The destination warehouse has to have somewhere to receive: otherwise the
                // goods would arrive at a place that does not exist.
                catalog.receivingLocationId(detail.header.toWarehouseId)
                    ?: return@inTransaction failure(
                        FailureKind.RULE_VIOLATION, "NO_RECEIVING_LOCATION",
                        "The destination warehouse has no receiving location.",
                        mapOf("warehouseId" to detail.header.toWarehouseId.toString()),
                    )
                if (transfers.openTransitLines(transferId) == 0) {
                    return@inTransaction failure(
                        FailureKind.CONFLICT, "NOTHING_IN_TRANSIT",
                        "There are no goods in transit to receive in this transfer.",
                        mapOf("transferId" to transferId.toString()),
                    )
                }

                transfers.receive(transferId, actor.id)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "TRANSFER_RECEIVED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("transfer", transferId.toString(), detail.header.code),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to "RECEIVED"),
                    ),
                )
                transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
            }
        }
}

class CancelTransfer(
    private val transfers: Transfers,
    private val catalog: TransferCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.cancel", origin) { grant ->
            tx.inTransaction {
                val detail = transfers.detail(transferId) ?: return@inTransaction transferNotFound(transferId)
                val transfer = toDomain(detail)
                // The domain decides: from `RECEIVED` it is not cancelled, and what was received is
                // corrected with a transfer back, which leaves a trace.
                when (val cancelled = transfer.cancel()) {
                    is Either.Left -> return@inTransaction cancelled.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }
                // If it was dispatched, the return to the origin is done by the engine.
                transfers.cancel(transferId, actor.id)

                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "TRANSFER_CANCELLED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("transfer", transferId.toString(), detail.header.code),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to "CANCELLED"),
                    ),
                )
                transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
            }
        }
}

/**
 * The domain transfer from what the query returned.
 *
 * It exists so that the transitions are decided by the domain's rules and not by an
 * `if` on the state written in the route.
 */
internal fun toDomain(detail: TransferDetail): Transfer = Transfer(
    id = detail.header.id,
    code = detail.header.code,
    fromWarehouseId = detail.header.fromWarehouseId,
    toWarehouseId = detail.header.toWarehouseId,
    status = TransferStatus.valueOf(detail.header.status),
    lines = detail.lines.map { it.line },
    note = detail.header.note,
    dispatchedBy = detail.header.dispatchedBy,
    receivedBy = detail.header.receivedBy,
)
