package dev.axzy.stock.mobile.feature.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.mobile.core.receiving.awaitsCount

/**
 * Which lines the receiving list shows. Each filter is one of the figures at the top of the list, so the number
 * on a figure is always the number of rows its filter leaves.
 */
enum class ReceivingFilter {
    ALL,
    PENDING,
    COUNTED,
    DISCREPANCIES,
    ;

    fun matches(line: InvoiceLine): Boolean = when (this) {
        ALL -> true
        PENDING -> line.awaitsCount
        COUNTED -> !line.awaitsCount && line.status != InvoiceLineStatus.CANCELLED
        DISCREPANCIES -> line.status == InvoiceLineStatus.DISCREPANCY
    }
}
