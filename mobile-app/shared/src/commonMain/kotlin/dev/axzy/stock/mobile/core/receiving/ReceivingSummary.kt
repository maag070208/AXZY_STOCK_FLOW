package dev.axzy.stock.mobile.core.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus

/**
 * Whether the line is still waiting for its count. These are the statuses from which the domain accepts a
 * capture; a test checks this against the domain, status by status, so the two cannot drift apart.
 */
val InvoiceLine.awaitsCount: Boolean
    get() = status == InvoiceLineStatus.PENDING || status == InvoiceLineStatus.ASSIGNED

/**
 * How the receiving work stands, counted from the lines. A cancelled line is not work, so it is not counted.
 * A line is pending until it has been counted; a counted line is one the operator has already captured.
 */
data class ReceivingSummary(
    val total: Int,
    val pending: Int,
    val counted: Int,
    val discrepancies: Int,
) {
    /** How much of the work is done, from 0 to 1. */
    val progress: Float get() = if (total == 0) 0f else counted.toFloat() / total

    companion object {
        fun of(lines: List<InvoiceLine>): ReceivingSummary {
            val active = lines.filter { it.status != InvoiceLineStatus.CANCELLED }
            val pending = active.count { it.awaitsCount }
            return ReceivingSummary(
                total = active.size,
                pending = pending,
                counted = active.size - pending,
                discrepancies = active.count { it.status == InvoiceLineStatus.DISCREPANCY },
            )
        }
    }
}
