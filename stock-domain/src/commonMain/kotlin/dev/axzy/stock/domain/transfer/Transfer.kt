package dev.axzy.stock.domain.transfer

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Id
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * STATE MACHINE OF THE TRANSFER BETWEEN WAREHOUSES (contract §7)
 *
 * ```
 *   DRAFT ──dispatch──> DISPATCHED ──receive──> RECEIVED
 *     │                    │
 *     │                    └──(in transit until it is received)
 *     └──cancel──> CANCELLED <──cancel──┘
 * ```
 *
 * ── THE INVARIANT THAT DEFINES THE WHOLE DESIGN ────────────────────────────────
 *
 * > **`Σ stock_by_location + Σ stock_in_transit` is preserved on EVERY**
 * > **transition.**
 *
 * The goods on the truck **still count** in the inventory. If the transfer
 * were modeled as two instant movements —leaves A, enters B— the product total would drop during the
 * trip and **the reports would lie**: an inventory report halfway through a transfer would show
 * less goods than there are.
 * And worse: the discrepancy would appear and disappear by itself, without anyone being able to
 * explicarlo.
 *
 * It is the reason `stock_in_transit` exists as a table since V3 and why
 * `recalculate_product_total` (V6) sums it.
 *
 * ── WHY THE STATE IS NOT A `String` ────────────────────────────────────
 *
 * In the legacy the states were free strings and **anyone could write
 * any of them**: `invoice.status = 'done'` from a controller, from a maintenance script
 * or from a query by hand. Here the transitions are the
 * ONLY functions that change the state, and each one validates the origin state.
 * An illegal jump —receiving a transfer that was never dispatched— **does not compile**
 * because there is no function that does it.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** State of a transfer. The values match the `transfer_status` enum. */
enum class TransferStatus {
    /** Created and still editable. The goods have NOT moved. */
    DRAFT,

    /** Dispatched: left the origin locations and entered transit. */
    DISPATCHED,

    /**
     * In transit.
     *
     * **A state distinct from `DISPATCHED` on purpose**, even though the normal flow
     * goes from one to the other without intervention: they are two different facts —"it left" and
     * "it is on the way"— and the transit report needs them separated to
     * say how many days each one has been running.
     */
    IN_TRANSIT,

    /** Received at the destination warehouse: the goods are in its locations. */
    RECEIVED,

    /** Cancelled. Only from `DRAFT` or `DISPATCHED`: what was received is corrected with a
     *  movement, not by cancelling the past. */
    CANCELLED,
    ;

    /** Is the goods of this transfer outside the locations? */
    val isInTransit: Boolean
        get() = this == DISPATCHED || this == IN_TRANSIT

    /** Can the content of the transfer still be changed? */
    val isEditable: Boolean
        get() = this == DRAFT
}

/** A line of the transfer: a product and how much travels. */
data class TransferLine(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val quantity: Quantity,
    /** Origin location it leaves from. `null` = the receiving / unassigned one. */
    val fromLocationId: Long? = null,
    val fromLocationDisplayName: String? = null,
    val note: String? = null,
)

/**
 * A transfer with its header and its lines.
 *
 * Immutable: each transition returns a NEW transfer. That way the state is always
 * consistent and the transitions are tested without a database or test doubles
 * — which is what makes these rules testable in milliseconds and the same in the
 * backend and in the mobile app.
 */
data class Transfer(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val toWarehouseId: Long,
    val status: TransferStatus,
    val lines: List<TransferLine> = emptyList(),
    val note: String? = null,
    val dispatchedBy: Long? = null,
    val receivedBy: Long? = null,
) {

    /** How many distinct products travel. */
    val lineCount: Int get() = lines.size

    /** The sum of the quantities. */
    val totalUnits: Quantity
        get() = lines.fold(Quantity.ZERO) { acc, linea -> acc + linea.quantity }

    /**
     * Marks the transfer as dispatched: the goods leave the
     * origin locations and enter transit.
     *
     * **What this function deliberately does NOT do is move the stock.** The domain
     * decides WHETHER the transition is legal and WHO performs it; the stock movement is
     * executed by the SQL engine in the same transaction that writes the ledger
     * and the transit (V6). Separating it is exactly what unbalances an inventory:
     * if the domain moved the stock and the ledger write
     * failed, the goods would have changed place without a record.
     */
    fun dispatch(userId: Long): Either<DomainError, Transfer> {
        if (lines.isEmpty()) return DomainError.EmptyTransfer().asLeft()

        val isValid = status == TransferStatus.DRAFT
        if (!isValid) {
            return DomainError.InvalidTransition(
                entity = "Traslado",
                from = status.name,
                action = "despachar",
            ).asLeft()
        }

        return copy(
            status = TransferStatus.DISPATCHED,
            dispatchedBy = userId,
        ).asRight()
    }

    /**
     * Marks the transfer as received at the destination warehouse.
     *
     * Only from `DISPATCHED` or `IN_TRANSIT`: **receiving something that was never
     * dispatched** would be treating as arrived goods that never left, and the
     * destination inventory would grow without the origin's having dropped.
     */
    fun receive(userId: Long): Either<DomainError, Transfer> {
        if (!status.isInTransit) {
            return DomainError.InvalidTransition(
                entity = "Traslado",
                from = status.name,
                action = "recibir",
            ).asLeft()
        }

        return copy(
            status = TransferStatus.RECEIVED,
            receivedBy = userId,
        ).asRight()
    }

    /**
     * Cancels the transfer.
     *
     * **From `RECEIVED` it is NOT possible.** What was received is in the destination locations and
     * cancelling it would leave the goods counted in the wrong place:
     * if it must be undone, it is done with a transfer back, which leaves a trace.
     * A `CANCELLED` over something already received would be deleting the past.
     */
    fun cancel(): Either<DomainError, Transfer> {
        val cancelable = status == TransferStatus.DRAFT || status == TransferStatus.DISPATCHED
        if (!cancelable) {
            return DomainError.InvalidTransition(
                entity = "Traslado",
                from = status.name,
                action = "cancelar",
            ).asLeft()
        }

        return copy(status = TransferStatus.CANCELLED).asRight()
    }

    companion object {
        /**
         * Validates the two rules that do not depend on the state, before creating anything.
         *
         * They are checked BEFORE touching the database so as to return a `422` with the
         * field indicated instead of translating a constraint error, which does not
         * say which field is wrong.
         */
        fun validate(
            fromWarehouseId: Long,
            toWarehouseId: Long,
            lines: List<TransferLine>,
        ): Either<DomainError, Unit> {
            if (fromWarehouseId == toWarehouseId) return DomainError.SameWarehouse().asLeft()
            if (lines.isEmpty()) return DomainError.EmptyTransfer().asLeft()
            return Unit.asRight()
        }
    }
}

/** The typed identifier of a transfer, so as not to confuse it with another `Long`. */
typealias TransferId = Id<Transfer>
