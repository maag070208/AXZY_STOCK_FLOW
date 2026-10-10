package dev.axzy.stock.domain.fulfillment

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ORDER FULFILLMENT (contract §6)
 *
 * ── THE LINE STATUS UNION IS OWN, AND THAT IS A CORRECTION ────
 *
 * The contract says so explicitly, and it is respected here:
 *
 * > "**Before, it reused the states of `InvoiceLineStatus` and that was an error**:
 * > `LOCATED` and `DISCREPANCY` belong to the receiving flow and mean nothing in
 * > fulfillment. Sharing the union forced the interface to handle impossible states, and an
 * > exhaustive `when` to have branches that never run.»
 *
 * `ALLOCATED` replaces `LOCATED`: in fulfillment the goods are **assigned** to an
 * order, not located. And `DISCREPANCY` does not exist: the equivalent is the SHORTAGE,
 * which is an outflow with reason `SHORTAGE` and origin `FULFILLMENT_SHORTAGE`, not a
 * line status.
 *
 * ── THE STOCK RULE, THE ONE THAT IS EASIEST TO GET WRONG ─────────────
 *
 * > **`supplied_quantity` is ALWAYS what has already been deducted from the inventory.**
 *
 * Packing deducts what is packed. Confirming deducts **what is MISSING** up to
 * `quantity`, not the whole `quantity`:
 *
 * ```
 *   quantity = 10, supplied = 7   →  packing 7 deducted 7
 *                                    confirmar descuenta 3  (7 + 3 = 10)
 *                                    total descontado   10  ✔
 * ```
 *
 * If confirming deducted the full `quantity`, the total deducted would be 17 and
 * **the inventory would have dropped 7 units too many**. It is a discrepancy that only
 * appears when the warehouse is reconciled, and it is not distinguishable from a real shrinkage.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Order status. Values of the `order_status` enum. */
enum class OrderStatus {
    /** Created, not started. */
    PENDING,

    /** Someone is fulfilling it: there are lines assigned or partially packed. */
    IN_PROGRESS,

    /** Everything packed, pending confirmation. */
    PACKAGED,

    /** Confirmed and pending export to the ERP. */
    PENDING_TO_OUT,

    /** Exported to the ERP. It is the end of the flow. */
    COMPLETED,

    /** Cancelled. Never deleted: it is cancelled with traceability. */
    CANCELLED,
    ;

    /** Can it still be packed in this order? */
    val allowsPackaging: Boolean
        get() = this == PENDING || this == IN_PROGRESS || this == PACKAGED
}

/**
 * Status of the ORDER LINE. Values of the `order_line_status` enum.
 *
 * **Own union, not the invoice's one** (see the header of this file).
 */
enum class OrderLineStatus {
    /** Not started. */
    PENDING,

    /** Assigned to an operator, who has it on their list. */
    ASSIGNED,

    /** Fulfilled from the shelf. The goods have already been deducted. */
    SUPPLIED,

    /** Verified by a second pair of eyes before the package is closed. */
    VERIFIED,

    /** Anulada. */
    CANCELLED,
    ;

    /** Is there work left to do on this line? */
    val isOpen: Boolean
        get() = this == PENDING || this == ASSIGNED
}

/** A line of the order. */
data class OrderLine(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val unitMeasure: String,
    /** What the client requests. */
    val quantity: Quantity,
    /**
     * **What has already been deducted from the inventory.** It is not "what was packed": it is the accumulated
     * amount the inventory already reflects.
     */
    val suppliedQuantity: Quantity = Quantity.ZERO,
    val status: OrderLineStatus = OrderLineStatus.PENDING,
    val assignedUserId: Long? = null,
    val locationId: Long? = null,
) {

    /** What is still MISSING to take out of the inventory. */
    val pendingQuantity: Quantity
        get() = quantity - suppliedQuantity

    /** Is it complete? */
    val isComplete: Boolean
        get() = suppliedQuantity >= quantity

    /**
     * Packs a quantity of this line.
     *
     * **Returns the new line, does not touch stock.** The inventory movement is
     * executed by the SQL engine in the same transaction that writes the ledger; the
     * domain decides whether the operation is legal and how much must be deducted. If the
     * domain moved the stock and the ledger write failed, the goods would have
     * changed place without a record.
     */
    fun packageQuantity(amount: Quantity): Either<DomainError, OrderLine> {
        if (status == OrderLineStatus.CANCELLED) {
            return DomainError.InvalidTransition(
                entity = "Order line",
                from = status.name,
                action = "empacar",
            ).asLeft()
        }
        if (amount <= Quantity.ZERO) {
            return DomainError.InvalidQuantity("The quantity to pack must be positive.").asLeft()
        }
        // It is checked against what is PENDING and not against `quantity`: packing 7 of 10
        // twice would add up to 14. The database prevents it with a `CHECK`, but here
        // it can say how much remains, which is what the operator needs.
        if (amount > pendingQuantity) {
            return DomainError.ExceedsPendingQuantity(
                requested = amount.value,
                pending = pendingQuantity.value,
            ).asLeft()
        }

        val nextSupplied = suppliedQuantity + amount
        return copy(
            suppliedQuantity = nextSupplied,
            status = if (nextSupplied >= quantity) OrderLineStatus.SUPPLIED else OrderLineStatus.ASSIGNED,
        ).asRight()
    }

    /**
     * Assigns the line to whoever is going to fulfill it, and optionally on which shelf.
     *
     * **Only from `PENDING` or `ASSIGNED`.** A line already fulfilled or cancelled is not reassigned:
     * assigning work on goods that already left would mean someone is going to take them out
     * twice. Reassigning while it is in progress is allowed —the shift changes, someone gets
     * sick— and that is why it is not a single-use transition.
     *
     * The status goes to `ASSIGNED` even if the line is left without a location: what the status says
     * is that **someone is in charge**, not where the goods are.
     */
    fun assign(userId: Long, locationId: Long?): Either<DomainError, OrderLine> {
        if (status != OrderLineStatus.PENDING && status != OrderLineStatus.ASSIGNED) {
            return DomainError.InvalidTransition(
                entity = "Order line",
                from = status.name,
                action = "asignar",
            ).asLeft()
        }
        return copy(
            status = OrderLineStatus.ASSIGNED,
            assignedUserId = userId,
            locationId = locationId ?: this.locationId,
        ).asRight()
    }
}

/** An order with its header and its lines. */
data class Order(
    val id: Long,
    val orderNumber: String,
    val status: OrderStatus,
    val lines: List<OrderLine>,
    val customerCode: String? = null,
    val concept: String? = null,
    val converted: Boolean = false,
    val exportStatus: String? = null,
    val exportAttempts: Int = 0,
    val exportLastError: String? = null,
) {

    val totalLines: Int get() = lines.size

    /** Complete lines. It is the contract's `packagedLines`. */
    val packagedLines: Int get() = lines.count { it.isComplete }

    /** Are all the lines complete or cancelled? */
    val allLinesDone: Boolean
        get() = lines.all { it.isComplete || it.status == OrderLineStatus.CANCELLED }

    /**
     * Marks the order as confirmed.
     *
     * **It does not deduct stock: it returns the lines with what must be deducted.**
     * The deduction of what is pending is done by the SQL engine, and this function is the one that
     * defines HOW MUCH "pending" is.
     */
    fun confirm(): Either<DomainError, Order> {
        if (converted) return DomainError.OrderAlreadyConfirmed(orderNumber).asLeft()
        if (status == OrderStatus.CANCELLED) {
            return DomainError.InvalidTransition(
                entity = "Orden",
                from = status.name,
                action = "confirmar",
            ).asLeft()
        }
        if (status == OrderStatus.COMPLETED) {
            return DomainError.OrderAlreadyConfirmed(orderNumber).asLeft()
        }

        return copy(status = OrderStatus.PENDING_TO_OUT).asRight()
    }

    /**
     * Records an export attempt to the ERP.
     *
     * **Idempotent by the converted `orderNumber`**, as the contract requires: if
     * the ERP already received it, exporting again must not create a second outflow.
     * This function only keeps the counter and the status of the attempt; the actual sending
     * belongs to the infrastructure layer.
     */
    fun recordExportAttempt(success: Boolean, error: String? = null): Either<DomainError, Order> {
        if (!converted && status != OrderStatus.PENDING_TO_OUT && status != OrderStatus.COMPLETED) {
            return DomainError.OrderNotConverted(orderNumber).asLeft()
        }

        return copy(
            exportAttempts = exportAttempts + 1,
            exportStatus = if (success) "SENT" else "FAILED",
            exportLastError = if (success) null else error,
            converted = converted || success,
            status = if (success) OrderStatus.COMPLETED else status,
        ).asRight()
    }

    /**
     * Cancels the order.
     *
     * **An exported order cannot be cancelled**: the ERP has an outflow that
     * the system would stop recognizing. To undo it, an inflow is made, which
     * deja rastro.
     */
    fun cancel(): Either<DomainError, Order> {
        if (status == OrderStatus.COMPLETED) {
            return DomainError.InvalidTransition(
                entity = "Orden",
                from = status.name,
                action = "anular",
            ).asLeft()
        }
        return copy(status = OrderStatus.CANCELLED).asRight()
    }

    /**
     * Recalculates the header status from its lines.
     *
     * **It is derived, not written by hand.** A `status` assigned at each
     * endpoint ends up saying different things depending on the entry point: it is
     * exactly the legacy's defect, where `status = 'done'` was written from
     * anywhere. Here there is ONE function that decides it, and it can be tested.
     */
    fun recalculatedStatus(): Order {
        if (status == OrderStatus.CANCELLED || status == OrderStatus.COMPLETED ||
            status == OrderStatus.PENDING_TO_OUT
        ) {
            return this
        }

        val derived = when {
            lines.isEmpty() -> OrderStatus.PENDING
            allLinesDone -> OrderStatus.PACKAGED
            lines.any { it.suppliedQuantity > Quantity.ZERO } -> OrderStatus.IN_PROGRESS
            lines.any { it.status == OrderLineStatus.ASSIGNED } -> OrderStatus.IN_PROGRESS
            else -> OrderStatus.PENDING
        }

        return copy(status = derived)
    }
}
