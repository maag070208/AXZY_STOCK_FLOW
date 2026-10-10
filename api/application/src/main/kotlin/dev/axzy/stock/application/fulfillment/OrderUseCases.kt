package dev.axzy.stock.application.fulfillment

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.catalog.Locations
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.inventory.AdjustStock
import dev.axzy.stock.application.inventory.AdjustmentInput
import dev.axzy.stock.application.inventory.MovementCommand
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
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.fulfillment.Order
import dev.axzy.stock.domain.fulfillment.OrderLine
import dev.axzy.stock.domain.fulfillment.OrderLineStatus
import dev.axzy.stock.domain.fulfillment.OrderStatus
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Clock
import java.time.LocalDate
import java.time.Year

/**
 * The fulfillment (M04) and ERP-export (M09) use cases.
 *
 * Two rules govern the rest and live in the domain:
 *
 * · **Packing deducts against what is PENDING, not what was requested** (S1): packing 7 of
 *   10 twice would add up to 14.
 * · **Confirming deducts `quantity - suppliedQuantity`**, not `quantity` (S2): what was
 *   packed has already left, and deducting it again in full would leave the inventory short and
 *   the discrepancy would be confused with a shrinkage.
 */

// ── Consultas ───────────────────────────────────────────────────────────────

class ListOrders(private val orders: Orders, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: OrderFilter,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<OrderHeader>> = access.authorizeOr(actor, "order.view", origin) {
        orders.page(filter, request).asRight()
    }
}

class GetOrder(private val orders: Orders, private val access: AccessDecider) {

    fun execute(actor: Actor, orderId: Long, origin: RequestOrigin): Outcome<OrderDetail> =
        access.authorizeOr(actor, "order.view", origin) {
            orders.detail(orderId)?.asRight() ?: orderNotFound(orderId)
        }
}

// ── Alta ────────────────────────────────────────────────────────────────────

class CreateOrder(
    private val catalog: OrderCatalog,
    private val orders: Orders,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, input: NewOrder, origin: RequestOrigin): Outcome<OrderDetail> =
        access.authorizeOr(actor, "order.create", origin) { grant ->
            if (input.lines.isEmpty()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "EMPTY_ORDER",
                    "An order needs at least one line.",
                    mapOf("field" to "lines"),
                )
            }
            when (val header = resolveHeader(input)) {
                is Either.Left -> header.error.asLeft()
                is Either.Right -> when (val lines = resolveLines(input.lines)) {
                    is Either.Left -> lines.error.asLeft()
                    is Either.Right -> insert(actor, header.value, lines.value, origin, grant)
                }
            }
        }

    private fun insert(
        actor: Actor,
        draft: OrderDraft,
        lines: List<ResolvedOrderLine>,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<OrderDetail> {
        if (orders.numberExists(draft.orderNumber)) return duplicateNumber(draft.orderNumber)

        return tx.inTransaction {
            when (val inserted = orders.insert(draft, lines)) {
                InsertOrderResult.DuplicateNumber -> duplicateNumber(draft.orderNumber)
                is InsertOrderResult.Inserted -> {
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "ORDER_CREATED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("order", inserted.id.toString(), draft.orderNumber),
                            grant = grant,
                            newState = mapOf("orderNumber" to draft.orderNumber, "lines" to lines.size),
                        ),
                    )
                    orders.detail(inserted.id)?.asRight() ?: orderNotFound(inserted.id)
                }
            }
        }
    }

    /** The number and the date are optional: if they do not come, the server sets them. */
    private fun resolveHeader(input: NewOrder): Outcome<OrderDraft> {
        val rawDate = input.packagingDate?.trim()
        val date = if (rawDate.isNullOrBlank()) {
            LocalDate.now(clock)
        } else {
            runCatching { LocalDate.parse(rawDate) }.getOrNull()
                ?: return failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                    "The packing date is not in the format YYYY-MM-DD.",
                    mapOf("field" to "packagingDate", "expected" to "AAAA-MM-DD", "value" to rawDate),
                )
        }
        val number = input.orderNumber?.trim()?.takeUnless { it.isBlank() }
            ?: orders.nextOrderNumber(Year.now(clock).value)
        if (number.length > 40) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                "The order number cannot exceed 40 characters.",
                mapOf("field" to "orderNumber", "maxLength" to "40"),
            )
        }
        return OrderDraft(
            orderNumber = number,
            packagingDate = date,
            customerCode = input.customerCode?.trim()?.takeUnless { it.isBlank() },
            concept = input.concept?.trim()?.takeUnless { it.isBlank() },
        ).asRight()
    }

    private fun resolveLines(lines: List<NewOrderLine>): Outcome<List<ResolvedOrderLine>> {
        val resolved = mutableListOf<ResolvedOrderLine>()
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
            resolved += ResolvedOrderLine(product.id, code, product.unitMeasureId, quantity)
        }
        return resolved.asRight()
    }
}

// ── Empaque ─────────────────────────────────────────────────────────────────

data class PackageInput(val quantity: Quantity?, val locationId: Long?)

/**
 * Packs a quantity of a line: **deducts stock and writes the ledger**.
 *
 * The quantity is validated against the line's pending amount (S1), and the balance is read with
 * a lock so that two simultaneous packings cannot leave the shelf negative.
 */
class PackageLine(
    private val catalog: OrderCatalog,
    private val orders: Orders,
    private val ledger: StockLedger,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        orderId: Long,
        lineId: Long,
        input: PackageInput,
        origin: RequestOrigin,
    ): Outcome<OrderDetail> = access.authorizeOr(actor, "order.package", origin) { grant ->
        tx.inTransaction {
            val detail = orders.detail(orderId) ?: return@inTransaction orderNotFound(orderId)
            val view = detail.lines.firstOrNull { it.line.id == lineId }
                ?: return@inTransaction lineNotFound(lineId)
            val line = view.line

            if (!OrderStatus.valueOf(detail.header.status).allowsPackaging) {
                return@inTransaction failure(
                    FailureKind.CONFLICT, "INVALID_TRANSITION",
                    "An order that is ${detail.header.status.lowercase()} does not allow packing.",
                    mapOf("orderId" to orderId.toString(), "status" to detail.header.status),
                )
            }
            val amount = input.quantity
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The quantity to pack is required.",
                    mapOf("field" to "quantity"),
                )

            // The domain decides whether the operation is legal and how much remains pending.
            val packaged = when (val result = line.packageQuantity(amount)) {
                is Either.Left -> return@inTransaction result.error.toFailure().asLeft()
                is Either.Right -> result.value
            }
            val location = (input.locationId ?: line.locationId)?.let { catalog.location(it) }
                ?: return@inTransaction locationRequired(lineId)

            when (val moved = move(actor, packaged, location, amount, detail)) {
                is Either.Left -> return@inTransaction moved.error.asLeft()
                is Either.Right -> Unit
            }

            orders.replaceLine(packaged, location.id, clock.instant())
            val updated = detail.lines.map { if (it.line.id == lineId) packaged else it.line }
            val status = recalculatedStatusOf(detail, updated)
            if (status != detail.header.status) orders.setStatus(orderId, status, clock.instant())

            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "ORDER_LINE_PACKAGED",
                    level = AuditLevel.CRITICAL,
                    entity = AuditEntity("order_line", lineId.toString(), detail.header.orderNumber),
                    grant = grant,
                    previousState = mapOf("suppliedQuantity" to line.suppliedQuantity.toPlain(), "location" to view.locationDisplayName),
                    newState = mapOf(
                        "suppliedQuantity" to packaged.suppliedQuantity.toPlain(),
                        "location" to location.displayName,
                    ),
                ),
            )
            orders.detail(orderId)!!.asRight()
        }
    }

    /** The outflow movement, with the balance read **with a lock** (Q9). */
    private fun move(
        actor: Actor,
        line: OrderLine,
        location: OrderLocation,
        amount: Quantity,
        detail: OrderDetail,
    ): Either<dev.axzy.stock.application.shared.Failure, Unit> {
        val available = ledger.balanceAt(line.productId, location.id, forUpdate = true)
        if (amount > available) {
            return DomainError.InsufficientStock(
                available = available.units,
                requested = amount.units,
                location = location.displayName,
            ).toFailure().copy(
                // The contract asks for the line in the detail: without it, with ten lines
                // the client does not know which one they are being told about.
                details = mapOf(
                    "available" to available.units.toString(),
                    "requested" to amount.units.toString(),
                    "lineId" to line.id.toString(),
                    "orderNumber" to detail.header.orderNumber,
                ),
            ).asLeft()
        }
        ledger.apply(
            MovementCommand(
                productId = line.productId,
                warehouseId = location.warehouseId,
                moveType = MovementType.FULFILLMENT_OUT,
                units = amount,
                fromLocationId = location.id,
                orderNumber = detail.header.orderNumber,
                userId = actor.id,
            ),
        )
        return Unit.asRight()
    }

    private fun locationRequired(lineId: Long) = failure(
        FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
        "Line $lineId has no location to take the goods from.",
        mapOf("lineId" to lineId.toString()),
    )
}

// ── Confirmation ─────────────────────────────────────────────────────────

/**
 * Confirms the order: deducts **what is missing** from each line and triggers the
 * export.
 *
 * The confirmation is **claimed** with a conditional statement before moving anything:
 * two simultaneous confirmations cannot deduct twice (S3). And if the ERP does not
 * respond, the confirmation is **not rolled back** —the stock has already left—: the attempt is
 * recorded for the manual retry.
 */
class ConfirmOrder(
    private val orders: Orders,
    private val ledger: StockLedger,
    private val catalog: OrderCatalog,
    private val erp: ErpClient,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, orderId: Long, origin: RequestOrigin): Outcome<OrderDetail> =
        access.authorizeOr(actor, "order.confirm", origin) { grant ->
            tx.inTransaction {
                val detail = orders.detail(orderId) ?: return@inTransaction orderNotFound(orderId)

                // The domain checks that the order admits confirmation.
                val order = Order(
                    id = detail.header.id,
                    orderNumber = detail.header.orderNumber,
                    status = OrderStatus.valueOf(detail.header.status),
                    lines = detail.lines.map { it.line },
                    converted = detail.header.converted,
                )
                when (val confirmed = order.confirm()) {
                    is Either.Left -> return@inTransaction confirmed.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }

                // The atomic claim: only one transaction gets the row.
                if (orders.claimConfirmation(orderId, clock.instant()) == 0) {
                    return@inTransaction DomainError.OrderAlreadyConfirmed(detail.header.orderNumber)
                        .toFailure().asLeft()
                }

                detail.lines.forEach { view ->
                    val pending = view.line.pendingQuantity
                    if (pending <= Quantity.ZERO) return@forEach
                    val location = view.line.locationId?.let { catalog.location(it) }
                        ?: return@inTransaction locationRequiredFor(view.line.id)
                    val available = ledger.balanceAt(view.line.productId, location.id, forUpdate = true)
                    if (pending > available) {
                        // A `Left` rolls back the whole transaction: no line
                        // is left half-deducted (S4), and the order goes back to its state.
                        return@inTransaction DomainError.InsufficientStock(
                            available = available.units,
                            requested = pending.units,
                            location = location.displayName,
                        ).toFailure().copy(
                            details = mapOf(
                                "available" to available.units.toString(),
                                "requested" to pending.units.toString(),
                                "lineId" to view.line.id.toString(),
                                "orderNumber" to detail.header.orderNumber,
                            ),
                        ).asLeft()
                    }
                    ledger.apply(
                        MovementCommand(
                            productId = view.line.productId,
                            warehouseId = location.warehouseId,
                            moveType = MovementType.FULFILLMENT_OUT,
                            units = pending,
                            fromLocationId = location.id,
                            orderNumber = detail.header.orderNumber,
                            userId = actor.id,
                        ),
                    )
                    orders.replaceLine(
                        view.line.copy(
                            suppliedQuantity = view.line.quantity,
                            status = OrderLineStatus.SUPPLIED,
                        ),
                        location.id,
                        clock.instant(),
                    )
                }

                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "ORDER_CONFIRMED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("order", orderId.toString(), detail.header.orderNumber),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to PENDING_TO_OUT, "lines" to detail.lines.size),
                    ),
                )
                // The export goes **after**: if it fails, the confirmation is already done.
                export(actor, orderId, origin, grant)
                orders.detail(orderId)!!.asRight()
            }
        }

    private fun locationRequiredFor(lineId: Long) = failure(
        FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
        "Line $lineId has no location to take the goods from.",
        mapOf("lineId" to lineId.toString()),
    )

    /** An attempt to send to the ERP, with its counter. It never rolls back the confirmation. */
    private fun export(
        actor: Actor,
        orderId: Long,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ) {
        val current = orders.detail(orderId) ?: return
        val result = if (current.header.converted) {
            // It was already in the ERP: a second outflow is not sent, it is only counted.
            ErpResult.Sent
        } else {
            erp.send(current)
        }
        val success = result is ErpResult.Sent
        val error = (result as? ErpResult.Failed)?.reason
        orders.recordExport(orderId, success, if (success) SENT else FAILED, error, clock.instant())
        audit.record(
            auditEvents.byActor(
                actor = actor,
                origin = origin,
                action = "ORDER_EXPORTED",
                level = AuditLevel.CRITICAL,
                entity = AuditEntity("order", orderId.toString(), current.header.orderNumber),
                grant = grant,
                newState = mapOf(
                    "status" to if (success) SENT else FAILED,
                    "attempts" to current.header.exportAttempts + 1,
                ),
                errorMessage = error,
            ),
        )
    }
}

// ── Manual export ───────────────────────────────────────────────────────

data class ExportAttempt(val attempts: Int, val status: String, val lastError: String?)

/**
 * The manual retry of the export.
 *
 * It is **idempotent by the converted `orderNumber`**: if the order was already exported, a
 * second outflow is not created and only the attempt is counted.
 */
class ExportOrder(
    private val orders: Orders,
    private val erp: ErpClient,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, orderId: Long, origin: RequestOrigin): Outcome<ExportAttempt> =
        access.authorizeOr(actor, "order.convert", origin) { grant ->
            tx.inTransaction {
                val detail = orders.detail(orderId) ?: return@inTransaction orderNotFound(orderId)
                val order = Order(
                    id = detail.header.id,
                    orderNumber = detail.header.orderNumber,
                    status = OrderStatus.valueOf(detail.header.status),
                    lines = detail.lines.map { it.line },
                    converted = detail.header.converted,
                )
                // Exporting something that has not been fulfilled would send the ERP an outflow that does not exist.
                when (val recorded = order.recordExportAttempt(success = false, error = null)) {
                    is Either.Left -> return@inTransaction recorded.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }

                val result = if (detail.header.converted) ErpResult.Sent else erp.send(detail)
                val success = result is ErpResult.Sent
                val error = (result as? ErpResult.Failed)?.reason
                orders.recordExport(orderId, success, if (success) SENT else FAILED, error, clock.instant())

                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "ORDER_EXPORT_RETRIED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("order", orderId.toString(), detail.header.orderNumber),
                        grant = grant,
                        newState = mapOf("attempts" to detail.header.exportAttempts + 1, "status" to if (success) SENT else FAILED),
                        errorMessage = error,
                    ),
                )
                ExportAttempt(
                    attempts = detail.header.exportAttempts + 1,
                    status = if (success) SENT else FAILED,
                    lastError = error,
                ).asRight()
            }
        }
}

// ── Faltante ────────────────────────────────────────────────────────────────

data class ShortageInput(
    val lineId: Long?,
    val quantity: Quantity?,
    val reason: AdjustmentReason?,
    val note: String?,
)

/**
 * Records a shortage during fulfillment.
 *
 * **Delegates to the single adjustment rule** with origin `FULFILLMENT_SHORTAGE`: fulfillment
 * does not reimplement how inventory moves, because over time one rule would require
 * approval and the other would not. The permission that counts is that of the adjustment
 * (`inventory.adjust_shortage`), which is what the delegated use case decides.
 */
class RegisterShortage(
    private val orders: Orders,
    private val adjustStock: AdjustStock,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(
        actor: Actor,
        orderId: Long,
        input: ShortageInput,
        idempotencyKey: java.util.UUID?,
        origin: RequestOrigin,
    ): Outcome<OrderDetail> = access.authorizeOr(actor, "order.package", origin) { grant ->
        val detail = orders.detail(orderId) ?: return@authorizeOr orderNotFound(orderId)
        val lineId = input.lineId ?: return@authorizeOr failure(
            FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
            "The shortage line is missing.",
            mapOf("field" to "lineId"),
        )
        val view = detail.lines.firstOrNull { it.line.id == lineId }
            ?: return@authorizeOr lineNotFound(lineId)
        val location = view.line.locationId
            ?: return@authorizeOr failure(
                FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
                "Line $lineId has no location to deduct the shortage from.",
                mapOf("lineId" to lineId.toString()),
            )
        val locationView = view.locationDisplayName
            ?: return@authorizeOr failure(
                FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
                "Line $lineId has no readable location.",
                mapOf("lineId" to lineId.toString()),
            )

        // The delegated adjustment does its own checks —quantity, reason and
        // permission— and writes the movement and its audit record.
        val adjustment = adjustStock.execute(
            actor = actor,
            input = AdjustmentInput(
                productCode = view.line.productCode,
                locationCode = locationView,
                quantity = input.quantity,
                direction = AdjustmentDirection.DECREASE.name,
                // No default: a shortage is an adjustment and the reason is mandatory (S8).
                reason = input.reason?.name,
                origin = AdjustmentOrigin.FULFILLMENT_SHORTAGE.name,
                note = input.note,
                approverId = null,
            ),
            idempotencyKey = idempotencyKey,
            origin = origin,
        )
        when (adjustment) {
            is Either.Left -> adjustment.error.asLeft()
            is Either.Right -> {
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "ORDER_SHORTAGE_REGISTERED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("order_line", lineId.toString(), detail.header.orderNumber),
                        grant = grant,
                        newState = mapOf(
                            "quantity" to input.quantity?.value?.toString(),
                            "location" to locationView,
                            "locationId" to location.toString(),
                        ),
                    ),
                )
                orders.detail(orderId)?.asRight() ?: orderNotFound(orderId)
            }
        }
    }
}

internal fun recalculatedStatusOf(detail: OrderDetail, lines: List<OrderLine>): String =
    Order(
        id = detail.header.id,
        orderNumber = detail.header.orderNumber,
        status = OrderStatus.valueOf(detail.header.status),
        lines = lines,
        converted = detail.header.converted,
    ).recalculatedStatus().status.name

internal const val SENT = "SENT"
internal const val FAILED = "FAILED"
internal const val PENDING_TO_OUT = "PENDING_TO_OUT"

internal fun Quantity.toPlain(): String = java.math.BigDecimal.valueOf(value, Quantity.SCALE).toPlainString()

internal fun orderNotFound(orderId: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "ORDER_NOT_FOUND",
    "Order $orderId does not exist.",
    mapOf("orderId" to orderId.toString()),
)

internal fun lineNotFound(lineId: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "ORDER_LINE_NOT_FOUND",
    "Line $lineId does not exist on this order.",
    mapOf("lineId" to lineId.toString()),
)

internal fun duplicateNumber(number: String): Outcome<Nothing> = failure(
    FailureKind.CONFLICT, "DUPLICATE_ORDER_NUMBER",
    "An order with the number '$number' already exists.",
    mapOf("orderNumber" to number),
)

/**
 * Assigns a line of an order to the person who is going to fulfill it (M04, `order.assign`).
 *
 * It is the route that was missing: the table and the response had `assigned_user_id` and `location_id`
 * from the start, and **nothing wrote them**, so the operator had no way to know what was theirs
 * nor to note which shelf the goods were on.
 *
 * `userId` is optional on purpose: without it, the caller **assigns to themselves**, which is the
 * operator's daily case. With it, a supervisor distributes the work. Assigning to someone else is not a
 * separate privilege: the permission is the same, and whoever has it can distribute.
 */
class AssignOrderLine(
    private val orders: Orders,
    private val accounts: UserAccounts,
    private val locations: Locations,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        orderId: Long,
        lineId: Long,
        assigneeId: Long?,
        locationId: Long?,
        origin: RequestOrigin,
    ): Outcome<OrderLineView> = access.authorizeOr(actor, "order.assign", origin) { grant ->
        tx.inTransaction {
            val view = orders.lineOf(orderId, lineId) ?: return@inTransaction lineNotFound(orderId, lineId)
            val userId = assigneeId ?: actor.id
            val assignee = accounts.loadActor(userId)?.actor
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "ASSIGNEE_NOT_FOUND",
                    "User $userId, to whom the line should be assigned, does not exist.",
                    mapOf("userId" to userId.toString()),
                )
            // A location that does not exist is rejected before writing: assigning an invented shelf
            // would send the operator to look for goods in a place that does not exist.
            locationId?.let { id ->
                locations.findById(id)
                    ?: return@inTransaction failure(
                        FailureKind.RULE_VIOLATION, "LOCATION_NOT_FOUND",
                        "Location $id does not exist.",
                        mapOf("locationId" to id.toString()),
                    )
            }

            when (val assigned = view.line.assign(userId, locationId)) {
                is Either.Left -> assigned.error.toFailure().asLeft()
                is Either.Right -> {
                    val changed = orders.assignLine(lineId, userId, locationId, clock.instant())
                    if (changed == 0) {
                        // Packing fulfilled it between the read and the write.
                        return@inTransaction failure(
                            FailureKind.CONFLICT, "LINE_CHANGED",
                            "The line changed while it was being assigned. Look it up again.",
                            mapOf("lineId" to lineId.toString()),
                        )
                    }
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "ORDER_LINE_ASSIGNED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("order_line", lineId.toString(), "Orden $orderId"),
                            grant = grant,
                            previousState = mapOf(
                                "status" to view.line.status.name,
                                "assignedUserId" to view.line.assignedUserId?.toString().orEmpty(),
                                "locationId" to view.line.locationId?.toString().orEmpty(),
                            ),
                            newState = mapOf(
                                "status" to assigned.value.status.name,
                                "assignedUserId" to userId.toString(),
                                "locationId" to locationId?.toString().orEmpty(),
                            ),
                        ),
                    )
                    (orders.lineOf(orderId, lineId) ?: view.copy(line = assigned.value)).asRight()
                }
            }
        }
    }

    private fun lineNotFound(orderId: Long, lineId: Long): Outcome<Nothing> = failure(
        FailureKind.NOT_FOUND, "ORDER_LINE_NOT_FOUND",
        "Order $orderId does not have line $lineId.",
        mapOf("orderId" to orderId.toString(), "lineId" to lineId.toString()),
    )
}
