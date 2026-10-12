package dev.axzy.stock.mobile.core.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine

/** Where an invoice stands, as the API reports it (`docs/api/recepcion.md`). */
enum class InvoiceStatus { PENDING, IN_PROGRESS, PENDING_TO_LOCATE, COMPLETED, CANCELLED }

/**
 * An invoice as a list shows it. [blockingLines] is how many of its lines still prevent closing it: not counted,
 * with a difference nobody approved, or assigned and not counted. The criterion is the domain's
 * (`InvoiceLineStatus.blocksInvoiceClosing`); the API counts with it and so does the app.
 */
data class InvoiceHeader(
    val id: Long,
    val number: String,
    val receiptDate: String,
    val status: InvoiceStatus,
    val totalLines: Int,
    val blockingLines: Int,
) {
    val resolvedLines: Int get() = totalLines - blockingLines

    /** How much of the invoice is resolved, from 0 to 1. */
    val progress: Float get() = if (totalLines == 0) 0f else resolvedLines.toFloat() / totalLines
}

/**
 * An invoice with its lines. The lines are the domain's; [productNames] is what the API adds for the person
 * reading the screen, by line id, because the domain's line carries only the product code.
 */
data class InvoiceDetail(
    val header: InvoiceHeader,
    val lines: List<InvoiceLine>,
    val productNames: Map<Long, String> = emptyMap(),
) {
    /** The same invoice with [line] in place of the one with its id, and the header's count brought up to date. */
    fun withLine(line: InvoiceLine): InvoiceDetail {
        val updated = lines.map { if (it.id == line.id) line else it }
        return copy(
            header = header.copy(blockingLines = updated.count { it.status.blocksInvoiceClosing }),
            lines = updated,
        )
    }
}
