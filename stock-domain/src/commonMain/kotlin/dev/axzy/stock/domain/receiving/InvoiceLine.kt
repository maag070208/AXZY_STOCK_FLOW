package dev.axzy.stock.domain.receiving

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * STATE MACHINE OF THE INVOICE LINE
 *
 * It is artifact 2 of the four critical ones. Three business rules live here that
 * the legacy did not have (or had wrong), and all three are confirmed with you:
 *
 *   · **B1 / R-75 — Every discrepancy blocks.** No threshold: any
 *     difference from the invoice prevents locating the goods until someone
 *     authorizes it. In the legacy this did not exist and goods were located with the
 *     difference unresolved.
 *
 *   · **R-74 — Segregation of duties.** Whoever counted **cannot approve**
 *     their own discrepancy. It prevents someone from covering up their own counting error.
 *
 *   · **R-80 — The boxes must add up.** If `quantity / unitsPerBox`
 *     does not give an integer, the count is rejected. An invoice for 100 pieces with boxes of
 *     30 is always wrongly captured data, not a case to handle.
 *
 * **The design makes illegal jumps impossible**: there is no `status` that can
 * be assigned by hand, only transition functions that validate the state of
 * origin. In the legacy, the states were free strings and `invoiceDetail.status
 * = 'done'` was written from anywhere (finding L1 and B-3).
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Status of an invoice line.
 *
 * English names for the enum (consistency with the rest of the model and with
 * the database values), documented in English.
 */
enum class InvoiceLineStatus {
    /** Line created: the goods have not been counted yet. */
    PENDING,

    /** Counted and the quantity **matches** the invoice. */
    CAPTURED,

    /** Counted and the quantity **does NOT match**: it requires approval. */
    DISCREPANCY,

    /**
     * A supervisor reviewed it and authorized the adjustment.
     * It is the step the new system adds: without it, it cannot be located.
     */
    APPROVED,

    /** Work assigned to an operator (stage before the count). */
    ASSIGNED,

    /** Goods located on a shelf, fully or partially. */
    LOCATED,

    /** Cancelled line. It is never deleted: it is cancelled with traceability. */
    CANCELLED,
    ;

    /** Indicates whether the line allows locating goods. */
    val allowsLocating: Boolean
        get() = when (this) {
            CAPTURED, APPROVED, ASSIGNED, LOCATED -> true
            PENDING, DISCREPANCY, CANCELLED -> false
        }

    /**
     * Indicates whether the line prevents closing the invoice.
     * It is the query that the legacy answered with a `filter` over loose strings.
     */
    val blocksInvoiceClosing: Boolean
        get() = when (this) {
            PENDING, DISCREPANCY, ASSIGNED -> true
            CAPTURED, APPROVED, LOCATED, CANCELLED -> false
        }
}

/**
 * An invoice line with its status and its quantities.
 *
 * Immutable: each transition returns a new line. That makes the state always
 * consistent and makes the transitions testable without mocks.
 */
data class InvoiceLine(
    val id: Long,
    val productCode: ProductCode,
    /** Quantity according to the supplier's invoice. */
    val invoicedQuantity: Quantity,
    /** Units per box. It cannot be zero (the type prevents it). */
    val unitsPerBox: UnitsPerBox,
    val status: InvoiceLineStatus,
    /** Physically counted quantity. `null` while it has not been counted. */
    val physicalQuantity: Quantity? = null,
    /** Physically counted boxes. */
    val physicalBoxQuantity: BoxQuantity? = null,
    /** Units already located on shelves. */
    val locatedQuantity: Quantity = Quantity.ZERO,
    /** Operator who did the physical count. Necessary for R-74. */
    val capturedBy: Long? = null,
    /** Supervisor who authorized the discrepancy. Necessary for the audit. */
    val approvedBy: Long? = null,
    /** Reason for the authorized adjustment (invariant I6). */
    val adjustmentReason: String? = null,
    /** Destination location. */
    val location: LocationCode? = null,
    /**
     * Units already **entered** into `RECEIVING` by this line.
     *
     * It is what makes counting twice not add up twice: the entry is written for the
     * difference between what is counted now and what was already entered. It exists because a line with a
     * discrepancy that the supervisor discards goes back to `PENDING` and is recounted
     * ([rejectDiscrepancy]), and without this number the ledger would count the goods twice.
     */
    val receivedQuantity: Quantity = Quantity.ZERO,
    /**
     * Who has the line assigned to count it.
     *
     * The column has existed in the database since the first migration and **nobody wrote it**: the
     * assignment route was the one missing. The `ASSIGNED` status only said "someone has it", without
     * saying who.
     */
    val assignedUserId: Long? = null,
) {

    /**
     * Difference between what was counted and what was invoiced.
     *
     * **This is the field the legacy declared in the model, DTO, mapper and
     * response and always returned `0`** (finding R-58 / L1). Here it is a
     * derived property: there is no way for it to drift out of sync because it is not
     * stored, it is computed.
     *
     * Positive = more arrived than invoiced. Negative = less arrived.
     */
    val difference: Quantity?
        get() = physicalQuantity?.let { it - invoicedQuantity }

    /** Indicates whether there is an unresolved discrepancy. */
    val hasUnresolvedDifference: Boolean
        get() = status == InvoiceLineStatus.DISCREPANCY

    /**
     * Units still pending to be located.
     * Difference between what was counted (or invoiced if not yet counted) and what was located.
     */
    val pendingToLocate: Quantity
        get() = (physicalQuantity ?: invoicedQuantity) - locatedQuantity

    // ═════════════════════════════════════════════════════════════════════════
    // TRANSICIONES
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Physical count of the line.
     *
     * Reglas aplicadas:
     *  · It can only be counted from `PENDING` or `ASSIGNED`.
     *  · The physical quantity must be positive.
     *  · **The boxes must add up exactly** (R-80).
     *  · If `physical == invoiced` → `CAPTURED`; if it differs → `DISCREPANCY`.
     *
     * @return new line, or the corresponding domain error.
     */
    fun capture(
        physicalQuantity: Quantity,
        physicalBoxQuantity: BoxQuantity,
        capturedBy: Long,
    ): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.PENDING && status != InvoiceLineStatus.ASSIGNED) {
            return DomainError.InvalidTransition(
                entity = "invoice line",
                from = status.name,
                action = "capturar",
            ).asLeft()
        }

        if (!physicalQuantity.isPositive) {
            return DomainError.InvalidQuantity(
                "The counted quantity must be greater than zero (counted $physicalQuantity)"
            ).asLeft()
        }

        // DESIGN NOTE: it is NOT validated here that the counted quantity divides exactly
        // by the units per box. Counting 80 pieces of an invoice of 100 with boxes of 50
        // means "1 box and 30 loose": it is a legitimate SHORTAGE, not
        // a wrongly captured datum. Validating it here would block exactly the case
        // that the discrepancy flow exists to resolve.
        //
        // The box validation applies to the invoice's SPECIFICATION and is done
        // when the line is created (see validatePackingSpecification).

        // The counted boxes must match the counted quantity: if 80 pieces are counted they
        // cannot be 2 boxes of 50 (that would be 100).
        if (!physicalBoxQuantity.isConsistentWith(physicalQuantity, unitsPerBox)) {
            return DomainError.InconsistentBoxCount(
                quantity = physicalQuantity.units,
                boxes = physicalBoxQuantity.value,
                unitsPerBox = unitsPerBox.value,
            ).asLeft()
        }

        val matchesInvoice = physicalQuantity == invoicedQuantity
        return copy(
            physicalQuantity = physicalQuantity,
            physicalBoxQuantity = physicalBoxQuantity,
            capturedBy = capturedBy,
            status = if (matchesInvoice) InvoiceLineStatus.CAPTURED else InvoiceLineStatus.DISCREPANCY,
        ).asRight()
    }

    /**
     * Approves a discrepancy and adjusts the line's quantity to what was counted.
     *
     * **The segregation-of-duties rule (R-74) applies here**: if whoever approves is
     * the same person who counted, it returns
     * [DomainError.SegregationOfDuties] and nothing is modified.
     *
     * The reason ([reason]) is mandatory: the adjustment moves inventory and without a reason it is an
     * audit hole (invariant I6).
     */
    fun approveDiscrepancy(
        approvedBy: Long,
        reason: String,
    ): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.DISCREPANCY) {
            return DomainError.NotPendingApproval(status.name).asLeft()
        }

        if (reason.isBlank()) {
            return DomainError.ReasonRequired("authorize an inventory discrepancy").asLeft()
        }

        // ── Segregation of duties (R-74) ─────────────────────────────────
        // The most important internal control of the flow: nobody signs their own
        // counting error. If the business someday decides to relax it, it is done
        // by disabling a policy, not by deleting this code.
        if (capturedBy != null && capturedBy == approvedBy) {
            return DomainError.SegregationOfDuties().asLeft()
        }

        return copy(
            // The invoiced quantity is adjusted to the counted reality: that is what the
            // supervisor authorizes when approving.
            invoicedQuantity = physicalQuantity ?: invoicedQuantity,
            approvedBy = approvedBy,
            adjustmentReason = reason,
            status = InvoiceLineStatus.APPROVED,
        ).asRight()
    }

    /**
     * Rejects the discrepancy and returns the line to counting.
     * The box counter and the quantity are cleared to force a recount.
     */
    fun rejectDiscrepancy(reason: String): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.DISCREPANCY) {
            return DomainError.NotPendingApproval(status.name).asLeft()
        }
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("reject a discrepancy").asLeft()
        }

        return copy(
            physicalQuantity = null,
            physicalBoxQuantity = null,
            capturedBy = null,
            status = InvoiceLineStatus.PENDING,
        ).asRight()
    }

    /**
     * Locates goods on a shelf.
     *
     * **This is where rule B1 takes effect**: if the line has an
     * unapproved discrepancy, it cannot be located. The error returned includes
     * the difference so that the UI can explain how much remains to be authorized.
     */
    fun locate(
        quantity: Quantity,
        toLocation: LocationCode? = null,
    ): DomainResult<InvoiceLine> {
        // ── Rule B1: a discrepancy blocks the location ─────────────────────
        if (status == InvoiceLineStatus.DISCREPANCY) {
            return DomainError.DiscrepancyNotApproved(
                difference = difference?.value ?: 0L,
            ).asLeft()
        }

        if (status == InvoiceLineStatus.CANCELLED) {
            return DomainError.InvalidTransition("invoice line", status.name, "ubicar").asLeft()
        }

        if (status == InvoiceLineStatus.PENDING) {
            return DomainError.InvalidTransition(
                entity = "invoice line",
                from = status.name,
                action = "ubicar",
                message = "The goods must be counted before they are located",
            ).asLeft()
        }

        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The quantity to locate must be greater than zero").asLeft()
        }

        val pending = pendingToLocate
        if (quantity > pending) {
            return DomainError.ExceedsLineQuantity(
                requested = quantity.value,
                available = pending.value,
                message = "Cannot locate $quantity units: " +
                    "only $pending remain to locate",
            ).asLeft()
        }

        val newLocated = locatedQuantity + quantity
        val target = physicalQuantity ?: invoicedQuantity
        /*
         * Locating **partially** approves nothing: the state is kept.
         *
         * It used to move to `APPROVED`, which was an error for two reasons: a line
         * counted that matches the invoice has no discrepancy to approve, and the database
         * forbids `APPROVED` without `approved_by` (enforced by the
         * `CHECK invoice_lines_approved_requires_approver`), so the write
         * would have failed. The correct state for "still needs locating" is the one it
         * already had: `CAPTURED` if it was counted, `APPROVED` if a supervisor already authorized it.
         */
        val complete = newLocated == target
        return copy(
            locatedQuantity = newLocated,
            location = toLocation ?: location,
            status = if (complete) InvoiceLineStatus.LOCATED else status,
        ).asRight()
    }

    /** Cancels the line. It is never deleted: it is marked and the trace is kept. */
    fun cancel(reason: String): DomainResult<InvoiceLine> {
        if (status == InvoiceLineStatus.LOCATED) {
            return DomainError.CannotDeleteWithInventoryEffect(
                message = "A line with goods already located cannot be voided. " +
                    "An adjustment movement must be recorded with a reason.",
            ).asLeft()
        }
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("void an invoice line").asLeft()
        }
        return copy(status = InvoiceLineStatus.CANCELLED).asRight()
    }

    /**
     * Assigns the line to an operator so they can count it.
     *
     * **Only from `PENDING`.** An already counted line is not reassigned: the count has a responsible person
     * ([capturedBy]) and changing it later would be rewriting who did the work. Nor from
     * `DISCREPANCY`: that line awaits a decision, not a counter.
     */
    fun assign(userId: Long): DomainResult<InvoiceLine> {
        if (status != InvoiceLineStatus.PENDING) {
            return DomainError.InvalidTransition("invoice line", status.name, "asignar").asLeft()
        }
        return copy(status = InvoiceLineStatus.ASSIGNED, assignedUserId = userId).asRight()
    }

    companion object {

        /**
         * Decision R-80, applied in the right place: **when creating the line**.
         *
         * The packing specification of an invoice must be coherent: if
         * it says 100 pieces and the boxes are of 30, 10 are left loose and the data is
         * impossible (nobody packs a fraction of a box). The creation is rejected.
         *
         * **It is NOT applied to the counted quantity.** Counting 80 of 100 with boxes of
         * 50 is a legitimate shortage (1 box + 30 loose), and it is precisely
         * what the discrepancy flow must capture, not block.
         *
         * Exception: quantities smaller than a box are loose goods and are
         * valid (20 pieces with boxes of 30).
         */
        fun validatePackingSpecification(
            invoicedQuantity: Quantity,
            unitsPerBox: UnitsPerBox,
        ): DomainResult<Unit> {
            if (invoicedQuantity.isZero) return Unit.asRight()

            val completeBoxes = invoicedQuantity.units / unitsPerBox.value
            val remainder = invoicedQuantity.units % unitsPerBox.value

            return if (completeBoxes > 0L && remainder > 0L) {
                DomainError.BoxesDoNotDivideEvenly(
                    quantity = invoicedQuantity.units,
                    unitsPerBox = unitsPerBox.value,
                    remainder = remainder,
                ).asLeft()
            } else {
                Unit.asRight()
            }
        }
    }
}
